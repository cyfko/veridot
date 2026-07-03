package io.github.cyfko.veridot.trustroots.api;

import io.github.cyfko.veridot.trustroots.api.exception.TrustRootProviderException;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPI interne du module veridot-trustroots pour la résolution distante.
 */
public interface TrustRootProvider {

    /**
     * Récupère la version la plus récente de la TrustEntry pour le sujet donné.
     */
    Optional<TrustEntry> fetch(String subject) throws TrustRootProviderException;

    /**
     * Récupère toutes les TrustEntry modifiées depuis l'instant donné.
     */
    default List<TrustEntry> fetchModifiedSince(Instant since) throws TrustRootProviderException {
        return Collections.emptyList();
    }

    /**
     * Récupère par lot les TrustEntry pour plusieurs sujets.
     */
    default Map<String, TrustEntry> fetchBatch(Collection<String> subjects)
            throws TrustRootProviderException {
        Map<String, TrustEntry> result = new java.util.HashMap<>();
        for (String subject : subjects) {
            fetch(subject).ifPresent(entry -> result.put(subject, entry));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Vérifie la connectivité avec le provider.
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Nom lisible de ce provider, utilisé dans les logs et métriques.
     */
    String name();
}
