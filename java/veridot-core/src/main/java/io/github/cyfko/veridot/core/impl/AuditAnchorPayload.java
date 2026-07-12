package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of an AUDIT_ANCHOR entry (§4.10).
 *
 * <p>Provides a cryptographic anchor (hash of external audit log) published into
 * a scope for tamper-evidence. Verifiers can compare the anchor hash with an
 * out-of-band audit trail.
 */
record AuditAnchorPayload(
    byte[] anchorHash,      // tag 0x01: SHA-256 hash of the audit log segment (32 bytes)
    long anchorTimestamp,    // tag 0x02: epoch milliseconds when the anchor was computed
    String auditLogRef      // tag 0x03: optional URI/reference to the external audit log
) {

    public enum Tag {
        ANCHOR_HASH((byte) 0x01),
        ANCHOR_TIMESTAMP((byte) 0x02),
        AUDIT_LOG_REF((byte) 0x03);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static AuditAnchorPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        byte[] anchorHash = TlvCodec.readBytes(fields, Tag.ANCHOR_HASH.code, true);
        long anchorTimestamp = TlvCodec.readI64(fields, Tag.ANCHOR_TIMESTAMP.code, true);
        String auditLogRef = fields.containsKey(Tag.AUDIT_LOG_REF.code)
            ? TlvCodec.readString(fields, Tag.AUDIT_LOG_REF.code, true)
            : null;

        return new AuditAnchorPayload(anchorHash, anchorTimestamp, auditLogRef);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(new TlvCodec.TlvField(Tag.ANCHOR_HASH.code, anchorHash));
        fields.add(TlvCodec.i64(Tag.ANCHOR_TIMESTAMP.code, anchorTimestamp));
        if (auditLogRef != null) {
            fields.add(TlvCodec.string(Tag.AUDIT_LOG_REF.code, auditLogRef));
        }
        return TlvCodec.encode(fields);
    }
}
