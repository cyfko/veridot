package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Encoder/Decoder for TLV Payload Encoding (§4.1).
 */
final class TlvCodec {

    record TlvField(byte tag, byte[] value) {}

    private TlvCodec() {}

    public static byte[] encode(List<TlvField> fields) {
        int totalLen = 0;
        for (TlvField field : fields) {
            if (field.tag == 0x00) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Tag cannot be 0x00");
            }
            totalLen += 1 + 2 + field.value.length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalLen);
        for (TlvField field : fields) {
            buffer.put(field.tag);
            buffer.putShort((short) field.value.length);
            buffer.put(field.value);
        }
        return buffer.array();
    }

    public static Map<Byte, byte[]> parse(byte[] payload) {
        if (payload == null) {
            return Collections.emptyMap();
        }

        Map<Byte, byte[]> result = new HashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        while (buffer.hasRemaining()) {
            if (buffer.remaining() < 3) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Malformed TLV payload: incomplete tag/length");
            }

            byte tag = buffer.get();
            if (tag == 0x00) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "TLV tag cannot be 0x00");
            }

            int len = Short.toUnsignedInt(buffer.getShort());
            if (buffer.remaining() < len) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, 
                    "Malformed TLV payload: expected " + len + " bytes for tag " + String.format("0x%02X", tag) + ", got " + buffer.remaining());
            }

            byte[] valBytes = new byte[len];
            buffer.get(valBytes);

            if (result.put(tag, valBytes) != null) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, 
                    "Duplicate TLV tag " + String.format("0x%02X", tag) + " in payload");
            }
        }

        return result;
    }

    // Helper reader methods

    public static byte readU8(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return 0;
        }
        if (val.length != 1) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Invalid length for tag " + String.format("0x%02X", tag) + ": expected 1, got " + val.length);
        }
        return val[0];
    }

    public static int readU16(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return 0;
        }
        if (val.length != 2) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Invalid length for tag " + String.format("0x%02X", tag) + ": expected 2, got " + val.length);
        }
        return Short.toUnsignedInt(ByteBuffer.wrap(val).getShort());
    }

    public static long readU32(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return 0;
        }
        if (val.length != 4) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Invalid length for tag " + String.format("0x%02X", tag) + ": expected 4, got " + val.length);
        }
        return Integer.toUnsignedLong(ByteBuffer.wrap(val).getInt());
    }

    public static long readU64(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return 0;
        }
        if (val.length != 8) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Invalid length for tag " + String.format("0x%02X", tag) + ": expected 8, got " + val.length);
        }
        return ByteBuffer.wrap(val).getLong();
    }

    public static long readI64(Map<Byte, byte[]> fields, byte tag, boolean required) {
        return readU64(fields, tag, required);
    }

    public static String readString(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return null;
        }
        return new String(val, StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return null;
        }
        return val;
    }

    public static List<String> readStringList(Map<Byte, byte[]> fields, byte tag, boolean required) {
        byte[] val = fields.get(tag);
        if (val == null) {
            if (required) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Missing required tag: " + String.format("0x%02X", tag));
            }
            return null;
        }

        List<String> list = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(val);
        while (buf.hasRemaining()) {
            if (buf.remaining() < 2) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Malformed string list encoding: incomplete length");
            }
            int len = Short.toUnsignedInt(buf.getShort());
            if (buf.remaining() < len) {
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Malformed string list encoding: string truncated");
            }
            byte[] strBytes = new byte[len];
            buf.get(strBytes);
            list.add(new String(strBytes, StandardCharsets.UTF_8));
        }
        return list;
    }

    // Helper writer methods

    public static TlvField u8(byte tag, byte value) {
        return new TlvField(tag, new byte[]{value});
    }

    public static TlvField u64(byte tag, long value) {
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(value);
        return new TlvField(tag, bytes);
    }

    public static TlvField i64(byte tag, long value) {
        return u64(tag, value);
    }

    public static TlvField bytes(byte tag, byte[] value) {
        if (value == null) {
            value = new byte[0];
        }
        return new TlvField(tag, value);
    }

    public static TlvField string(byte tag, String value) {
        if (value == null) {
            value = "";
        }
        return new TlvField(tag, value.getBytes(StandardCharsets.UTF_8));
    }

    public static TlvField stringList(byte tag, List<String> values) {
        if (values == null) {
            values = Collections.emptyList();
        }
        int totalLen = 0;
        List<byte[]> byteArrays = new ArrayList<>();
        for (String val : values) {
            byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
            byteArrays.add(bytes);
            totalLen += 2 + bytes.length;
        }

        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        for (byte[] bytes : byteArrays) {
            buf.putShort((short) bytes.length);
            buf.put(bytes);
        }

        return new TlvField(tag, buf.array());
    }
}
