package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.Algorithm;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The canonical binary envelope format for Protocol V5 (§3).
 *
 * <h3>Wire layout (V5)</h3>
 * <pre>
 * Offset  Size  Field
 * 0–1     2B    Magic (0x56 0x44 = "VD")
 * 2       1B    ProtoVersion (0x05)
 * 3       1B    EntryType
 * 4–5     2B    Flags (u16 BE)          ← was 1B in V4
 * 6–7     2B    ScopeLen (u16 BE)       ← offset +1 vs V4
 * 8+      var   Scope (UTF-8)
 * ...     2B    KeyLen (u16 BE)
 * ...     var   Key (UTF-8)
 * ...     8B    Version (u64 BE)
 * ...     8B    Timestamp (i64 BE, epoch ms)
 * ...     2B    IssuerLen (u16 BE)
 * ...     var   Issuer (UTF-8)
 * ...     4B    PayloadLen (u32 BE)
 * ...     var   Payload
 * ...     1B    SigAlg
 * ...     2B    SigLen (u16 BE)
 * ...     var   Signature
 * </pre>
 */
public final class Envelope {

    public static final byte[] MAGIC = { 0x56, 0x44 }; // "VD"
    public static final byte PROTO_VERSION = 0x05;

    public final byte protoVersion;
    public final EntryType entryType;
    public final int flags;           // u16 (was byte in V4)
    public final Scope scope;
    public final String key;
    public final long version;
    public final long timestamp;
    public final String issuer;
    public final byte[] payload;
    public final Algorithm sigAlg;
    public final byte[] signature;

    public Envelope(byte protoVersion, EntryType entryType, int flags, Scope scope, String key,
                    long version, long timestamp, String issuer, byte[] payload, Algorithm sigAlg, byte[] signature) {
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
     * Parses and structurally validates a V5 envelope (§3.1, §3.2).
     * Does not verify the cryptographic signature.
     *
     * @param raw the raw envelope bytes
     * @return the parsed {@link Envelope}
     * @throws VeridotException if the envelope is malformed
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
                String.format("Invalid magic or protocol version. Expected 0x564405, got 0x%02X%02X%02X", raw[0], raw[1], raw[2]));
        }

        ByteBuffer buffer = ByteBuffer.wrap(raw);
        buffer.position(3); // skip magic + protoVersion

        if (buffer.remaining() < 3) { // entryType(1) + flags(2)
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

        // 3. Flags validation — u16 BE (2 bytes) (§3.3)
        int flags = Flags.decode(raw, buffer.position());
        buffer.position(buffer.position() + 2);
        Flags.validateReservedBits(flags);

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
        if (keyLen > 4096) {
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
        if (issuerLen < 1 || issuerLen > 4096) {
            throw new VeridotException(ErrorCode.INVALID_IDENTIFIER_LENGTH, entryIdLoggable, "Issuer string length must be 1-4096 bytes: " + issuerLen);
        }
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
        Algorithm sigAlg;
        try {
            sigAlg = Algorithm.fromCode(buffer.get());
        } catch (IllegalArgumentException e) {
            throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, entryIdLoggable, e.getMessage());
        }

        // Coherence check for COMPACT_SIG flag and sigAlg (§3.3)
        boolean compactSigSet = Flags.has(flags, Flags.COMPACT_SIG);
        if (compactSigSet && !sigAlg.isCompactSig()) {
            throw new VeridotException(ErrorCode.COMPACT_SIG_FLAG_MISMATCH, entryIdLoggable, 
                "COMPACT_SIG flag set but sigAlg " + sigAlg.name() + " does not produce compact signatures");
        }
        if (!compactSigSet && sigAlg.isCompactSig()) {
            throw new VeridotException(ErrorCode.COMPACT_SIG_FLAG_MISMATCH, entryIdLoggable, 
                "COMPACT_SIG flag not set but sigAlg " + sigAlg.name() + " requires it");
        }

        // Coherence check for HYBRID_SIG flag and sigAlg (§6.2)
        boolean hybridSigSet = Flags.has(flags, Flags.HYBRID_SIG);
        if (hybridSigSet && !sigAlg.isHybrid()) {
            throw new VeridotException(ErrorCode.COMPACT_SIG_FLAG_MISMATCH, entryIdLoggable, 
                "HYBRID_SIG flag set but sigAlg " + sigAlg.name() + " is not a hybrid algorithm");
        }
        if (!hybridSigSet && sigAlg.isHybrid()) {
            throw new VeridotException(ErrorCode.COMPACT_SIG_FLAG_MISMATCH, entryIdLoggable, 
                "HYBRID_SIG flag not set but sigAlg " + sigAlg.name() + " requires it");
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
     * Encodes a signed envelope to raw bytes (V5 wire format).
     *
     * @param builder the envelope builder with all fields set
     * @param signature the cryptographic signature
     * @return the encoded envelope bytes
     */
    public static byte[] encode(EnvelopeBuilder builder, byte[] signature) {
        byte[] scopeBytes = builder.scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = builder.key.getBytes(StandardCharsets.UTF_8);
        byte[] issuerBytes = builder.issuer.getBytes(StandardCharsets.UTF_8);
        byte[] payload = builder.payload != null ? builder.payload : new byte[0];
        byte[] sig = signature != null ? signature : new byte[0];

        // V5: flags is 2 bytes (u16) instead of 1 byte → total header +1 vs V4
        int totalLen = 2 + 1 + 1 + 2 + 2 + scopeBytes.length + 2 + keyBytes.length 
                     + 8 + 8 + 2 + issuerBytes.length + 4 + payload.length + 1 + 2 + sig.length;
        ByteBuffer buffer = ByteBuffer.allocate(totalLen);

        buffer.put(MAGIC);                              // 2B magic
        buffer.put(PROTO_VERSION);                      // 1B protoVersion
        buffer.put(builder.entryType.code);             // 1B entryType
        buffer.put(Flags.encode(builder.flags));        // 2B flags (u16 BE)
        buffer.putShort((short) scopeBytes.length);     // 2B scopeLen
        buffer.put(scopeBytes);
        buffer.putShort((short) keyBytes.length);       // 2B keyLen
        buffer.put(keyBytes);
        buffer.putLong(builder.version);                // 8B version
        buffer.putLong(builder.timestamp);              // 8B timestamp
        buffer.putShort((short) issuerBytes.length);    // 2B issuerLen
        buffer.put(issuerBytes);
        buffer.putInt(payload.length);                  // 4B payloadLen
        buffer.put(payload);
        buffer.put(builder.sigAlg.getCode());           // 1B sigAlg
        buffer.putShort((short) sig.length);            // 2B sigLen
        buffer.put(sig);

        return buffer.array();
    }

    /**
     * Returns the canonical bytes to sign (§3.4): all bytes preceding sigAlg.
     * This includes the 2-byte flags field (V5).
     */
    public byte[] canonicalSigningBytes() {
        byte[] scopeBytes = scope.value().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] issuerBytes = issuer.getBytes(StandardCharsets.UTF_8);

        // V5: flags = 2 bytes → signLen includes +1 vs V4
        int signLen = 2 + 1 + 1 + 2 + 2 + scopeBytes.length + 2 + keyBytes.length 
                    + 8 + 8 + 2 + issuerBytes.length + 4 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(signLen);

        buffer.put(MAGIC);
        buffer.put(protoVersion);
        buffer.put(entryType.code);
        buffer.put(Flags.encode(flags));                // 2B flags (u16 BE)
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
