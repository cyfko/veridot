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

    @Deprecated
    public KeyEpochPayload(byte algCode, long epochId, byte[] pk, long validFrom, long validUntil, String site, String token) {
        this(Algorithm.fromCode(algCode), epochId, pk, validFrom, validUntil, site, token);
    }

    public static KeyEpochPayload decode(byte[] tlvBytes) {
        Map<Byte, byte[]> fields = TlvCodec.parse(tlvBytes);

        Algorithm alg = Algorithm.fromCode(TlvCodec.readU8(fields, (byte) 0x01, true));
        long epochId = TlvCodec.readU64(fields, (byte) 0x02, true);
        byte[] pk = TlvCodec.readBytes(fields, (byte) 0x03, true);
        long validFrom = TlvCodec.readI64(fields, (byte) 0x04, true);
        long validUntil = TlvCodec.readI64(fields, (byte) 0x05, true);
        String site = TlvCodec.readString(fields, (byte) 0x06, false);
        String token = TlvCodec.readString(fields, (byte) 0x07, false);

        return new KeyEpochPayload(alg, epochId, pk, validFrom, validUntil, site, token);
    }

    public byte[] encode() {
        List<TlvCodec.TlvField> fields = new ArrayList<>();
        fields.add(TlvCodec.u8((byte) 0x01, alg.getCode()));
        fields.add(TlvCodec.u64((byte) 0x02, epochId));
        fields.add(TlvCodec.bytes((byte) 0x03, pk));
        fields.add(TlvCodec.i64((byte) 0x04, validFrom));
        fields.add(TlvCodec.i64((byte) 0x05, validUntil));
        if (site != null) {
            fields.add(TlvCodec.string((byte) 0x06, site));
        }
        if (token != null) {
            fields.add(TlvCodec.string((byte) 0x07, token));
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
