package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import java.security.PrivateKey;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages periodic snapshot-based version watermark reconciliation (§11.4).
 */
final class ReconciliationManager implements AutoCloseable {

    private final SignatureVerifier signatureVerifier = new SignatureVerifier();
    private final java.util.Map<Scope, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public void reconcile(Scope scope, Broker broker, VersionWatermark watermark,
                          SignatureVerifier sigVerifier, TrustRoot trustRoot,
                          EntryPublisher publisher, String issuerId,
                          PrivateKey signingKey, byte sigAlg) {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }
        if (broker == null) {
            throw new IllegalArgumentException("Broker cannot be null");
        }
        if (watermark == null) {
            throw new IllegalArgumentException("Watermark cannot be null");
        }
        if (trustRoot == null) {
            throw new IllegalArgumentException("TrustRoot cannot be null");
        }
        if (publisher == null) {
            throw new IllegalArgumentException("Publisher cannot be null");
        }

        List<Broker.BrokerEntry> entries;
        try {
            entries = broker.snapshot(scope);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "Broker unavailable during reconciliation snapshot", e);
        }

        if (entries == null) {
            return;
        }

        Set<EntryId> distinctEntryIds = new HashSet<>();
        long now = System.currentTimeMillis();

        for (Broker.BrokerEntry entry : entries) {
            try {
                Envelope envelope = Envelope.parse(entry.envelopeBytes());
                sigVerifier.verify(envelope, trustRoot);

                EntryId entryId = envelope.entryId();
                distinctEntryIds.add(entryId);

                // Reconcile watermark: accept if version > local watermark
                try {
                    watermark.accept(entryId, envelope.version);
                } catch (VeridotException e) {
                    if (e.getErrorCode() != ErrorCode.STALE_VERSION) {
                        throw e;
                    }
                    // If version <= watermark, ignore (it is not a violation for reconciliation)
                }
            } catch (Exception e) {
                // Ignore invalid snapshot entries to proceed with other entries
            }
        }

        // Publish SNAPSHOT_MARKER entry
        EntryId markerId = new EntryId(scope, EntryType.SNAPSHOT_MARKER, "");
        long version = Math.max(watermark.current(markerId) + 1, 1);
        
        SnapshotMarkerPayload markerPayload = new SnapshotMarkerPayload(now, distinctEntryIds.size());
        byte[] payloadBytes = markerPayload.encode();

        try {
            publisher.publish(EntryType.SNAPSHOT_MARKER, scope, "", version, payloadBytes, signingKey, sigAlg, issuerId, broker)
                     .join();
            watermark.accept(markerId, version);
        } catch (Exception e) {
            // Ignore snapshot marker publication errors to prevent interrupting the system
        }
    }

    public void startPeriodicReconciliation(Scope scope, Duration maxInterval,
                                             ScheduledExecutorService scheduler,
                                             Broker broker, VersionWatermark watermark,
                                             TrustRoot trustRoot, EntryPublisher publisher,
                                             String issuerId, PrivateKey signingKey, byte sigAlg) {
        stopPeriodicReconciliation(scope);

        long intervalMs = maxInterval.toMillis();
        if (intervalMs <= 0) {
            intervalMs = 3600000; // default 60 minutes
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                reconcile(scope, broker, watermark, signatureVerifier, trustRoot, publisher, issuerId, signingKey, sigAlg);
            } catch (Exception e) {
                // Log and ignore to allow subsequent reconciliation runs
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        tasks.put(scope, future);
    }

    public void stopPeriodicReconciliation(Scope scope) {
        ScheduledFuture<?> future = tasks.remove(scope);
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public void close() {
        for (Scope scope : tasks.keySet()) {
            stopPeriodicReconciliation(scope);
        }
    }

    // Visible for testing
    int tasksCountForTest() {
        return tasks.size();
    }
}
