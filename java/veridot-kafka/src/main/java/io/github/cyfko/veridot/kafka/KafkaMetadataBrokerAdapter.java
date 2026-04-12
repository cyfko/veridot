package io.github.cyfko.veridot.kafka;

import io.github.cyfko.veridot.core.MetadataBroker;
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
 *   <li><strong>Get path</strong>: {@link #get} → local RocksDB lookup (sub-millisecond)</li>
 * </ul>
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
public class KafkaMetadataBrokerAdapter implements MetadataBroker {
    private static final Logger logger = Logger.getLogger(KafkaMetadataBrokerAdapter.class.getName());

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
    public static KafkaMetadataBrokerAdapter of(Properties props){
        if (!props.containsKey(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)){
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
     * @param key     the Protocol V2 {@code messageId} or reserved revocation/config key;
     *                must not be {@code null} or blank
     * @param message the Protocol V2 metadata message to publish; an empty string signals
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
                logger.severe("Error sending the public key to kafka: " + metadata.offset());
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    /**
     * Retrieves the verification metadata message associated with the given key from the
     * local RocksDB store.
     *
     * <p>Since all Kafka messages are persisted locally, this operation does not involve
     * a network round-trip. If the entry was revoked (empty-string send), it will have
     * been deleted from RocksDB and this method will throw.</p>
     *
     * @param keyId the Protocol V2 {@code messageId} or reserved key; must not be {@code null}
     * @return the verification metadata message stored for {@code keyId}
     * @throws BrokerExtractionException if the key does not exist in RocksDB, or if a
     *                                   database access error occurs
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
    /**
     * Schedules a recurring task to persist messages consumed from the Kafka topic into RocksDB.
     * Expired entry cleanup is intentionally omitted: expiration is validated at read-time
     * by {@code GenericSignerVerifier} using TTL metadata embedded in V2 messages.
     */
    private void scheduleAsyncWork() {
        scheduler.scheduleAtFixedRate(() -> {
            saveKafkaMessagesOnEmbeddedDatabase(consumer, db);
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Returns all RocksDB keys whose string representation starts with the given prefix.
     *
     * <p>Uses RocksDB's lexicographic ordering and {@code seek} to efficiently locate the
     * prefix boundary. Internal metadata keys (e.g., the consumer group ID persistence key)
     * are excluded from the results.</p>
     *
     * @param prefix the key prefix to search for (e.g., {@code "2:user-123:"})
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
     * <p>
     * Handles Protocol V2 __REVOKE__ messages (§5) by deleting targeted sequences from RocksDB.
     * </p>
     *
     * @param consumer the KafkaConsumer instance.
     * @param db the RocksDB database.
     */
    private void saveKafkaMessagesOnEmbeddedDatabase(KafkaConsumer<String,String> consumer, RocksDB db) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10)); // At most 10 seconds to wait for.
        for (var record: records) {
            try {
                String key = record.key();
                String message = record.value();
                if (key == null || key.isBlank() || message == null) {
                    continue;
                }

                if (message.isBlank()){
                    // remove this entry (direct deletion or V1-style revocation)
                    db.delete(key.getBytes());
                } else if (key.contains(":__REVOKE__")) {
                    // V2 structured revocation message (§5.2)
                    processRevocationMessage(key, message, db);
                    // Persist the __REVOKE__ message itself (for interoperability)
                    db.put(key.getBytes(), message.getBytes());
                } else {
                    // Normal message — persist on embedded DB
                    byte[] bytes = message.getBytes();
                    db.put(key.getBytes(), bytes);
                }
            } catch (RocksDBException ex) {
                logger.severe(ex.getMessage());
            }
        }
    }

    /**
     * Processes a V2 __REVOKE__ message: parses the {@code target} property and
     * deletes the corresponding sequences from RocksDB.
     *
     * @param key     the revocation key, e.g. {@code "2:user123:__REVOKE__"}
     * @param message the full V2 revocation message with metadata
     * @param db      the RocksDB instance
     */
    private void processRevocationMessage(String key, String message, RocksDB db) {
        try {
            // Parse groupId from key: "2:groupId:__REVOKE__"
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

            if ("__ALL__".equals(target)) {
                // Delete all normal sequences for this group
                String prefix = "2:" + groupId + ":";
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
                String targetKey = "2:" + groupId + ":" + target;
                db.delete(targetKey.getBytes(StandardCharsets.UTF_8));
            }
            logger.info("Processed __REVOKE__ for group " + groupId + ", target=" + target);
        } catch (Exception e) {
            logger.severe("Error processing revocation message: " + e.getMessage());
        }
    }

}
