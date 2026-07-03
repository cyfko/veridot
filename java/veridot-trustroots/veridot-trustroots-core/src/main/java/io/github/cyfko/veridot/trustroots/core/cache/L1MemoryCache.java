package io.github.cyfko.veridot.trustroots.core.cache;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Cache L1 en mémoire thread-safe et hautement concurrent basé sur {@link ConcurrentHashMap}.
 * <p>
 * Conçu pour respecter la contrainte d'exclusion absolue des verrous bloquants (Lock-Free) sur le chemin critique
 * de la méthode de résolution de clés {@code resolve()}.
 */
public class L1MemoryCache {
    
    /** Dictionnaire sous-jacent stockant les clés en mémoire. */
    private final ConcurrentHashMap<String, CachedKeyEntry> map = new ConcurrentHashMap<>();
    
    /** Taille maximale autorisée pour le cache L1 afin d'éviter la saturation mémoire. */
    private final int maxSize;

    /**
     * Initialise le cache avec une taille maximale.
     *
     * @param maxSize Nombre maximal d'entrées autorisées.
     */
    public L1MemoryCache(int maxSize) {
        this.maxSize = maxSize;
    }

    /**
     * Récupère une entrée du cache L1.
     *
     * @param subject Identifiant du sujet.
     * @return Un {@link Optional} contenant la {@link CachedKeyEntry} si présente, sinon {@link Optional#empty()}.
     */
    public Optional<CachedKeyEntry> get(String subject) {
        return Optional.ofNullable(map.get(subject));
    }

    /**
     * Enregistre ou met à jour une entrée dans le cache L1.
     *
     * @param subject Identifiant du sujet.
     * @param entry L'entrée en cache à insérer.
     * @throws IllegalStateException si la taille limite du cache est dépassée.
     */
    public void put(String subject, CachedKeyEntry entry) {
        if (map.size() >= maxSize && !map.containsKey(subject)) {
            throw new IllegalStateException("L1 cache max size reached: " + maxSize);
        }
        map.put(subject, entry);
    }

    /**
     * Expulse une entrée du cache L1.
     *
     * @param subject Identifiant du sujet à supprimer.
     */
    public void evict(String subject) {
        map.remove(subject);
    }

    /**
     * Vide complètement le cache L1.
     */
    public void clear() {
        map.clear();
    }

    /**
     * Retourne le nombre actuel d'entrées dans le cache L1.
     *
     * @return La taille du cache.
     */
    public int size() {
        return map.size();
    }

    /**
     * Indique si le cache L1 est vide.
     *
     * @return {@code true} si vide, sinon {@code false}.
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Effectue un calcul atomique de mise à jour ou d'insertion sur le cache L1.
     *
     * @param subject Identifiant du sujet.
     * @param remappingFunction Fonction de recalcul de la valeur.
     * @return La nouvelle valeur calculée insérée.
     * @throws IllegalStateException si la taille maximale est atteinte lors d'une insertion.
     */
    public CachedKeyEntry compute(String subject, BiFunction<? super String, ? super CachedKeyEntry, ? extends CachedKeyEntry> remappingFunction) {
        return map.compute(subject, (k, v) -> {
            if (v == null && map.size() >= maxSize) {
                CachedKeyEntry val = remappingFunction.apply(k, v);
                if (val != null) {
                    throw new IllegalStateException("L1 cache max size reached: " + maxSize);
                }
                return null;
            }
            return remappingFunction.apply(k, v);
        });
    }

    /**
     * Retourne la collection complète des valeurs stockées en mémoire.
     *
     * @return Les entrées {@link CachedKeyEntry} du cache L1.
     */
    public Collection<CachedKeyEntry> values() {
        return map.values();
    }
}
