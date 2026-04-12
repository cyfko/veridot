package io.github.cyfko.veridot.kafka;

/// Provide defaults to some constants.
abstract class ConstantDefault{
    static final String KAFKA_BOOSTRAP_SERVERS = "localhost:9092";
    static final String TOKEN_VERIFIER_TOPIC = "token-verifier";
    static final String EMBEDDED_DATABASE_PATH = "veridot_db_data";
    static final String UNIQUE_BROKER_GROUP_ID_KEY = "veridot_db";
}

/// Defines environment variable names.
abstract class Env{
    static final String KAFKA_BOOSTRAP_SERVERS = "VDOT_KAFKA_BOOSTRAP_SERVERS";
    static final String KAFKA_TOKEN_VERIFIER_TOPIC = "VDOT_TOKEN_VERIFIER_TOPIC";
    static final String EMBEDDED_DATABASE_PATH = "VDOT_EMBEDDED_DATABASE_PATH";
}

/**
 * Runtime constants resolved from environment variables for the Kafka adapter,
 * with sensible built-in defaults.
 *
 * <p>These constants are used by {@link KafkaMetadataBrokerAdapter} when no explicit
 * configuration is provided. Override any value through the corresponding environment
 * variable (set before the JVM starts):</p>
 *
 * <table border="1">
 *   <caption>Environment variable overrides</caption>
 *   <tr><th>Environment variable</th><th>Constant</th><th>Default value</th></tr>
 *   <tr><td>{@code VDOT_KAFKA_BOOSTRAP_SERVERS}</td>
 *       <td>{@link #KAFKA_BOOSTRAP_SERVERS}</td><td>{@code localhost:9092}</td></tr>
 *   <tr><td>{@code VDOT_TOKEN_VERIFIER_TOPIC}</td>
 *       <td>{@link #KAFKA_TOKEN_VERIFIER_TOPIC}</td><td>{@code token-verifier}</td></tr>
 *   <tr><td>{@code VDOT_EMBEDDED_DATABASE_PATH}</td>
 *       <td>{@link #EMBEDDED_DATABASE_PATH}</td><td>{@code veridot_db_data}</td></tr>
 * </table>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see KafkaMetadataBrokerAdapter
 */
public abstract class Constant {

    /**
     * Comma-delimited list of {@code host:port} pairs used to establish the initial
     * connection to the Kafka cluster (bootstrap servers).
     *
     * <p>Override with the {@code VDOT_KAFKA_BOOSTRAP_SERVERS} environment variable.
     * Default: {@code localhost:9092}.</p>
     */
    public static final String KAFKA_BOOSTRAP_SERVERS;

    /**
     * The Kafka topic on which verification metadata messages are produced and consumed.
     *
     * <p>All services sharing the same topic will receive and locally persist every
     * metadata message, enabling cross-service token verification. Override with the
     * {@code VDOT_TOKEN_VERIFIER_TOPIC} environment variable. Default: {@code token-verifier}.</p>
     */
    public static final String KAFKA_TOKEN_VERIFIER_TOPIC;

    /**
     * The file-system path of the embedded RocksDB directory used to persist
     * verification metadata locally.
     *
     * <p>The directory is created automatically if it does not exist.
     * Override with the {@code VDOT_EMBEDDED_DATABASE_PATH} environment variable.
     * Default: {@code veridot_db_data} (relative to the working directory).</p>
     */
    public static final String EMBEDDED_DATABASE_PATH;


    static {
        final var boostrapServers = System.getenv(Env.KAFKA_BOOSTRAP_SERVERS);
        KAFKA_BOOSTRAP_SERVERS = boostrapServers != null ? boostrapServers : ConstantDefault.KAFKA_BOOSTRAP_SERVERS;

        final var topic = System.getenv(Env.KAFKA_TOKEN_VERIFIER_TOPIC);
        KAFKA_TOKEN_VERIFIER_TOPIC = topic != null ? topic: ConstantDefault.TOKEN_VERIFIER_TOPIC;

        final var dbPath = System.getenv(Env.EMBEDDED_DATABASE_PATH);
        EMBEDDED_DATABASE_PATH = dbPath != null ? dbPath : ConstantDefault.EMBEDDED_DATABASE_PATH;
    }
}
