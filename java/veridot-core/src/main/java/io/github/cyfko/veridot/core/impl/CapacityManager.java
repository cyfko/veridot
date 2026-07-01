package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import io.github.cyfko.veridot.core.exceptions.VeridotException;



import io.github.cyfko.veridot.core.Algorithm;
import java.security.PrivateKey;
import java.util.List;

/**
 * Orchestrates session capacity management and Eviction/Fencing (§10.2).
 */
final class CapacityManager {

    private final FenceManager fenceManager = new FenceManager();
    private final SessionCounter sessionCounter = new SessionCounter();
    private final EvictionSelector evictionSelector = new EvictionSelector();

    public void enforceCapacity(Scope groupScope, ConfigPayload config,
                                 String processorId, Broker broker, TrustRoot trustRoot,
                                 EntryPublisher publisher, VersionWatermark watermark,
                                 LivenessChecker livenessChecker, PrivateKey signingKey,
                                 Algorithm sigAlg, String issuerId) {
        if (groupScope == null) {
            throw new IllegalArgumentException("groupScope cannot be null");
        }
        if (config == null) {
            return; // no config, treat as unbounded
        }
        if (!config.max().isPresent()) {
            return; // unbounded
        }

        int max = config.max().getAsInt();
        int maxRetries = 3;
        long backoff = 50; // ms

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                long now = System.currentTimeMillis();

                // 1. Acquire FENCE grant (§9.4)
                // Set fence validity duration based on clock drift config
                long fenceValidUntil = now + Config.MAX_CLOCK_DRIFT_SECONDS * 1000L;
                FenceManager.FenceGrant grant;
                try {
                    grant = fenceManager.acquire(groupScope, processorId, fenceValidUntil, publisher, broker, trustRoot, watermark, signingKey, sigAlg, issuerId);
                } catch (VeridotException e) {
                    throw e;
                } catch (Exception e) {
                    throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "Failed to acquire FENCE grant", e);
                }

                // 2. Fetch snapshot
                List<Broker.BrokerEntry> entries;
                try {
                    entries = broker.snapshot(groupScope);
                } catch (Exception e) {
                    entries = null;
                }

                // Assert FENCE is still valid after reading snapshot, before processing capacity modifications (§9.4)
                fenceManager.assertFenceValid(groupScope, grant.fenceCounter(), broker, trustRoot, watermark);

                // Garbage collect expired sessions (lazy GC for V3 compatibility)
                if (entries != null) {
                    for (Broker.BrokerEntry entry : entries) {
                        try {
                            Envelope envelope = Envelope.parse(entry.envelopeBytes());
                            if (envelope.entryType == EntryType.KEY_EPOCH) {
                                KeyEpochPayload keyEpoch = KeyEpochPayload.decode(envelope.payload);
                                if (now >= keyEpoch.validUntil()) {
                                    // Expired! Delete KEY_EPOCH from broker
                                    publisher.publish(EntryType.KEY_EPOCH, groupScope, envelope.key, envelope.version + 1, new byte[0],
                                                      signingKey, sigAlg, issuerId, broker).join();

                                    // Publish LIVENESS(REVOKED)
                                    EntryId liveId = new EntryId(groupScope, EntryType.LIVENESS, envelope.key);
                                    LivenessPayload revokedPayload = new LivenessPayload(LivenessPayload.REVOKED, now, now);
                                    publisher.publish(EntryType.LIVENESS, groupScope, envelope.key, watermark.current(liveId) + 1, revokedPayload.encode(),
                                                      signingKey, sigAlg, issuerId, broker).join();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                // 3. Count active sessions
                List<SessionCounter.SessionInfo> active = sessionCounter.listActive(groupScope, broker, trustRoot, watermark, livenessChecker, now);

                // 4. Evaluate capacity and eviction (§10.2)
                if (active.size() >= max) {
                    if (config.pol() == 0x04) { // REJECT
                        // Throw V3 SessionCapacityExceededException for V3 test compatibility
                        throw new SessionCapacityExceededException(groupScope.groupId(), max);
                    }

                    // Evict a session
                    SessionCounter.SessionInfo victim = evictionSelector.select(config.pol(), active);
                    if (victim != null) {
                        EntryId victimLiveId = new EntryId(groupScope, EntryType.LIVENESS, victim.sessionKey());
                        long nextVersion = Math.max(watermark.current(victimLiveId) + 1, victim.lastVersion() + 1);

                        LivenessPayload revokedPayload = new LivenessPayload(LivenessPayload.REVOKED, now, now); // revoke immediately
                        byte[] payloadBytes = revokedPayload.encode();

                        // Assert FENCE is still valid right before performing mutations (§9.4)
                        fenceManager.assertFenceValid(groupScope, grant.fenceCounter(), broker, trustRoot, watermark);

                        // Publish revocation
                        try {
                            publisher.publish(EntryType.LIVENESS, groupScope, victim.sessionKey(), nextVersion, payloadBytes, signingKey, sigAlg, issuerId, broker)
                                     .join();

                            // V3 compatibility: delete the evicted session's KEY_EPOCH entry from broker
                            publisher.publish(EntryType.KEY_EPOCH, groupScope, victim.sessionKey(), nextVersion, new byte[0], signingKey, sigAlg, issuerId, broker)
                                     .join();

                        } catch (Exception e) {
                            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, victimLiveId.loggable(), "Failed to publish session revocation", e);
                        }

                        // Update watermark for the evicted session
                        watermark.accept(victimLiveId, nextVersion);
                    }
                }

                // Isolation verified and mutations published successfully, exit retry loop
                break;

            } catch (VeridotException e) {
                if (e.getErrorCode() == ErrorCode.FENCE_TOKEN_STALE && attempt < maxRetries) {
                    try {
                        Thread.sleep(backoff * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }
}
