package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Counts and lists active sessions strictly based on positively verified LIVENESS entries (§10.1).
 *
 * <p>V5: session validity is determined solely by LIVENESS entries. There is no longer
 * a dependency on the former key-epoch entry type. A session is active if its LIVENESS entry is ACTIVE
 * and not expired.
 */
final class SessionCounter {

    private final CapabilityVerifier capabilityVerifier = new CapabilityVerifier();

    record SessionInfo(String sessionKey, long lastAsOf, long lastVersion) {}

    public int countActive(Scope groupScope, Broker broker, TrustRoot trustRoot,
                           VersionWatermark watermark, LivenessChecker livenessChecker,
                           long nowMillis) {
        return listActive(groupScope, broker, trustRoot, watermark, livenessChecker, nowMillis).size();
    }

    public List<SessionInfo> listActive(Scope groupScope, Broker broker, TrustRoot trustRoot,
                                        VersionWatermark watermark, LivenessChecker livenessChecker,
                                        long nowMillis) {
        if (groupScope == null) {
            throw new IllegalArgumentException("groupScope cannot be null");
        }
        if (broker == null) {
            throw new IllegalArgumentException("Broker cannot be null");
        }

        List<Broker.BrokerEntry> entries;
        try {
            entries = broker.snapshot(groupScope);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.BROKER_UNREACHABLE, null, "Failed to fetch snapshot for capacity check", e);
        }

        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        List<SessionInfo> activeSessions = new ArrayList<>();

        for (Broker.BrokerEntry entry : entries) {
            try {
                Envelope envelope = Envelope.parse(entry.envelopeBytes());
                if (envelope.entryType != EntryType.LIVENESS) {
                    continue;
                }

                EntryId liveEntryId = envelope.entryId();

                // V5: Verify liveness. If this throws, the session is not active.
                livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, nowMillis);

                // Decode the LIVENESS payload to extract asOf
                LivenessPayload livenessPayload = LivenessPayload.decode(envelope.payload);

                // V5: LIVENESS alone determines session validity (no key-epoch check)
                if (!livenessPayload.isActive()) {
                    continue;
                }
                if (!livenessPayload.isFresh(nowMillis)) {
                    continue;
                }

                activeSessions.add(new SessionInfo(envelope.key, livenessPayload.asOf(), envelope.version));
            } catch (Exception e) {
                // Ignore any invalid or stale liveness entries (fail-closed / default-deny)
            }
        }

        // Sort by asOf ascending to support eviction strategies (FIFO, LRU, LIFO)
        activeSessions.sort(Comparator.comparingLong(SessionInfo::lastAsOf));

        return activeSessions;
    }
}
