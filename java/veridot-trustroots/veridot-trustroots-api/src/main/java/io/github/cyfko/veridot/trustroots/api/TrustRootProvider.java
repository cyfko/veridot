package io.github.cyfko.veridot.trustroots.api;

import io.github.cyfko.veridot.trustroots.api.exception.TrustRootProviderException;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface de fournisseur de service (SPI) du module {@code veridot-trustroots}.
 * Permet la récupération distante des clés publiques de confiance (Trust Entries) depuis les clusters TAD.
 */
public interface TrustRootProvider {

    /**
     * Récupère la version la plus récente de la {@link TrustEntry} pour le sujet donné.
     *
     * @param subject L'identifiant unique du sujet (ex: "service-name").
     * @return Un {@link Optional} contenant la {@link TrustEntry} si trouvée, sinon {@link Optional#empty()}.
     * @throws TrustRootProviderException si une erreur de communication ou réseau survient.
     */
    Optional<TrustEntry> fetch(String subject) throws TrustRootProviderException;

    /**
     * Récupère toutes les entrées {@link TrustEntry} modifiées depuis l'instant donné.
     * Utilisé pour la synchronisation différentielle incrémentale.
     *
     * @param since L'instant de référence à partir duquel les modifications sont recherchées.
     * @return La liste des {@link TrustEntry} modifiées depuis cet instant.
     * @throws TrustRootProviderException si une erreur réseau ou de traitement survient.
     */
    default List<TrustEntry> fetchModifiedSince(Instant since) throws TrustRootProviderException {
        return Collections.emptyList();
    }

    /**
     * Récupère par lot les entrées {@link TrustEntry} pour plusieurs sujets.
     *
     * @param subjects La collection d'identifiants de sujets à résoudre.
     * @return Une Map associant chaque sujet trouvé à son entrée {@link TrustEntry} correspondante.
     * @throws TrustRootProviderException si une erreur de traitement ou réseau survient.
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
     * Vérifie la connectivité et la santé opérationnelle du provider.
     *
     * @return {@code true} si le service distant est joignable et sain, sinon {@code false}.
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * Nom lisible de ce provider, principalement utilisé à des fins de journalisation (logs) et d'exposition de métriques.
     *
     * @return Le nom unique du provider.
     */
    String name();
}
