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

    public enum Tag {
        SNAPSHOT_AT((byte) 0x01),
        ENTRY_COUNT((byte) 0x02);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static SnapshotMarkerPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        long snapshotAt = TlvCodec.readI64(fields, Tag.SNAPSHOT_AT.code, true);
        long entryCount = TlvCodec.readU32(fields, Tag.ENTRY_COUNT.code, true);

        return new SnapshotMarkerPayload(snapshotAt, entryCount);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.i64(Tag.SNAPSHOT_AT.code, snapshotAt));

        byte[] countBytes = new byte[4];
        ByteBuffer.wrap(countBytes).putInt((int) entryCount);
        fields.add(new TlvCodec.TlvField(Tag.ENTRY_COUNT.code, countBytes));

        return TlvCodec.encode(fields);
    }
}
