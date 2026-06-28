package io.github.cyfko.veridot.core.impl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a SNAPSHOT_MARKER entry (§11.5).
 * Represents a point-in-time snapshot verification marker.
 */
record SnapshotMarkerPayload(
    long snapshotAt,    // tag 0x01: i64
    long entryCount     // tag 0x02: u32 (stored as long)
) {

    public static SnapshotMarkerPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        long snapshotAt = TlvCodec.readI64(fields, (byte) 0x01, true);
        long entryCount = TlvCodec.readU32(fields, (byte) 0x02, true);

        return new SnapshotMarkerPayload(snapshotAt, entryCount);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.i64((byte) 0x01, snapshotAt));

        byte[] countBytes = new byte[4];
        ByteBuffer.wrap(countBytes).putInt((int) entryCount);
        fields.add(new TlvCodec.TlvField((byte) 0x02, countBytes));

        return TlvCodec.encode(fields);
    }
}
