package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry for tracking and enforcing monotonic version updates per EntryId (§11.1).
 */
final class VersionWatermark {
    private final ConcurrentHashMap<String, Long> watermarks = new ConcurrentHashMap<>();
    private static final ObjectMapper mapper = new ObjectMapper();

    public void accept(EntryId entryId, long version) {
        if (version == 0) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(), "Version cannot be 0");
        }

        String key = toMapKey(entryId);
        watermarks.compute(key, (k, current) -> {
            long currentVal = current == null ? 0L : current;
            if (version <= currentVal) {
                throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(),
                    "Incoming version " + version + " is not strictly greater than recorded watermark " + currentVal);
            }
            return version;
        });
    }

    public long current(EntryId entryId) {
        return watermarks.getOrDefault(toMapKey(entryId), 0L);
    }

    /**
     * Serializes the watermarks to a byte array (§12.3.1).
     */
    public byte[] snapshot() {
        try {
            return mapper.writeValueAsBytes(new HashMap<>(watermarks));
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize VersionWatermark", e);
        }
    }

    /**
     * Restores the watermarks from a serialized snapshot.
     */
    @SuppressWarnings("unchecked")
    public void restore(byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) {
            watermarks.clear();
            return;
        }
        try {
            Map<String, Object> rawMap = mapper.readValue(snapshot, Map.class);
            watermarks.clear();
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                if (entry.getValue() instanceof Number) {
                    watermarks.put(entry.getKey(), ((Number) entry.getValue()).longValue());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to restore VersionWatermark", e);
        }
    }

    private static String toMapKey(EntryId entryId) {
        // Safe key construction using NUL separator
        return entryId.scope().value() + "\0" + entryId.entryType().code + "\0" + entryId.key();
    }
}
