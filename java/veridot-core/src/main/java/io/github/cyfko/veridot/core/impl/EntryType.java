package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;

/**
 * Registered Entry Types in Protocol V5 (§4, Appendix C.1).
 *
 * <p>Each entry type corresponds to a distinct payload schema. Entry type 0x01
 * is reserved (eliminated in V5). Codes 0x00 and 0x0B+ are
 * unregistered and MUST be rejected with {@link ErrorCode#UNREGISTERED_ENTRY_TYPE}.
 */
public enum EntryType {

    // 0x01 is RESERVED — the former key-epoch type was eliminated in V5 (no enum constant)

    CAPABILITY((byte) 0x02),
    CONFIG((byte) 0x03),
    LIVENESS((byte) 0x04),
    FENCE((byte) 0x05),
    SNAPSHOT_MARKER((byte) 0x06),
    SECURE_PAYLOAD((byte) 0x07),
    SIGNED_DATA((byte) 0x08),
    AUDIT_ANCHOR((byte) 0x09),
    TRUST_REVOCATION((byte) 0x0A);

    public final byte code;

    EntryType(byte code) {
        this.code = code;
    }

    /**
     * Resolves an entry type from its wire code.
     *
     * @param code the single-byte entry type code from the envelope
     * @return the corresponding {@link EntryType}
     * @throws VeridotException with {@link ErrorCode#UNREGISTERED_ENTRY_TYPE} if the code is
     *         unknown, reserved (0x01), or out of range
     */
    public static EntryType fromCode(byte code) {
        if (code == 0x01) {
            throw new VeridotException(
                ErrorCode.UNREGISTERED_ENTRY_TYPE, null,
                "Entry type 0x01 is reserved (key-epoch eliminated in V5)"
            );
        }
        for (EntryType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new VeridotException(
            ErrorCode.UNREGISTERED_ENTRY_TYPE, null,
            "Unknown entry type code: 0x" + String.format("%02X", code)
        );
    }
}
