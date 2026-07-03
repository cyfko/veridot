package io.github.cyfko.veridot.trustroots.core.cache;

import java.security.PublicKey;
import java.time.Instant;

/**
 * Représente une entrée de clé en mémoire dans le cache L1 de Veridot.
 * Contient la clé publique décodée pour accélérer la résolution de tokens.
 *
 * @param subject Identifiant unique du sujet.
 * @param version Numéro de version séquentielle de la clé.
 * @param publicKey Clé publique Java pré-décodée et prête à l'emploi.
 * @param notAfter Instant d'expiration nominal de la clé publique.
 * @param staleDeadline Limite absolue tolérée de validité de secours (notAfter + staleWindow).
 * @param cachedAt Instant d'insertion dans le cache L1.
 */
public record CachedKeyEntry(
    String subject,
    long version,
    PublicKey publicKey,
    Instant notAfter,
    Instant staleDeadline,
    Instant cachedAt
) {
    /**
     * Indique si la clé est encore dans sa période nominale de validité.
     *
     * @param now Instant actuel.
     * @return {@code true} si la clé est nominale, sinon {@code false}.
     */
    public boolean isValid(Instant now) {
        return !now.isAfter(notAfter);
    }

    /**
     * Indique si la clé est expirée nominalement mais reste tolérée dans la fenêtre de secours (Stale).
     *
     * @param now Instant actuel.
     * @return {@code true} si la clé est obsolète (stale) mais valide en secours, sinon {@code false}.
     */
    public boolean isStale(Instant now) {
        return now.isAfter(notAfter) && !now.isAfter(staleDeadline);
    }

    /**
     * Indique si la clé a dépassé toutes ses limites de validité, y compris de secours.
     *
     * @param now Instant actuel.
     * @return {@code true} si la clé est complètement expirée, sinon {@code false}.
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(staleDeadline);
    }
}
