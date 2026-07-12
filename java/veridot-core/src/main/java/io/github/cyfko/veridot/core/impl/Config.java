package io.github.cyfko.veridot.core.impl;

import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import io.github.cyfko.veridot.core.Algorithm;

/// Provide defaults to some constants.
abstract class ConstantDefault {
    static final long RECONCILIATION_INTERVAL_MINUTES = 15;
    static final long CAPABILITY_CACHE_TTL_SECONDS = 10;
    static final long CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = 5;
    static final long MAX_CLOCK_DRIFT_SECONDS = 300;
    static final int  MIN_RSA_KEY_LENGTH = 2048;
    static final long RECONCILIATION_MAX_STALENESS_MINUTES = 60;
    // V5 additions
    static final long TRUST_CACHE_SYNC_HOURS = 6;
    static final long TRUST_STALE_WINDOW_SECONDS = 3600;
    static final long TAAS_DIGEST_INTERVAL_SECONDS = 3600;
    static final int  DIGEST_TOLERANCE = 2;
    static final long FENCE_ANCHOR_MAX_AGE_SECONDS = 600;
}

/// Defines environment variable names.
abstract class Env {
    static final String RECONCILIATION_INTERVAL_MINUTES = "VDOT_RECONCILIATION_INTERVAL_MINUTES";
    static final String CAPABILITY_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_CACHE_TTL_SECONDS";
    static final String CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS";
    static final String CLOCK_DRIFT_TOLERANCE_SECONDS = "VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS";
    static final String ALLOWED_SIG_ALGS = "VDOT_ALLOWED_SIG_ALGS";
    static final String MIN_RSA_KEY_LENGTH = "VDOT_MIN_RSA_KEY_LENGTH";
    static final String WATERMARK_PERSISTENCE_FILE = "VDOT_WATERMARK_PERSISTENCE_FILE";
    static final String RECONCILIATION_MAX_STALENESS_MINUTES = "VDOT_RECONCILIATION_MAX_STALENESS_MINUTES";
    // V5 additions
    static final String TRUST_CACHE_SYNC_HOURS = "VDOT_TRUST_CACHE_SYNC_HOURS";
    static final String TRUST_STALE_WINDOW_SECONDS = "VDOT_TRUST_STALE_WINDOW_SECONDS";
    static final String TAAS_DIGEST_INTERVAL = "VDOT_TAAS_DIGEST_INTERVAL";
    static final String DIGEST_TOLERANCE = "VDOT_DIGEST_TOLERANCE";
    static final String FENCE_ANCHOR_MAX_AGE = "VDOT_FENCE_ANCHOR_MAX_AGE";
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
     * <p>This value corresponds to the version prefix embedded in every Protocol V5
     * token reference (e.g., {@code "8:group:orders:session-A"}).</p>
     */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;


    /**
     * The interval in minutes between automatic VersionWatermark periodic reconciliations against the broker.
     *
     * <p>Override with the {@code VDOT_RECONCILIATION_INTERVAL_MINUTES} environment variable.
     * Default is {@code 15} minutes. Reducing this value reduces the cross-instance watermark propagation
     * latency window, at the cost of more frequent {@code broker.snapshot()} query calls.</p>
     */
    public static final long RECONCILIATION_INTERVAL_MINUTES;


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

    // ── V5 Constants ──────────────────────────────────────────────────

    /**
     * HKDF salt for watermark persistence integrity (V5, §6.8).
     */
    public static final String WATERMARK_HKDF_SALT = "veridot-watermark-v5";

    /** How often (hours) the local TrustRoot cache syncs with TAAS (Appendix G). */
    public static final long TRUST_CACHE_SYNC_HOURS;

    /** Grace window (seconds) for stale TrustEntries past notAfter (§5.6.3). */
    public static final long TRUST_STALE_WINDOW_SECONDS;

    /** Interval (seconds) between TAAS digest publications (§18.2). */
    public static final long TAAS_DIGEST_INTERVAL_SECONDS;

    /** Allowable entry-count divergence before flagging broker omission (§18.2.1). */
    public static final int DIGEST_TOLERANCE;

    /** Max age (seconds) of a FENCE anchoredAt timestamp (§18.3). */
    public static final long FENCE_ANCHOR_MAX_AGE_SECONDS;

    static {

        long parsedReconciliation = ConstantDefault.RECONCILIATION_INTERVAL_MINUTES;
        final var reconciliationRate = getEnvOrProp(Env.RECONCILIATION_INTERVAL_MINUTES);
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
        final var capCacheRate = getEnvOrProp(Env.CAPABILITY_CACHE_TTL_SECONDS);
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
        final var capNegCacheRate = getEnvOrProp(Env.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS);
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
        final var clockDriftRate = getEnvOrProp(Env.CLOCK_DRIFT_TOLERANCE_SECONDS);
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
        final var minRsaVal = getEnvOrProp(Env.MIN_RSA_KEY_LENGTH);
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
        final var allowedAlgsVal = getEnvOrProp(Env.ALLOWED_SIG_ALGS);
        if (allowedAlgsVal != null && !allowedAlgsVal.isBlank()) {
            String[] parts = allowedAlgsVal.split(",");
            for (String part : parts) {
                String clean = part.trim().toUpperCase();
                if (clean.equals("ED25519") || clean.equals("EDDSA") || clean.equals("1") || clean.equals("0X01")) {
                    parsedAlgs.add(Algorithm.ED25519);
                } else if (clean.equals("ECDSA") || clean.equals("ECDSA-P256") || clean.equals("ECDSA_P256") || clean.equals("2") || clean.equals("0X02")) {
                    parsedAlgs.add(Algorithm.ECDSA_P256);
                } else if (clean.equals("RSA") || clean.equals("RSA-SHA256") || clean.equals("RSA_SHA256") || clean.equals("3") || clean.equals("0X03")) {
                    parsedAlgs.add(Algorithm.RSA_SHA256);
                } else if (clean.equals("RSA-PSS") || clean.equals("RSA_PSS") || clean.equals("4") || clean.equals("0X04")) {
                    parsedAlgs.add(Algorithm.RSA_PSS);
                } else if (clean.equals("ED25519-MLDSA65") || clean.equals("ED25519_MLDSA65") || clean.equals("5") || clean.equals("0X05")) {
                    parsedAlgs.add(Algorithm.ED25519_MLDSA65);
                } else if (clean.equals("ECDSA-P256-MLDSA65") || clean.equals("ECDSA_P256_MLDSA65") || clean.equals("6") || clean.equals("0X06")) {
                    parsedAlgs.add(Algorithm.ECDSA_P256_MLDSA65);
                } else if (clean.equals("MLDSA65") || clean.equals("ML-DSA-65") || clean.equals("7") || clean.equals("0X07")) {
                    parsedAlgs.add(Algorithm.MLDSA65);
                } else {
                    System.getLogger(Config.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "Ignoring unknown signature algorithm in " + Env.ALLOWED_SIG_ALGS + ": '" + part + "'");
                }
            }
        }
        if (parsedAlgs.isEmpty()) {
            parsedAlgs.add(Algorithm.ED25519);
            parsedAlgs.add(Algorithm.ECDSA_P256);
        }
        ALLOWED_SIG_ALGS = Collections.unmodifiableSet(parsedAlgs);

        WATERMARK_PERSISTENCE_FILE = getEnvOrProp(Env.WATERMARK_PERSISTENCE_FILE);

        long parsedStaleness = ConstantDefault.RECONCILIATION_MAX_STALENESS_MINUTES;
        final var stalenessVal = getEnvOrProp(Env.RECONCILIATION_MAX_STALENESS_MINUTES);
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

        // ── V5 Constants initialization ──
        TRUST_CACHE_SYNC_HOURS = parseLongEnv(Env.TRUST_CACHE_SYNC_HOURS, ConstantDefault.TRUST_CACHE_SYNC_HOURS, 1, 168);
        TRUST_STALE_WINDOW_SECONDS = parseLongEnv(Env.TRUST_STALE_WINDOW_SECONDS, ConstantDefault.TRUST_STALE_WINDOW_SECONDS, 0, 86400);
        TAAS_DIGEST_INTERVAL_SECONDS = parseLongEnv(Env.TAAS_DIGEST_INTERVAL, ConstantDefault.TAAS_DIGEST_INTERVAL_SECONDS, 60, 86400);
        DIGEST_TOLERANCE = (int) parseLongEnv(Env.DIGEST_TOLERANCE, ConstantDefault.DIGEST_TOLERANCE, 0, 100);
        FENCE_ANCHOR_MAX_AGE_SECONDS = parseLongEnv(Env.FENCE_ANCHOR_MAX_AGE, ConstantDefault.FENCE_ANCHOR_MAX_AGE_SECONDS, 60, 3600);
    }

    private static String getEnvOrProp(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            val = System.getProperty(key);
        }
        return val;
    }

    private static long parseLongEnv(String envKey, long defaultVal, long min, long max) {
        final var envVal = getEnvOrProp(envKey);
        if (envVal != null) {
            try {
                long parsed = Long.parseLong(envVal);
                if (parsed >= min && parsed <= max) {
                    return parsed;
                }
                System.getLogger(Config.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Ignoring invalid " + envKey + "=" + parsed
                        + " (must be " + min + "–" + max + "). Using default: " + defaultVal);
            } catch (NumberFormatException e) {
                System.getLogger(Config.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "Ignoring non-numeric " + envKey + "='" + envVal + "'. Using default: " + defaultVal);
            }
        }
        return defaultVal;
    }
}
