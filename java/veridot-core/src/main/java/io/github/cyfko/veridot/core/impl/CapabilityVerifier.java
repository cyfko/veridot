package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies capabilities and delegation chains for configuration and fencing actions (§6.4).
 */
final class CapabilityVerifier {

    private final SignatureVerifier signatureVerifier = new SignatureVerifier();

    // Cache to prevent redundant resolution within short periods
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(boolean authorized, long expiresAt) {}

    public void assertAuthorized(String issuer, Scope scope, Broker broker, TrustRoot trustRoot) {
        assertAuthorized(issuer, scope, null, broker, trustRoot);
    }

    public void assertAuthorized(String issuer, Scope scope, String siteId, Broker broker, TrustRoot trustRoot) {
        if (issuer == null) {
            throw new IllegalArgumentException("Issuer cannot be null");
        }
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }

        long now = System.currentTimeMillis();
        String cacheKey = issuer + "\0" + scope.value() + "\0" + (siteId != null ? siteId : "");
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && now < entry.expiresAt) {
            if (entry.authorized) {
                return;
            } else {
                throw new VeridotException(ErrorCode.CAPABILITY_NOT_FOUND, null, "Issuer " + issuer + " is not authorized for scope " + scope.value() + " (cached)");
            }
        }

        try {
            checkCapabilityChain(issuer, scope, siteId, 0, broker, trustRoot, now);
            // Cache success for up to 60 seconds
            cache.put(cacheKey, new CacheEntry(true, now + 60000));
        } catch (VeridotException e) {
            cache.put(cacheKey, new CacheEntry(false, now + 5000)); // Cache failure for 5 seconds to prevent hammer
            throw e;
        }
    }

    private int checkCapabilityChain(String subject, Scope targetScope, String siteId, int currentDepth, 
                                     Broker broker, TrustRoot trustRoot, long now) {
        // Step 1: Root Identity check (§6.5)
        if (trustRoot.isRootIdentity(subject)) {
            return 0; // Root terminates chain at depth 0
        }

        if (currentDepth > 10) {
            throw new VeridotException(ErrorCode.DELEGATION_DEPTH_EXCEEDED, null, 
                "Delegation chain depth limit exceeded during verification (max 10 hops)");
        }

        // Step 2: Fetch capability entry
        byte[] capBytes = null;
        EntryId targetEntryId = new EntryId(targetScope, EntryType.CAPABILITY, subject);
        
        try {
            capBytes = broker.get(targetEntryId.storageKey());
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, targetEntryId.loggable(), 
                "Failed to retrieve capability from broker", e);
        }

        if (capBytes == null && targetScope.isGroup()) {
            // Try Site Scope capability
            if (siteId != null && !siteId.isEmpty()) {
                EntryId siteEntryId = new EntryId(Scope.site(siteId), EntryType.CAPABILITY, subject);
                try {
                    capBytes = broker.get(siteEntryId.storageKey());
                } catch (Exception e) {
                    throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, siteEntryId.loggable(), 
                        "Failed to retrieve capability from broker", e);
                }
            }
            // Try Global Scope capability
            if (capBytes == null) {
                EntryId globalEntryId = new EntryId(Scope.global(), EntryType.CAPABILITY, subject);
                try {
                    capBytes = broker.get(globalEntryId.storageKey());
                } catch (Exception e) {
                    throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, globalEntryId.loggable(), 
                        "Failed to retrieve capability from broker", e);
                }
            }
        }

        if (capBytes == null) {
            throw new VeridotException(ErrorCode.CAPABILITY_NOT_FOUND, targetEntryId.loggable(), 
                "No capability entry found for subject " + subject + " covering scope " + targetScope.value());
        }

        // Step 3: Validate capability entry envelope
        Envelope capEnvelope = Envelope.parse(capBytes);
        signatureVerifier.verify(capEnvelope, trustRoot);

        // Step 4: Validate capability payload
        CapabilityPayload capPayload = CapabilityPayload.decode(capEnvelope.payload);

        if (now >= capPayload.validUntil()) {
            throw new VeridotException(ErrorCode.CAPABILITY_EXPIRED, capEnvelope.entryId().loggable(), 
                "Capability expired at " + capPayload.validUntil());
        }

        if (!capPayload.covers(targetScope)) {
            throw new VeridotException(ErrorCode.CAPABILITY_NOT_FOUND, capEnvelope.entryId().loggable(), 
                "Capability scope patterns do not cover target scope: " + targetScope.value());
        }

        // Step 5: Recurse to authorize capability's issuer
        int parentDepth = checkCapabilityChain(capEnvelope.issuer, targetScope, siteId, currentDepth + 1, broker, trustRoot, now);
        int totalDepth = parentDepth + 1;

        if (totalDepth > Byte.toUnsignedInt(capPayload.maxDelegationDepth())) {
            throw new VeridotException(ErrorCode.DELEGATION_DEPTH_EXCEEDED, capEnvelope.entryId().loggable(), 
                "Capability delegation depth exceeded: chain depth is " + totalDepth + ", max allowed is " + capPayload.maxDelegationDepth());
        }

        return totalDepth;
    }
}
