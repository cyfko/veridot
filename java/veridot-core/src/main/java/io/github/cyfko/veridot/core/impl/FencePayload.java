package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Payload of a FENCE entry (§4.6, §10).
 *
 * <p>Totally orders capacity-affecting mutations within a scope.
 * V5 adds {@code anchoredAt} (tag 0x04) for State Transparency anchoring (§18.3).
 */
record FencePayload(
    long fenceCounter,      // tag 0x01: monotonic fence counter
    String grantedTo,       // tag 0x02: subject granted the fence
    long validUntil,        // tag 0x03: epoch milliseconds
    OptionalLong anchoredAt // tag 0x04: optional epoch milliseconds — V5 NEW (§18.3)
) {

    public enum Tag {
        FENCE_COUNTER((byte) 0x01),
        GRANTED_TO((byte) 0x02),
        VALID_UNTIL((byte) 0x03),
        ANCHORED_AT((byte) 0x04);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static FencePayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        long fenceCounter = TlvCodec.readU64(fields, Tag.FENCE_COUNTER.code, true);
        String grantedTo = TlvCodec.readString(fields, Tag.GRANTED_TO.code, true);
        long validUntil = TlvCodec.readI64(fields, Tag.VALID_UNTIL.code, true);
        OptionalLong anchoredAt = fields.containsKey(Tag.ANCHORED_AT.code)
            ? OptionalLong.of(TlvCodec.readI64(fields, Tag.ANCHORED_AT.code, true))
            : OptionalLong.empty();

        return new FencePayload(fenceCounter, grantedTo, validUntil, anchoredAt);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u64(Tag.FENCE_COUNTER.code, fenceCounter));
        fields.add(TlvCodec.string(Tag.GRANTED_TO.code, grantedTo));
        fields.add(TlvCodec.i64(Tag.VALID_UNTIL.code, validUntil));
        if (anchoredAt.isPresent()) {
            fields.add(TlvCodec.i64(Tag.ANCHORED_AT.code, anchoredAt.getAsLong()));
        }
        return TlvCodec.encode(fields);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= validUntil;
    }

    /**
     * Validates that anchoredAt, if present, is within the acceptable window (§18.3).
     *
     * @param nowMillis current time in epoch milliseconds
     * @return true if anchoredAt is absent or within [now - FENCE_ANCHOR_MAX_AGE, now]
     */
    public boolean isAnchorValid(long nowMillis) {
        if (anchoredAt.isEmpty()) {
            return true;
        }
        long anchor = anchoredAt.getAsLong();
        long maxAge = Config.FENCE_ANCHOR_MAX_AGE_SECONDS * 1000L;
        return anchor >= (nowMillis - maxAge) && anchor <= nowMillis;
    }
}
