package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.BrokerTransportException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MetadataBroker} implementation for unit testing.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. Sending an empty message removes the entry (revocation).
 * </p>
 *
 * <p><strong>F5 fix</strong>: {@link #sendLocal} is equivalent to a full {@link #send}
 * in this in-memory implementation, since there is no distinction between local and
 * remote writes.</p>
 */
public class InMemoryMetadataBroker implements MetadataBroker {

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> send(String key, String message) throws BrokerTransportException {
        if (key == null || key.isBlank() || message == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (message.isBlank()) {
            store.remove(key);
        } else {
            store.put(key, message);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * In the in-memory implementation, a local write is equivalent to a full distributed
     * write. This satisfies the F5 contract (same-node read-after-write) trivially.
     */
    @Override
    public void sendLocal(String key, String message) {
        if (key == null || key.isBlank() || message == null) return;
        if (message.isBlank()) {
            store.remove(key);
        } else {
            store.put(key, message);
        }
    }

    @Override
    public String get(String key) throws BrokerExtractionException {
        String value = store.get(key);
        if (value == null) {
            throw new BrokerExtractionException("Key not found: " + key);
        }
        return value;
    }

    @Override
    public List<String> getKeysByPrefix(String prefix) throws BrokerExtractionException {
        List<String> result = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(key);
            }
        }
        return result;
    }

    /** For test assertions: total number of entries in the store. */
    public int size() {
        return store.size();
    }

    /** For test assertions: whether a specific key exists in the store. */
    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    /** For test assertions: raw stored value for a key (may be null). */
    public String getRaw(String key) {
        return store.get(key);
    }
}
