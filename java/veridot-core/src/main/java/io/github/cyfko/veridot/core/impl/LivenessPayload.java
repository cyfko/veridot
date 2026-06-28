package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a LIVENESS entry (§8.2).
 * Represents positive-proof session validation/revocation.
 */
record LivenessPayload(
    byte status,      // tag 0x01: 0x01=ACTIVE, 0x02=REVOKED
    long asOf,        // tag 0x02: milliseconds since epoch
    long validUntil   // tag 0x03: milliseconds since epoch
) {
    public static final byte ACTIVE  = 0x01;
    public static final byte REVOKED = 0x02;

    public static LivenessPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        byte status = TlvCodec.readU8(fields, (byte) 0x01, true);
        long asOf = TlvCodec.readI64(fields, (byte) 0x02, true);
        long validUntil = TlvCodec.readI64(fields, (byte) 0x03, true);

        return new LivenessPayload(status, asOf, validUntil);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u8((byte) 0x01, status));
        fields.add(TlvCodec.i64((byte) 0x02, asOf));
        fields.add(TlvCodec.i64((byte) 0x03, validUntil));
        return TlvCodec.encode(fields);
    }

    public boolean isActive() {
        return status == ACTIVE;
    }

    public boolean isFresh(long nowMillis) {
        return nowMillis < validUntil;
    }
}
