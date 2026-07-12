package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * State Transparency verification utilities (§18).
 *
 * <p>Provides static methods for verifying TAAS digest consistency against local state,
 * detecting liveness gaps for known peers, and detecting capability version mismatches
 * across instances. These checks form the client-side portion of the State Transparency
 * subsystem defined in Protocol V5 §18.
 *
 * <p>All methods accept primitive parameters rather than {@code SignedDigest} objects because
 * {@code veridot-core} does not depend on {@code veridot-trustroots-api}.
 *
 * @see ErrorCode#DIGEST_SIGNATURE_INVALID
 * @see ErrorCode#BROKER_OMISSION_SUSPECTED
 * @see ErrorCode#LIVENESS_GAP_DETECTED
 * @see ErrorCode#CAPABILITY_VERSION_MISMATCH
 */
public class StateTransparencyChecker {

    private static final Logger logger = Logger.getLogger(StateTransparencyChecker.class.getName());

    private StateTransparencyChecker() {
        // Utility class — prevent instantiation
    }

    /**
     * §18.2.1 — Verify TAAS digest entry count matches local state.
     *
     * <p>Checks that the TAAS digest signature is valid, then compares the digest's
     * {@code entryCount} against the sum of local trust entries and revocations.
     * If the absolute difference exceeds {@link Config#DIGEST_TOLERANCE}, a
     * {@link VeridotException} with {@link ErrorCode#BROKER_OMISSION_SUSPECTED} is thrown.
     *
     * @param digestEntryCount      the entryCount from the SignedDigest
     * @param localTrustEntryCount  local count of TrustEntry records for this scope
     * @param localRevocationCount  local count of TRUST_REVOCATION records for this scope
     * @param digestSignatureValid  whether the digest signature was verified successfully
     * @throws VeridotException V5701 if signature is invalid, V5702 if count diverges beyond tolerance
     */
    public static void verifyDigestConsistency(int digestEntryCount, int localTrustEntryCount,
                                                int localRevocationCount, boolean digestSignatureValid) {
        if (!digestSignatureValid) {
            throw new VeridotException(ErrorCode.DIGEST_SIGNATURE_INVALID, null,
                "TAAS digest signature is invalid");
        }
        int localCount = localTrustEntryCount + localRevocationCount;
        int tolerance = Config.DIGEST_TOLERANCE;
        if (Math.abs(digestEntryCount - localCount) > tolerance) {
            String detail = "TAAS digest divergence: expected=" + digestEntryCount
                + ", local=" + localCount + ", tolerance=" + tolerance;
            logger.severe(detail);
            throw new VeridotException(ErrorCode.BROKER_OMISSION_SUSPECTED, null, detail);
        }
    }

    /**
     * §18.4 — Detect LIVENESS gaps for known peers.
     *
     * <p>For each known peer, checks whether the last LIVENESS signal received exceeds
     * twice the expected interval. Peers exceeding this threshold are returned in the
     * result list. The caller should raise {@link ErrorCode#LIVENESS_GAP_DETECTED} if
     * the returned list is non-empty.
     *
     * @param knownPeers           the set of known peer identifiers
     * @param lastLivenessReceived map from peer identifier to the timestamp of the last LIVENESS received
     * @param expectedInterval     the expected interval between LIVENESS signals
     * @return list of peer identifiers with detected liveness gaps (empty if none)
     */
    public static List<String> detectLivenessGaps(Set<String> knownPeers,
                                                   Map<String, Instant> lastLivenessReceived,
                                                   Duration expectedInterval) {
        List<String> gapped = new ArrayList<>();
        Instant now = Instant.now();
        Duration maxGap = expectedInterval.multipliedBy(2);
        for (String peer : knownPeers) {
            Instant lastSeen = lastLivenessReceived.getOrDefault(peer, Instant.EPOCH);
            if (Duration.between(lastSeen, now).compareTo(maxGap) > 0) {
                gapped.add(peer);
            }
        }
        return gapped;
    }

    /**
     * §18.4 — Compare CAPABILITY versions across instances.
     *
     * <p>For each capability, compares the local version against the peer's reported version.
     * A mismatch is flagged only if the same divergence has persisted for at least 3 consecutive
     * reconciliation cycles (indicated by {@code consecutiveMismatchCycles}). The caller should
     * raise {@link ErrorCode#CAPABILITY_VERSION_MISMATCH} if the returned list is non-empty.
     *
     * @param localVersions              map from capability key to local version number
     * @param peerVersions               map from capability key to peer-reported version number
     * @param consecutiveMismatchCycles  number of consecutive reconciliation cycles where
     *                                    mismatches have been observed
     * @return list of capability keys with persistent version mismatches (empty if none)
     */
    public static List<String> detectCapabilityMismatches(Map<String, Long> localVersions,
                                                          Map<String, Long> peerVersions,
                                                          int consecutiveMismatchCycles) {
        List<String> mismatches = new ArrayList<>();
        for (var entry : localVersions.entrySet()) {
            Long peerVersion = peerVersions.get(entry.getKey());
            if (peerVersion != null && !peerVersion.equals(entry.getValue()) && consecutiveMismatchCycles >= 3) {
                mismatches.add(entry.getKey());
            }
        }
        return mismatches;
    }
}
