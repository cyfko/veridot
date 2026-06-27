package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.time.Instant;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Retrieves key announcements from the MetadataBroker and validates their signatures,
 * clock drift, and expiry using the TrustAnchor.
 */
class MetadataVerifier {
    private static final Logger logger = Logger.getLogger(MetadataVerifier.class.getName());

    private final MetadataBroker metadataBroker;
    private final TrustAnchor trustAnchor;

    public MetadataVerifier(MetadataBroker metadataBroker, TrustAnchor trustAnchor) {
        this.metadataBroker = metadataBroker;
        this.trustAnchor = trustAnchor;
    }

    public Map<String, String> verifyKeyAnnouncement(String messageId) throws BrokerExtractionException {
        String message = metadataBroker.get(messageId);
        if (message == null || message.isBlank()) {
            throw new BrokerExtractionException("Public key metadata not found for messageId: " + messageId);
        }

        Map<String, String> meta = Protocol.parseMetadata(message);

        // F1: Validate the key announcement signature via TrustAnchor
        try {
            TrustedAnnouncement.verify(messageId, meta, trustAnchor);
        } catch (TrustResolutionException.SignatureRejected e) {
            logger.severe("SECURITY: Key announcement signature rejected for messageId=" + messageId + ": " + e.getMessage());
            throw new BrokerExtractionException("Key announcement signature rejected: " + e.getMessage(), e);
        } catch (TrustResolutionException.Unavailable e) {
            logger.warning("TrustAnchor temporarily unavailable for messageId=" + messageId + ": " + e.getMessage());
            throw new BrokerExtractionException("TrustAnchor unavailable: " + e.getMessage(), e);
        } catch (TrustResolutionException e) {
            throw new BrokerExtractionException("Trust anchor validation failed: " + e.getMessage(), e);
        }

        // Validate timestamp presence and clock drift (§9.1)
        String tsStr = meta.get(Protocol.PROP_TS);
        if (tsStr == null) {
            throw new BrokerExtractionException("Missing timestamp in key announcement");
        }
        long ts = Long.parseLong(tsStr);
        long now = Instant.now().getEpochSecond();
        if (ts > now + Config.MAX_CLOCK_DRIFT_SECONDS) {
            throw new BrokerExtractionException(
                    "Message timestamp is " + (ts - now) + "s in the future (max drift: ±5min)");
        }

        // Validate expiration (§3.3.4)
        String ttlStr = meta.get(Protocol.PROP_TTL);
        if (ttlStr != null) {
            long ttl = Long.parseLong(ttlStr);
            if (now >= ts + ttl) {
                throw new BrokerExtractionException("Token metadata has expired");
            }
            if (ts + ttl + Config.MAX_CLOCK_DRIFT_SECONDS < now) {
                throw new BrokerExtractionException(
                        "Message expired: timestamp + ttl + drift = " + (ts + ttl + Config.MAX_CLOCK_DRIFT_SECONDS)
                                + " < now = " + now);
            }
        }

        return meta;
    }
}
