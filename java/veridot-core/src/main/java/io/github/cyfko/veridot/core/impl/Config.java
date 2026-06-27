package io.github.cyfko.veridot.core.impl;

/// Provide defaults to some constants.
abstract class ConstantDefault {
    static final long KEYS_ROTATION_MINUTES = 1440; // 24 hours
    static final int  ASYMMETRIC_KEY_SIZE   = 3072;  // RSA-3072 (NIST recommendation post-2030)
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

    private Config() {
        // Prevent instantiation of config constants holder
    }

    /**
     * Protocol version implemented by this library.
     *
     * <p>This value corresponds to the version prefix embedded in every Protocol V3
     * {@code messageId} (e.g., {@code "3:user-123:session-A"}).</p>
     */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

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
     * The RSA key size in bits used when generating ephemeral signing key pairs.
     *
     * <p>Veridot uses <strong>RSA-3072</strong> by default. RSA-2048 was the previous
     * implicit default, but is scheduled for deprecation by NIST around 2030-2035.
     * By targeting 3072 now we avoid a second breaking change when that deprecation
     * becomes effective.</p>
     *
     * @implNote Value: {@code 3072}.
     */
    public static final int ASYMMETRIC_KEY_SIZE = ConstantDefault.ASYMMETRIC_KEY_SIZE;

    /**
     * The default cryptographic mode identifier embedded in Protocol V3 metadata messages.
     *
     * <p>This value is stored in the {@code alg} property of each V3 metadata message so
     * that verifiers know which algorithm was used to sign the token.</p>
     *
     * @implNote Currently {@code "rsa"}.
     */
    public static final String DEFAULT_CRYPTO_MODE = "rsa";

    /**
     * How long resolved configs are cached before re-querying the broker.
     */
    public static final long CONFIG_CACHE_TTL_SECONDS = 60;

    /**
     * Maximum allowed clock drift between signer and verifier (§9.1).
     */
    public static final long MAX_CLOCK_DRIFT_SECONDS = 300;

    static {
        long parsedRotation = ConstantDefault.KEYS_ROTATION_MINUTES;
        final var rotationRate = System.getenv(Env.KEYS_ROTATION_MINUTES);
        if (rotationRate != null) {
            try {
                long parsed = Long.parseLong(rotationRate);
                if (parsed >= 1) {
                    parsedRotation = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.KEYS_ROTATION_MINUTES + "=" + parsed
                                    + " (must be >= 1). Using default: " + ConstantDefault.KEYS_ROTATION_MINUTES);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.KEYS_ROTATION_MINUTES + "='" + rotationRate
                                + "'. Using default: " + ConstantDefault.KEYS_ROTATION_MINUTES);
            }
        }
        KEYS_ROTATION_MINUTES = parsedRotation;
    }
}
