package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.nio.charset.StandardCharsets;

/**
 * Unique identifier for a protocol entry: (scope, entryType, key) (§3.3).
 */
record EntryId(Scope scope, EntryType entryType, String key) {

    public EntryId {
        if (scope == null) {
            throw new IllegalArgumentException("Scope cannot be null");
        }
        if (entryType == null) {
            throw new IllegalArgumentException("EntryType cannot be null");
        }
        if (key == null) {
            key = "";
        }

        // Validate singleton constraints
        boolean isSingleton = (entryType == EntryType.CONFIG || 
                               entryType == EntryType.FENCE || 
                               entryType == EntryType.SNAPSHOT_MARKER);
        if (isSingleton && !key.isEmpty()) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, 
                "Key must be empty for singleton entry type " + entryType.name() + ". Got: " + key);
        }

        // Validate key character and length constraints
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length > 4096) {
            throw new VeridotException(ErrorCode.INVALID_IDENTIFIER_LENGTH, loggable(), 
                "Key length exceeds 4096 bytes: " + keyBytes.length);
        }

        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == 0x00 || (c >= 0x01 && c <= 0x1F)) {
                throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, loggable(), 
                    "Key contains invalid control character: " + String.format("0x%02X", (int) c));
            }
        }
    }

    /**
     * Computes the broker storage key (§3.3): scope ‖ 0x00 ‖ entryType.code ‖ 0x00 ‖ key.
     */
    public byte[] storageKey() {
        byte[] scopeBytes = scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);

        byte[] result = new byte[scopeBytes.length + 1 + 1 + 1 + keyBytes.length];
        System.arraycopy(scopeBytes, 0, result, 0, scopeBytes.length);
        result[scopeBytes.length] = 0x00;
        result[scopeBytes.length + 1] = entryType.code;
        result[scopeBytes.length + 2] = 0x00;
        System.arraycopy(keyBytes, 0, result, scopeBytes.length + 3, keyBytes.length);

        return result;
    }

    public String loggable() {
        return "(" + scope.value() + ", " + entryType.name() + ", " + key + ")";
    }
}
