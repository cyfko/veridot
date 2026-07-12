package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a SIGNED_DATA entry (§4.9).
 *
 * <p>Carries arbitrary signed data published by a subject into a scope.
 * The payload is opaque to the protocol; interpretation is application-specific.
 */
record SignedDataPayload(
    String contentType,     // tag 0x01: MIME type (e.g. "application/json")
    byte[] data,            // tag 0x02: raw data bytes
    String label,           // tag 0x03: optional human-readable label
    long createdAt,         // tag 0x04: epoch milliseconds
    String correlationId    // tag 0x05: optional correlation identifier
) {

    public enum Tag {
        CONTENT_TYPE((byte) 0x01),
        DATA((byte) 0x02),
        LABEL((byte) 0x03),
        CREATED_AT((byte) 0x04),
        CORRELATION_ID((byte) 0x05);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static SignedDataPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        String contentType = TlvCodec.readString(fields, Tag.CONTENT_TYPE.code, true);
        byte[] data = TlvCodec.readBytes(fields, Tag.DATA.code, true);
        String label = fields.containsKey(Tag.LABEL.code)
            ? TlvCodec.readString(fields, Tag.LABEL.code, true)
            : null;
        long createdAt = TlvCodec.readI64(fields, Tag.CREATED_AT.code, true);
        String correlationId = fields.containsKey(Tag.CORRELATION_ID.code)
            ? TlvCodec.readString(fields, Tag.CORRELATION_ID.code, true)
            : null;

        return new SignedDataPayload(contentType, data, label, createdAt, correlationId);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.string(Tag.CONTENT_TYPE.code, contentType));
        fields.add(new TlvCodec.TlvField(Tag.DATA.code, data));
        if (label != null) {
            fields.add(TlvCodec.string(Tag.LABEL.code, label));
        }
        fields.add(TlvCodec.i64(Tag.CREATED_AT.code, createdAt));
        if (correlationId != null) {
            fields.add(TlvCodec.string(Tag.CORRELATION_ID.code, correlationId));
        }
        return TlvCodec.encode(fields);
    }
}
