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
 * Implémentation principale de {@link PublicKeyTrustRoot} orchestrant les niveaux de cache L1 et L2.
 * <p>
 * Cette classe met en œuvre :
 * <ul>
 *     <li>Une résolution lock-free en cache L1 (mémoire) et L2 (RocksDB local) sur le chemin critique.</li>
 *     <li>Un rafraîchissement asynchrone d'arrière-plan via un thread démon unique ({@code veridot-trust-refresh}).</li>
 *     <li>Une tolérance aux pannes réseau en supportant une période transitoire obsolète (Stale Window).</li>
 *     <li>Une synchronisation incrémentale différentielle automatique et périodique avec le cluster TAD.</li>
 * </ul>
 */
public final class CachingTrustRoot implements PublicKeyTrustRoot, AutoCloseable {

    /**
     * États de cycle de vie opérationnels du moteur {@code CachingTrustRoot}.
     */
    public enum State {
        /** Moteur créé mais non initialisé. */
        CREATED,
        /** Initialisation et bootstrap en cours. */
        INITIALIZING,
        /** Moteur prêt à répondre aux résolutions de clés. */
        INITIALIZED,
        /** Échec de l'initialisation (TAD indisponible et cache local vide). */
        FAILED,
        /** Moteur arrêté et ressources libérées. */
        CLOSED
    }

    /** Cache mémoire L1 de niveau supérieur. */
    private final L1MemoryCache l1Cache;
    
    /** Cache persistant local RocksDB L2 de niveau inférieur. */
    private final L2Cache l2Cache;
    
    /** Fournisseur distant de confiance (API client vers TAD). */
    private final TrustRootProvider provider;
    
    /** Validateur cryptographique de signatures. */
    private final SignatureVerifier signatureVerifier;
    
    /** Seuil à partir duquel une clé arrivant à expiration doit être rafraîchie en arrière-plan. */
    private final Duration refreshThreshold;
    
    /** Fenêtre temporelle de secours pendant laquelle une clé expirée nominalement reste acceptée. */
    private final Duration staleWindow;
    
    /** Intervalle de temps pour la synchronisation incrémentale périodique d'arrière-plan. */
    private final Duration fullSyncInterval;
    
    /** Délai maximal d'attente lors d'une résolution concomitante à l'initialisation. */
    private final Duration resolveWaitTimeout;
    
    /** Planificateur monotâche pour exécuter toutes les opérations réseau asynchrones. */
    private final ScheduledExecutorService scheduler;
    
    /** Latch permettant d'attendre que le bootstrap soit terminé lors des résolutions concurrentes au démarrage. */
    private final CountDownLatch initializationLatch = new CountDownLatch(1);
    
    /** État volatile du cycle de vie opérationnel. */
    private volatile State state = State.CREATED;

    /**
     * Construit une instance de {@code CachingTrustRoot}.
     *
     * @param l1Cache Cache L1.
     * @param l2Cache Cache L2.
     * @param provider Fournisseur d'API TAD.
     * @param signatureVerifier Validateur de signatures.
     * @param refreshThreshold Seuil de rafraîchissement anticipé.
     * @param staleWindow Fenêtre de validité de secours.
     * @param fullSyncInterval Périodicité de synchronisation globale.
     * @param resolveWaitTimeout Timeout de résolution au boot.
     */
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

    /**
     * Crée une instance de Builder fluide.
     *
     * @return Un {@link CachingTrustRootBuilder}.
     */
    public static CachingTrustRootBuilder builder() {
        return new CachingTrustRootBuilder();
    }

    /**
     * Retourne l'état actuel du cycle de vie opérationnel.
     *
     * @return L'état du moteur.
     */
    public State state() {
        return state;
    }

    /**
     * Initialise le moteur et effectue le bootstrap initial.
     * <p>
     * Le bootstrap suit cette logique :
     * 1. Chargement de toutes les entrées présentes dans le cache local L2.
     * 2. Promotion des entrées valides ou encore tolérées dans le cache L1.
     * 3. Si au moins une clé valide est trouvée, le démarrage réussit immédiatement.
     * 4. Si aucune clé valide n'est trouvée (démarrage à froid), tente une synchronisation directe avec le TAD.
     *
     * @throws TrustRootInitializationException si l'initialisation échoue et qu'aucune clé n'est disponible.
     */
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
                    // Ignore les clés corrompues ou invalides dans L2
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
        // Attente en cas d'appel concurrent lors de l'initialisation du cache
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

        // Étape 1 : Résolution rapide en cache L1 mémoire (Lock-Free)
        Optional<CachedKeyEntry> l1Opt = l1Cache.get(subject);
        if (l1Opt.isPresent()) {
            CachedKeyEntry entry = l1Opt.get();
            if (entry.isValid(now)) {
                return new TrustIdentity(entry.publicKey(), false);
            }
            if (entry.isStale(now)) {
                // Déclencher le rafraîchissement asynchrone pour mettre à jour la clé
                refreshAsync(subject);
                return new TrustIdentity(entry.publicKey(), false);
            }
            l1Cache.evict(subject);
        }

        // Étape 2 : Fallback sur le cache L2 RocksDB (Lock-Free)
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

    /**
     * Déclenche un rafraîchissement asynchrone pour récupérer la clé du sujet depuis le TAD d'arrière-plan.
     *
     * @param subject Identifiant du sujet à rafraîchir.
     * @return Un {@link CompletableFuture} représentant l'exécution asynchrone de la tâche.
     */
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
                // Ignorer ou loguer silencieusement en arrière-plan pour éviter de bloquer l'appelant
            }
        }, scheduler);
    }

    /**
     * Effectue la synchronisation différentielle incrémentale en interrogeant le fournisseur
     * pour toutes les modifications depuis la date de la dernière synchronisation.
     */
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

    /**
     * Promeut une {@link TrustEntry} du cache L2 vers le cache L1 mémoire en décodant la clé.
     */
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
                Instant.now(),
                entry.kemPublicKey(),
                entry.isInstanceScoped(),
                entry.algorithm()
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

    /**
     * Planifie un rafraîchissement asynchrone si la durée de validité restante de la clé est inférieure au seuil nominal.
     */
    private void scheduleRefreshIfNeeded(TrustEntry entry) {
        Instant now = Instant.now();
        Duration remaining = Duration.between(now, entry.notAfter());
        if (remaining.compareTo(refreshThreshold) < 0) {
            refreshAsync(entry.subject());
        }
    }

    /**
     * Démarre la tâche planifiée récurrente pour les synchronisations incrémentales globales périodiques.
     */
    private void startScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (provider.isHealthy()) {
                    synchronizeFromProvider();
                }
            } catch (Exception e) {
                // Silencieux
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
