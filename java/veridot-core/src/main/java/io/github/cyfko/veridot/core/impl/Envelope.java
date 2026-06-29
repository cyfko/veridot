package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The canonical binary envelope format for Protocol V4 (§3).
 */
public final class Envelope {

    public static final byte[] MAGIC = { 0x56, 0x44 }; // "VD"
    public static final byte PROTO_VERSION = 0x04;

    public final byte protoVersion;
    public final EntryType entryType;
    public final byte flags;
    public final Scope scope;
    public final String key;
    public final long version;
    public final long timestamp;
    public final String issuer;
    public final byte[] payload;
    public final byte sigAlg;
    public final byte[] signature;

    public Envelope(byte protoVersion, EntryType entryType, byte flags, Scope scope, String key,
                    long version, long timestamp, String issuer, byte[] payload, byte sigAlg, byte[] signature) {
        this.protoVersion = protoVersion;
        this.entryType = entryType;
        this.flags = flags;
        this.scope = scope;
        this.key = key != null ? key : "";
        this.version = version;
        this.timestamp = timestamp;
        this.issuer = issuer;
        this.payload = payload != null ? payload : new byte[0];
        this.sigAlg = sigAlg;
        this.signature = signature != null ? signature : new byte[0];
    }

    /**
     * Parses and structurally validates the envelope (§3.1, §3.2).
     * Does not verify the signature.
     */
    public static Envelope parse(byte[] raw) {
        if (raw == null) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope bytes cannot be null");
        }
        if (raw.length < 3) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope too short to read magic/protoVersion");
        }

        // 1. Magic and ProtoVersion validation (§3.2)
        if (raw[0] != MAGIC[0] || raw[1] != MAGIC[1] || raw[2] != PROTO_VERSION) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, 
                String.format("Invalid magic or protocol version. Expected 0x564404, got 0x%02X%02X%02X", raw[0], raw[1], raw[2]));
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.position(3); // skip magic + protoVersion

        if (buffer.remaining() < 2) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope truncated before entryType/flags");
        }

        // 2. EntryType validation
        byte entryTypeCode = buffer.get();
        EntryType entryType;
        try {
            entryType = EntryType.fromCode(entryTypeCode);
        } catch (VeridotException e) {
            throw new VeridotException(ErrorCode.UNREGISTERED_ENTRY_TYPE, null, "Unregistered entry type code: " + entryTypeCode);
        }

        // 3. Flags validation
        byte flags = buffer.get();
        if ((flags & 0xFE) != 0) {
            throw new VeridotException(ErrorCode.RESERVED_FLAG_SET, null, "Reserved flag bits 1-7 must be zero");
        }

        // 4. Scope validation
        if (buffer.remaining() < 2) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope truncated before scope length");
        }
        int scopeLen = Short.toUnsignedInt(buffer.getShort());
        if (scopeLen < 1 || scopeLen > 4096) {
            throw new VeridotException(ErrorCode.INVALID_IDENTIFIER_LENGTH, null, "Scope length must be 1-4096 bytes: " + scopeLen);
        }
        if (buffer.remaining() < scopeLen) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope truncated: scope string missing bytes");
        }
        byte[] scopeBytes = new byte[scopeLen];
        buffer.get(scopeBytes);
        Scope scope;
        try {
            scope = Scope.parse(new String(scopeBytes, StandardCharsets.UTF_8));
        } catch (VeridotException e) {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, "Invalid scope grammar: " + e.getMessage());
        }

        // 5. Key validation
        if (buffer.remaining() < 2) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope truncated before key length");
        }
        int keyLen = Short.toUnsignedInt(buffer.getShort());
        if (keyLen < 0 || keyLen > 4096) {
            throw new VeridotException(ErrorCode.INVALID_IDENTIFIER_LENGTH, null, "Key length must be 0-4096 bytes: " + keyLen);
        }
        if (buffer.remaining() < keyLen) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Raw envelope truncated: key string missing bytes");
        }
        byte[] keyBytes = new byte[keyLen];
        buffer.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        // Validate key character and singleton constraints
        String entryIdLoggable = "(" + scope.value() + ", " + entryType.name() + ", " + key + ")";
        boolean isSingleton = (entryType == EntryType.CONFIG || 
                               entryType == EntryType.FENCE || 
                               entryType == EntryType.SNAPSHOT_MARKER);
        if (isSingleton && !key.isEmpty()) {
            throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, entryIdLoggable, 
                "Key must be empty for singleton entry type: " + entryType.name());
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == 0x00 || (c >= 0x01 && c <= 0x1F)) {
                throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, entryIdLoggable, 
                    "Key contains invalid control character: " + String.format("0x%02X", (int) c));
            }
        }

        // 6. Version and Timestamp
        if (buffer.remaining() < 16) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated before version/timestamp");
        }
        long version = buffer.getLong();
        long timestamp = buffer.getLong();

        // 7. Issuer
        if (buffer.remaining() < 2) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated before issuer length");
        }
        int issuerLen = Short.toUnsignedInt(buffer.getShort());
        if (buffer.remaining() < issuerLen) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated: issuer string missing bytes");
        }
        byte[] issuerBytes = new byte[issuerLen];
        buffer.get(issuerBytes);
        String issuer = new String(issuerBytes, StandardCharsets.UTF_8);

        // 8. Payload
        if (buffer.remaining() < 4) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated before payload length");
        }
        long payloadLen = Integer.toUnsignedLong(buffer.getInt());
        if (payloadLen > 65536) {
            throw new VeridotException(ErrorCode.INVALID_PAYLOAD_LENGTH, entryIdLoggable, "Payload length must be 0-65536 bytes: " + payloadLen);
        }
        if (buffer.remaining() < (int) payloadLen) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated: payload missing bytes");
        }
        byte[] payload = new byte[(int) payloadLen];
        buffer.get(payload);

        // 9. SigAlg and Signature
        if (buffer.remaining() < 3) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated before sigAlg/sigLen");
        }
        byte sigAlg = buffer.get();
        if (sigAlg != 0x01 && sigAlg != 0x02 && sigAlg != 0x03) {
            throw new VeridotException(ErrorCode.SIGALG_KEY_MISMATCH, entryIdLoggable, "Unknown sigAlg value: " + sigAlg);
        }

        // Coherence check for COMPACT_SIG flag and sigAlg
        byte compactSigBit = (byte) (flags & 0x01);
        if (compactSigBit == 1 && sigAlg != 0x02) {
            throw new VeridotException(ErrorCode.RESERVED_FLAG_SET, entryIdLoggable, "COMPACT_SIG flag set but sigAlg is not Ed25519");
        }
        if (compactSigBit == 0 && sigAlg == 0x02) {
            throw new VeridotException(ErrorCode.RESERVED_FLAG_SET, entryIdLoggable, "COMPACT_SIG flag not set but sigAlg is Ed25519");
        }

        int sigLen = Short.toUnsignedInt(buffer.getShort());
        if (buffer.remaining() < sigLen) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Raw envelope truncated: signature missing bytes");
        }
        byte[] signature = new byte[sigLen];
        buffer.get(signature);

        if (buffer.hasRemaining()) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, entryIdLoggable, "Trailing bytes found after envelope signature");
        }

        return new Envelope(PROTO_VERSION, entryType, flags, scope, key, version, timestamp, issuer, payload, sigAlg, signature);
    }

    /**
     * Encodes a signed envelope to raw bytes.
     */
    public static byte[] encode(EnvelopeBuilder builder, byte[] signature) {
        byte[] scopeBytes = builder.scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = builder.key.getBytes(StandardCharsets.UTF_8);
        byte[] issuerBytes = builder.issuer.getBytes(StandardCharsets.UTF_8);
        byte[] payload = builder.payload != null ? builder.payload : new byte[0];
        byte[] sig = signature != null ? signature : new byte[0];

        int totalLen = 2 + 1 + 1 + 1 + 2 + scopeBytes.length + 2 + keyBytes.length + 8 + 8 + 2 + issuerBytes.length + 4 + payload.length + 1 + 2 + sig.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);

        buffer.put(MAGIC);
        buffer.put(PROTO_VERSION);
        buffer.put(builder.entryType.code);
        buffer.put(builder.flags);
        buffer.putShort((short) scopeBytes.length);
        buffer.put(scopeBytes);
        buffer.putShort((short) keyBytes.length);
        buffer.put(keyBytes);
        buffer.putLong(builder.version);
        buffer.putLong(builder.timestamp);
        buffer.putShort((short) issuerBytes.length);
        buffer.put(issuerBytes);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.put(builder.sigAlg);
        buffer.putShort((short) sig.length);
        buffer.put(sig);

        return buffer.array();
    }

    /**
     * Returns the canonical bytes to sign (§3.4): all bytes preceding sigAlg.
     */
    public byte[] canonicalSigningBytes() {
        byte[] scopeBytes = scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] issuerBytes = issuer.getBytes(StandardCharsets.UTF_8);

        int signLen = 2 + 1 + 1 + 1 + 2 + scopeBytes.length + 2 + keyBytes.length + 8 + 8 + 2 + issuerBytes.length + 4 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(signLen);

        buffer.put(MAGIC);
        buffer.put(protoVersion);
        buffer.put(entryType.code);
        buffer.put(flags);
        buffer.putShort((short) scopeBytes.length);
        buffer.put(scopeBytes);
        buffer.putShort((short) keyBytes.length);
        buffer.put(keyBytes);
        buffer.putLong(version);
        buffer.putLong(timestamp);
        buffer.putShort((short) issuerBytes.length);
        buffer.put(issuerBytes);
        buffer.putInt(payload.length);
        buffer.put(payload);

        return buffer.array();
    }

    public EntryId entryId() {
        return new EntryId(scope, entryType, key);
    }
}
