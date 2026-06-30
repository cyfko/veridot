package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VerificationTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier sv;
    private TestTrustSetup trust;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        trust = TestTrustSetup.create();
        sv = trust.newSignerVerifier(broker);
    }

    @Test
    void verify_valid_direct_token_returns_payload() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("hello world", cfg);
        VerifiedData<String> result = sv.verify(jwt, s -> s);
        assertEquals("hello world", result.data());
        assertEquals("u1", result.groupId());
        assertNotNull(result.sequenceId());
    }

    @Test
    void verify_valid_indirect_token_returns_payload() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("hello world", cfg);
        VerifiedData<String> result = sv.verify(messageId, s -> s);
        assertEquals("hello world", result.data());
        assertEquals("u1", result.groupId());
        assertNotNull(result.sequenceId());
    }

    @Test
    void verify_valid_pojo_direct() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("email", "test@example.com", "role", "admin");

        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign(data, cfg);

        @SuppressWarnings("unchecked")
        VerifiedData<Map<String, String>> result = sv.verify(jwt, s -> {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("test@example.com", result.data().get("email"));
        assertEquals("admin", result.data().get("role"));
    }

    @Test
    void verify_valid_pojo_indirect() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("userId", "42");

        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign(data, cfg);

        @SuppressWarnings("unchecked")
        VerifiedData<Map<String, String>> result = sv.verify(messageId, s -> {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("42", result.data().get("userId"));
    }

    @Test
    void verify_invalid_token_throws_BrokerExtractionException() {
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("invalid.token.here", s -> s));
    }

    @Test
    void verify_unknown_messageId_throws() {
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:unknown:session", s -> s));
    }

    @Test
    void verify_expired_direct_token_throws() throws InterruptedException {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(1)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("hello", cfg);
        Thread.sleep(2000); // wait for TTL to pass
        assertThrows(Exception.class, () -> sv.verify(jwt, s -> s),
                "Expired token must throw an exception");
    }

    @Test
    void verify_expired_indirect_token_throws() throws InterruptedException {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(1)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("hello", cfg);
        Thread.sleep(2000);
        assertThrows(BrokerExtractionException.class, () -> sv.verify(messageId, s -> s),
                "Expired INDIRECT token must throw BrokerExtractionException");
    }

    @Test
    void verify_futureTimestamp_beyond5min_throws() throws Exception {
        // Manually build an envelope with a timestamp 10 minutes in the future
        long futureTs = System.currentTimeMillis() + 600000; // +10 min
        Scope scope = Scope.group("drift-grp");
        
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair ephemeral = gen.generateKeyPair();

        KeyEpochPayload payload = new KeyEpochPayload(
            Algorithm.RSA_SHA256, 1L, ephemeral.getPublic().getEncoded(), futureTs, futureTs + 3600000, null, null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(scope)
            .key("s1")
            .version(1L)
            .timestamp(futureTs)
            .issuer(trust.signerId)
            .payload(payload.encode())
            .sigAlg(Algorithm.RSA_SHA256);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(trust.longTermKeyPair.getPrivate());
        sig.update(Envelope.encode(builder, new byte[0])); // We need to sign canonical bytes, but buildCanonicalBytes / canonicalSigningBytes uses all except sigAlg
        // To make it simple, let's sign the canonical bytes
        
        // Wait, builder doesn't have a direct canonical bytes builder.
        // Let's create a temporary Envelope to sign
        Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "s1", 1L, futureTs, trust.signerId, payload.encode(), Algorithm.RSA_SHA256, new byte[0]);
        sig.update(tempEnv.canonicalSigningBytes());
        byte[] signature = sig.sign();

        byte[] encoded = Envelope.encode(builder, signature);
        
        EntryId id = new EntryId(scope, EntryType.KEY_EPOCH, "s1");
        broker.put(id.storageKey(), encoded).join();

        // Verify must reject due to clock drift > 5 min (§5.3)
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:drift-grp:s1", s -> s),
                "Must reject messages with timestamp > 5min in the future");
    }

    @Test
    void verify_tampered_metadata_without_valid_signature_throws() throws Exception {
        // Manually build an envelope with a tampered/invalid signature
        long ts = System.currentTimeMillis();
        Scope scope = Scope.group("attack-grp");
        
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair ephemeral = gen.generateKeyPair();

        KeyEpochPayload payload = new KeyEpochPayload(
            Algorithm.RSA_SHA256, 1L, ephemeral.getPublic().getEncoded(), ts, ts + 3600000, null, null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(scope)
            .key("evil-session")
            .version(1L)
            .timestamp(ts)
            .issuer(trust.signerId)
            .payload(payload.encode())
            .sigAlg(Algorithm.RSA_SHA256);

        byte[] badSignature = new byte[256]; // Dummy signature of all zeros

        byte[] encoded = Envelope.encode(builder, badSignature);
        
        EntryId id = new EntryId(scope, EntryType.KEY_EPOCH, "evil-session");
        broker.put(id.storageKey(), encoded).join();

        // Verify must reject because signature verification fails
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:attack-grp:evil-session", s -> s),
                "Must reject metadata with invalid signature");
    }
}
