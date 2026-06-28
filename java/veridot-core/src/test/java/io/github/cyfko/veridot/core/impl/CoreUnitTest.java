package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class CoreUnitTest {

    private KeyPair rsaKeyPair;
    private KeyPair ecKeyPair;
    private TrustRoot trustRoot;

    @BeforeEach
    public void setUp() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        rsaKeyPair = rsaGen.generateKeyPair();

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(256);
        ecKeyPair = ecGen.generateKeyPair();

        trustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                if ("root-rsa".equals(issuer)) {
                    return rsaKeyPair.getPublic();
                } else if ("root-ec".equals(issuer)) {
                    return ecKeyPair.getPublic();
                }
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown issuer: " + issuer);
            }

            @Override
            public boolean isRootIdentity(String issuer) {
                return "root-rsa".equals(issuer) || "root-ec".equals(issuer);
            }
        };
    }

    @Test
    public void testTlvCodec() {
        // Encode
        List<TlvCodec.TlvField> fields = List.of(
            TlvCodec.u8((byte) 0x01, (byte) 42),
            TlvCodec.u64((byte) 0x02, 123456789L),
            TlvCodec.string((byte) 0x03, "hello world"),
            TlvCodec.stringList((byte) 0x04, List.of("a", "b", "c"))
        );

        byte[] encoded = TlvCodec.encode(fields);
        assertNotNull(encoded);

        // Decode
        Map<Byte, byte[]> parsed = TlvCodec.parse(encoded);
        assertEquals(42, TlvCodec.readU8(parsed, (byte) 0x01, true));
        assertEquals(123456789L, TlvCodec.readU64(parsed, (byte) 0x02, true));
        assertEquals("hello world", TlvCodec.readString(parsed, (byte) 0x03, true));
        
        List<String> list = TlvCodec.readStringList(parsed, (byte) 0x04, true);
        assertEquals(List.of("a", "b", "c"), list);
    }

    @Test
    public void testTlvCodecDuplicateTag() {
        List<TlvCodec.TlvField> fields = List.of(
            TlvCodec.u8((byte) 0x01, (byte) 42),
            TlvCodec.u8((byte) 0x01, (byte) 43)
        );
        byte[] encoded = TlvCodec.encode(fields);
        assertThrows(VeridotException.class, () -> TlvCodec.parse(encoded));
    }

    @Test
    public void testScopeValidation() {
        // Valid
        Scope.parse("global");
        Scope.parse("group:user-123");
        Scope.parse("site:location_abc");

        // Invalid grammar
        assertThrows(VeridotException.class, () -> Scope.parse(""));
        assertThrows(VeridotException.class, () -> Scope.parse("invalid-scope"));
        assertThrows(VeridotException.class, () -> Scope.parse("group:"));
        assertThrows(VeridotException.class, () -> Scope.parse("group:user:123")); // colon in id
        
        // Invalid character
        assertThrows(VeridotException.class, () -> Scope.parse("group:user\0123"));
    }

    @Test
    public void testEntryId() {
        Scope s = Scope.parse("group:user-123");
        EntryId id = new EntryId(s, EntryType.KEY_EPOCH, "session-A");
        assertEquals(s, id.scope());
        assertEquals(EntryType.KEY_EPOCH, id.entryType());
        assertEquals("session-A", id.key());

        byte[] keyBytes = id.storageKey();
        assertNotNull(keyBytes);

        // Singleton key validation
        assertThrows(VeridotException.class, () -> new EntryId(s, EntryType.CONFIG, "non-empty-key"));
    }

    @Test
    public void testEnvelopeParseAndEncode() throws Exception {
        Scope scope = Scope.parse("group:user-123");
        byte[] payload = new byte[]{1, 2, 3, 4};

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(scope)
            .key("session-A")
            .version(10L)
            .timestamp(System.currentTimeMillis())
            .issuer("root-rsa")
            .payload(payload)
            .sigAlg((byte) 0x01); // RSA-SHA256

        Envelope tempEnv = new Envelope(
            Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "session-A",
            10L, builder.timestamp, "root-rsa", payload, (byte) 0x01, null
        );

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(tempEnv.canonicalSigningBytes());
        byte[] signature = sig.sign();

        byte[] encoded = Envelope.encode(builder, signature);
        assertNotNull(encoded);

        // Parse and check values
        Envelope parsed = Envelope.parse(encoded);
        assertEquals(Envelope.PROTO_VERSION, parsed.protoVersion);
        assertEquals(EntryType.KEY_EPOCH, parsed.entryType);
        assertEquals(scope, parsed.scope);
        assertEquals("session-A", parsed.key);
        assertEquals(10L, parsed.version);
        assertEquals("root-rsa", parsed.issuer);
        assertArrayEquals(payload, parsed.payload);
        assertEquals((byte) 0x01, parsed.sigAlg);
        assertArrayEquals(signature, parsed.signature);

        // Verify with signature verifier
        SignatureVerifier verifier = new SignatureVerifier();
        verifier.verify(parsed, trustRoot);
    }

    @Test
    public void testVersionWatermark() {
        VersionWatermark wm = new VersionWatermark();
        EntryId id = new EntryId(Scope.parse("group:user"), EntryType.KEY_EPOCH, "key");

        assertEquals(0L, wm.current(id));

        wm.accept(id, 1L);
        assertEquals(1L, wm.current(id));

        wm.accept(id, 5L);
        assertEquals(5L, wm.current(id));

        // Stale version rejection
        assertThrows(VeridotException.class, () -> wm.accept(id, 5L));
        assertThrows(VeridotException.class, () -> wm.accept(id, 4L));
        assertThrows(VeridotException.class, () -> wm.accept(id, 0L));

        // Snapshot and restore
        byte[] snap = wm.snapshot();
        assertNotNull(snap);

        VersionWatermark wm2 = new VersionWatermark();
        wm2.restore(snap);
        assertEquals(5L, wm2.current(id));
    }

    @Test
    public void testRsaKeySizeRestriction() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(1024);
        KeyPair shortKey = rsaGen.generateKeyPair();
        
        TrustRoot badTrust = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                return shortKey.getPublic();
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return true;
            }
        };

        Scope scope = Scope.parse("group:user-123");
        Envelope envelope = new Envelope(
            Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "session-A",
            10L, System.currentTimeMillis(), "root-rsa", new byte[0], (byte) 0x01, new byte[0]
        );
        
        SignatureVerifier verifier = new SignatureVerifier();
        VeridotException ex = assertThrows(VeridotException.class, () -> verifier.verify(envelope, badTrust));
        assertEquals(ErrorCode.SIGALG_KEY_MISMATCH, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("RSA key size must be at least"));
    }

    @Test
    public void testFileWatermarkStore() throws java.io.IOException {
        java.io.File tempFile = java.io.File.createTempFile("veridot_watermarks_", ".dat");
        tempFile.deleteOnExit();

        FileWatermarkStore store = new FileWatermarkStore(tempFile);
        assertNull(store.load());

        byte[] snapshot = "test_watermarks".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.save(snapshot);

        byte[] loaded = store.load();
        assertArrayEquals(snapshot, loaded);
    }

    @Test
    public void testVeridotMetrics() throws Exception {
        io.github.cyfko.veridot.core.VeridotMetrics.reset();
        assertEquals(0, io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.sum());
        assertEquals(0, io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.sum());

        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "root-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x01
        )) {
            // Act: sign a valid token (should increment accepted)
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Act: verify valid token
            sv.verify(token, s -> s);
            
            // Accepted should be at least 1 (verification increments verifyKeyEpoch)
            assertTrue(io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.sum() > 0);

            // Act: try verifying an invalid token (should increment rejected)
            assertThrows(Exception.class, () -> sv.verify("invalid-token", s -> s));
            assertTrue(io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.sum() > 0);
        }
    }

    @Test
    public void testReconciliationStalenessCheck() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "root-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x01,
                -1,
                EvictionPolicy.FIFO,
                1, // 1 minute interval override
                null
        )) {
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Verify works initially
            assertNotNull(sv.verify(token, s -> s));

            // Force reconciliation to start for the scope, which sets reconciledScopes
            sv.reconciliationManager.reconcile(
                Scope.group("group1"), inMemoryBroker, sv.watermarkForTest(),
                new SignatureVerifier(), trustRoot, new EntryPublisher(), "root-rsa",
                rsaKeyPair.getPrivate(), (byte) 0x01, null
            );
            
            // Trigger reconciliation periodic start so it registers in reconciledScopes
            sv.hasActiveToken(token); 

            // Mock the last reconciled time to be very old (exceeding staleness limit of 60 minutes)
            sv.reconciliationManager.setLastReconciledForTest(Scope.group("group1"), System.currentTimeMillis() - 70 * 60 * 1000L);

            // Verifying should now throw RECONCILIATION_STALE
            VeridotException ex = assertThrows(VeridotException.class, () -> sv.verify(token, s -> s));
            assertEquals(ErrorCode.RECONCILIATION_STALE, ex.getErrorCode());
        }
    }
}
