package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.TrustRoot;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the hierarchical configuration effective for a group scope (§7.2).
 */
final class ConfigResolver {

    private final SignatureVerifier signatureVerifier = new SignatureVerifier();
    private final ConcurrentHashMap<String, CachedConfig> cache = new ConcurrentHashMap<>();

    private record CachedConfig(ConfigPayload payload, long expiresAt) {}

    public ConfigPayload resolve(Scope groupScope, String siteId,
                                  Broker broker, TrustRoot trustRoot,
                                  CapabilityVerifier capabilityVerifier,
                                  VersionWatermark watermark) {
        if (groupScope == null) {
            throw new IllegalArgumentException("groupScope cannot be null");
        }

        long now = System.currentTimeMillis();
        String cacheKey = groupScope.value() + "\0" + (siteId != null ? siteId : "");
        CachedConfig cached = cache.get(cacheKey);
        if (cached != null && now < cached.expiresAt) {
            return cached.payload();
        }

        ConfigPayload payload = resolveFromBroker(groupScope, siteId, broker, trustRoot, capabilityVerifier, watermark);
        
        // Cache configuration for 60 seconds (CONFIG_CACHE_TTL_SECONDS = 60)
        cache.put(cacheKey, new CachedConfig(payload, now + 60000));
        return payload;
    }

    private ConfigPayload resolveFromBroker(Scope groupScope, String siteId,
                                            Broker broker, TrustRoot trustRoot,
                                            CapabilityVerifier capabilityVerifier,
                                            VersionWatermark watermark) {
        // Priority 1: Group level config
        ConfigPayload config = tryParseConfig(groupScope, broker, trustRoot, capabilityVerifier, watermark);
        if (config != null) {
            return config;
        }

        // Priority 2: Site level config
        if (siteId != null && !siteId.isEmpty()) {
            config = tryParseConfig(Scope.site(siteId), broker, trustRoot, capabilityVerifier, watermark);
            if (config != null) {
                return config;
            }
        }

        // Priority 3: Global level config
        config = tryParseConfig(Scope.global(), broker, trustRoot, capabilityVerifier, watermark);
        if (config != null) {
            return config;
        }

        return null;
    }

    private ConfigPayload tryParseConfig(Scope scope, Broker broker, TrustRoot trustRoot,
                                         CapabilityVerifier capabilityVerifier, VersionWatermark watermark) {
        EntryId entryId = new EntryId(scope, EntryType.CONFIG, "");
        byte[] bytes;
        try {
            bytes = broker.get(entryId.storageKey());
        } catch (Exception e) {
            return null; // treat transport/broker failure as absent config, fall through
        }

        if (bytes == null) {
            return null;
        }

        try {
            Envelope envelope = Envelope.parse(bytes);
            signatureVerifier.verify(envelope, trustRoot);
            
            // §7.4: config issuer must hold capability for the scope
            capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);

            // Decode payload first to check temporal validity
            ConfigPayload config = ConfigPayload.decode(envelope.payload);
            long now = System.currentTimeMillis();
            long validityMs = config.validity().isPresent() ? config.validity().getAsLong() : 360000L * 1000L;
            if (now >= envelope.timestamp + validityMs) {
                return null; // Config expired
            }

            // (L-02): CONFIG must pass through watermark acceptance
            long currentWatermark = watermark.current(entryId);
            if (envelope.version < currentWatermark) {
                return null;
            }
            if (envelope.version > currentWatermark) {
                watermark.accept(entryId, envelope.version);
            }

            return config;
        } catch (Exception e) {
            // §7.5: malformed config is rejected and ignored
            return null;
        }
    }

    public void invalidateCache(Scope scope) {
        if (scope != null) {
            cache.keySet().removeIf(key -> key.startsWith(scope.value() + "\0"));
        }
    }

    public void clearCache() {
        cache.clear();
    }
}
