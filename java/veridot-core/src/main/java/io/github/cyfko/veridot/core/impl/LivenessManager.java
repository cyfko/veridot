package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import io.github.cyfko.veridot.core.Algorithm;
import java.security.PrivateKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages active session publication, revoking, and periodic renewal loops (§8.5).
 */
final class LivenessManager {

    private final EntryPublisher publisher;
    private final Broker broker;
    private final PrivateKey signingKey;
    private final Algorithm sigAlg;
    private final String issuerId;

    private final Map<EntryId, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();

    public LivenessManager(EntryPublisher publisher, Broker broker, PrivateKey signingKey, Algorithm sigAlg, String issuerId) {
        this.publisher = publisher;
        this.broker = broker;
        this.signingKey = signingKey;
        this.sigAlg = sigAlg;
        this.issuerId = issuerId;
    }

    public void publishActive(EntryId liveEntryId, long validityDurationMillis, VersionWatermark watermark) {
        long now = System.currentTimeMillis();
        long version = Math.max(watermark.current(liveEntryId) + 1, 1);

        LivenessPayload payload = new LivenessPayload(LivenessPayload.ACTIVE, now, now + validityDurationMillis);
        byte[] payloadBytes = payload.encode();

        try {
            publisher.publish(EntryType.LIVENESS, liveEntryId.scope(), liveEntryId.key(), version, payloadBytes, signingKey, sigAlg, issuerId, broker)
                     .join();
            watermark.accept(liveEntryId, version);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, liveEntryId.loggable(), "Failed to publish LIVENESS(ACTIVE) entry", e);
        }
    }

    public void publishRevoked(EntryId liveEntryId, VersionWatermark watermark) {
        long now = System.currentTimeMillis();
        long version = Math.max(watermark.current(liveEntryId) + 1, 1);

        LivenessPayload payload = new LivenessPayload(LivenessPayload.REVOKED, now, now);
        byte[] payloadBytes = payload.encode();

        try {
            publisher.publish(EntryType.LIVENESS, liveEntryId.scope(), liveEntryId.key(), version, payloadBytes, signingKey, sigAlg, issuerId, broker)
                     .join();
            watermark.accept(liveEntryId, version);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, liveEntryId.loggable(), "Failed to publish LIVENESS(REVOKED) entry", e);
        }
    }

    public void startRenewalLoop(EntryId liveEntryId, long renewalWindowMillis, VersionWatermark watermark, ScheduledExecutorService scheduler) {
        stopRenewalLoop(liveEntryId);

        // Renew at 80% of the validity duration to satisfy the "within the last 20%" rule
        long delay = (long) (renewalWindowMillis * 0.8);
        if (delay <= 0) {
            delay = 1000; // fallback to 1 second minimum
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                publishActive(liveEntryId, renewalWindowMillis, watermark);
            } catch (Exception e) {
                // Log and ignore to allow subsequent renewal retries
            }
        }, delay, delay, TimeUnit.MILLISECONDS);

        renewalTasks.put(liveEntryId, future);
    }

    public void stopRenewalLoop(EntryId liveEntryId) {
        ScheduledFuture<?> future = renewalTasks.remove(liveEntryId);
        if (future != null) {
            future.cancel(false);
        }
    }

    public void stopAll() {
        for (EntryId id : renewalTasks.keySet()) {
            stopRenewalLoop(id);
        }
    }
}
