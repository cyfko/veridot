package io.github.cyfko.veridot.core.impl;

/// Provide defaults to some constants.
abstract class ConstantDefault {
    static final long KEYS_ROTATION_MINUTES = 1440; // 24 hours
}

/// Defines environment variable names.
abstract class Env {
    static final String KEYS_ROTATION_MINUTES = "VDOT_KEYS_ROTATION_MINUTES";
}

/**
 * A Placeholder for some constants.
 */
public abstract class Config {

    /**
     * Protocol version implemented by this library.
     */
    public static final int PROTOCOL_VERSION = ProtocolV2.VERSION;

    /**
     * The interval in minutes before the current used Asymmetric keys-pair changes.
     */
    public static final long KEYS_ROTATION_MINUTES;

    /**
     * Algorithm used to generate asymmetric keys pair.
     */
    public static final String ASYMMETRIC_KEYPAIR_ALGORITHM = "RSA";

    /**
     * Default cryptographic mode identifier used in Protocol V2 messages.
     */
    public static final String DEFAULT_CRYPTO_MODE = "rsa";

    static {
        final var rotationRate = System.getenv(Env.KEYS_ROTATION_MINUTES);
        KEYS_ROTATION_MINUTES = rotationRate != null
                ? Long.parseLong(rotationRate)
                : ConstantDefault.KEYS_ROTATION_MINUTES;
    }
}
