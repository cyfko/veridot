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
 * Payload of a CONFIG entry (§7.3).
 * Specifies the configuration (capacity limit, eviction strategy, default TTL) of a scope.
 */
record ConfigPayload(
    OptionalInt max,            // tag 0x01: optional u32
    byte pol,                   // tag 0x02: optional u8 (default 0x01)
    OptionalLong dttl,          // tag 0x03: optional u64
    Optional<String> name,      // tag 0x04: optional string
    Optional<String> description, // tag 0x05: optional string
    OptionalLong validity       // tag 0x06: optional u64 (validity duration in ms)
) {

    public static ConfigPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        OptionalInt max = fields.containsKey((byte) 0x01) 
            ? OptionalInt.of((int) TlvCodec.readU32(fields, (byte) 0x01, true))
            : OptionalInt.empty();

        byte pol = fields.containsKey((byte) 0x02)
            ? TlvCodec.readU8(fields, (byte) 0x02, true)
            : 0x01; // Default FIFO

        OptionalLong dttl = fields.containsKey((byte) 0x03)
            ? OptionalLong.of(TlvCodec.readU64(fields, (byte) 0x03, true))
            : OptionalLong.empty();

        Optional<String> name = fields.containsKey((byte) 0x04)
            ? Optional.of(TlvCodec.readString(fields, (byte) 0x04, true))
            : Optional.empty();

        Optional<String> description = fields.containsKey((byte) 0x05)
            ? Optional.of(TlvCodec.readString(fields, (byte) 0x05, true))
            : Optional.empty();

        OptionalLong validity = fields.containsKey((byte) 0x06)
            ? OptionalLong.of(TlvCodec.readU64(fields, (byte) 0x06, true))
            : OptionalLong.empty();

        return new ConfigPayload(max, pol, dttl, name, description, validity);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        if (max.isPresent()) {
            byte[] bytes = new byte[4];
            ByteBuffer.wrap(bytes).putInt(max.getAsInt());
            fields.add(new TlvCodec.TlvField((byte) 0x01, bytes));
        }
        // Always write policy to avoid ambiguity
        fields.add(TlvCodec.u8((byte) 0x02, pol));
        if (dttl.isPresent()) {
            fields.add(TlvCodec.u64((byte) 0x03, dttl.getAsLong()));
        }
        name.ifPresent(s -> fields.add(TlvCodec.string((byte) 0x04, s)));
        description.ifPresent(s -> fields.add(TlvCodec.string((byte) 0x05, s)));
        if (validity.isPresent()) {
            fields.add(TlvCodec.u64((byte) 0x06, validity.getAsLong()));
        }

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
