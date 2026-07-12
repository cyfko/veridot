package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Envelope} — V5 §3 binary envelope format.
 *
 * <p>Focuses on:
 * <ul>
 *   <li>Encode/parse round-trip with V5 u16 flags</li>
 *   <li>Proto version 0x05 validation</li>
 *   <li>Rejection of unknown entry types</li>
 *   <li>Flags reserved-bit validation</li>
 * </ul>
 */
class EnvelopeV5Test {

    // ── Encode / Parse round-trip ────────────────────────────────────

    @Test
    void encodeAndParse_roundTripPreservesAllFields() {
        EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.LIVENESS)
                .flags(Flags.COMPACT_SIG | Flags.INSTANCE_SCOPED)
                .scope(Scope.global())
                .key("liveness-key-1")
                .version(42L)
                .timestamp(System.currentTimeMillis())
                .issuer("test-svc@abcdefghijklmnopqrstuvwxyz012345")
                .payload(new byte[]{0x01, 0x02, 0x03})
                .sigAlg(Algorithm.ED25519);

        byte[] fakeSig = new byte[64]; // Ed25519 signature placeholder
        byte[] encoded = Envelope.encode(builder, fakeSig);
        Envelope parsed = Envelope.parse(encoded);

        assertEquals(Envelope.PROTO_VERSION, parsed.protoVersion);
        assertEquals(EntryType.LIVENESS, parsed.entryType);
        assertEquals(Flags.COMPACT_SIG | Flags.INSTANCE_SCOPED, parsed.flags);
        assertEquals("global", parsed.scope.value());
        assertEquals("liveness-key-1", parsed.key);
        assertEquals(42L, parsed.version);
        assertEquals(builder.timestamp, parsed.timestamp);
        assertEquals("test-svc@abcdefghijklmnopqrstuvwxyz012345", parsed.issuer);
        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, parsed.payload);
        assertEquals(Algorithm.ED25519, parsed.sigAlg);
        assertArrayEquals(fakeSig, parsed.signature);
    }

    @Test
    void encodeAndParse_roundTripWithGroupScope() {
        EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.CAPABILITY)
                .flags(0)
                .scope(Scope.group("team-alpha"))
                .key("cap-1")
                .version(1L)
                .timestamp(1000L)
                .issuer("issuer-id")
                .payload(new byte[0])
                .sigAlg(Algorithm.RSA_SHA256);

        byte[] sig = new byte[]{0x0A, 0x0B};
        byte[] encoded = Envelope.encode(builder, sig);
        Envelope parsed = Envelope.parse(encoded);

        assertTrue(parsed.scope.isGroup());
        assertEquals("team-alpha", parsed.scope.groupId());
        assertEquals("cap-1", parsed.key);
    }

    @Test
    void encodeAndParse_roundTripWithSignedDataEntryType() {
        EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.SIGNED_DATA)
                .flags(Flags.COMPACT_SIG | Flags.INSTANCE_SCOPED)
                .scope(Scope.global())
                .key("msg-001")
                .version(100L)
                .timestamp(2000L)
                .issuer("svc@hash0123456789012345678901234")
                .payload("hello".getBytes(StandardCharsets.UTF_8))
                .sigAlg(Algorithm.ED25519);

        byte[] sig = new byte[64];
        byte[] encoded = Envelope.encode(builder, sig);
        Envelope parsed = Envelope.parse(encoded);

        assertEquals(EntryType.SIGNED_DATA, parsed.entryType);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), parsed.payload);
    }

    @Test
    void encodeAndParse_roundTripWithHybridFlags() {
        EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.LIVENESS)
                .flags(Flags.COMPACT_SIG | Flags.HYBRID_SIG | Flags.INSTANCE_SCOPED)
                .scope(Scope.global())
                .key("hk-1")
                .version(1L)
                .timestamp(1000L)
                .issuer("hybrid-issuer")
                .payload(new byte[0])
                .sigAlg(Algorithm.ED25519_MLDSA65);

        byte[] sig = new byte[32];
        byte[] encoded = Envelope.encode(builder, sig);
        Envelope parsed = Envelope.parse(encoded);

        assertTrue(Flags.has(parsed.flags, Flags.HYBRID_SIG));
        assertTrue(Flags.has(parsed.flags, Flags.COMPACT_SIG));
        assertTrue(Flags.has(parsed.flags, Flags.INSTANCE_SCOPED));
        assertEquals(Algorithm.ED25519_MLDSA65, parsed.sigAlg);
    }

    // ── Proto version validation ─────────────────────────────────────

    @Test
    void parse_rejectsWrongProtoVersion() {
        // Build a valid envelope then corrupt the proto version byte
        EnvelopeBuilder builder = minimalBuilder();
        byte[] encoded = Envelope.encode(builder, new byte[64]);
        encoded[2] = 0x04; // set to V4 instead of V5

        VeridotException ex = assertThrows(VeridotException.class,
                () -> Envelope.parse(encoded));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_rejectsWrongMagicBytes() {
        byte[] garbage = new byte[]{0x00, 0x00, 0x05, 0x02};
        VeridotException ex = assertThrows(VeridotException.class,
                () -> Envelope.parse(garbage));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    // ── Unknown entry types ──────────────────────────────────────────

    @Test
    void parse_rejectsReservedEntryType0x01() {
        EnvelopeBuilder builder = minimalBuilder();
        byte[] encoded = Envelope.encode(builder, new byte[64]);
        // Entry type is at offset 3 (magic=2 + protoVersion=1)
        encoded[3] = 0x01; // reserved KEY_EPOCH code
        VeridotException ex = assertThrows(VeridotException.class,
                () -> Envelope.parse(encoded));
        assertEquals(ErrorCode.UNREGISTERED_ENTRY_TYPE, ex.getErrorCode());
    }

    @Test
    void parse_rejectsUnknownEntryType0x0B() {
        EnvelopeBuilder builder = minimalBuilder();
        byte[] encoded = Envelope.encode(builder, new byte[64]);
        encoded[3] = 0x0B; // out-of-range
        VeridotException ex = assertThrows(VeridotException.class,
                () -> Envelope.parse(encoded));
        assertEquals(ErrorCode.UNREGISTERED_ENTRY_TYPE, ex.getErrorCode());
    }

    // ── Flags validation ─────────────────────────────────────────────

    @Test
    void parse_rejectsReservedFlagBits() {
        EnvelopeBuilder builder = minimalBuilder();
        byte[] encoded = Envelope.encode(builder, new byte[64]);
        // Flags are at offset 4-5 (u16 BE). Set bit 4 (reserved)
        encoded[4] = 0x00;
        encoded[5] = (byte) (encoded[5] | 0x10); // set bit 4
        VeridotException ex = assertThrows(VeridotException.class,
                () -> Envelope.parse(encoded));
        assertEquals(ErrorCode.RESERVED_FLAG_SET, ex.getErrorCode());
    }

    // ── Flags u16 encoding ───────────────────────────────────────────

    @Test
    void flags_encodeDecodesU16BigEndian() {
        int flags = Flags.COMPACT_SIG | Flags.INSTANCE_SCOPED; // 0x0009
        byte[] encoded = Flags.encode(flags);
        assertEquals(2, encoded.length);
        assertEquals(0x00, encoded[0] & 0xFF);
        assertEquals(0x09, encoded[1] & 0xFF);
        assertEquals(flags, Flags.decode(encoded, 0));
    }

    @Test
    void flags_validateReservedBits_throwsOnBit4() {
        assertThrows(VeridotException.class,
                () -> Flags.validateReservedBits(0x0010));
    }

    @Test
    void flags_validateReservedBits_passesOnDefinedBitsOnly() {
        assertDoesNotThrow(() -> Flags.validateReservedBits(0x000F)); // all 4 defined bits set
        assertDoesNotThrow(() -> Flags.validateReservedBits(0x0000)); // no bits set
    }

    // ── Truncated / null input ───────────────────────────────────────

    @Test
    void parse_rejectsNull() {
        assertThrows(VeridotException.class, () -> Envelope.parse(null));
    }

    @Test
    void parse_rejectsTooShort() {
        assertThrows(VeridotException.class, () -> Envelope.parse(new byte[]{0x56, 0x44}));
    }

    // ── canonicalSigningBytes ────────────────────────────────────────

    @Test
    void canonicalSigningBytes_excludesSignature() {
        EnvelopeBuilder builder = minimalBuilder();
        byte[] sig = new byte[]{1, 2, 3, 4};
        byte[] encoded = Envelope.encode(builder, sig);
        Envelope parsed = Envelope.parse(encoded);

        byte[] canonical = parsed.canonicalSigningBytes();
        // canonical should not include the sigAlg(1) + sigLen(2) + sig(N) at the end
        // total encoded = canonical + 1 + 2 + sigLen
        assertEquals(encoded.length - 1 - 2 - sig.length, canonical.length);
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Creates a minimal valid EnvelopeBuilder for use in corruption tests.
     * Uses LIVENESS + ED25519 with COMPACT_SIG to pass coherence checks.
     */
    private static EnvelopeBuilder minimalBuilder() {
        return new EnvelopeBuilder()
                .entryType(EntryType.LIVENESS)
                .flags(Flags.COMPACT_SIG)
                .scope(Scope.global())
                .key("k1")
                .version(1L)
                .timestamp(1000L)
                .issuer("test-issuer")
                .payload(new byte[0])
                .sigAlg(Algorithm.ED25519);
    }
}
