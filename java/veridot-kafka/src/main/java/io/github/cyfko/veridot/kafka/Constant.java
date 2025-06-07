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
 * A Placeholder for some constants.
 */
public abstract class Constant {

    /**
     * Comma-delimited list of host:port pairs to use for establishing the initial connections to the Kafka cluster.
     */
    public static final String KAFKA_BOOSTRAP_SERVERS;

    /**
     * The Kafka topic used for producing/consuming asymmetric public keys.
     */
    public static final String KAFKA_TOKEN_VERIFIER_TOPIC;

    /**
     * The Path to the embedded database directory.
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
