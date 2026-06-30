package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.Algorithm;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Payload of a KEY_EPOCH entry (§5.2).
 * Distributes public key material, algorithm, and temporal validity window.
 */
record KeyEpochPayload(
    Algorithm alg,        // tag 0x01: Ephemeral key algorithm
    long epochId,        // tag 0x02
    byte[] pk,           // tag 0x03: DER-encoded public key
    long validFrom,      // tag 0x04: milliseconds since epoch
    long validUntil,     // tag 0x05: milliseconds since epoch
    String site,         // tag 0x06: optional site membership
    String token         // tag 0x07: optional token for indirect mode
) {

    public enum Tag {
        ALG((byte) 0x01),
        EPOCH_ID((byte) 0x02),
        PK((byte) 0x03),
        VALID_FROM((byte) 0x04),
        VALID_UNTIL((byte) 0x05),
        SITE((byte) 0x06),
        TOKEN((byte) 0x07);

        public final byte code;
        Tag(byte code) { this.code = code; }
    }

    @Deprecated
    public KeyEpochPayload(byte algCode, long epochId, byte[] pk, long validFrom, long validUntil, String site, String token) {
        this(Algorithm.fromCode(algCode), epochId, pk, validFrom, validUntil, site, token);
    }

    public static KeyEpochPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        Algorithm alg = Algorithm.fromCode(TlvCodec.readU8(fields, Tag.ALG.code, true));
        long epochId = TlvCodec.readU64(fields, Tag.EPOCH_ID.code, true);
        byte[] pk = TlvCodec.readBytes(fields, Tag.PK.code, true);
        long validFrom = TlvCodec.readI64(fields, Tag.VALID_FROM.code, true);
        long validUntil = TlvCodec.readI64(fields, Tag.VALID_UNTIL.code, true);
        String site = TlvCodec.readString(fields, Tag.SITE.code, false);
        String token = TlvCodec.readString(fields, Tag.TOKEN.code, false);

        return new KeyEpochPayload(alg, epochId, pk, validFrom, validUntil, site, token);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u8(Tag.ALG.code, alg.getCode()));
        fields.add(TlvCodec.u64(Tag.EPOCH_ID.code, epochId));
        fields.add(TlvCodec.bytes(Tag.PK.code, pk));
        fields.add(TlvCodec.i64(Tag.VALID_FROM.code, validFrom));
        fields.add(TlvCodec.i64(Tag.VALID_UNTIL.code, validUntil));
        if (site != null) {
            fields.add(TlvCodec.string(Tag.SITE.code, site));
        }
        if (token != null) {
            fields.add(TlvCodec.string(Tag.TOKEN.code, token));
        }
        return TlvCodec.encode(fields);
    }

    /**
     * Reconstructs the ephemeral PublicKey from pk and alg.
     */
    public PublicKey publicKey() {
        try {
            X509EncodedKeySpec spec = new X509EncodedKeySpec(pk);
            KeyFactory kf = KeyFactory.getInstance(alg.getJcaKeyAlg());
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Failed to reconstruct public key from DER payload", e);
        }
    }
}
