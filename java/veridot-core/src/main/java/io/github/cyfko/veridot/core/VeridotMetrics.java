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

    private VeridotMetrics() {}

    /**
     * Resets all metrics.
     */
    public static void reset() {
        ENVELOPE_ACCEPTED.reset();
        ENVELOPE_REJECTED.reset();
        FENCE_CONTENTIONS.reset();
        RECONCILIATIONS.reset();
    }
}
