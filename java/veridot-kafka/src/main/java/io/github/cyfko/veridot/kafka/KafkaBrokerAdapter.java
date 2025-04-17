package io.github.cyfko.veridot.kafka;

import io.github.cyfko.veridot.core.Broker;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A Kafka-based implementation of the {@link io.github.cyfko.veridot.core.Broker} interface that integrates Kafka messaging
 * with an embedded RocksDB key-value store for persistent message tracking and retrieval.
 *
 * <p>This broker adapter supports both sending messages (public keys or signed tokens) to a Kafka topic and retrieving
 * them using a UUID-based mechanism. Kafka messages are asynchronously persisted in a RocksDB database for later retrieval,
 * which is useful when using UUID-based token modes.</p>
 *
 * <p>Features:</p>
 * <ul>
 *   <li>Kafka producer and consumer integration</li>
 *   <li>Embedded RocksDB for message persistence and retrieval</li>
 *   <li>Automatic background processing to persist Kafka records and remove expired entries</li>
 *   <li>Pluggable configuration via {@link Properties}</li>
 * </ul>
 *
 * <p>Required Kafka property:</p>
 * <ul>
 *   <li>{@link org.apache.kafka.clients.producer.ProducerConfig#BOOTSTRAP_SERVERS_CONFIG} - Kafka cluster endpoint(s)</li>
 * </ul>
 *
 * @see io.github.cyfko.veridot.core.Broker
 * @see org.apache.kafka.clients.producer.KafkaProducer
 * @see org.apache.kafka.clients.consumer.KafkaConsumer
 * @see org.rocksdb.RocksDB
 */
public class KafkaBrokerAdapter implements Broker {
    private static final Logger logger = Logger.getLogger(KafkaBrokerAdapter.class.getName());

    private final KafkaProducer<String,String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final RocksDB db;
    private final Options options;
    private final ScheduledExecutorService scheduler;
    private Properties properties;

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
     * Factory method to create a KafkaBrokerAdapter from custom properties.
     *
     * @param props Kafka + DVerify properties.
     * @return a new instance of KafkaBrokerAdapter.
     * @throws IllegalArgumentException if required properties are missing.
     */
    public static KafkaBrokerAdapter of(Properties props){
        if (!props.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)){
            throw new IllegalArgumentException("missing properties when trying to construct KafkaDataSigner");
        }

        Properties usedProps = new Properties();
        usedProps.putAll(props);
        PropertiesUtil.addUniqueKafkaProperties(usedProps);
        return new KafkaBrokerAdapter(usedProps);
    }

    private KafkaBrokerAdapter(Properties props) {
        try {
            // configure embedded database
            this.options = new Options().setCreateIfMissing(true);
            this.db = RocksDB.open(options, props.getProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG));

            // configure Kafka
            props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, getOrCreateUniqueGroupId());
            this.properties = props;
            this.producer = new KafkaProducer<>(properties);
            this.consumer = new KafkaConsumer<>(properties);
            this.consumer.subscribe(List.of(Constant.KAFKA_TOKEN_VERIFIER_TOPIC));

            // Add a shutdown hook to gracefully close the embedded database
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Closing RocksDB...");
                closeDB();
            }));

            // Initialize async work.
            this.scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduleAsyncWork();

        } catch (RocksDBException e) {
            logger.severe(e.getMessage());
            throw new RuntimeException("Unable to initialize KafkaVerifier: Embedded Database could not be opened.");
        }
    }

    /**
     * Construct a KafkaBrokerAdapter using a custom bootstrap server string.
     *
     * @param boostrapServers Kafka bootstrap server(s) to connect to.
     */
    public KafkaBrokerAdapter(String boostrapServers) {
        this(PropertiesUtil.of(defaultKafkaProperties(), boostrapServers));
    }

    /**
     * Construct a KafkaBrokerAdapter using only the default Kafka properties.
     */
    public KafkaBrokerAdapter() {
        this(defaultKafkaProperties());
    }

    /**
     * Sends a message to the configured Kafka topic using the provided key.
     * The message may later be fetched from an embedded RocksDB store.
     *
     * @param key the unique identifier to associate the message with.
     * @param message the message content to send.
     * @return a CompletableFuture that completes when the message is successfully sent or fails.
     */
    @Override
    public CompletableFuture<Void> send(String key, String message) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        producer.send(new ProducerRecord<>(properties.getProperty(SignerConfig.BROKER_TOPIC_CONFIG), key, message), (metadata, exception) -> {
            if (exception == null) {
                logger.info("Public key sent to Kafka successfully with offset: " + metadata.offset());
                future.complete(null);
            } else {
                logger.severe("Error sending the public key to kafka: " + metadata.offset());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Retrieves a stored message by key from the embedded RocksDB store.
     * If the message is not found, a {@link BrokerExtractionException} is thrown.
     *
     * @param keyId the unique identifier of the message.
     * @return the corresponding message content.
     * @throws BrokerExtractionException if the key does not exist or database access fails.
     */
    @Override
    public String get(String keyId) {
        try {
            byte[] bytes = db.get(keyId.getBytes());
            final String messageValue = bytes != null ? new String(bytes) : null;
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
     * Periodically persists Kafka messages and purges expired keys from RocksDB.
     */
    private void scheduleAsyncWork() {
        scheduler.scheduleAtFixedRate(() -> {
            removeOldEmbeddedDatabaseEntries(db);
            saveKafkaMessagesOnEmbeddedDatabase(consumer, db);
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Fetches or generates a persistent group ID used by the Kafka consumer.
     *
     * @return a stable group ID, persisted in RocksDB.
     * @throws RocksDBException if retrieval or persistence fails.
     */
    private String getOrCreateUniqueGroupId() throws RocksDBException {
        byte[] idByte = db.get(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY.getBytes());
        if (idByte == null) {
            idByte = UUID.randomUUID().toString().getBytes();
            db.put(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY.getBytes(), idByte);
        }
        return new String(idByte);
    }

    /**
     * Releases RocksDB and RocksDB Options resources gracefully.
     */
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
     * @param consumer the KafkaConsumer instance.
     * @param db the RocksDB database.
     */
    private static void saveKafkaMessagesOnEmbeddedDatabase(KafkaConsumer<String,String> consumer, RocksDB db) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10)); // At most 10 seconds to wait for.
        for (var record: records) {
            try {
                String key = record.key();
                String message = record.value();
                if (key == null || key.isBlank() || message == null) {
                    continue;
                }

                if (message.isBlank()){
                    // remove this entry
                    db.delete(key.getBytes());
                } else {
                    // persist on embedded DB
                    byte[] bytes = message.getBytes();
                    db.put(key.getBytes(), bytes);
                }
            } catch (RocksDBException ex) {
                logger.severe(ex.getMessage());
            }
        }
    }

    /**
     * Iterates through all RocksDB entries and removes those considered expired based on timestamp logic.
     *
     * @param db the RocksDB instance.
     */
    private static void removeOldEmbeddedDatabaseEntries(RocksDB db) {
        try (RocksIterator iterator = db.newIterator()) {
            long now = System.currentTimeMillis();

            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                String value = new String(iterator.value(), StandardCharsets.UTF_8);
                if (key.equals(ConstantDefault.UNIQUE_BROKER_GROUP_ID_KEY)) continue;

                // Extract timestamp from stored value
                long timestamp = Long.parseLong(value.split(":")[2]);

                // Custom predicate: Delete if expired AND value contains "value1"
                if ((now - timestamp) > 0) {
                    db.delete(iterator.key());
                    logger.info("Deleted key {} after expiration." + key);
                }
            }
        } catch (RocksDBException e) {
            logger.severe(e.getMessage());
        } catch (Exception e) {
            logger.severe("Error when running cleanup task: " + e.getMessage());
        }
    }
}
