package io.github.cyfko.veridot.core.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents the TLV payload of a SECURE_PAYLOAD entry (§12.2).
 */
public record SecurePayload(
    Byte encAlg,          // tag 0x01: optional encryption algorithm (0x01 = AES-256-GCM)
    byte[] nonce,        // tag 0x02: optional IV
    byte[] recipients,   // tag 0x03: optional serialized recipient blocks
    byte[] data,         // tag 0x04: required data (encrypted or plaintext)
    String payloadType   // tag 0x05: optional MIME type
) {
    public enum Tag {
        ENC_ALG((byte) 0x01),
        NONCE((byte) 0x02),
        RECIPIENTS((byte) 0x03),
        DATA((byte) 0x04),
        PAYLOAD_TYPE((byte) 0x05);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    public static SecurePayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);
        
        Byte encAlg = fields.containsKey(Tag.ENC_ALG.code) ? fields.get(Tag.ENC_ALG.code)[0] : null;
        byte[] nonce = TlvCodec.readBytes(fields, Tag.NONCE.code, false);
        byte[] recipients = TlvCodec.readBytes(fields, Tag.RECIPIENTS.code, false);
        byte[] data = TlvCodec.readBytes(fields, Tag.DATA.code, true);
        String payloadType = TlvCodec.readString(fields, Tag.PAYLOAD_TYPE.code, false);
        
        return new SecurePayload(encAlg, nonce, recipients, data, payloadType);
    }
    
    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        if (encAlg != null) {
            fields.add(TlvCodec.u8(Tag.ENC_ALG.code, encAlg));
        }
        if (nonce != null) {
            fields.add(TlvCodec.bytes(Tag.NONCE.code, nonce));
        }
        if (recipients != null) {
            fields.add(TlvCodec.bytes(Tag.RECIPIENTS.code, recipients));
        }
        fields.add(TlvCodec.bytes(Tag.DATA.code, data));
        if (payloadType != null) {
            fields.add(TlvCodec.string(Tag.PAYLOAD_TYPE.code, payloadType));
        }
        return TlvCodec.encode(fields);
    }
}
