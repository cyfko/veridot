package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;


/**
 * Enforces positive-proof default-deny liveness verification (§8.3, §8.6).
 */
final class LivenessChecker {

    private final SignatureVerifier signatureVerifier = new SignatureVerifier();

    public void assertLive(EntryId liveEntryId, Broker broker, TrustRoot trustRoot,
                           VersionWatermark watermark, CapabilityVerifier capabilityVerifier,
                           long nowMillis) {
        if (liveEntryId == null) {
            throw new IllegalArgumentException("liveEntryId cannot be null");
        }
        if (broker == null) {
            throw new IllegalArgumentException("Broker cannot be null");
        }
        if (trustRoot == null) {
            throw new IllegalArgumentException("TrustRoot cannot be null");
        }
        if (watermark == null) {
            throw new IllegalArgumentException("Watermark cannot be null");
        }
        if (capabilityVerifier == null) {
            throw new IllegalArgumentException("CapabilityVerifier cannot be null");
        }

        String loggable = liveEntryId.loggable();

        // 1. Fetch liveness entry
        byte[] bytes;
        try {
            bytes = broker.get(liveEntryId.storageKey());
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, loggable, "Broker unavailable when fetching liveness", e);
        }

        if (bytes == null) {
            throw new VeridotException(ErrorCode.LIVENESS_NOT_ESTABLISHED, loggable, "Liveness entry absent");
        }

        // 2. Parse envelope & TLV Payload (V-10 duplicate check before signature)
        Envelope envelope;
        LivenessPayload payload;
        try {
            envelope = Envelope.parse(bytes);
            payload = LivenessPayload.decode(envelope.payload);
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.LIVENESS_NOT_ESTABLISHED, loggable, "Malformed liveness envelope or payload", e);
        }

        // 3. Verify signature
        try {
            signatureVerifier.verify(envelope, trustRoot);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, loggable, "Liveness envelope signature verification failed", e);
        }

        // 4. Verify capability of the liveness issuer (§8.6)
        try {
            capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.CAPABILITY_NOT_FOUND, loggable, "Liveness issuer not authorized for scope", e);
        }

        // 5. Verify version is strictly greater than or equal to watermark (§11.1)
        // CRITICAL SECURITY HARDENING (V4201): Protocol V4 §11.1 explicitly dictates that version 0
        // is unconditionally invalid to prevent initial-state replay/rollback attacks (where watermark is 0
        // and 0 < 0 is false, allowing version 0 to pass relative monotone checks).
        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.STALE_VERSION, loggable,
                "Entry version 0 is unconditionally rejected (§11.1 V4201)");
        }
        long currentWatermark = watermark.current(liveEntryId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.STALE_VERSION, loggable, 
                "Liveness version " + envelope.version + " is stale. Watermark is " + currentWatermark);
        }
        if (envelope.version > currentWatermark) {
            watermark.accept(liveEntryId, envelope.version);
        }

        // 7. Verify ACTIVE status
        if (!payload.isActive()) {
            throw new VeridotException(ErrorCode.LIVENESS_NOT_ESTABLISHED, loggable, "Liveness status is not ACTIVE (status = " + payload.status() + ")");
        }

        // 8. Verify temporal validity (freshness)
        if (!payload.isFresh(nowMillis)) {
            throw new VeridotException(ErrorCode.LIVENESS_NOT_ESTABLISHED, loggable, "Liveness attestation expired at " + payload.validUntil());
        }
    }
}
