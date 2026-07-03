package io.github.cyfko.veridot.trustroots.core.cache;

import java.security.PublicKey;
import java.time.Instant;

/**
 * Représente une entrée de clé mise en cache en mémoire (L1).
 */
public record CachedKeyEntry(
    String subject,
    long version,
    PublicKey publicKey,
    Instant notAfter,
    Instant staleDeadline, // notAfter + staleWindow
    Instant cachedAt
) {
    public boolean isValid(Instant now) {
        return !now.isAfter(notAfter);
    }

    public boolean isStale(Instant now) {
        return now.isAfter(notAfter) && !now.isAfter(staleDeadline);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(staleDeadline);
    }
}
