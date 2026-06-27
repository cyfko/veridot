package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Checks for revocation tombstones and verifies tombstone signatures via the TrustAnchor,
 * applying the latest-timestamp-wins rules.
 */
class RevocationManager {
    private static final Logger logger = Logger.getLogger(RevocationManager.class.getName());

    private final MetadataBroker metadataBroker;
    private final TrustAnchor trustAnchor;

    public RevocationManager(MetadataBroker metadataBroker, TrustAnchor trustAnchor) {
        this.metadataBroker = metadataBroker;
        this.trustAnchor = trustAnchor;
    }

    public void validateNotRevoked(String groupId, String sequenceId, Map<String, String> announcementMeta)
            throws BrokerExtractionException {
        String revokeKey = Protocol.buildRevocationKey(groupId);
        String tombstoneMsg;
        try {
            tombstoneMsg = metadataBroker.get(revokeKey);
        } catch (Exception e) {
            // No tombstone found → not revoked, proceed normally
            return;
        }

        if (tombstoneMsg == null || tombstoneMsg.isBlank()) {
            return; // deleted tombstone entry
        }

        try {
            Map<String, String> tombstoneMeta = Protocol.parseMetadata(tombstoneMsg);
            String tombstoneTimestampStr = tombstoneMeta.get(Protocol.PROP_TS);
            String announcementTimestampStr = announcementMeta.get(Protocol.PROP_TS);
            String tombstoneTarget = tombstoneMeta.get(Protocol.PROP_TARGET);

            if (tombstoneTimestampStr == null || announcementTimestampStr == null) {
                return; // malformed entries — let other validations handle it
            }

            long tombstoneTs = Long.parseLong(tombstoneTimestampStr);
            long announcementTs = Long.parseLong(announcementTimestampStr);

            // Verify tombstone signature using TrustAnchor (prevents forged tombstones)
            String tombstoneSigB64 = tombstoneMeta.get(Protocol.PROP_SIG);
            String tombstoneSignerId = tombstoneMeta.get(Protocol.PROP_SID);
            if (tombstoneSigB64 == null || tombstoneSigB64.isEmpty()
                    || tombstoneSignerId == null || tombstoneTarget == null) {
                logger.warning("Ignoring unsigned/incomplete tombstone for group=" + groupId);
                return;
            }

            try {
                TrustedAnnouncement.verify(revokeKey, tombstoneMeta, trustAnchor);
                // Tombstone signature verified — if we reach here and tombstoneTs > announcementTs,
                // the revocation is cryptographically proven
                if (tombstoneTs > announcementTs) {
                    if (Protocol.SEQ_ALL.equals(tombstoneTarget) || sequenceId.equals(tombstoneTarget)) {
                        logger.info("Rejecting token — verified tombstone (ts=" + tombstoneTs
                                + ") supersedes announcement (ts=" + announcementTs + ")");
                        throw new BrokerExtractionException(
                                "Session revoked: verified tombstone (ts=" + tombstoneTs
                                        + ") supersedes announcement (ts=" + announcementTs + ")");
                    }
                }
            } catch (BrokerExtractionException e) {
                throw e;
            } catch (TrustResolutionException e) {
                // Tombstone signature invalid — ignore the tombstone (could be forged)
                logger.warning("Ignoring tombstone with invalid signature for group=" + groupId + ": " + e.getMessage());
            }
        } catch (BrokerExtractionException e) {
            throw e;
        } catch (Exception e) {
            // Malformed tombstone — log and proceed (don't block verification on bad tombstones)
            logger.warning("Failed to parse tombstone for group=" + groupId + ": " + e.getMessage());
        }
    }
}
