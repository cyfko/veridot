package io.github.cyfko.veridot.kafka;

/**
 * Configuration property keys for the Kafka <em>producer</em> side of the
 * {@link KafkaMetadataBrokerAdapter}.
 *
 * <p>Pass these keys (and their values) in the {@link java.util.Properties} map when
 * constructing a {@link KafkaMetadataBrokerAdapter} via
 * {@link KafkaMetadataBrokerAdapter#of(java.util.Properties) KafkaMetadataBrokerAdapter.of(props)}.</p>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see KafkaMetadataBrokerAdapter
 * @see VerifierConfig
 */
public abstract class SignerConfig {

    /**
     * Property key for the Kafka topic on which verification metadata messages are published.
     *
     * <p>The value must satisfy Kafka topic naming rules (letters, digits, {@code .}, {@code _},
     * {@code -}, max 249 characters). If not set, defaults to the value of
     * {@link Constant#KAFKA_TOKEN_VERIFIER_TOPIC} (env var {@code VDOT_TOKEN_VERIFIER_TOPIC},
     * or built-in default {@code token-verifier}).</p>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * Properties props = new Properties();
     * props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092");
     * props.put(SignerConfig.BROKER_TOPIC_CONFIG, "my-veridot-topic");
     * MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
     * }</pre>
     */
    public static final String BROKER_TOPIC_CONFIG = "veridot.broker.topic";
}
