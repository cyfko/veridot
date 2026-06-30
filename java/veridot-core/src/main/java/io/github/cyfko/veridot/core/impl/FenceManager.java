package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import io.github.cyfko.veridot.core.Algorithm;
import java.security.PrivateKey;

/**
 * Manages FENCE tokens and coordinates capacity-affecting mutations (§9).
 */
final class FenceManager {

    private final SignatureVerifier signatureVerifier = new SignatureVerifier();

    record FenceGrant(long fenceCounter, long validUntil) {}

    /**
     * Acquires a FENCE grant for the scope. Increments the fence counter, publishes the FENCE entry,
     * and returns the grant.
     */
    public FenceGrant acquire(Scope scope, String grantedTo, long validUntilMillis,
                              EntryPublisher publisher, Broker broker, TrustRoot trustRoot,
                              VersionWatermark watermark, PrivateKey signingKey, Algorithm sigAlg, String issuerId) {
        EntryId fenceEntryId = new EntryId(scope, EntryType.FENCE, "");
        String loggable = fenceEntryId.loggable();

        long nextCounter = 1;
        long nextVersion = 1;

        // 1. Fetch current FENCE entry from broker
        byte[] bytes;
        try {
            bytes = broker.get(fenceEntryId.storageKey());
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, loggable, "Broker unavailable when fetching FENCE", e);
        }

        if (bytes != null) {
            try {
                Envelope envelope = Envelope.parse(bytes);
                signatureVerifier.verify(envelope, trustRoot);
                FencePayload currentPayload = FencePayload.decode(envelope.payload);

                nextCounter = currentPayload.fenceCounter() + 1;
                nextVersion = Math.max(watermark.current(fenceEntryId) + 1, envelope.version + 1);
            } catch (Exception e) {
                // If existing FENCE is corrupt, start from watermark + 1
                nextCounter = Math.max(watermark.current(fenceEntryId) + 1, 1);
                nextVersion = nextCounter;
            }
        } else {
            nextVersion = Math.max(watermark.current(fenceEntryId) + 1, 1);
            nextCounter = nextVersion;
        }

        // Align version with fenceCounter to allow tracking via watermark
        nextVersion = Math.max(nextVersion, nextCounter);
        nextCounter = nextVersion;

        FencePayload newPayload = new FencePayload(nextCounter, grantedTo, validUntilMillis);
        byte[] payloadBytes = newPayload.encode();

        // 2. Publish FENCE entry
        try {
            publisher.publish(EntryType.FENCE, scope, "", nextVersion, payloadBytes, signingKey, sigAlg, issuerId, broker)
                     .join();
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, loggable, "Failed to publish FENCE grant to broker", e);
        }

        // 3. Update local watermark
        watermark.accept(fenceEntryId, nextVersion);

        return new FenceGrant(nextCounter, validUntilMillis);
    }

    /**
     * Asserts that a fenceCounter is still valid (not stale) for a capacity mutation
     * by checking the local watermark and verifying against the broker state (§9.4).
     */
    public void assertFenceValid(Scope scope, long fenceCounter, Broker broker, TrustRoot trustRoot, VersionWatermark watermark) {
        EntryId fenceEntryId = new EntryId(scope, EntryType.FENCE, "");
        
        // 1. Verify against local watermark
        long currentWatermark = watermark.current(fenceEntryId);
        if (fenceCounter < currentWatermark) {
            throw new VeridotException(ErrorCode.FENCE_TOKEN_STALE, fenceEntryId.loggable(), 
                "Fence counter " + fenceCounter + " is stale compared to local watermark " + currentWatermark);
        }

        // 2. Fetch current FENCE from broker to detect concurrent updates on other nodes
        byte[] bytes;
        try {
            bytes = broker.get(fenceEntryId.storageKey());
        } catch (Exception e) {
            // Broker unavailable: fall back to local watermark check
            return;
        }

        if (bytes != null) {
            try {
                Envelope envelope = Envelope.parse(bytes);
                signatureVerifier.verify(envelope, trustRoot);
                FencePayload currentPayload = FencePayload.decode(envelope.payload);
                if (fenceCounter < currentPayload.fenceCounter()) {
                    // Update local watermark to prevent future redundant broker calls
                    watermark.accept(fenceEntryId, Math.max(envelope.version, currentPayload.fenceCounter()));
                    throw new VeridotException(ErrorCode.FENCE_TOKEN_STALE, fenceEntryId.loggable(), 
                        "Fence counter " + fenceCounter + " is stale. Latest broker fence is " + currentPayload.fenceCounter());
                }
            } catch (VeridotException e) {
                throw e;
            } catch (Exception ignored) {
                // If FENCE is corrupt, fall back to success
            }
        }
    }
}
