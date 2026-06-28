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

    public static FencePayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        long fenceCounter = TlvCodec.readU64(fields, (byte) 0x01, true);
        String grantedTo = TlvCodec.readString(fields, (byte) 0x02, true);
        long validUntil = TlvCodec.readI64(fields, (byte) 0x03, true);

        return new FencePayload(fenceCounter, grantedTo, validUntil);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u64((byte) 0x01, fenceCounter));
        fields.add(TlvCodec.string((byte) 0x02, grantedTo));
        fields.add(TlvCodec.i64((byte) 0x03, validUntil));
        return TlvCodec.encode(fields);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= validUntil;
    }
}
