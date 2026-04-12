package io.github.cyfko.veridot.kafka;

/**
 * Configuration property keys for the Kafka <em>consumer</em> and RocksDB persistence side
 * of the {@link KafkaMetadataBrokerAdapter}.
 *
 * <p>Pass these keys (and their values) in the {@link java.util.Properties} map when
 * constructing a {@link KafkaMetadataBrokerAdapter} via
 * {@link KafkaMetadataBrokerAdapter#of(java.util.Properties) KafkaMetadataBrokerAdapter.of(props)}.</p>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see KafkaMetadataBrokerAdapter
 * @see SignerConfig
 */
public abstract class VerifierConfig {

    /**
     * Property key for the file-system path of the embedded RocksDB directory.
     *
     * <p>The directory is created automatically by RocksDB if it does not exist.
     * Use an absolute path to avoid working-directory ambiguity. If not set, defaults
     * to the value of {@link Constant#EMBEDDED_DATABASE_PATH} (env var
     * {@code VDOT_EMBEDDED_DATABASE_PATH}, or built-in default {@code veridot_db_data}).</p>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, "/var/lib/veridot/db");
     * }</pre>
     */
    public static final String EMBEDDED_DB_PATH_CONFIG = "veridot.embedded.db";

    /**
     * Property key for the Kafka topic from which verification metadata messages are consumed.
     *
     * <p>Must match the topic configured on the producer side ({@link SignerConfig#BROKER_TOPIC_CONFIG}).
     * If not set, defaults to the value of {@link Constant#KAFKA_TOKEN_VERIFIER_TOPIC} (env var
     * {@code VDOT_TOKEN_VERIFIER_TOPIC}, or built-in default {@code token-verifier}).</p>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * props.put(VerifierConfig.BROKER_TOPIC_CONFIG, "my-veridot-topic");
     * }</pre>
     */
    public static final String BROKER_TOPIC_CONFIG = "veridot.broker.topic";
}
