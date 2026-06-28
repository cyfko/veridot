package io.github.cyfko.veridot.kafka;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.impl.Envelope;
import io.github.cyfko.veridot.core.impl.Scope;
import io.github.cyfko.veridot.core.impl.ErrorCode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Kafka + RocksDB implementation of the Broker interface for Protocol V4 (§12.2).
 */
public class KafkaBroker implements Broker, AutoCloseable {

    private static final Logger logger = Logger.getLogger(KafkaBroker.class.getName());

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final RocksDB db;
    private final Options options;
    private final ExecutorService consumerExecutor;
    private final Properties properties;
    private final String topic;

    private volatile boolean closed = false;

    // Local cache to bypass read-after-write latencies on the signing node
    private final java.util.Map<String, byte[]> localCache = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        try {
            RocksDB.loadLibrary();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public KafkaBroker(Properties props) {
        if (props == null || !props.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            throw new IllegalArgumentException("Kafka bootstrap servers property is required");
        }

        this.properties = new Properties();
        this.properties.putAll(props);
        PropertiesUtil.addUniqueKafkaProperties(this.properties);

        // Turn off auto commit for manual offset commit safety (F-08)
        this.properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        
        try {
            this.options = new Options().setCreateIfMissing(true);
            this.db = RocksDB.open(options, this.properties.getProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG));
            
            this.producer = new KafkaProducer<>(this.properties);
            this.consumer = new KafkaConsumer<>(this.properties);
            this.topic = this.properties.getProperty(VerifierConfig.BROKER_TOPIC_CONFIG, Constant.KAFKA_TOKEN_VERIFIER_TOPIC);
            this.consumer.subscribe(Collections.singletonList(this.topic));

            this.consumerExecutor = Executors.newSingleThreadExecutor();
            this.consumerExecutor.submit(this::runConsumerLoop);

            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        } catch (Exception e) {
            close();
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Failed to initialize KafkaBroker", e);
        }
    }

    private void runConsumerLoop() {
        while (!closed) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, String> record : records) {
                    byte[] storageKey = HexFormat.of().parseHex(record.key());
                    if (record.value() == null || record.value().isEmpty()) {
                        try {
                            db.delete(storageKey);
                            localCache.remove(toHexKey(storageKey));
                        } catch (RocksDBException e) {
                            logger.severe("RocksDB error on delete: do NOT commit offset to allow retry");
                            throw e;
                        }
                        continue;
                    }

                    byte[] envelopeBytes = Base64.getDecoder().decode(record.value());

                    try {
                        // F-02: validate envelope structure before writing to RocksDB
                        Envelope.parse(envelopeBytes);

                        // Persist to RocksDB
                        db.put(storageKey, envelopeBytes);

                        // Update local cache
                        localCache.put(toHexKey(storageKey), envelopeBytes);

                    } catch (VeridotException e) {
                        logger.warning("Rejected non-conforming Kafka record: " + e.getErrorCode());
                    } catch (RocksDBException e) {
                        logger.severe("RocksDB error: do NOT commit offset to allow retry");
                        throw e; // break loop to trigger retry
                    }
                }
                
                if (!records.isEmpty()) {
                    // F-08: commit offset only after successful processing
                    consumer.commitSync();
                }
            } catch (Exception e) {
                if (closed) break;
                logger.severe("Error in Kafka consumer loop: " + e.getMessage());
                try {
                    Thread.sleep(1000); // Backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public CompletableFuture<Void> put(byte[] storageKey, byte[] envelopeBytes) {
        if (storageKey == null || envelopeBytes == null) {
            throw new IllegalArgumentException("storageKey and envelopeBytes cannot be null");
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        String key = toHexKey(storageKey);

        if (envelopeBytes.length == 0) {
            localCache.remove(key);
            producer.send(new ProducerRecord<>(topic, key, null), (metadata, exception) -> {
                if (exception != null) {
                    future.completeExceptionally(new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "Kafka send tombstone failed", exception));
                } else {
                    future.complete(null);
                }
            });
            return future;
        }

        // Validate envelope well-formedness before publishing (§12.2)
        try {
            Envelope.parse(envelopeBytes);
        } catch (VeridotException e) {
            return CompletableFuture.failedFuture(e);
        }

        String value = Base64.getEncoder().encodeToString(envelopeBytes);

        // Put to local cache immediately to bypass read-after-write latency on this node
        putLocal(storageKey, envelopeBytes);

        producer.send(new ProducerRecord<>(topic, key, value), (metadata, exception) -> {
            if (exception != null) {
                future.completeExceptionally(new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "Kafka send failed", exception));
            } else {
                future.complete(null);
            }
        });

        return future;
    }

    @Override
    public byte[] get(byte[] storageKey) {
        if (storageKey == null) {
            return null;
        }

        String hexKey = toHexKey(storageKey);
        byte[] cached = localCache.get(hexKey);
        if (cached != null) {
            return cached;
        }

        try {
            byte[] bytes = db.get(storageKey);
            if (bytes != null) {
                localCache.put(hexKey, bytes);
                return bytes;
            }
            return null;
        } catch (RocksDBException e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "RocksDB access error", e);
        }
    }

    @Override
    public List<BrokerEntry> snapshot(Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }

        byte[] scopeBytes = scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] lowerBound = new byte[scopeBytes.length + 1];
        System.arraycopy(scopeBytes, 0, lowerBound, 0, scopeBytes.length);
        lowerBound[scopeBytes.length] = 0x00; // Separator NUL byte

        byte[] upperBound = new byte[scopeBytes.length + 1];
        System.arraycopy(scopeBytes, 0, upperBound, 0, scopeBytes.length);
        upperBound[scopeBytes.length] = 0x01;

        List<BrokerEntry> list = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(lowerBound); iterator.isValid(); iterator.next()) {
                byte[] keyBytes = iterator.key();
                
                // Binary range scan check
                if (compareBytes(keyBytes, upperBound) >= 0) {
                    break;
                }
                list.add(new BrokerEntry(keyBytes, iterator.value()));
            }
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "RocksDB iterator error during snapshot", e);
        }

        return list;
    }

    @Override
    public void putLocal(byte[] storageKey, byte[] envelopeBytes) {
        if (storageKey != null && envelopeBytes != null) {
            localCache.put(toHexKey(storageKey), envelopeBytes);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
        }

        if (consumerExecutor != null) {
            consumerExecutor.shutdownNow();
            try {
                consumerExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        if (consumer != null) {
            try {
                consumer.close(Duration.ofSeconds(2));
            } catch (Exception ignored) {}
        }

        if (producer != null) {
            try {
                producer.close(Duration.ofSeconds(2));
            } catch (Exception ignored) {}
        }

        if (db != null) {
            db.close();
        }
        if (options != null) {
            options.close();
        }
    }

    private static String toHexKey(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int b1 = Byte.toUnsignedInt(a[i]);
            int b2 = Byte.toUnsignedInt(b[i]);
            if (b1 != b2) {
                return b1 - b2;
            }
        }
        return a.length - b.length;
    }
}
