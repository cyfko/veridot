package io.github.cyfko.veridot.core.impl;

import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import io.github.cyfko.veridot.core.Algorithm;

/// Provide defaults to some constants.
abstract class ConstantDefault {
    static final long KEYS_ROTATION_MINUTES = 1440; // 24 hours
    static final int  ASYMMETRIC_KEY_SIZE   = 3072;  // RSA-3072 (NIST recommendation post-2030)
    static final long RECONCILIATION_INTERVAL_MINUTES = 15;
    static final long CAPABILITY_CACHE_TTL_SECONDS = 60;
    static final long CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = 5;
    static final long MAX_CLOCK_DRIFT_SECONDS = 300;
    static final int  MIN_RSA_KEY_LENGTH = 2048;
    static final long RECONCILIATION_MAX_STALENESS_MINUTES = 60;
}

/// Defines environment variable names.
abstract class Env {
    static final String KEYS_ROTATION_MINUTES = "VDOT_KEYS_ROTATION_MINUTES";
    static final String RECONCILIATION_INTERVAL_MINUTES = "VDOT_RECONCILIATION_INTERVAL_MINUTES";
    static final String CAPABILITY_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_CACHE_TTL_SECONDS";
    static final String CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS";
    static final String CLOCK_DRIFT_TOLERANCE_SECONDS = "VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS";
    static final String ALLOWED_SIG_ALGS = "VDOT_ALLOWED_SIG_ALGS";
    static final String MIN_RSA_KEY_LENGTH = "VDOT_MIN_RSA_KEY_LENGTH";
    static final String WATERMARK_PERSISTENCE_FILE = "VDOT_WATERMARK_PERSISTENCE_FILE";
    static final String RECONCILIATION_MAX_STALENESS_MINUTES = "VDOT_RECONCILIATION_MAX_STALENESS_MINUTES";
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
 *   <tr><td>{@code VDOT_RECONCILIATION_INTERVAL_MINUTES}</td>
 *       <td>{@link #RECONCILIATION_INTERVAL_MINUTES}</td><td>15 (15 min)</td></tr>
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
     * <p>This value corresponds to the version prefix embedded in every Protocol V4
     * {@code messageId} (e.g., {@code "4:user-123:session-A"}).</p>
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
     * The interval in minutes between automatic VersionWatermark periodic reconciliations against the broker.
     *
     * <p>Override with the {@code VDOT_RECONCILIATION_INTERVAL_MINUTES} environment variable.
     * Default is {@code 15} minutes. Reducing this value reduces the cross-instance watermark propagation
     * latency window, at the cost of more frequent {@code broker.snapshot()} query calls.</p>
     */
    public static final long RECONCILIATION_INTERVAL_MINUTES;

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
    public static final long MAX_CLOCK_DRIFT_SECONDS;

    /**
     * Allowed signature algorithms for envelopes.
     */
    public static final Set<Algorithm> ALLOWED_SIG_ALGS;

    /**
     * Minimum allowed RSA public key size in bits.
     */
    public static final int MIN_RSA_KEY_LENGTH;

    /**
     * File path to save and load version watermarks snapshot for persistent monotonicity (§12.3.1).
     */
    public static final String WATERMARK_PERSISTENCE_FILE;

    /**
     * Maximum allowed staleness of the local cache before rejecting validation (Option C).
     */
    public static final long RECONCILIATION_MAX_STALENESS_MINUTES;

    /**
     * How long verified capabilities are cached (positive caching).
     */
    public static final long CAPABILITY_CACHE_TTL_SECONDS;

    /**
     * How long invalid/denied capability results are cached to prevent hammering (negative caching).
     */
    public static final long CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS;

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

        long parsedReconciliation = ConstantDefault.RECONCILIATION_INTERVAL_MINUTES;
        final var reconciliationRate = System.getenv(Env.RECONCILIATION_INTERVAL_MINUTES);
        if (reconciliationRate != null) {
            try {
                long parsed = Long.parseLong(reconciliationRate);
                if (parsed >= 1) {
                    parsedReconciliation = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.RECONCILIATION_INTERVAL_MINUTES + "=" + parsed
                                    + " (must be >= 1). Using default: " + ConstantDefault.RECONCILIATION_INTERVAL_MINUTES);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.RECONCILIATION_INTERVAL_MINUTES + "='" + reconciliationRate
                                + "'. Using default: " + ConstantDefault.RECONCILIATION_INTERVAL_MINUTES);
            }
        }
        RECONCILIATION_INTERVAL_MINUTES = parsedReconciliation;

        long parsedCapCache = ConstantDefault.CAPABILITY_CACHE_TTL_SECONDS;
        final var capCacheRate = System.getenv(Env.CAPABILITY_CACHE_TTL_SECONDS);
        if (capCacheRate != null) {
            try {
                long parsed = Long.parseLong(capCacheRate);
                if (parsed >= 0) {
                    parsedCapCache = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.CAPABILITY_CACHE_TTL_SECONDS + "=" + parsed
                                    + " (must be >= 0). Using default: " + ConstantDefault.CAPABILITY_CACHE_TTL_SECONDS);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.CAPABILITY_CACHE_TTL_SECONDS + "='" + capCacheRate
                                + "'. Using default: " + ConstantDefault.CAPABILITY_CACHE_TTL_SECONDS);
            }
        }
        CAPABILITY_CACHE_TTL_SECONDS = parsedCapCache;

        long parsedCapNegCache = ConstantDefault.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS;
        final var capNegCacheRate = System.getenv(Env.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS);
        if (capNegCacheRate != null) {
            try {
                long parsed = Long.parseLong(capNegCacheRate);
                if (parsed >= 0) {
                    parsedCapNegCache = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS + "=" + parsed
                                    + " (must be >= 0). Using default: " + ConstantDefault.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS + "='" + capNegCacheRate
                                + "'. Using default: " + ConstantDefault.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS);
            }
        }
        CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = parsedCapNegCache;

        long parsedClockDrift = ConstantDefault.MAX_CLOCK_DRIFT_SECONDS;
        final var clockDriftRate = System.getenv(Env.CLOCK_DRIFT_TOLERANCE_SECONDS);
        if (clockDriftRate != null) {
            try {
                long parsed = Long.parseLong(clockDriftRate);
                if (parsed >= 0 && parsed <= 600) {
                    parsedClockDrift = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.CLOCK_DRIFT_TOLERANCE_SECONDS + "=" + parsed
                                    + " (must be between 0 and 600). Using default: " + ConstantDefault.MAX_CLOCK_DRIFT_SECONDS);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.CLOCK_DRIFT_TOLERANCE_SECONDS + "='" + clockDriftRate
                                + "'. Using default: " + ConstantDefault.MAX_CLOCK_DRIFT_SECONDS);
            }
        }
        MAX_CLOCK_DRIFT_SECONDS = parsedClockDrift;

        int parsedMinRsa = ConstantDefault.MIN_RSA_KEY_LENGTH;
        final var minRsaVal = System.getenv(Env.MIN_RSA_KEY_LENGTH);
        if (minRsaVal != null) {
            try {
                int parsed = Integer.parseInt(minRsaVal);
                if (parsed >= 1024) {
                    parsedMinRsa = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.MIN_RSA_KEY_LENGTH + "=" + parsed
                                    + " (must be >= 1024). Using default: " + ConstantDefault.MIN_RSA_KEY_LENGTH);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.MIN_RSA_KEY_LENGTH + "='" + minRsaVal
                                + "'. Using default: " + ConstantDefault.MIN_RSA_KEY_LENGTH);
            }
        }
        MIN_RSA_KEY_LENGTH = parsedMinRsa;

        Set<Algorithm> parsedAlgs = new HashSet<>();
        final var allowedAlgsVal = System.getenv(Env.ALLOWED_SIG_ALGS);
        if (allowedAlgsVal != null && !allowedAlgsVal.isBlank()) {
            String[] parts = allowedAlgsVal.split(",");
            for (String part : parts) {
                String clean = part.trim().toUpperCase();
                if (clean.equals("RSA") || clean.equals("1") || clean.equals("0X01")) {
                    parsedAlgs.add(Algorithm.RSA_SHA256);
                } else if (clean.equals("ECDSA") || clean.equals("ECDSA-SHA256") || clean.equals("2") || clean.equals("0X02")) {
                    parsedAlgs.add(Algorithm.ECDSA_SHA256);
                } else if (clean.equals("RSA-PSS") || clean.equals("3") || clean.equals("0X03")) {
                    parsedAlgs.add(Algorithm.RSA_PSS);
                } else if (clean.equals("ED25519") || clean.equals("EDDSA") || clean.equals("4") || clean.equals("0X04")) {
                    parsedAlgs.add(Algorithm.ED25519);
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring unknown signature algorithm in " + Env.ALLOWED_SIG_ALGS + ": '" + part + "'");
                }
            }
        }
        if (parsedAlgs.isEmpty()) {
            parsedAlgs.add(Algorithm.RSA_SHA256);
            parsedAlgs.add(Algorithm.ECDSA_SHA256);
            parsedAlgs.add(Algorithm.RSA_PSS);
            parsedAlgs.add(Algorithm.ED25519);
        }
        ALLOWED_SIG_ALGS = Collections.unmodifiableSet(parsedAlgs);

        WATERMARK_PERSISTENCE_FILE = System.getenv(Env.WATERMARK_PERSISTENCE_FILE);

        long parsedStaleness = ConstantDefault.RECONCILIATION_MAX_STALENESS_MINUTES;
        final var stalenessVal = System.getenv(Env.RECONCILIATION_MAX_STALENESS_MINUTES);
        if (stalenessVal != null) {
            try {
                long parsed = Long.parseLong(stalenessVal);
                if (parsed >= 1) {
                    parsedStaleness = parsed;
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring invalid " + Env.RECONCILIATION_MAX_STALENESS_MINUTES + "=" + parsed
                                    + " (must be >= 1). Using default: " + ConstantDefault.RECONCILIATION_MAX_STALENESS_MINUTES);
                }
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                        System.Logger.Level.WARNING,
                        "Ignoring non-numeric " + Env.RECONCILIATION_MAX_STALENESS_MINUTES + "='" + stalenessVal
                                + "'. Using default: " + ConstantDefault.RECONCILIATION_MAX_STALENESS_MINUTES);
            }
        }
        RECONCILIATION_MAX_STALENESS_MINUTES = parsedStaleness;
    }
}
