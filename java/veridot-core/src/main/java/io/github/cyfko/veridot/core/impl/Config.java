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
 * Runtime configuration constants for the Veridot library, resolved from environment
 * variables at class loading time with sensible built-in defaults.
 *
 * <p>These constants control low-level operational behaviour of the library and are
 * intentionally separate from per-group signing configuration (see
 * {@link BasicConfigurer}).</p>
 *
 * <h2>Environment variables</h2>
 * <table border="1">
 *   <caption>Supported environment variables and their defaults</caption>
 *   <tr><th>Variable</th><th>Constant</th><th>Default</th></tr>
 *   <tr><td>{@code VDOT_KEYS_ROTATION_MINUTES}</td>
 *       <td>{@link #KEYS_ROTATION_MINUTES}</td><td>1440 (24 h)</td></tr>
 * </table>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 */
public abstract class Config {

    /**
     * Protocol version implemented by this library.
     *
     * <p>This value corresponds to the version prefix embedded in every Protocol V2
     * {@code messageId} (e.g., {@code "2:user-123:session-A"}).</p>
     */
    public static final int PROTOCOL_VERSION = ProtocolV2.VERSION;

    /**
     * The interval in minutes between automatic ephemeral key-pair rotations.
     *
     * <p>Override with the {@code VDOT_KEYS_ROTATION_MINUTES} environment variable.
     * Default is {@code 1440} (24 hours). Reducing this value increases forward secrecy
     * but invalidates tokens signed with the previous key sooner.</p>
     */
    public static final long KEYS_ROTATION_MINUTES;

    /**
     * The asymmetric algorithm used to generate signing key pairs.
     *
     * @implNote Currently {@code "RSA"}. This is an implementation detail of
     *           {@code GenericSignerVerifier} and is not mandated by the protocol specification.
     */
    public static final String ASYMMETRIC_KEYPAIR_ALGORITHM = "RSA";

    /**
     * The default cryptographic mode identifier embedded in Protocol V2 metadata messages.
     *
     * <p>This value is stored in the {@code mode} property of each V2 metadata message so
     * that verifiers know which algorithm was used to sign the token.</p>
     *
     * @implNote Currently {@code "rsa"}.
     */
    public static final String DEFAULT_CRYPTO_MODE = "rsa";

    static {
        final var rotationRate = System.getenv(Env.KEYS_ROTATION_MINUTES);
        KEYS_ROTATION_MINUTES = rotationRate != null
                ? Long.parseLong(rotationRate)
                : ConstantDefault.KEYS_ROTATION_MINUTES;
    }
}
