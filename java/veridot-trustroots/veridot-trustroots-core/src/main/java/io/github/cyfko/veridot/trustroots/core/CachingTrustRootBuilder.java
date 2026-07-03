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
 * Builder fluent pour configurer et instancier un moteur {@link CachingTrustRoot}.
 * Permet de définir les seuils de cache, la taille maximale, et le répertoire L2.
 */
public final class CachingTrustRootBuilder {

    /** Fournisseur d'API TAD. */
    private TrustRootProvider provider;
    
    /** Cache L2 personnalisé (si spécifié). */
    private L2Cache l2Cache;
    
    /** Répertoire local pour stocker les fichiers RocksDB (si cache L2 par défaut). */
    private Path l2Directory;
    
    /** Taille maximale autorisée en mémoire pour le cache L1. Valeur par défaut : 10000. */
    private int l1MaxSize = 10000;
    
    /** Seuil anticipé de rafraîchissement d'une clé. Valeur par défaut : 1 heure. */
    private Duration refreshThreshold = Duration.ofHours(1);
    
    /** Fenêtre temporelle de validité transitoire de secours (Stale). Valeur par défaut : 5 minutes. */
    private Duration staleWindow = Duration.ofMinutes(5);
    
    /** Périodicité de synchronisation globale incrémentale. Valeur par défaut : 6 heures. */
    private Duration fullSyncInterval = Duration.ofHours(6);
    
    /** Délai maximum toléré de résolution concurrentielle au boot. Valeur par défaut : 5 secondes. */
    private Duration resolveWaitTimeout = Duration.ofSeconds(5);

    /**
     * Constructeur par défaut.
     */
    public CachingTrustRootBuilder() {
    }

    /**
     * Définit le fournisseur d'API TAD distant (obligatoire).
     *
     * @param provider Fournisseur d'API.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder provider(TrustRootProvider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Spécifie une implémentation personnalisée du cache L2 (exclut {@code l2Directory}).
     *
     * @param l2Cache Instance L2 personnalisée.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder l2Cache(L2Cache l2Cache) {
        this.l2Cache = l2Cache;
        return this;
    }

    /**
     * Spécifie le répertoire local RocksDB pour le cache L2 par défaut.
     *
     * @param l2Directory Chemin du répertoire de base de données local.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder l2Directory(Path l2Directory) {
        this.l2Directory = l2Directory;
        return this;
    }

    /**
     * Définit la taille maximale pour le cache L1 en mémoire.
     *
     * @param l1MaxSize Taille maximale.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder l1MaxSize(int l1MaxSize) {
        this.l1MaxSize = l1MaxSize;
        return this;
    }

    /**
     * Définit le seuil de validité avant expiration pour le rafraîchissement asynchrone anticipé.
     *
     * @param refreshThreshold Durée limite de fraîcheur nominale.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder refreshThreshold(Duration refreshThreshold) {
        this.refreshThreshold = refreshThreshold;
        return this;
    }

    /**
     * Définit la fenêtre de validité de secours (Stale Window).
     *
     * @param staleWindow Durée de secours tolérée.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder staleWindow(Duration staleWindow) {
        this.staleWindow = staleWindow;
        return this;
    }

    /**
     * Définit la périodicité pour les tâches de synchronisation globale incrémentale.
     *
     * @param fullSyncInterval Périodicité.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder fullSyncInterval(Duration fullSyncInterval) {
        this.fullSyncInterval = fullSyncInterval;
        return this;
    }

    /**
     * Définit le délai d'attente maximum pour la résolution au boot concurrent.
     *
     * @param resolveWaitTimeout Délai maximum.
     * @return {@code this} Builder.
     */
    public CachingTrustRootBuilder resolveWaitTimeout(Duration resolveWaitTimeout) {
        this.resolveWaitTimeout = resolveWaitTimeout;
        return this;
    }

    /**
     * Construit et instancie l'objet {@link CachingTrustRoot} configuré.
     *
     * @return L'instance {@link CachingTrustRoot} configurée.
     * @throws IllegalStateException si des paramètres obligatoires sont manquants.
     */
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
