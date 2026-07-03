package io.github.cyfko.veridot.trustroots.core.cache;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Interface pour l'implémentation du cache L2 (persistant local).
 */
public interface L2Cache extends AutoCloseable {
    
    Optional<TrustEntry> get(String subject);
    
    void put(TrustEntry entry);
    
    List<TrustEntry> loadAll();
    
    Optional<Instant> lastSyncTime();
    
    void markSyncTime(Instant time);
    
    long estimatedSize();
    
    @Override
    void close();
}
