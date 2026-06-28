package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.impl.Scope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Broker implementation for V4 unit testing.
 */
public class InMemoryBroker implements Broker {

    private static final class ByteArrayKey {
        private final byte[] bytes;

        ByteArrayKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ByteArrayKey)) return false;
            return Arrays.equals(bytes, ((ByteArrayKey) o).bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final ConcurrentHashMap<ByteArrayKey, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> put(byte[] storageKey, byte[] envelopeBytes) {
        if (storageKey == null) throw new IllegalArgumentException("storageKey cannot be null");
        ByteArrayKey key = new ByteArrayKey(storageKey);
        if (envelopeBytes == null || envelopeBytes.length == 0) {
            store.remove(key);
        } else {
            store.put(key, envelopeBytes);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public byte[] get(byte[] storageKey) {
        if (storageKey == null) return null;
        return store.get(new ByteArrayKey(storageKey));
    }

    @Override
    public List<BrokerEntry> snapshot(Scope scope) {
        if (scope == null) throw new IllegalArgumentException("scope cannot be null");
        List<BrokerEntry> result = new ArrayList<>();
        
        for (Map.Entry<ByteArrayKey, byte[]> entry : store.entrySet()) {
            byte[] value = entry.getValue();
            if (value == null || value.length == 0) continue;
            try {
                io.github.cyfko.veridot.core.impl.Envelope env = io.github.cyfko.veridot.core.impl.Envelope.parse(value);
                if (env.scope.equals(scope)) {
                    result.add(new BrokerEntry(entry.getKey().bytes, value));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    @Override
    public void putLocal(byte[] storageKey, byte[] envelopeBytes) {
        put(storageKey, envelopeBytes);
    }

    public int size() {
        return store.size();
    }

    public boolean containsKey(byte[] storageKey) {
        if (storageKey == null) return false;
        return store.containsKey(new ByteArrayKey(storageKey));
    }

    public void clear() {
        store.clear();
    }
}
