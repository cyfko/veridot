package io.github.cyfko.veridot.core;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe class to track core library security and operational metrics
 * with zero external dependencies (Option B).
 */
public final class VeridotMetrics {
    public static final LongAdder ENVELOPE_ACCEPTED = new LongAdder();
    public static final LongAdder ENVELOPE_REJECTED = new LongAdder();
    public static final LongAdder FENCE_CONTENTIONS = new LongAdder();
    public static final LongAdder RECONCILIATIONS = new LongAdder();

    /** veridot_attestation_verifications_total — incremented in TaasStateMachine on each attestation check. */
    public static final LongAdder ATTESTATION_VERIFICATIONS = new LongAdder();

    /** veridot_security_alerts_total — incremented in TaasStateMachine when attestation fails on a rotation. */
    public static final LongAdder SECURITY_ALERTS = new LongAdder();

    private VeridotMetrics() {}

    /**
     * Resets all metrics.
     */
    public static void reset() {
        ENVELOPE_ACCEPTED.reset();
        ENVELOPE_REJECTED.reset();
        FENCE_CONTENTIONS.reset();
        RECONCILIATIONS.reset();
        ATTESTATION_VERIFICATIONS.reset();
        SECURITY_ALERTS.reset();
    }
}
