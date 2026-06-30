package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import io.github.cyfko.veridot.core.Algorithm;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Executes the 9-step Verification Process for signed objects (§5.4).
 */
final class EntryVerifier {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(EntryVerifier.class.getName());
    private final SignatureVerifier signatureVerifier = new SignatureVerifier();

    /**
     * Executes steps 1 through 7 of the verification process (§5.4).
     *
     * @return the verified KeyEpochPayload containing the ephemeral key material
     */
    public KeyEpochPayload verifyKeyEpoch(
            EntryId keyEpochId,
            Broker broker,
            TrustRoot trustRoot,
            VersionWatermark watermark,
            CapabilityVerifier capabilityVerifier,
            LivenessChecker livenessChecker,
            long nowMillis) {
        try {
            KeyEpochPayload payload = verifyKeyEpochInternal(
                keyEpochId, broker, trustRoot, watermark, capabilityVerifier, livenessChecker, nowMillis
            );
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.increment();
            return payload;
        } catch (RuntimeException e) {
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.increment();
            throw e;
        }
    }

    private KeyEpochPayload verifyKeyEpochInternal(
            EntryId keyEpochId,
            Broker broker,
            TrustRoot trustRoot,
            VersionWatermark watermark,
            CapabilityVerifier capabilityVerifier,
            LivenessChecker livenessChecker,
            long nowMillis) {

        if (keyEpochId == null) {
            throw new IllegalArgumentException("keyEpochId cannot be null");
        }
        if (keyEpochId.entryType() != EntryType.KEY_EPOCH) {
            throw new IllegalArgumentException("Expected EntryType.KEY_EPOCH, got " + keyEpochId.entryType());
        }

        String loggable = keyEpochId.loggable();

        // Step 2: Retrieve from broker
        byte[] bytes;
        try {
            bytes = broker.get(keyEpochId.storageKey());
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, loggable, "Broker unavailable", e);
        }

        if (bytes == null) {
            throw new VeridotException(ErrorCode.LIVENESS_NOT_ESTABLISHED, loggable, "KEY_EPOCH entry absent from broker");
        }

        // Step 3: Structural validation & Payload TLV parsing (V-10 duplicate check before signature)
        Envelope envelope = Envelope.parse(bytes);
        KeyEpochPayload payload = KeyEpochPayload.decode(envelope.payload);

        // Step 4: Trust validation
        signatureVerifier.verify(envelope, trustRoot);

        // Log a warning if clock drift exceeds 50% of the configured tolerance
        long driftMillis = Math.abs(nowMillis - envelope.timestamp);
        long toleranceMillis = Config.MAX_CLOCK_DRIFT_SECONDS * 1000L;
        if (driftMillis > toleranceMillis / 2) {
            logger.warning("Clock drift detected for entry " + keyEpochId.loggable() + ": " + (driftMillis / 1000.0) + " seconds (exceeds 50% of tolerance: " + Config.MAX_CLOCK_DRIFT_SECONDS + "s)");
        }

        // Step 5: Capability validation
        // First resolve if we have siteId in the KeyEpoch payload. We need to parse payload for siteId.
        capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, payload.site(), broker, trustRoot);

        // Verify version watermark monotone (§11.1)
        // CRITICAL SECURITY HARDENING (V4201): Protocol V4 §11.1 explicitly dictates that version 0
        // is unconditionally invalid to prevent initial-state replay/rollback attacks (where watermark is 0
        // and 0 < 0 is false, allowing version 0 to pass relative monotone checks).
        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.STALE_VERSION, loggable,
                "Entry version 0 is unconditionally rejected (§11.1 V4201)");
        }
        long currentWatermark = watermark.current(keyEpochId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.STALE_VERSION, loggable, 
                "KEY_EPOCH version " + envelope.version + " is stale. Watermark is " + currentWatermark);
        }

        // Step 6: Temporal validation (§5.3)
        // 1. now >= validFrom - Config.MAX_CLOCK_DRIFT_SECONDS * 1000L
        if (nowMillis < payload.validFrom() - Config.MAX_CLOCK_DRIFT_SECONDS * 1000L) {
            throw new VeridotException(ErrorCode.KEY_EPOCH_EXPIRED, loggable, 
                "KEY_EPOCH not valid yet (now=" + nowMillis + ", validFrom=" + payload.validFrom() + ")");
        }
        // 2. now < validUntil
        if (nowMillis >= payload.validUntil()) {
            throw new VeridotException(ErrorCode.KEY_EPOCH_EXPIRED, loggable, 
                "KEY_EPOCH expired (now=" + nowMillis + ", validUntil=" + payload.validUntil() + ")");
        }

        // Step 7: Liveness validation
        // LIVENESS entry id is (scope, EntryType.LIVENESS, key)
        EntryId liveEntryId = new EntryId(envelope.scope, EntryType.LIVENESS, envelope.key);
        livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, nowMillis);

        // Apply watermark atomically for both keyEpoch and liveness if valid and strictly greater
        if (envelope.version > currentWatermark) {
            watermark.accept(keyEpochId, envelope.version);
        }

        return payload;
    }

    /**
     * Step 8: Cryptographically verifies the signed object's signature.
     */
    public void verifyCryptographic(byte[] signedObjectBytes, byte[] signatureBytes, KeyEpochPayload epoch) {
        if (signedObjectBytes == null) {
            throw new IllegalArgumentException("Signed object bytes cannot be null");
        }
        if (signatureBytes == null) {
            throw new IllegalArgumentException("Signature bytes cannot be null");
        }
        if (epoch == null) {
            throw new IllegalArgumentException("KeyEpochPayload cannot be null");
        }

        try {
            PublicKey pk = epoch.publicKey();
            Signature sig = Signature.getInstance(epoch.alg().getJcaSignatureAlg());
            if (epoch.alg() == Algorithm.RSA_PSS) {
                try {
                    sig.setParameter(new java.security.spec.PSSParameterSpec(
                        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
                    ));
                } catch (Exception ignored) {}
            }

            sig.initVerify(pk);
            sig.update(signedObjectBytes);
            if (!sig.verify(signatureBytes)) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Cryptographic verification of signed object failed");
            }
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Signed object verification threw exception", e);
        }
    }
}
