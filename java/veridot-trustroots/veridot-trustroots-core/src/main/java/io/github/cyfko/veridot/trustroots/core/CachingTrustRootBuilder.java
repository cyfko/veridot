package io.github.cyfko.veridot.trustroots.core;

import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.core.cache.L1MemoryCache;
import io.github.cyfko.veridot.trustroots.core.cache.L2Cache;
import io.github.cyfko.veridot.trustroots.core.cache.RocksDbL2Cache;
import io.github.cyfko.veridot.trustroots.core.validation.SignatureVerifier;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Builder fluent pour configurer et construire une instance de CachingTrustRoot.
 */
public final class CachingTrustRootBuilder {

    private TrustRootProvider provider;
    private L2Cache l2Cache;
    private Path l2Directory;
    
    private int l1MaxSize = 10000;
    private Duration refreshThreshold = Duration.ofHours(1);
    private Duration staleWindow = Duration.ofMinutes(5);
    private Duration fullSyncInterval = Duration.ofHours(6);
    private Duration resolveWaitTimeout = Duration.ofSeconds(5);

    public CachingTrustRootBuilder provider(TrustRootProvider provider) {
        this.provider = provider;
        return this;
    }

    public CachingTrustRootBuilder l2Cache(L2Cache l2Cache) {
        this.l2Cache = l2Cache;
        return this;
    }

    public CachingTrustRootBuilder l2Directory(Path l2Directory) {
        this.l2Directory = l2Directory;
        return this;
    }

    public CachingTrustRootBuilder l1MaxSize(int l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
        return this;
    }

    public CachingTrustRootBuilder refreshThreshold(Duration refreshThreshold) {
        this.refreshThreshold = refreshThreshold;
        return this;
    }

    public CachingTrustRootBuilder staleWindow(Duration staleWindow) {
        this.staleWindow = staleWindow;
        return this;
    }

    public CachingTrustRootBuilder fullSyncInterval(Duration fullSyncInterval) {
        this.fullSyncInterval = fullSyncInterval;
        return this;
    }

    public CachingTrustRootBuilder resolveWaitTimeout(Duration resolveWaitTimeout) {
        this.resolveWaitTimeout = resolveWaitTimeout;
        return this;
    }

    public CachingTrustRoot build() {
        Objects.requireNonNull(provider, "provider is required");
        
        if (l2Cache == null) {
            Objects.requireNonNull(l2Directory, "Either l2Cache or l2Directory must be specified");
            try {
                l2Cache = new RocksDbL2Cache(l2Directory.toAbsolutePath().toString());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to construct RocksDbL2Cache", e);
            }
        }

        L1MemoryCache l1Cache = new L1MemoryCache(l1MaxSize);
        SignatureVerifier signatureVerifier = new SignatureVerifier();

        return new CachingTrustRoot(
            l1Cache,
            l2Cache,
            provider,
            signatureVerifier,
            refreshThreshold,
            staleWindow,
            fullSyncInterval,
            resolveWaitTimeout
        );
    }
}
