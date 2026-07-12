package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import io.github.cyfko.veridot.core.Algorithm;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Entry verification logic for Protocol V5 (§5.4).
 *
 * <p>V5 eliminates {@code verifyKeyEpoch()} — identity resolution is now done
 * via {@code kid} header → TrustRoot in GenericSignerVerifier. This class
 * retains the envelope-level verification methods and adds native SIGNED_DATA
 * verification.
 */
final class EntryVerifier {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(EntryVerifier.class.getName());
    private final SignatureVerifier signatureVerifier = new SignatureVerifier();

    /**
     * Verifies a SIGNED_DATA entry from the broker (§4.9, §8.2).
     *
     * <p>Steps: retrieve → parse → verify envelope signature → capability → watermark → temporal.
     *
     * @return the verified {@link SignedDataPayload}
     */
    public SignedDataPayload verifySignedData(
            EntryId entryId,
            Broker broker,
            TrustRoot trustRoot,
            VersionWatermark watermark,
            CapabilityVerifier capabilityVerifier,
            long nowMillis) {
        try {
            SignedDataPayload payload = verifySignedDataInternal(
                entryId, broker, trustRoot, watermark, capabilityVerifier, nowMillis
            );
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.increment();
            return payload;
        } catch (RuntimeException e) {
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.increment();
            throw e;
        }
    }

    private SignedDataPayload verifySignedDataInternal(
            EntryId entryId,
            Broker broker,
            TrustRoot trustRoot,
            VersionWatermark watermark,
            CapabilityVerifier capabilityVerifier,
            long nowMillis) {

        if (entryId == null) {
            throw new IllegalArgumentException("entryId cannot be null");
        }
        if (entryId.entryType() != EntryType.SIGNED_DATA) {
            throw new IllegalArgumentException("Expected EntryType.SIGNED_DATA, got " + entryId.entryType());
        }

        String loggable = entryId.loggable();

        // Step 1: Retrieve from broker
        byte[] bytes;
        try {
            bytes = broker.get(entryId.storageKey());
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.BROKER_UNREACHABLE, loggable, "Broker unavailable", e);
        }

        if (bytes == null) {
            throw new VeridotException(ErrorCode.ENTRY_NOT_FOUND, loggable, "SIGNED_DATA entry absent from broker");
        }

        // Step 2: Structural validation
        Envelope envelope = Envelope.parse(bytes);
        SignedDataPayload payload = SignedDataPayload.decode(envelope.payload);

        // Step 3: Trust validation (envelope signature)
        signatureVerifier.verify(envelope, trustRoot);

        // Clock drift warning
        long driftMillis = Math.abs(nowMillis - envelope.timestamp);
        long toleranceMillis = Config.MAX_CLOCK_DRIFT_SECONDS * 1000L;
        if (driftMillis > toleranceMillis / 2) {
            logger.warning("Clock drift detected for entry " + loggable + ": "
                + (driftMillis / 1000.0) + " seconds (exceeds 50% of tolerance: "
                + Config.MAX_CLOCK_DRIFT_SECONDS + "s)");
        }

        // Step 4: Capability validation
        capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);

        // Step 5: Version watermark monotone (§11.1)
        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, loggable,
                "Entry version 0 is unconditionally rejected (§11.1)");
        }
        long currentWatermark = watermark.current(entryId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, loggable,
                "SIGNED_DATA version " + envelope.version + " is stale. Watermark is " + currentWatermark);
        }

        if (envelope.version > currentWatermark) {
            watermark.accept(entryId, envelope.version);
        }

        return payload;
    }

    /**
     * Verifies a generic envelope's integrity (signature + trust resolution).
     */
    public void verifyEnvelope(
            Envelope envelope,
            TrustRoot trustRoot) {
        signatureVerifier.verify(envelope, trustRoot);
    }
}
