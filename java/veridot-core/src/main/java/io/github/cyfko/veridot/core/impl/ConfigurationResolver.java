package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier.EvictionPolicy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Resolves the configuration cache and distributed configurations (Local → Site → Global → Default)
 * from the broker, verifying authentication and authority.
 */
class ConfigurationResolver {
    private static final Logger logger = Logger.getLogger(ConfigurationResolver.class.getName());

    private final MetadataBroker metadataBroker;
    private final TrustAnchor trustAnchor;
    private final EffectiveConfig defaultConfig;
    private final ConcurrentHashMap<String, CachedConfig> configCache = new ConcurrentHashMap<>();

    private record CachedConfig(EffectiveConfig config, long resolvedAt) {
        public boolean isExpired() {
            return Instant.now().getEpochSecond() > resolvedAt + Config.CONFIG_CACHE_TTL_SECONDS;
        }
    }

    public ConfigurationResolver(MetadataBroker metadataBroker, TrustAnchor trustAnchor, EffectiveConfig defaultConfig) {
        this.metadataBroker = metadataBroker;
        this.trustAnchor = trustAnchor;
        this.defaultConfig = defaultConfig;
    }

    public EffectiveConfig resolveConfig(String groupId) {
        CachedConfig cached = configCache.get(groupId);
        if (cached != null && !cached.isExpired()) {
            return cached.config();
        }

        EffectiveConfig resolved = resolveFromBroker(groupId);
        configCache.put(groupId, new CachedConfig(resolved, Instant.now().getEpochSecond()));
        return resolved;
    }

    public void invalidateLocal(String scopeId) {
        configCache.remove(scopeId);
    }

    public void invalidateAll() {
        configCache.clear();
    }

    private EffectiveConfig resolveFromBroker(String groupId) {
        // Priority 1: Local config
        EffectiveConfig local = tryParseConfig(Protocol.buildLocalConfigKey(groupId));
        if (local != null) return local;

        // Priority 2: Site config (if the group declares a site in its messages)
        String siteId = resolveSiteForGroup(groupId);
        if (siteId != null) {
            EffectiveConfig site = tryParseConfig(Protocol.buildSiteConfigKey(siteId));
            if (site != null) return site;
        }

        // Priority 3: Global config
        EffectiveConfig global = tryParseConfig(Protocol.buildGlobalConfigKey());
        if (global != null) return global;

        // Priority 4: Constructor defaults
        return defaultConfig;
    }

    private EffectiveConfig tryParseConfig(String configKey) {
        try {
            String msg = metadataBroker.get(configKey);
            if (msg == null || msg.isBlank()) return null;
            Map<String, String> meta = Protocol.parseMetadata(msg);

            // F9 — authenticity check before trusting config content
            try {
                TrustedAnnouncement.verify(configKey, meta, trustAnchor);
            } catch (TrustResolutionException.SignatureRejected e) {
                logger.warning("Rejected unsigned/forged config at " + configKey
                        + " — falling back to next priority level: " + e.getMessage());
                return null;
            } catch (TrustResolutionException.Unavailable e) {
                logger.warning("TrustAnchor unavailable for config at " + configKey
                        + ", falling back to next priority level: " + e.getMessage());
                return null;
            }

            String sid = meta.get(Protocol.PROP_SID);
            if (sid != null && !trustAnchor.isAuthorizedForScope(sid, configKey)) {
                logger.severe("SECURITY: sid=" + sid + " not authorized for config scope " + configKey);
                return null;
            }

            // Validate: timestamp and validUntil present and config not expired (§4.2.4)
            String tsStr = meta.get(Protocol.PROP_TS);
            String vuStr = meta.get(Protocol.PROP_EXP);
            if (tsStr == null || vuStr == null) return null;
            long validUntil = Long.parseLong(vuStr);
            if (Instant.now().getEpochSecond() > validUntil) return null; // config expired

            // Parse optional properties with semantic validation (H2/M8)
            int ms = defaultConfig.maxSessions();
            if (meta.containsKey(Protocol.PROP_MAX)) {
                int brokerMs = Integer.parseInt(meta.get(Protocol.PROP_MAX));
                if (brokerMs == -1 || brokerMs > 0) {
                    ms = brokerMs;
                } else {
                    logger.warning("Ignoring invalid broker maxSessions=" + brokerMs + " (must be -1 or > 0)");
                }
            }
            EvictionPolicy pol = meta.containsKey(Protocol.PROP_POL)
                    ? EvictionPolicy.valueOf(meta.get(Protocol.PROP_POL))
                    : defaultConfig.policy();
            long dttl = defaultConfig.defaultTTL();
            if (meta.containsKey(Protocol.PROP_DTTL)) {
                long brokerTtl = Long.parseLong(meta.get(Protocol.PROP_DTTL));
                if (brokerTtl > 0) {
                    dttl = brokerTtl;
                } else {
                    logger.warning("Ignoring invalid broker defaultTTL=" + brokerTtl + " (must be > 0)");
                }
            }

            return new EffectiveConfig(ms, pol, dttl);
        } catch (Exception e) {
            return null; // malformed config → skip
        }
    }

    private String resolveSiteForGroup(String groupId) {
        try {
            String prefix = Protocol.groupPrefix(groupId);
            List<String> keys = metadataBroker.getKeysByPrefix(prefix);
            for (String key : keys) {
                if (Protocol.isReservedSequence(key)) continue;
                try {
                    String msg = metadataBroker.get(key);
                    if (msg == null || msg.isBlank()) continue;
                    Map<String, String> meta = Protocol.parseMetadata(msg);
                    String site = meta.get(Protocol.PROP_SITE);
                    if (site != null && !site.isBlank()) return site;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
