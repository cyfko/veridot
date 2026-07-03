package io.github.cyfko.veridot.trustroots.core;

import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.impl.ErrorCode;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootInitializationException;
import io.github.cyfko.veridot.trustroots.core.cache.CachedKeyEntry;
import io.github.cyfko.veridot.trustroots.core.cache.L1MemoryCache;
import io.github.cyfko.veridot.trustroots.core.cache.L2Cache;
import io.github.cyfko.veridot.trustroots.core.validation.SignatureVerifier;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Implémentation principale de TrustRoot avec cache L1/L2 et rafraîchissement asynchrone.
 */
public final class CachingTrustRoot implements PublicKeyTrustRoot, AutoCloseable {

    public enum State {
        CREATED,
        INITIALIZING,
        INITIALIZED,
        FAILED,
        CLOSED
    }

    private final L1MemoryCache l1Cache;
    private final L2Cache l2Cache;
    private final TrustRootProvider provider;
    private final SignatureVerifier signatureVerifier;
    
    private final Duration refreshThreshold;
    private final Duration staleWindow;
    private final Duration fullSyncInterval;
    private final Duration resolveWaitTimeout;
    
    private final ScheduledExecutorService scheduler;
    private final CountDownLatch initializationLatch = new CountDownLatch(1);
    private volatile State state = State.CREATED;

    public CachingTrustRoot(
            L1MemoryCache l1Cache,
            L2Cache l2Cache,
            TrustRootProvider provider,
            SignatureVerifier signatureVerifier,
            Duration refreshThreshold,
            Duration staleWindow,
            Duration fullSyncInterval,
            Duration resolveWaitTimeout) {
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        this.provider = provider;
        this.signatureVerifier = signatureVerifier;
        this.refreshThreshold = refreshThreshold;
        this.staleWindow = staleWindow;
        this.fullSyncInterval = fullSyncInterval;
        this.resolveWaitTimeout = resolveWaitTimeout;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "veridot-trust-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    public static CachingTrustRootBuilder builder() {
        return new CachingTrustRootBuilder();
    }

    public State state() {
        return state;
    }

    public void initialize() throws TrustRootInitializationException {
        if (state == State.INITIALIZED) {
            return;
        }
        state = State.INITIALIZING;
        
        try {
            List<TrustEntry> l2Entries = l2Cache.loadAll();
            Instant now = Instant.now();
            boolean hasValidEntry = false;
            
            for (TrustEntry entry : l2Entries) {
                try {
                    signatureVerifier.verify(entry);
                    if (entry.isValidAt(now) || entry.isValidAt(now.minus(staleWindow))) {
                        promoteToL1(entry);
                        hasValidEntry = true;
                    }
                } catch (Exception e) {
                    // Ignore corrupted or invalid signatures from L2
                }
            }

            if (hasValidEntry) {
                state = State.INITIALIZED;
                initializationLatch.countDown();
            } else {
                try {
                    synchronizeFromProvider();
                    state = State.INITIALIZED;
                    initializationLatch.countDown();
                } catch (Exception e) {
                    state = State.FAILED;
                    initializationLatch.countDown();
                    throw new TrustRootInitializationException("Failed to initialize TrustRoot from provider", e);
                }
            }

            startScheduler();
        } catch (Exception e) {
            state = State.FAILED;
            initializationLatch.countDown();
            if (e instanceof TrustRootInitializationException) {
                throw (TrustRootInitializationException) e;
            }
            throw new TrustRootInitializationException("Unexpected error during TrustRoot initialization", e);
        }
    }

    @Override
    public TrustIdentity resolve(String subject) {
        if (state == State.INITIALIZING) {
            try {
                boolean completed = initializationLatch.await(resolveWaitTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, subject, "Cache not ready (initialization timeout)");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, subject, "Interrupted while waiting for cache initialization");
            }
        }

        if (state == State.FAILED) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, subject, "TrustRoot initialization failed");
        }
        
        if (state == State.CLOSED) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, subject, "TrustRoot is closed");
        }

        Instant now = Instant.now();

        // 1. Cache L1
        Optional<CachedKeyEntry> l1Opt = l1Cache.get(subject);
        if (l1Opt.isPresent()) {
            CachedKeyEntry entry = l1Opt.get();
            if (entry.isValid(now)) {
                return new TrustIdentity(entry.publicKey(), false);
            }
            if (entry.isStale(now)) {
                refreshAsync(subject);
                return new TrustIdentity(entry.publicKey(), false);
            }
            l1Cache.evict(subject);
        }

        // 2. Cache L2 (RocksDB)
        Optional<TrustEntry> l2Opt = l2Cache.get(subject);
        if (l2Opt.isPresent()) {
            TrustEntry entry = l2Opt.get();
            if (entry.isValidAt(now)) {
                CachedKeyEntry promoted = promoteToL1(entry);
                scheduleRefreshIfNeeded(entry);
                return new TrustIdentity(promoted.publicKey(), false);
            }
            if (entry.isValidAt(now.minus(staleWindow))) {
                CachedKeyEntry promoted = promoteToL1(entry);
                refreshAsync(subject);
                return new TrustIdentity(promoted.publicKey(), false);
            }
        }

        throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, subject, "No trusted public key found for subject: " + subject);
    }

    public CompletableFuture<Void> refreshAsync(String subject) {
        return CompletableFuture.runAsync(() -> {
            try {
                Optional<TrustEntry> entryOpt = provider.fetch(subject);
                if (entryOpt.isPresent()) {
                    TrustEntry entry = entryOpt.get();
                    signatureVerifier.verify(entry);
                    l2Cache.put(entry);
                    promoteToL1(entry);
                }
            } catch (Exception e) {
                // Log warning or handle silently
            }
        }, scheduler);
    }

    private void synchronizeFromProvider() throws Exception {
        Instant lastSync = l2Cache.lastSyncTime().orElse(Instant.EPOCH);
        List<TrustEntry> updates = provider.fetchModifiedSince(lastSync);
        
        Instant now = Instant.now();
        for (TrustEntry entry : updates) {
            signatureVerifier.verify(entry);
            l2Cache.put(entry);
            if (entry.isValidAt(now) || entry.isValidAt(now.minus(staleWindow))) {
                promoteToL1(entry);
            }
        }
        l2Cache.markSyncTime(now);
    }

    private CachedKeyEntry promoteToL1(TrustEntry entry) {
        try {
            byte[] keyBytes = Base64.getUrlDecoder().decode(entry.publicKeyEncoded());
            KeyFactory kf = KeyFactory.getInstance(entry.algorithm().jcaKeyAlgorithm());
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(keyBytes));
            
            Instant staleDeadline = entry.notAfter().plus(staleWindow);
            CachedKeyEntry cached = new CachedKeyEntry(
                entry.subject(),
                entry.version(),
                publicKey,
                entry.notAfter(),
                staleDeadline,
                Instant.now()
            );
            
            l1Cache.compute(entry.subject(), (k, existing) -> {
                if (existing == null || entry.version() >= existing.version()) {
                    return cached;
                }
                return existing;
            });
            return cached;
        } catch (Exception e) {
            throw new RuntimeException("Failed to promote TrustEntry to L1", e);
        }
    }

    private void scheduleRefreshIfNeeded(TrustEntry entry) {
        Instant now = Instant.now();
        Duration remaining = Duration.between(now, entry.notAfter());
        if (remaining.compareTo(refreshThreshold) < 0) {
            refreshAsync(entry.subject());
        }
    }

    private void startScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (provider.isHealthy()) {
                    synchronizeFromProvider();
                }
            } catch (Exception e) {
                // Handle or log failure
            }
        }, fullSyncInterval.toMillis(), fullSyncInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        state = State.CLOSED;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        l2Cache.close();
    }
}
