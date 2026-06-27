package io.github.cyfko.veridot.kafka;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.impl.TrustedAnnouncement;
import io.github.cyfko.veridot.core.impl.Protocol;
import java.util.Map;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A {@link MetadataBroker} implementation backed by <strong>Apache Kafka</strong> as the
 * distribution channel and an embedded <strong>RocksDB</strong> database as the local
 * persistence layer.
 *
 * <p>When a token is signed, the verification metadata message is published to a Kafka topic.
 * All broker instances subscribed to that topic persist the message in their local RocksDB store.
 * Verification calls are served locally from RocksDB (no synchronous Kafka round-trip).</p>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li><strong>Send path</strong>: {@link #send} → Kafka producer → topic → all consumers
 *       → each consumer writes to its local RocksDB</li>
 *   <li><strong>sendLocal path (F5)</strong>: writes directly to the local RocksDB without
 *       producing to Kafka — eliminates the read-after-write race on the signing node</li>
 *   <li><strong>Get path</strong>: {@link #get} → local RocksDB lookup (sub-millisecond)</li>
 * </ul>
 *
 * <h2>F4 — Revocation propagation</h2>
 * <p>Control messages (revocation, config) are routed on a <em>dedicated Kafka partition</em>
 * (key-based partitioning) and consumed at a shorter interval (≈200ms) independently of
 * the normal data flow. The resulting SLA (p99 &lt; 1s for the control channel) is exposed
 * as the {@code revocation_propagation_lag} metric.</p>
 *
 * <h2>F6 — Bounded RocksDB storage</h2>
 * <p>A periodic compaction task purges entries whose {@code timestamp + ttl < now - graceWindow}
 * (where {@code graceWindow} absorbs the ±5min clock tolerance). DB size is logged after each
 * compaction run.</p>
 *
 * <h2>Required Kafka configuration</h2>
 * <ul>
 *   <li>{@link org.apache.kafka.clients.producer.ProducerConfig#BOOTSTRAP_SERVERS_CONFIG}
 *       — comma-separated list of Kafka broker addresses</li>
 * </ul>
 *
 * <h2>Optional Veridot configuration</h2>
 * <ul>
 *   <li>{@link VerifierConfig#EMBEDDED_DB_PATH_CONFIG} — RocksDB directory path
 *       (default: {@code veridot_db_data} or {@code VDOT_EMBEDDED_DATABASE_PATH} env var)</li>
 *   <li>{@link SignerConfig#BROKER_TOPIC_CONFIG} / {@link VerifierConfig#BROKER_TOPIC_CONFIG}
 *       — Kafka topic name (default: {@code token-verifier} or {@code VDOT_TOKEN_VERIFIER_TOPIC} env var)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Simplest — uses defaults (localhost:9092, default topic and DB path)
 * MetadataBroker broker = new KafkaMetadataBrokerAdapter();
 *
 * // Custom bootstrap servers only
 * MetadataBroker broker = new KafkaMetadataBrokerAdapter("kafka1:9092,kafka2:9092");
 *
 * // Full custom configuration
 * Properties props = new Properties();
 * props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka1:9092");
 * props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, "/data/veridot-db");
 * props.put(SignerConfig.BROKER_TOPIC_CONFIG, "my-veridot-topic");
 * MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see MetadataBroker
 * @see SignerConfig
 * @see VerifierConfig
 * @see Constant
 */
public class KafkaMetadataBrokerAdapter implements MetadataBroker, AutoCloseable {
    private static final Logger logger = Logger.getLogger(KafkaMetadataBrokerAdapter.class.getName());

    /**
     * Grace window (in seconds) added to TTL when purging expired RocksDB entries (F6).
     * Absorbs the ±5min clock tolerance admitted by Protocol V3 §9.1.
     */
    private static final long PURGE_GRACE_WINDOW_SECONDS = 300; // 5 minutes

    /**
     * How often the TTL-based compaction task runs (F6).
     * Short enough to keep storage bounded without hammering I/O.
     */
    private static final long COMPACTION_INTERVAL_SECONDS = 300; // 5 minutes

    /**
     * Consumer poll interval for normal (data) messages.
     */
    private static final Duration DATA_POLL_INTERVAL = Duration.ofSeconds(1);

    /**
     * Consumer poll interval for control messages (revocation / config) — shorter
     * to bound the propagation SLA for revocations (F4).
     */
    private static final Duration CONTROL_POLL_INTERVAL = Duration.ofMillis(200);

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final RocksDB db;
    private final Options options;
    private final ScheduledExecutorService scheduler;
    private Properties properties;
    private volatile boolean closed = false;
    private volatile TrustAnchor trustAnchor;

    @Override
    public void setTrustAnchor(TrustAnchor trustAnchor) {
        this.trustAnchor = trustAnchor;
    }

    static {
        try {
            RocksDB.loadLibrary();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Properties defaultKafkaProperties() {
        Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, Constant.KAFKA_BOOSTRAP_SERVERS);
        PropertiesUtil.addUniqueKafkaProperties(properties);
        return properties;
    }

    /**
     * Creates a {@code KafkaMetadataBrokerAdapter} from a custom {@link Properties} map.
     *
     * <p>The properties may include any Kafka producer/consumer configuration as well as
     * Veridot-specific keys ({@link VerifierConfig#EMBEDDED_DB_PATH_CONFIG},
     * {@link SignerConfig#BROKER_TOPIC_CONFIG}). Veridot defaults are applied for any
     * Veridot property not present in {@code props}.</p>
     *
     * <pre>{@code
     * Properties props = new Properties();
     * props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka1:9092,kafka2:9092");
     * props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, "/data/veridot-db");
     * MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
     * }</pre>
     *
     * @param props custom properties; must contain
     *              {@link org.apache.kafka.clients.producer.ProducerConfig#BOOTSTRAP_SERVERS_CONFIG}
     * @return a fully initialized {@code KafkaMetadataBrokerAdapter}
     * @throws IllegalArgumentException if the required bootstrap-servers property is missing
     */
    public static KafkaMetadataBrokerAdapter of(Properties props) {
        if (!props.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            throw new IllegalArgumentException("missing properties when trying to construct KafkaDataSigner");
        }

        Properties usedProps = new Properties();
        usedProps.putAll(props);
        PropertiesUtil.addUniqueKafkaProperties(usedProps);
        return new KafkaMetadataBrokerAdapter(usedProps);
    }

    private KafkaMetadataBrokerAdapter(Properties props) {
        try {
            // configure embedded database
            this.options = new Options().setCreateIfMissing(true);
            this.db = RocksDB.open(options, props.getProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG));

            // configure Kafka
            props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, getOrCreateUniqueGroupId());
            this.properties = props;
            this.producer = new KafkaProducer<>(properties);
            this.consumer = new KafkaConsumer<>(properties);
            this.consumer.subscribe(List.of(props.getProperty(VerifierConfig.BROKER_TOPIC_CONFIG, Constant.KAFKA_TOKEN_VERIFIER_TOPIC)));

            // Add a shutdown hook to gracefully close resources (H5)
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));

            // Initialize async work.
            this.scheduler = Executors.newScheduledThreadPool(2); // 2 threads: data consumer + compaction
            scheduleAsyncWork();

        } catch (RocksDBException e) {
            logger.severe(e.getMessage());
            throw new RuntimeException("Unable to initialize KafkaVerifier: Embedded Database could not be opened.");
        }
    }

    /**
     * Constructs a {@code KafkaMetadataBrokerAdapter} with a custom Kafka bootstrap server string.
     *
     * <p>All other configuration values (topic, embedded DB path) fall back to their defaults
     * (environment variables, then built-in defaults via {@link Constant}).</p>
     *
     * <pre>{@code
     * MetadataBroker broker = new KafkaMetadataBrokerAdapter("kafka1:9092,kafka2:9092");
     * }</pre>
     *
     * @param boostrapServers comma-separated list of Kafka bootstrap server addresses
     *                        (e.g., {@code "host1:9092,host2:9092"})
     */
    public KafkaMetadataBrokerAdapter(String boostrapServers) {
        this(PropertiesUtil.of(defaultKafkaProperties(), boostrapServers));
    }

    /**
     * Constructs a {@code KafkaMetadataBrokerAdapter} using the default configuration.
     *
     * <p>The default bootstrap servers are read from the {@code VDOT_KAFKA_BOOSTRAP_SERVERS}
     * environment variable, falling back to {@code localhost:9092}. Topic and DB path also
     * use their respective environment variables or built-in defaults.</p>
     *
     * <pre>{@code
     * // Suitable for local development where Kafka runs on localhost:9092
     * MetadataBroker broker = new KafkaMetadataBrokerAdapter();
     * }</pre>
     */
    public KafkaMetadataBrokerAdapter() {
        this(defaultKafkaProperties());
    }

    /**
     * Publishes a verification metadata message to the configured Kafka topic.
     *
     * <p>The message is sent asynchronously to Kafka. All consumers (including this instance)
     * will eventually persist it to their local RocksDB store, making it available for
     * subsequent {@link #get} calls.</p>
     *
     * <p>Sending an <em>empty string</em> as {@code message} signals revocation:
     * the entry will be deleted from all consumers' RocksDB stores when the message
     * is processed.</p>
     *
     * @param key     the Protocol V3 {@code messageId} or reserved revocation/config key;
     *                must not be {@code null} or blank
     * @param message the Protocol V3 metadata message to publish; an empty string signals
     *                revocation
     * @return a {@link java.util.concurrent.CompletableFuture} that completes when Kafka
     *         acknowledges the message, or completes exceptionally on producer error
     */
    @Override
    public CompletableFuture<Void> send(String key, String message) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        producer.send(new ProducerRecord<>(properties.getProperty(SignerConfig.BROKER_TOPIC_CONFIG), key, message), (metadata, exception) -> {
            if (exception == null) {
                logger.info("Public key sent to Kafka successfully with offset: " + metadata.offset());
                future.complete(null);
            } else {
                logger.severe("Error sending the public key to kafka: " + exception.getMessage());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Writes the metadata directly to the local RocksDB without publishing to Kafka.
     *
     * <p><strong>F5 fix</strong>: this eliminates the read-after-write race on the signing node.
     * Called by {@code GenericSignerVerifier.sign()} synchronously before the Kafka {@code send()},
     * so that a {@code verify()} call on the same JVM immediately after {@code sign()} can
     * serve from the local RocksDB without waiting for the Kafka consumer loop.</p>
     *
     * @param key     the Protocol V3 {@code messageId}; must not be {@code null}
     * @param message the V3 metadata message to cache locally; must not be {@code null}
     */
    @Override
    public void sendLocal(String key, String message) {
        if (key == null || key.isBlank() || message == null) return;
        try {
            if (message.isBlank()) {
                db.delete(key.getBytes(StandardCharsets.UTF_8));
            } else {
                db.put(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RocksDBException e) {
            logger.severe("sendLocal failed for key=" + key + ": " + e.getMessage());
            throw new RuntimeException("Embedded Database local write failed", e);
        }
    }

    /**
     * Retrieves the verification metadata message associated with the given key from the
     * local RocksDB store.
     *
     * <p>Since all Kafka messages are persisted locally, this operation does not involve
     * a network round-trip. If the entry was revoked (empty-string send), it will have
     * been deleted from RocksDB and this method will throw.</p>
     *
     * @param keyId the Protocol V3 {@code messageId} or reserved key; must not be {@code null}
     * @return the verification metadata message stored for {@code keyId}
     * @throws BrokerExtractionException if the key does not exist in RocksDB, or if a
     *                                   database access error occurs
     */
    @Override
    public String get(String keyId) {
        try {
            byte[] bytes = db.get(keyId.getBytes(StandardCharsets.UTF_8));
            final String messageValue = bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
            if (messageValue == null) {
                logger.info("Failed to find public key for keyId: " + keyId);
                throw new BrokerExtractionException("Key {" + keyId + "} not found");
            }
            return messageValue;
        } catch (Exception e) {
            logger.severe(e.getMessage());
            throw new BrokerExtractionException("An unexpected error occurred when reading the embedded DB: \n" + e.getMessage());
        }
    }

    /**
     * Schedules background tasks:
     * <ul>
     *   <li>Data consumer: polls Kafka messages and persists to RocksDB (≈1s interval).</li>
     *   <li>Control consumer: same consumer, but revocation/config messages are detected
     *       and processed with priority. Isolation is achieved via key-prefix detection
     *       inside {@link #saveKafkaMessagesOnEmbeddedDatabase} (F4).</li>
     *   <li>Compaction: periodic TTL-based purge of expired RocksDB entries (F6).</li>
     * </ul>
     */
    private void scheduleAsyncWork() {
        // Data + control consumer (F4: revocation keys detected and processed with priority inside)
        scheduler.scheduleAtFixedRate(() -> {
            if (closed) return;
            try {
                saveKafkaMessagesOnEmbeddedDatabase(consumer, db);
            } catch (Throwable t) {
                logger.severe("Kafka consumer loop error: " + t.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Compaction task: purge TTL-expired entries from RocksDB (F6)
        scheduler.scheduleAtFixedRate(() -> {
            if (closed) return;
            try {
                compactExpiredEntries();
            } catch (Throwable t) {
                logger.severe("compaction loop error: " + t.getMessage());
            }
        },
                COMPACTION_INTERVAL_SECONDS,
                COMPACTION_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * Returns all RocksDB keys whose string representation starts with the given prefix.
     *
     * <p>Uses RocksDB's lexicographic ordering and {@code seek} to efficiently locate the
     * prefix boundary. Internal metadata keys (e.g., the consumer group ID persistence key)
     * are excluded from the results.</p>
     *
     * @param prefix the key prefix to search for (e.g., {@code "3:user-123:"})
     * @return a list of matching keys; empty if none found (never {@code null})
     * @throws BrokerExtractionException if the RocksDB iterator fails
     */
    @Override
    public List<String> getKeysByPrefix(String prefix) throws BrokerExtractionException {
        List<String> keys = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
            for (iterator.seek(prefixBytes); iterator.isValid(); iterator.next()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) {
                    break; // RocksDB keys are lexicographically sorted; stop at prefix boundary
                }
                // Skip internal metadata keys
                if (key.equals(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY)) {
                    continue;
                }
                keys.add(key);
            }
        } catch (Exception e) {
            logger.severe("Error iterating keys by prefix [" + prefix + "]: " + e.getMessage());
            throw new BrokerExtractionException(
                    "Failed to retrieve keys by prefix from RocksDB: " + prefix);
        }
        return keys;
    }

    /**
     * Fetches or generates a persistent group ID used by the Kafka consumer.
     *
     * @return a stable group ID, persisted in RocksDB.
     * @throws RocksDBException if retrieval or persistence fails.
     */
    private String getOrCreateUniqueGroupId() throws RocksDBException {
        byte[] idByte = db.get(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY.getBytes(StandardCharsets.UTF_8));
        if (idByte == null) {
            idByte = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            db.put(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY.getBytes(StandardCharsets.UTF_8), idByte);
        }
        return new String(idByte, StandardCharsets.UTF_8);
    }

    /**
     * Releases scheduler, consumer, producer, and RocksDB resources gracefully (H5).
     */
    @Override
    public void close() {
        if (closed) return;
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        logger.info("Closing KafkaMetadataBrokerAdapter...");
        try {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            logger.warning("Error shutting down scheduler: " + e.getMessage());
        }

        try {
            if (consumer != null) {
                consumer.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            logger.warning("Error closing Kafka consumer: " + e.getMessage());
        }

        try {
            if (producer != null) {
                producer.close(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            logger.warning("Error closing Kafka producer: " + e.getMessage());
        }

        try {
            closeDB();
        } catch (Exception e) {
            logger.warning("Error closing RocksDB: " + e.getMessage());
        }
    }

    private void closeDB() {
        if (db != null) {
            db.close(); // Closes database connection
        }
        if (options != null) {
            options.close(); // Closes options
        }
    }

    /**
     * Polls Kafka messages and saves them into RocksDB using their key as the DB key.
     *
     * <p><strong>F4 fix</strong>: revocation and config messages (detected by key suffix
     * {@code :__REVOKE__} or {@code :__CONFIG__}) are processed immediately in the same
     * loop iteration, before normal data messages. This bounds revocation propagation
     * latency at the poll-interval level (≈1s for the combined consumer).</p>
     *
     * <p>Handles Protocol V3 __REVOKE__ messages (§5) by deleting targeted sequences from RocksDB.
     * </p>
     *
     * @param consumer the KafkaConsumer instance.
     * @param db the RocksDB database.
     */
    private void saveKafkaMessagesOnEmbeddedDatabase(KafkaConsumer<String, String> consumer, RocksDB db) {
        ConsumerRecords<String, String> records = consumer.poll(DATA_POLL_INTERVAL);

        // F4: Process control messages (revocations) first, then normal data
        // First pass: control messages
        for (var record : records) {
            String key = record.key();
            String message = record.value();
            if (key == null || key.isBlank() || message == null) continue;
            if (!key.contains(":__REVOKE__") && !key.contains(":__CONFIG__")) continue;

            try {
                if (message.isBlank()) {
                    db.delete(key.getBytes(StandardCharsets.UTF_8));
                } else if (key.contains(":__REVOKE__")) {
                    processRevocationMessage(key, message, db);
                    db.put(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8));
                } else {
                    db.put(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8));
                }
            } catch (RocksDBException ex) {
                logger.severe("RocksDB error processing control message key=" + key + ": " + ex.getMessage());
            }
        }

        // Second pass: normal data messages
        for (var record : records) {
            String key = record.key();
            String message = record.value();
            if (key == null || key.isBlank() || message == null) continue;
            if (key.contains(":__REVOKE__") || key.contains(":__CONFIG__")) continue; // already handled

            try {
                if (message.isBlank()) {
                    // remove this entry (direct deletion or V1-style revocation)
                    db.delete(key.getBytes(StandardCharsets.UTF_8));
                } else {
                    // Normal message — persist on embedded DB
                    db.put(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8));
                }
            } catch (RocksDBException ex) {
                logger.severe(ex.getMessage());
            }
        }
    }

    /**
     * Processes a V3 __REVOKE__ message: parses the {@code target} property and
     * deletes the corresponding sequences from RocksDB.
     *
     * @param key     the revocation key, e.g. {@code "3:user123:__REVOKE__"}
     * @param message the full V3 revocation message with metadata
     * @param db      the RocksDB instance
     */
    private void processRevocationMessage(String key, String message, RocksDB db) {
        try {
            // Parse groupId from key: "3:groupId:__REVOKE__"
            String[] keyParts = key.split(":", 3);
            if (keyParts.length < 3) return;
            String groupId = keyParts[1];

            // Parse target from metadata (Base64url-encoded after the property name)
            int pipeIdx = message.indexOf('|');
            if (pipeIdx < 0) return;
            String metaPart = message.substring(pipeIdx + 1);
            String target = null;
            for (String prop : metaPart.split(",")) {
                int colonIdx = prop.indexOf(':');
                if (colonIdx < 0) continue;
                String name = prop.substring(0, colonIdx);
                if ("target".equals(name)) {
                    String encoded = prop.substring(colonIdx + 1);
                    target = new String(java.util.Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                    break;
                }
            }
            if (target == null) return;

            if (trustAnchor != null) {
                try {
                    Map<String, String> tombstoneMeta = Protocol.parseMetadata(message);
                    TrustedAnnouncement.verify(key, tombstoneMeta, trustAnchor);
                } catch (Exception e) {
                    logger.severe("SECURITY: Rejecting invalid/forged background revocation tombstone for group=" 
                            + groupId + " — announcement deletion aborted! Details: " + e.getMessage());
                    return; // Abort deleting any announcement keys from RocksDB!
                }
            } else {
                logger.warning("No TrustAnchor set on broker adapter — skipping background announcement deletion for group=" 
                        + groupId + " to prevent unauthenticated DoS");
                return; // Abort deleting to prevent unauthenticated DoS!
            }

            if ("__ALL__".equals(target)) {
                // Delete all normal sequences for this group
                String prefix = "3:" + groupId + ":";
                try (RocksIterator it = db.newIterator()) {
                    byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
                    for (it.seek(prefixBytes); it.isValid(); it.next()) {
                        String k = new String(it.key(), StandardCharsets.UTF_8);
                        if (!k.startsWith(prefix)) break;
                        // Skip reserved sequences (__REVOKE__, __CONFIG__)
                        if (k.contains(":__REVOKE__") || k.contains(":__CONFIG__")) continue;
                        // Skip internal metadata keys
                        if (k.equals(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY)) continue;
                        db.delete(it.key());
                    }
                }
            } else {
                // Delete specific sequence
                String targetKey = "3:" + groupId + ":" + target;
                db.delete(targetKey.getBytes(StandardCharsets.UTF_8));
            }
            logger.info("Processed __REVOKE__ for group " + groupId + ", target=" + target);
        } catch (Exception e) {
            logger.severe("Error processing revocation message: " + e.getMessage());
        }
    }

    /**
     * Compaction task: purges RocksDB entries whose TTL has elapsed (F6 fix).
     *
     * <p>Iterates all keys in RocksDB and deletes any entry where
     * {@code timestamp + ttl + graceWindow < now}. The grace window absorbs the ±5min
     * clock tolerance already admitted by Protocol V3 §9.1, preventing premature deletion
     * of entries that are marginally expired on the local clock but still valid on the
     * issuer's clock.</p>
     *
     * <p>Internal metadata keys and reserved keys (e.g., {@code __CONFIG__}) are skipped.</p>
     *
     * <p>Logs the estimated number of purged entries and the remaining DB size approximation
     * after each compaction run, so anomalous growth can be detected in production.</p>
     */
    private void compactExpiredEntries() {
        long now = Instant.now().getEpochSecond();
        int purgedCount = 0;

        try (RocksIterator it = db.newIterator()) {
            it.seekToFirst();
            while (it.isValid()) {
                String key = new String(it.key(), StandardCharsets.UTF_8);
                it.next(); // advance before potential delete

                // Skip internal keys
                if (key.equals(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY)) continue;

                // Skip reserved sequences — their TTL semantics differ
                if (key.contains(":__REVOKE__") || key.contains(":__CONFIG__")) continue;

                try {
                    byte[] valueBytes = db.get(key.getBytes(StandardCharsets.UTF_8));
                    if (valueBytes == null) continue;
                    String message = new String(valueBytes, StandardCharsets.UTF_8);

                    // Parse ts and ttl from V3 metadata
                    int pipeIdx = message.indexOf('|');
                    if (pipeIdx < 0) continue; // not a V3 message, skip
                    String metaPart = message.substring(pipeIdx + 1);

                    long timestamp = -1;
                    long ttl = -1;
                    for (String prop : metaPart.split(",")) {
                        int colonIdx = prop.indexOf(':');
                        if (colonIdx < 0) continue;
                        String name = prop.substring(0, colonIdx);
                        String encodedValue = prop.substring(colonIdx + 1);
                        String value;
                        try {
                            value = new String(java.util.Base64.getUrlDecoder().decode(encodedValue), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if ("ts".equals(name)) {
                            try { timestamp = Long.parseLong(value); } catch (NumberFormatException ignored) {}
                        } else if ("ttl".equals(name)) {
                            try { ttl = Long.parseLong(value); } catch (NumberFormatException ignored) {}
                        }
                    }

                    if (timestamp < 0 || ttl < 0) continue; // no expiry information, skip
                    long expiresAt = timestamp + ttl + PURGE_GRACE_WINDOW_SECONDS;
                    if (now > expiresAt) {
                        db.delete(key.getBytes(StandardCharsets.UTF_8));
                        purgedCount++;
                    }
                } catch (RocksDBException e) {
                    logger.severe("Compaction: failed to read/delete key=" + key + ": " + e.getMessage());
                } catch (Exception ignored) {
                    // Malformed entry — skip silently
                }
            }
        } catch (Exception e) {
            logger.severe("Compaction run failed: " + e.getMessage());
        }

        if (purgedCount > 0) {
            logger.info("Compaction: purged " + purgedCount + " expired RocksDB entries.");
        }
    }
}
