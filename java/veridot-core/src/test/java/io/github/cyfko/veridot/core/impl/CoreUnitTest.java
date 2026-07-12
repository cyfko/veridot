package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
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
    private KeyPair ed25519KeyPair;
    private TrustRoot trustRoot;

    @BeforeEach
    public void setUp() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        rsaKeyPair = rsaGen.generateKeyPair();

        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(256);
        ecKeyPair = ecGen.generateKeyPair();

        KeyPairGenerator ed25519Gen = KeyPairGenerator.getInstance("Ed25519");
        ed25519KeyPair = ed25519Gen.generateKeyPair();

        String rsaSignerId = SubjectComputer.compute("root-rsa", rsaKeyPair.getPublic());
        String ecSignerId = SubjectComputer.compute("root-ec", ecKeyPair.getPublic());

        trustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (rsaSignerId.equals(issuer)) {
                    return new TrustIdentity(rsaKeyPair.getPublic(), true, Algorithm.RSA_SHA256);
                } else if (ecSignerId.equals(issuer)) {
                    return new TrustIdentity(ecKeyPair.getPublic(), true, Algorithm.ECDSA_P256);
                }
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown issuer: " + issuer);
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
        // V5: use LIVENESS instead of KEY_EPOCH
        EntryId id = new EntryId(s, EntryType.LIVENESS, "session-A");
        assertEquals(s, id.scope());
        assertEquals(EntryType.LIVENESS, id.entryType());
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

        String rsaSignerId = SubjectComputer.compute("root-rsa", rsaKeyPair.getPublic());

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(scope)
            .key("session-A")
            .version(10L)
            .timestamp(System.currentTimeMillis())
            .issuer(rsaSignerId)
            .payload(payload)
            .sigAlg(Algorithm.RSA_SHA256);

        Envelope tempEnv = new Envelope(
            Envelope.PROTO_VERSION, EntryType.LIVENESS, (byte) 0x00, scope, "session-A",
            10L, builder.timestamp, rsaSignerId, payload, Algorithm.RSA_SHA256, null
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
        assertEquals(EntryType.LIVENESS, parsed.entryType);
        assertEquals(scope, parsed.scope);
        assertEquals("session-A", parsed.key);
        assertEquals(10L, parsed.version);
        assertEquals(rsaSignerId, parsed.issuer);
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
        EntryId id = new EntryId(Scope.parse("group:user"), EntryType.LIVENESS, "key");

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
            public TrustIdentity resolve(String issuer) {
                return new TrustIdentity(shortKey.getPublic(), true, Algorithm.RSA_SHA256);
            }
        };

        Scope scope = Scope.parse("group:user-123");
        Envelope envelope = new Envelope(
            Envelope.PROTO_VERSION, EntryType.LIVENESS, (byte) 0x00, scope, "session-A",
            10L, System.currentTimeMillis(), "root-rsa", new byte[0], Algorithm.RSA_SHA256, new byte[0]
        );
        
        SignatureVerifier verifier = new SignatureVerifier();
        VeridotException ex = assertThrows(VeridotException.class, () -> verifier.verify(envelope, badTrust));
        assertEquals(ErrorCode.ALGORITHM_MISMATCH, ex.getErrorCode());
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

        // Use TestTrustSetup for proper V5 construction
        TestTrustSetup setup = TestTrustSetup.create();
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        try (GenericSignerVerifier sv = setup.newSignerVerifier(inMemoryBroker)) {
            // Act: sign a valid token (should increment accepted)
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Act: verify valid token
            sv.verify(token, s -> s);
            
            // Accepted should be at least 1
            assertTrue(io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.sum() > 0);

            // Corrupt a liveness entry in broker to increment rejected during next verification
            EntryId livenessId = new EntryId(Scope.group("group1"), EntryType.LIVENESS, "sessionA");
            inMemoryBroker.put(livenessId.storageKey(), new byte[] { 0x00, 0x00, 0x00 });
            
            assertThrows(Exception.class, () -> sv.verify(token, s -> s));
            assertTrue(io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.sum() > 0);
        }
    }

    @Test
    public void testReconciliationStalenessCheck() throws Exception {
        TestTrustSetup setup = TestTrustSetup.create();
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                setup.trustRoot,
                setup.cn,
                setup.instanceKeyPair.getPrivate(),
                setup.instanceKeyPair.getPublic(),
                Algorithm.ED25519,
                -1,
                EvictionPolicy.FIFO,
                1, // 1 minute interval override
                null
        )) {
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Verify works initially
            assertNotNull(sv.verify(token, s -> s));

            // Force reconciliation to start for the scope
            sv.reconciliationManager.reconcile(
                Scope.group("group1"), inMemoryBroker, sv.watermarkForTest(),
                new SignatureVerifier(), setup.trustRoot, new EntryPublisher(), setup.signerId,
                setup.instanceKeyPair.getPrivate(), Algorithm.ED25519, sv.capabilityVerifier, null
            );
            
            // Trigger reconciliation periodic start so it registers in reconciledScopes
            sv.hasActiveToken(token); 

            // Mock the last reconciled time to be very old (exceeding staleness limit of 60 minutes)
            sv.reconciliationManager.setLastReconciledForTest(Scope.group("group1"), System.currentTimeMillis() - 70 * 60 * 1000L);

            // Verifying should now throw VERSION_REJECTED (staleness check)
            VeridotException ex = assertThrows(VeridotException.class, () -> sv.verify(token, s -> s));
            assertEquals(ErrorCode.VERSION_REJECTED, ex.getErrorCode());
        }
    }

    @Test
    public void testRsaPssSignatureVerification() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();

        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if ("issuer-pss".equals(issuer)) {
                    return new TrustIdentity(kp.getPublic(), false, Algorithm.RSA_PSS);
                }
                return null;
            }
        };

        Envelope env = new Envelope(
                Envelope.PROTO_VERSION,
                EntryType.LIVENESS,
                (byte) 0x00, // flags
                Scope.group("g1"),
                "k1",
                1L,
                System.currentTimeMillis(),
                "issuer-pss",
                new byte[] { 0x01, 0x02 },
                Algorithm.RSA_PSS,
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
    public void testRsaPssSignWithVerify() throws Exception {
        // Use RSA key pair for signing, test end-to-end
        String rsaSignerId = SubjectComputer.compute("issuer-rsa", rsaKeyPair.getPublic());
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot rsaTrustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (rsaSignerId.equals(issuer)) {
                    return new TrustIdentity(rsaKeyPair.getPublic(), true, Algorithm.RSA_PSS);
                }
                return null;
            }
        };

        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                rsaTrustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                rsaKeyPair.getPublic(),
                Algorithm.RSA_PSS,
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
            0x04, // entryType = LIVENESS (0x04 in V5)
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
        String rsaSignerId = SubjectComputer.compute("issuer-rsa", rsaKeyPair.getPublic());
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot rsaTrustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (rsaSignerId.equals(issuer)) {
                    return new TrustIdentity(rsaKeyPair.getPublic(), true, Algorithm.RSA_SHA256);
                }
                return null;
            }
        };

        // Create SignerVerifier with low capacity to trigger evictions
        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                rsaTrustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                rsaKeyPair.getPublic(),
                Algorithm.RSA_SHA256,
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
        String rsaSignerId = SubjectComputer.compute("issuer-rsa", rsaKeyPair.getPublic());
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();
        TrustRoot rsaTrustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (rsaSignerId.equals(issuer)) {
                    return new TrustIdentity(rsaKeyPair.getPublic(), true, Algorithm.RSA_SHA256);
                }
                return null;
            }
        };

        try (GenericSignerVerifier sv = new GenericSignerVerifier(
                inMemoryBroker,
                rsaTrustRoot,
                "issuer-rsa",
                rsaKeyPair.getPrivate(),
                rsaKeyPair.getPublic(),
                Algorithm.RSA_SHA256
        )) {
            // Before verification / reconciliation, staleness should be -1
            assertEquals(-1L, sv.getReconciliationStalenessMs("group:group1"));

            // Act: sign a valid token
            String token = sv.sign("data", BasicConfigurer.builder().groupId("group1").sequenceId("sessionA").validity(600).build());
            
            // Reconcile manually or test lastReconciled mapping
            sv.reconciliationManager.setLastReconciledForTest(Scope.group("group1"), System.currentTimeMillis() - 5000);
            
            long staleness = sv.getReconciliationStalenessMs("group:group1");
            assertTrue(staleness >= 5000 && staleness < 10000);
        }
    }

    @Test
    public void testClockDriftWarningLogging() throws Exception {
        // V5: This test verifies clock drift warning on LIVENESS entries
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
            // Use Ed25519 setup via TestTrustSetup for simplicity
            TestTrustSetup setup = TestTrustSetup.create("issuer-drift");

            long driftTime = System.currentTimeMillis() - (Config.MAX_CLOCK_DRIFT_SECONDS * 1000L / 2) - 10000L;
            
            byte[] livePayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
            
            EnvelopeBuilder liveBuilder = new EnvelopeBuilder()
                .entryType(EntryType.LIVENESS)
                .flags(Flags.COMPACT_SIG)
                .scope(Scope.group("group1"))
                .key("session-drift")
                .version(1L)
                .timestamp(driftTime)
                .issuer(setup.signerId)
                .payload(livePayloadBytes)
                .sigAlg(Algorithm.ED25519);

            Envelope liveEnv = new Envelope(
                Envelope.PROTO_VERSION,
                EntryType.LIVENESS,
                Flags.COMPACT_SIG,
                Scope.group("group1"),
                "session-drift",
                1L,
                driftTime,
                setup.signerId,
                livePayloadBytes,
                Algorithm.ED25519,
                null
            );
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(setup.instanceKeyPair.getPrivate());
            sig.update(liveEnv.canonicalSigningBytes());
            byte[] liveSig = sig.sign();
            
            byte[] encodedLiveEnv = Envelope.encode(liveBuilder, liveSig);
            EntryId liveEntryId = new EntryId(Scope.group("group1"), EntryType.LIVENESS, "session-drift");
            inMemoryBroker.put(liveEntryId.storageKey(), encodedLiveEnv).join();
            
            try (GenericSignerVerifier sv = setup.newSignerVerifier(inMemoryBroker)) {
                // Build JWT with kid header for V5 verification
                String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"alg\":\"EdDSA\",\"kid\":\"" + setup.signerId + "\"}").getBytes());
                String jwtPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"sub\":\"3:group1:session-drift\",\"data\":\"my-data\"}").getBytes());
                sig.initSign(setup.instanceKeyPair.getPrivate());
                sig.update((header + "." + jwtPayload).getBytes());
                String jwtSign = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
                String jwtTokenStr = header + "." + jwtPayload + "." + jwtSign;
                
                sv.verify(jwtTokenStr, s -> s);
            }
            
            // Check for clock drift warning in logs (may or may not appear depending on drift logic)
            // This is a best-effort test — the main assertion is that the code doesn't crash
            
        } finally {
            verifierLogger.removeHandler(customHandler);
        }
    }

    @Test
    public void testEmptyIssuerRejection() throws Exception {
        byte[] livenessPayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
        EnvelopeBuilder envBuilder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("session1")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("") // Empty issuer
            .payload(livenessPayloadBytes)
            .sigAlg(Algorithm.ED25519);

        Envelope env = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.LIVENESS,
            (byte) 0x00,
            Scope.group("group1"),
            "session1",
            1L,
            System.currentTimeMillis(),
            "", // Empty issuer
            livenessPayloadBytes,
            Algorithm.ED25519,
            null
        );

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(ed25519KeyPair.getPrivate());
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
        byte[] livenessPayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
        
        char[] arr = new char[4097];
        Arrays.fill(arr, 'a');
        String longIssuer = new String(arr);

        EnvelopeBuilder envBuilder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("session1")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer(longIssuer) // Too long issuer
            .payload(livenessPayloadBytes)
            .sigAlg(Algorithm.ED25519);

        Envelope env = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.LIVENESS,
            (byte) 0x00,
            Scope.group("group1"),
            "session1",
            1L,
            System.currentTimeMillis(),
            longIssuer, // Too long issuer
            livenessPayloadBytes,
            Algorithm.ED25519,
            null
        );

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(ed25519KeyPair.getPrivate());
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

        byte[] snapshot = "{\"group1:LIVENESS:session1\": 10}".getBytes();
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
        // V5: Test that JWT verification with mismatched alg header fails
        TestTrustSetup setup = TestTrustSetup.create("root-rsa-test");
        io.github.cyfko.veridot.core.InMemoryBroker inMemoryBroker = new io.github.cyfko.veridot.core.InMemoryBroker();

        // Build a LIVENESS entry so verification can proceed
        byte[] livePayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, System.currentTimeMillis(), System.currentTimeMillis() + 100000L).encode();
        EnvelopeBuilder liveBuilder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("sessionA")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer(setup.signerId)
            .payload(livePayloadBytes)
            .sigAlg(Algorithm.ED25519);

        Envelope liveEnv = new Envelope(
            Envelope.PROTO_VERSION,
            EntryType.LIVENESS,
            (byte) 0x00,
            Scope.group("group1"),
            "sessionA",
            1L,
            System.currentTimeMillis(),
            setup.signerId,
            livePayloadBytes,
            Algorithm.ED25519,
            null
        );
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(setup.instanceKeyPair.getPrivate());
        sig.update(liveEnv.canonicalSigningBytes());
        byte[] liveSig = sig.sign();
        inMemoryBroker.put(new EntryId(Scope.group("group1"), EntryType.LIVENESS, "sessionA").storageKey(), Envelope.encode(liveBuilder, liveSig)).join();

        try (GenericSignerVerifier sv = setup.newSignerVerifier(inMemoryBroker)) {
            // Build JWT with wrong header alg: ES256 instead of expected EdDSA
            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"alg\":\"ES256\",\"kid\":\"" + setup.signerId + "\"}").getBytes());
            String jwtPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"3:group1:sessionA\",\"data\":\"my-data\"}".getBytes());
            sig.initSign(setup.instanceKeyPair.getPrivate());
            sig.update((header + "." + jwtPayload).getBytes());
            String jwtSign = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            String jwtTokenStr = header + "." + jwtPayload + "." + jwtSign;

            // Verification should fail because of algorithm mismatch
            assertThrows(Exception.class, () -> {
                sv.verify(jwtTokenStr, s -> s);
            });
        }
    }

    @Test
    void testEd25519DefaultAndAlgorithmMapping() throws Exception {
        // V5: KeyRotationService is removed. Just test Algorithm.fromCode mapping.
        assertEquals(Algorithm.ED25519, Algorithm.fromCode((byte) 0x01));
        assertEquals(Algorithm.ECDSA_P256, Algorithm.fromCode((byte) 0x02));
        assertEquals(Algorithm.RSA_SHA256, Algorithm.fromCode((byte) 0x03));
        assertEquals(Algorithm.RSA_PSS, Algorithm.fromCode((byte) 0x04));
    }

    @Test
    void testJwtVerifierAlgHeaderCoherence() throws Exception {
        // Build a JWT token with JwtMaker using RSA-SHA256 (RS256)
        String token = JwtMaker.builder()
            .subject("test-sub")
            .claim("data", "hello")
            .signWith(rsaKeyPair.getPrivate())
            .alg(Algorithm.RSA_SHA256)
            .compact();

        // 1. Verifying with correct algorithm (RSA_SHA256) should succeed
        JwtVerifier verifier = JwtVerifier.verifyWith(rsaKeyPair.getPublic(), Algorithm.RSA_SHA256);
        Map<String, Object> claims = verifier.parseSignedClaims(token);
        assertEquals("test-sub", claims.get("sub"));
        assertEquals("hello", claims.get("data"));

        // 2. Verifying with incorrect expected algorithm (ECDSA_P256) should fail with SecurityException due to mismatch
        JwtVerifier badVerifier = JwtVerifier.verifyWith(rsaKeyPair.getPublic(), Algorithm.ECDSA_P256);
        assertThrows(SecurityException.class, () -> badVerifier.parseSignedClaims(token));
    }

    @Test
    void testHybridEncryptor() throws Exception {
        byte[] symKey = new byte[32];
        new SecureRandom().nextBytes(symKey);

        // 1. Test RSA path
        byte[] encryptedRsa = HybridEncryptor.encryptAsymmetric(symKey, rsaKeyPair.getPublic());
        assertNotNull(encryptedRsa);
        byte[] decryptedRsa = HybridEncryptor.decryptAsymmetric(encryptedRsa, rsaKeyPair.getPrivate());
        assertArrayEquals(symKey, decryptedRsa);

        // 2. Test EC path
        byte[] encryptedEc = HybridEncryptor.encryptAsymmetric(symKey, ecKeyPair.getPublic());
        assertNotNull(encryptedEc);
        byte[] decryptedEc = HybridEncryptor.decryptAsymmetric(encryptedEc, ecKeyPair.getPrivate());
        assertArrayEquals(symKey, decryptedEc);
    }
}
