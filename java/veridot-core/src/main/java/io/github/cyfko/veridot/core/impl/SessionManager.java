package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import io.github.cyfko.veridot.core.EvictionPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Handles per-group session capacity limits, lazy garbage collection of expired sessions,
 * and session eviction strategies (FIFO, LIFO, LRU, REJECT).
 */
class SessionManager {
    private static final Logger logger = Logger.getLogger(SessionManager.class.getName());

    private static final long EVICTION_SEND_TIMEOUT_SECONDS = 10;

    private final MetadataBroker metadataBroker;
    private final ConfigurationResolver configResolver;
    private final MetadataPublisher metadataPublisher;

    public SessionManager(MetadataBroker metadataBroker, ConfigurationResolver configResolver, MetadataPublisher metadataPublisher) {
        this.metadataBroker = metadataBroker;
        this.configResolver = configResolver;
        this.metadataPublisher = metadataPublisher;
    }

    public void enforceSessionLimit(String groupId) {
        EffectiveConfig config = configResolver.resolveConfig(groupId);
        if (config.maxSessions() == -1) {
            return; // no session capacity limit
        }

        try {
            String prefix = Protocol.groupPrefix(groupId);
            List<String> allKeys = metadataBroker.getKeysByPrefix(prefix);

            // Filter to non-expired, non-reserved active keys; garbage-collect expired entries
            List<String> validKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (Protocol.isReservedSequence(key)) continue;
                if (isMessageIdActive(key)) {
                    validKeys.add(key);
                } else {
                    // Lazy GC: remove expired entries from the broker to prevent stale accumulation
                    try {
                        metadataBroker.send(key, ""); // fire-and-forget is acceptable for GC
                    } catch (Exception gc) {
                        logger.fine("Failed to GC expired key " + key + ": " + gc.getMessage());
                    }
                }
            }

            while (validKeys.size() >= config.maxSessions()) {
                if (config.policy() == EvictionPolicy.REJECT) {
                    throw new SessionCapacityExceededException(groupId, config.maxSessions());
                }
                String toEvict = selectEvictionTarget(validKeys, config.policy());
                try {
                    // Publish formal revocation, then delete — with short timeout (F8)
                    String[] parts = Protocol.parseMessageId(toEvict);
                    metadataPublisher.publishRevocationTombstone(groupId, parts[2]);
                    metadataBroker.send(toEvict, "").get(EVICTION_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    logger.warning("Eviction send timed out for key " + toEvict + " after "
                            + EVICTION_SEND_TIMEOUT_SECONDS + "s — proceeding anyway");
                } catch (Exception e) {
                    logger.severe("Failed to evict key " + toEvict + ": " + e.getMessage());
                }
                validKeys.remove(toEvict);
            }
        } catch (SessionCapacityExceededException e) {
            throw e; // must propagate to caller
        } catch (Exception e) {
            logger.severe("Error enforcing session limit for group [" + groupId + "]: " + e.getMessage());
        }
    }

    public boolean isMessageIdActive(String messageId) {
        try {
            String message = metadataBroker.get(messageId);
            if (message == null || message.isBlank()) return false;
            Map<String, String> meta = Protocol.parseMetadata(message);

            // Clock drift check (§9.1)
            String tsStr = meta.get(Protocol.PROP_TS);
            if (tsStr != null) {
                long ts = Long.parseLong(tsStr);
                if (ts > Instant.now().getEpochSecond() + Config.MAX_CLOCK_DRIFT_SECONDS) return false;
            }

            // TTL check
            String ttlStr = meta.get(Protocol.PROP_TTL);
            if (ttlStr == null) return true; // no TTL = never expires
            if (tsStr == null) return true;
            long timestamp = Long.parseLong(tsStr);
            long ttl = Long.parseLong(ttlStr);
            return Instant.now().getEpochSecond() < timestamp + ttl;
        } catch (BrokerExtractionException e) {
            return false; // key not found = not active
        } catch (Exception e) {
            logger.severe("Error checking isMessageIdActive for [" + messageId + "]: " + e.getMessage());
            return false;
        }
    }

    private String selectEvictionTarget(List<String> keys, EvictionPolicy policy) {
        String selected = keys.get(0);
        long selectedTs = getTimestampForKey(selected);

        for (int i = 1; i < keys.size(); i++) {
            long ts = getTimestampForKey(keys.get(i));
            switch (policy) {
                case FIFO, LRU -> {
                    if (ts < selectedTs) { selected = keys.get(i); selectedTs = ts; }
                }
                case LIFO -> {
                    if (ts > selectedTs) { selected = keys.get(i); selectedTs = ts; }
                }
            }
        }
        return selected;
    }

    private long getTimestampForKey(String key) {
        try {
            String msg = metadataBroker.get(key);
            Map<String, String> meta = Protocol.parseMetadata(msg);
            return Long.parseLong(meta.getOrDefault(Protocol.PROP_TS, "0"));
        } catch (Exception e) {
            return 0;
        }
    }
}
