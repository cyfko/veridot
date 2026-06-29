package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.Algorithm;
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
        assertEquals(Algorithm.RSA_SHA256, parsed.sigAlg);
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
            // Corrupt the key epoch in broker to increment rejected during next verification
            EntryId keyEpochId = new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "sessionA");
            inMemoryBroker.put(keyEpochId.storageKey(), new byte[] { 0x00, 0x00, 0x00 });
            
            assertThrows(Exception.class, () -> sv.verify(token, s -> s));
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
                rsaKeyPair.getPrivate(), (byte) 0x01, sv.capabilityVerifier, null
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

    @Test
    public void testRsaPssSignatureVerification() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                if ("issuer-pss".equals(issuer)) {
                    return kp.getPublic();
                }
                return null;
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return false;
            }
        };

        Envelope env = new Envelope(
                Envelope.PROTO_VERSION,
                EntryType.KEY_EPOCH,
                (byte) 0x00, // flags
                Scope.group("g1"),
                "k1",
                1L,
                System.currentTimeMillis(),
                "issuer-pss",
                new byte[] { 0x01, 0x02 },
                (byte) 0x03, // RSA-PSS
                null
        );

        // Sign the envelope
        Signature sig = Signature.getInstance("RSASSA-PSS");
        sig.setParameter(new java.security.spec.PSSParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
        ));
        sig.initSign(kp.getPrivate());
        sig.update(env.canonicalSigningBytes());
        byte[] signature = sig.sign();

        Envelope signedEnv = new Envelope(
                env.protoVersion,
                env.entryType,
                env.flags,
                env.scope,
                env.key,
                env.version,
                env.timestamp,
                env.issuer,
                env.payload,
                env.sigAlg,
                signature
        );

        SignatureVerifier verifier = new SignatureVerifier();
        // Should verify without exception
        verifier.verify(signedEnv, trustRoot);
    }

    @Test
    public void testRsaPssEphemeralSignature() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                if ("issuer-rsa".equals(issuer)) {
                    return rsaKeyPair.getPublic();
                }
                return null;
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return "issuer-rsa".equals(issuer);
            }
        };

        // Create SignerVerifier with RSA-PSS (0x03) for ephemeral algorithm
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x03, // Ephemeral alg = RSA-PSS
                -1,
                EvictionPolicy.FIFO,
                60,
                null
        )) {
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Verify should succeed using PSS verification internally
            io.github.cyfko.veridot.core.VerifiedData<String> verified = sv.verify(token, s -> s);
            assertEquals("data", verified.data());
        }
    }

    @Test
    public void testEnvelopeParserFuzzing() throws Exception {
        byte[] validBytes = new byte[] {
            0x56, 0x44, // magic
            0x04, // version
            0x01, // entryType
            0x00, // flags
            0x00, 0x08, // scopeLen
            'g', 'r', 'o', 'u', 'p', ':', 'g', '1',
            0x00, 0x02, // keyLen
            'k', '1',
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, // version
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, // timestamp
            0x00, 0x03, // issuerLen
            'i', 's', 's',
            0x00, 0x00, 0x00, 0x02, // payloadLen
            0x01, 0x02, // payload
            0x01, // sigAlg
            0x00, 0x04, // sigLen
            0x01, 0x02, 0x03, 0x04 // signature
        };

        // Truncation fuzzing
        for (int i = 0; i < validBytes.length; i++) {
            final int len = i;
            byte[] truncated = new byte[len];
            System.arraycopy(validBytes, 0, truncated, 0, len);
            assertThrows(Exception.class, () -> Envelope.parse(truncated));
        }

        // Random bit flipping fuzzing
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 100; i++) {
            byte[] mutated = validBytes.clone();
            int index = rnd.nextInt(mutated.length);
            mutated[index] ^= (byte) (1 << rnd.nextInt(8));
            try {
                Envelope.parse(mutated);
            } catch (Exception expected) {
                // Should only throw controlled exceptions
                assertTrue(expected instanceof VeridotException || expected instanceof IllegalArgumentException);
            }
        }
    }

    @Test
    public void testFencedCapacityConcurrence() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                if ("issuer-rsa".equals(issuer)) {
                    return rsaKeyPair.getPublic();
                }
                return null;
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return "issuer-rsa".equals(issuer);
            }
        };

        // Create SignerVerifier with low capacity to trigger evictions
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x01,
                3, // max sessions = 3
                EvictionPolicy.FIFO,
                60,
                null
        )) {
            // Concurrent session insertions
            int numThreads = 5;
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            
            for (int i = 0; i < numThreads; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        String token = sv.sign("data-" + idx, BasicConfigurer.builder().groupId("group1").sequenceId("session-" + idx).validity(600).build());
                        assertNotNull(sv.verify(token, s -> s));
                    } catch (Exception e) {
                        // Concurrent eviction or fencing conflicts are expected to throw controlled VeridotException, but not unhandled errors
                        assertTrue(e instanceof VeridotException);
                    }
                }));
            }

            for (java.util.concurrent.Future<?> f : futures) {
                f.get();
            }
            executor.shutdown();
        }
    }

    @Test
    public void testReconciliationStalenessApi() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                if ("issuer-rsa".equals(issuer)) {
                    return rsaKeyPair.getPublic();
                }
                return null;
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return "issuer-rsa".equals(issuer);
            }
        };

        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x01
        )) {
            // Before verification / reconciliation, staleness should be -1
            assertEquals(-1L, sv.getReconciliationStalenessMs("group:group1"));

            // Act: sign a valid token (should trigger/ensure reconciliation start, but it might not run snapshot immediately)
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Reconcile manually or test lastReconciled mapping
            sv.reconciliationManager.setLastReconciledForTest(Scope.group("group1"), System.currentTimeMillis() - 5000);
            
            long staleness = sv.getReconciliationStalenessMs("group:group1");
            assertTrue(staleness >= 5000 && staleness < 10000);
        }
    }

    @Test
    public void testClockDriftWarningLogging() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        
        java.util.logging.Logger verifierLogger = java.util.logging.Logger.getLogger(EntryVerifier.class.getName());
        java.util.List<java.util.logging.LogRecord> logRecords = new java.util.ArrayList<>();
        java.util.logging.Handler customHandler = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logRecords.add(record);
            }
            @Override
            public void flush() {}
            @Override
            public void close() throws SecurityException {}
        };
        verifierLogger.addHandler(customHandler);

        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            
            TrustRoot tr = new PublicKeyTrustRoot() {
                @Override
                public PublicKey resolve(String issuer) {
                    if ("issuer-drift".equals(issuer)) return kp.getPublic();
                    return null;
                }
                @Override
                public boolean isRootIdentity(String issuer) {
                    return "issuer-drift".equals(issuer);
                }
            };
            
            byte[] payloadBytes = new KeyEpochPayload(
                (byte) 0x01, // alg
                1L, // epochId
                kp.getPublic().getEncoded(), // pk
                System.currentTimeMillis() - 100000L, // validFrom
                System.currentTimeMillis() + 100000L, // validUntil
                "site1", // site
                "token1" // token
            ).encode();
            
            long driftTime = System.currentTimeMillis() - (Config.MAX_CLOCK_DRIFT_SECONDS * 1000L / 2) - 10000L;
            
            EnvelopeBuilder envBuilder = new EnvelopeBuilder()
                .entryType(EntryType.KEY_EPOCH)
                .flags((byte) 0x00)
                .scope(Scope.group("group1"))
                .key("session-drift")
                .version(1L)
                .timestamp(driftTime)
                .issuer("issuer-drift")
                .payload(payloadBytes)
                .sigAlg((byte) 0x01);

            Envelope env = new Envelope(
                Envelope.PROTO_VERSION,
                EntryType.KEY_EPOCH,
                (byte) 0x00,
                Scope.group("group1"),
                "session-drift",
                1L,
                driftTime,
                "issuer-drift",
                payloadBytes,
                (byte) 0x01,
                null
            );
            
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(kp.getPrivate());
            sig.update(env.canonicalSigningBytes());
            byte[] signature = sig.sign();
            
            byte[] encodedEnv = Envelope.encode(envBuilder, signature);
            EntryId entryId = new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "session-drift");
            inMemoryBroker.put(entryId.storageKey(), encodedEnv).join();
            
            byte[] livePayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
            
            EnvelopeBuilder liveBuilder = new EnvelopeBuilder()
                .entryType(EntryType.LIVENESS)
                .flags((byte) 0x00)
                .scope(Scope.group("group1"))
                .key("session-drift")
                .version(1L)
                .timestamp(System.currentTimeMillis())
                .issuer("issuer-drift")
                .payload(livePayloadBytes)
                .sigAlg((byte) 0x01);

            Envelope liveEnv = new Envelope(
                Envelope.PROTO_VERSION,
                EntryType.LIVENESS,
                (byte) 0x00,
                Scope.group("group1"),
                "session-drift",
                1L,
                System.currentTimeMillis(),
                "issuer-drift",
                livePayloadBytes,
                (byte) 0x01,
                null
            );
            sig.initSign(kp.getPrivate());
            sig.update(liveEnv.canonicalSigningBytes());
            byte[] liveSig = sig.sign();
            
            byte[] encodedLiveEnv = Envelope.encode(liveBuilder, liveSig);
            EntryId liveEntryId = new EntryId(Scope.group("group1"), EntryType.LIVENESS, "session-drift");
            inMemoryBroker.put(liveEntryId.storageKey(), encodedLiveEnv).join();
            
            try (GenericSignerVerifier sv = new GenericSignerVerifier(
                    inMemoryBroker,
                    tr,
                    "issuer-drift",
                    kp.getPrivate(),
                    (byte) 0x01
            )) {
                String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"RS256\"}".getBytes());
                String jwtPayload = Base64.getUrlEncoder().encodeToString("{\"sub\":\"3:group1:session-drift\",\"data\":\"my-data\"}".getBytes());
                sig.initSign(kp.getPrivate());
                sig.update((header + "." + jwtPayload).getBytes());
                String jwtSign = Base64.getUrlEncoder().encodeToString(sig.sign());
                String jwtTokenStr = header + "." + jwtPayload + "." + jwtSign;
                
                sv.verify(jwtTokenStr, s -> s);
            }
            
            boolean hasWarning = logRecords.stream().anyMatch(r -> 
                r.getLevel() == java.util.logging.Level.WARNING && r.getMessage().contains("Clock drift detected")
            );
            assertTrue(hasWarning, "Warning message for clock drift should be logged");
            
        } finally {
            verifierLogger.removeHandler(customHandler);
        }
    }

    @Test
    public void testEmptyIssuerRejection() throws Exception {
        byte[] payloadBytes = new KeyEpochPayload((byte) 0x01, 1L, rsaKeyPair.getPublic().getEncoded(), System.currentTimeMillis(), System.currentTimeMillis() + 100000L, "site1", null).encode();
        EnvelopeBuilder envBuilder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("session1")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("") // Empty issuer
            .payload(payloadBytes)
            .sigAlg((byte) 0x01);

        Envelope env = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.KEY_EPOCH,
            (byte) 0x00,
            Scope.group("group1"),
            "session1",
            1L,
            System.currentTimeMillis(),
            "", // Empty issuer
            payloadBytes,
            (byte) 0x01,
            null
        );

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(env.canonicalSigningBytes());
        byte[] signature = sig.sign();

        byte[] encodedEnv = Envelope.encode(envBuilder, signature);
        
        // Decoding should fail due to empty issuer string
        VeridotException ex = assertThrows(VeridotException.class, () -> {
            Envelope.parse(encodedEnv);
        });
        assertEquals(ErrorCode.INVALID_IDENTIFIER_LENGTH, ex.getErrorCode());
    }

    @Test
    public void testTooLongIssuerRejection() throws Exception {
        byte[] payloadBytes = new KeyEpochPayload((byte) 0x01, 1L, rsaKeyPair.getPublic().getEncoded(), System.currentTimeMillis(), System.currentTimeMillis() + 100000L, "site1", null).encode();
        
        char[] arr = new char[4097];
        Arrays.fill(arr, 'a');
        String longIssuer = new String(arr);

        EnvelopeBuilder envBuilder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("session1")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer(longIssuer) // Too long issuer
            .payload(payloadBytes)
            .sigAlg((byte) 0x01);

        Envelope env = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.KEY_EPOCH,
            (byte) 0x00,
            Scope.group("group1"),
            "session1",
            1L,
            System.currentTimeMillis(),
            longIssuer, // Too long issuer
            payloadBytes,
            (byte) 0x01,
            null
        );

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(env.canonicalSigningBytes());
        byte[] signature = sig.sign();

        byte[] encodedEnv = Envelope.encode(envBuilder, signature);
        
        // Decoding should fail due to too long issuer string
        VeridotException ex = assertThrows(VeridotException.class, () -> {
            Envelope.parse(encodedEnv);
        });
        assertEquals(ErrorCode.INVALID_IDENTIFIER_LENGTH, ex.getErrorCode());
    }

    @Test
    public void testWatermarkHmacIntegrity() throws Exception {
        java.io.File tempFile = java.io.File.createTempFile("veridot-watermarks", ".json");
        tempFile.deleteOnExit();

        byte[] key = "test-hmac-key-1234567890123456789".getBytes();
        FileWatermarkStore store = new FileWatermarkStore(tempFile, key);

        byte[] snapshot = "{\"group1:KEY_EPOCH:session1\": 10}".getBytes();
        store.save(snapshot);

        // Load must succeed initially
        byte[] loaded = store.load();
        assertNotNull(loaded);
        assertArrayEquals(snapshot, loaded);

        // Modify the file content (tampering)
        byte[] rawBytes = java.nio.file.Files.readAllBytes(tempFile.toPath());
        if (rawBytes.length > 35) {
            rawBytes[35] ^= 0x01; // flip a bit in payload or HMAC
        }
        java.nio.file.Files.write(tempFile.toPath(), rawBytes);

        // Load must fail (return null)
        byte[] loadedTampered = store.load();
        assertNull(loadedTampered);
    }

    @Test
    public void testJwtHeaderAlgCoherence() throws Exception {
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        byte[] payloadBytes = new KeyEpochPayload((byte) 0x01, 1L, rsaKeyPair.getPublic().getEncoded(), System.currentTimeMillis(), System.currentTimeMillis() + 100000L, "site1", null).encode();
        EnvelopeBuilder envBuilder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("sessionA")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("root-rsa")
            .payload(payloadBytes)
            .sigAlg((byte) 0x01);

        Envelope env = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.KEY_EPOCH,
            (byte) 0x00,
            Scope.group("group1"),
            "sessionA",
            1L,
            System.currentTimeMillis(),
            "root-rsa",
            payloadBytes,
            (byte) 0x01,
            null
        );

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(env.canonicalSigningBytes());
        byte[] signature = sig.sign();

        byte[] encodedEnv = Envelope.encode(envBuilder, signature);
        inMemoryBroker.put(new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "sessionA").storageKey(), encodedEnv).join();

        byte[] livePayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
        EnvelopeBuilder liveBuilder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("sessionA")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("root-rsa")
            .payload(livePayloadBytes)
            .sigAlg((byte) 0x01);

        Envelope liveEnv = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.LIVENESS,
            (byte) 0x00,
            Scope.group("group1"),
            "sessionA",
            1L,
            System.currentTimeMillis(),
            "root-rsa",
            livePayloadBytes,
            (byte) 0x01,
            null
        );
        sig.initSign(rsaKeyPair.getPrivate());
        sig.update(liveEnv.canonicalSigningBytes());
        byte[] liveSig = sig.sign();
        inMemoryBroker.put(new EntryId(Scope.group("group1"), EntryType.LIVENESS, "sessionA").storageKey(), Envelope.encode(liveBuilder, liveSig)).join();

        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                trustRoot,
                "root-rsa",
                rsaKeyPair.getPrivate(),
                (byte) 0x01
        )) {
            // Build JWT with wrong header alg: ES256 instead of expected RS256
            String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"ES256\"}".getBytes());
            String jwtPayload = Base64.getUrlEncoder().encodeToString("{\"sub\":\"3:group1:sessionA\",\"data\":\"my-data\"}".getBytes());
            sig.initSign(rsaKeyPair.getPrivate());
            sig.update((header + "." + jwtPayload).getBytes());
            String jwtSign = Base64.getUrlEncoder().encodeToString(sig.sign());
            String jwtTokenStr = header + "." + jwtPayload + "." + jwtSign;

            // Verification should fail because of algorithm mismatch
            assertThrows(Exception.class, () -> {
                sv.verify(jwtTokenStr, s -> s);
            });
        }
    }

    @Test
    void testEd25519DefaultAndUnification() throws Exception {
        // 1. KeyRotationService should prefer Ed25519 by default
        try (KeyRotationService krs = new KeyRotationService()) {
            assertEquals(Algorithm.ED25519, krs.snapshot().alg());
            PublicKey pub = krs.getPublicKey();
            assertEquals("Ed25519", pub.getAlgorithm());
        }

        // 2. Algorithm.fromCode mapping
        assertEquals(Algorithm.RSA_SHA256, Algorithm.fromCode((byte) 0x01));
        assertEquals(Algorithm.ECDSA_SHA256, Algorithm.fromCode((byte) 0x02));
        assertEquals(Algorithm.RSA_PSS, Algorithm.fromCode((byte) 0x03));
        assertEquals(Algorithm.ED25519, Algorithm.fromCode((byte) 0x04));
    }
}
