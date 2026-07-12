package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Payload of a CONFIG entry (§4.4, §7.3).
 *
 * <p>Specifies the configuration (capacity limit, eviction strategy, default TTL) of a scope.
 * V5 adds {@code maxInstanceLifetime} (tag 0x07) and {@code attestationPlugin} (tag 0x08).
 */
record ConfigPayload(
    OptionalInt max,                    // tag 0x01: optional u32 — max sessions
    byte pol,                           // tag 0x02: u8 — eviction policy (default 0x01 FIFO)
    OptionalLong dttl,                  // tag 0x03: optional u64 — default TTL (ms)
    Optional<String> name,              // tag 0x04: optional string — scope display name
    Optional<String> description,       // tag 0x05: optional string — scope description
    OptionalLong validity,              // tag 0x06: optional u64 — validity duration (ms)
    OptionalLong maxInstanceLifetime,   // tag 0x07: optional u64 — max instance lifetime (ms) — V5 NEW
    Optional<String> attestationPlugin  // tag 0x08: optional string — required attestation plugin name — V5 NEW
) {

    public enum Tag {
        MAX((byte) 0x01),
        POL((byte) 0x02),
        DTTL((byte) 0x03),
        NAME((byte) 0x04),
        DESCRIPTION((byte) 0x05),
        VALIDITY((byte) 0x06),
        MAX_INSTANCE_LIFETIME((byte) 0x07),
        ATTESTATION_PLUGIN((byte) 0x08);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static ConfigPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        OptionalInt max = fields.containsKey(Tag.MAX.code) 
            ? OptionalInt.of((int) TlvCodec.readU32(fields, Tag.MAX.code, true))
            : OptionalInt.empty();

        byte pol = fields.containsKey(Tag.POL.code)
            ? TlvCodec.readU8(fields, Tag.POL.code, true)
            : 0x01; // Default FIFO

        OptionalLong dttl = fields.containsKey(Tag.DTTL.code)
            ? OptionalLong.of(TlvCodec.readU64(fields, Tag.DTTL.code, true))
            : OptionalLong.empty();

        Optional<String> name = fields.containsKey(Tag.NAME.code)
            ? Optional.of(TlvCodec.readString(fields, Tag.NAME.code, true))
            : Optional.empty();

        Optional<String> description = fields.containsKey(Tag.DESCRIPTION.code)
            ? Optional.of(TlvCodec.readString(fields, Tag.DESCRIPTION.code, true))
            : Optional.empty();

        OptionalLong validity = fields.containsKey(Tag.VALIDITY.code)
            ? OptionalLong.of(TlvCodec.readU64(fields, Tag.VALIDITY.code, true))
            : OptionalLong.empty();

        OptionalLong maxInstanceLifetime = fields.containsKey(Tag.MAX_INSTANCE_LIFETIME.code)
            ? OptionalLong.of(TlvCodec.readU64(fields, Tag.MAX_INSTANCE_LIFETIME.code, true))
            : OptionalLong.empty();

        Optional<String> attestationPlugin = fields.containsKey(Tag.ATTESTATION_PLUGIN.code)
            ? Optional.of(TlvCodec.readString(fields, Tag.ATTESTATION_PLUGIN.code, true))
            : Optional.empty();

        return new ConfigPayload(max, pol, dttl, name, description, validity, maxInstanceLifetime, attestationPlugin);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        if (max.isPresent()) {
            byte[] bytes = new byte[4];
            ByteBuffer.wrap(bytes).putInt(max.getAsInt());
            fields.add(new TlvCodec.TlvField(Tag.MAX.code, bytes));
        }
        // Always write policy to avoid ambiguity
        fields.add(TlvCodec.u8(Tag.POL.code, pol));
        if (dttl.isPresent()) {
            fields.add(TlvCodec.u64(Tag.DTTL.code, dttl.getAsLong()));
        }
        name.ifPresent(s -> fields.add(TlvCodec.string(Tag.NAME.code, s)));
        description.ifPresent(s -> fields.add(TlvCodec.string(Tag.DESCRIPTION.code, s)));
        if (validity.isPresent()) {
            fields.add(TlvCodec.u64(Tag.VALIDITY.code, validity.getAsLong()));
        }
        if (maxInstanceLifetime.isPresent()) {
            fields.add(TlvCodec.u64(Tag.MAX_INSTANCE_LIFETIME.code, maxInstanceLifetime.getAsLong()));
        }
        attestationPlugin.ifPresent(s -> fields.add(TlvCodec.string(Tag.ATTESTATION_PLUGIN.code, s)));

        return TlvCodec.encode(fields);
    }

    public EvictionPolicy evictionPolicy() {
        return switch (pol) {
            case 0x01 -> EvictionPolicy.FIFO;
            case 0x02 -> EvictionPolicy.LIFO;
            case 0x03 -> EvictionPolicy.LRU;
            case 0x04 -> EvictionPolicy.REJECT;
            default -> throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Unknown eviction policy code: " + pol);
        };
    }

    public static byte fromEvictionPolicy(EvictionPolicy policy) {
        if (policy == null) {
            return 0x01; // default FIFO
        }
        return switch (policy) {
            case FIFO -> 0x01;
            case LIFO -> 0x02;
            case LRU -> 0x03;
            case REJECT -> 0x04;
        };
    }
}
