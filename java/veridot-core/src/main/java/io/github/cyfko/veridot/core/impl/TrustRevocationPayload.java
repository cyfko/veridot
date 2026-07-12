package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a TRUST_REVOCATION entry (§4.11).
 *
 * <p>Published by TAAS when a subject's trust entry is revoked (§15.6).
 * This is a TAAS-committed entry type used for State Transparency (§18.1).
 */
record TrustRevocationPayload(
    String revokedSubject,     // tag 0x01: subject identifier being revoked
    long revokedAt,            // tag 0x02: epoch milliseconds when revocation was processed
    String reason              // tag 0x03: optional human-readable revocation reason
) {

    public enum Tag {
        REVOKED_SUBJECT((byte) 0x01),
        REVOKED_AT((byte) 0x02),
        REASON((byte) 0x03);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static TrustRevocationPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        String revokedSubject = TlvCodec.readString(fields, Tag.REVOKED_SUBJECT.code, true);
        long revokedAt = TlvCodec.readI64(fields, Tag.REVOKED_AT.code, true);
        String reason = fields.containsKey(Tag.REASON.code)
            ? TlvCodec.readString(fields, Tag.REASON.code, true)
            : null;

        return new TrustRevocationPayload(revokedSubject, revokedAt, reason);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.string(Tag.REVOKED_SUBJECT.code, revokedSubject));
        fields.add(TlvCodec.i64(Tag.REVOKED_AT.code, revokedAt));
        if (reason != null) {
            fields.add(TlvCodec.string(Tag.REASON.code, reason));
        }
        return TlvCodec.encode(fields);
    }
}
