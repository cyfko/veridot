package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a FENCE entry (§9.2).
 * Totally orders capacity-affecting mutations.
 */
record FencePayload(
    long fenceCounter,   // tag 0x01
    String grantedTo,    // tag 0x02
    long validUntil      // tag 0x03
) {

    public enum Tag {
        FENCE_COUNTER((byte) 0x01),
        GRANTED_TO((byte) 0x02),
        VALID_UNTIL((byte) 0x03);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static FencePayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        long fenceCounter = TlvCodec.readU64(fields, Tag.FENCE_COUNTER.code, true);
        String grantedTo = TlvCodec.readString(fields, Tag.GRANTED_TO.code, true);
        long validUntil = TlvCodec.readI64(fields, Tag.VALID_UNTIL.code, true);

        return new FencePayload(fenceCounter, grantedTo, validUntil);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u64(Tag.FENCE_COUNTER.code, fenceCounter));
        fields.add(TlvCodec.string(Tag.GRANTED_TO.code, grantedTo));
        fields.add(TlvCodec.i64(Tag.VALID_UNTIL.code, validUntil));
        return TlvCodec.encode(fields);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= validUntil;
    }
}
