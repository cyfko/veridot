package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;

/**
 * Registered Entry Types in Protocol V4 (§4).
 */
public enum EntryType {
    KEY_EPOCH((byte) 0x01),
    CAPABILITY((byte) 0x02),
    CONFIG((byte) 0x03),
    LIVENESS((byte) 0x04),
    FENCE((byte) 0x05),
    SNAPSHOT_MARKER((byte) 0x06),
    SECURE_PAYLOAD((byte) 0x07);

    public final byte code;

    EntryType(byte code) {
        this.code = code;
    }

    public static EntryType fromCode(byte code) {
        for (EntryType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new VeridotException(ErrorCode.UNREGISTERED_ENTRY_TYPE, null, "Unknown entry type code: " + code);
    }
}
