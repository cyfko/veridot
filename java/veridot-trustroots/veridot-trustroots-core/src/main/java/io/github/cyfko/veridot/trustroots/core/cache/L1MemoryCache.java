package io.github.cyfko.veridot.trustroots.core.cache;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Cache L1 en mémoire thread-safe basé sur ConcurrentHashMap.
 */
public class L1MemoryCache {
    private final ConcurrentHashMap<String, CachedKeyEntry> map = new ConcurrentHashMap<>();
    private final int maxSize;

    public L1MemoryCache(int maxSize) {
        this.maxSize = maxSize;
    }

    public Optional<CachedKeyEntry> get(String subject) {
        return Optional.ofNullable(map.get(subject));
    }

    public void put(String subject, CachedKeyEntry entry) {
        if (map.size() >= maxSize && !map.containsKey(subject)) {
            throw new IllegalStateException("L1 cache max size reached: " + maxSize);
        }
        map.put(subject, entry);
    }

    public void evict(String subject) {
        map.remove(subject);
    }

    public void clear() {
        map.clear();
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public CachedKeyEntry compute(String subject, BiFunction<? super String, ? super CachedKeyEntry, ? extends CachedKeyEntry> remappingFunction) {
        return map.compute(subject, (k, v) -> {
            if (v == null && map.size() >= maxSize) {
                // Check if the remapping function would add a new entry
                CachedKeyEntry val = remappingFunction.apply(k, v);
                if (val != null) {
                    throw new IllegalStateException("L1 cache max size reached: " + maxSize);
                }
                return null;
            }
            return remappingFunction.apply(k, v);
        });
    }

    public Collection<CachedKeyEntry> values() {
        return map.values();
    }
}
