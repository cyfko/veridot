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
    void verify_valid_native_token_returns_payload() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.NATIVE).build();
        String nativeRef = sv.sign("hello world", cfg);
        VerifiedData<String> result = sv.verify(nativeRef, s -> s);
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
    void verify_valid_pojo_native() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("userId", "42");

        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.NATIVE).build();
        String nativeRef = sv.sign(data, cfg);

        @SuppressWarnings("unchecked")
        VerifiedData<Map<String, String>> result = sv.verify(nativeRef, s -> {
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
    void verify_unknown_nativeRef_throws() {
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("8:unknown:session", s -> s));
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
    void verify_expired_native_token_throws() throws InterruptedException {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(1)
                .distribution(DistributionMode.NATIVE).build();
        String nativeRef = sv.sign("hello", cfg);
        Thread.sleep(2000);
        assertThrows(Exception.class, () -> sv.verify(nativeRef, s -> s),
                "Expired NATIVE token must throw");
    }

    @Test
    void verify_tampered_metadata_without_valid_signature_throws() throws Exception {
        // Manually build an envelope with a tampered/invalid signature
        long ts = System.currentTimeMillis();
        Scope scope = Scope.group("attack-grp");

        // Build a LIVENESS entry with a bad signature
        byte[] livenessPayloadBytes = new LivenessPayload(LivenessPayload.ACTIVE, ts, ts + 3600000).encode();

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(scope)
            .key("evil-session")
            .version(1L)
            .timestamp(ts)
            .issuer(trust.signerId)
            .payload(livenessPayloadBytes)
            .sigAlg(Algorithm.ED25519);

        byte[] badSignature = new byte[64]; // Dummy Ed25519 signature of all zeros

        byte[] encoded = Envelope.encode(builder, badSignature);

        EntryId id = new EntryId(scope, EntryType.LIVENESS, "evil-session");
        broker.put(id.storageKey(), encoded).join();

        // Verification using direct JWT that references this group should detect the tampering
        var cfg = BasicConfigurer.builder().groupId("attack-grp").sequenceId("evil-session").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        // This sign should overwrite the liveness entry with a valid one, so let's test differently:
        // Instead, try to verify a NATIVE reference that points to the tampered entry
        EntryId signedDataId = new EntryId(scope, EntryType.SIGNED_DATA, "evil-session");
        broker.put(signedDataId.storageKey(), encoded).join(); // put bad envelope as SIGNED_DATA too

        assertThrows(Exception.class,
                () -> sv.verify("8:attack-grp:evil-session", s -> s),
                "Must reject metadata with invalid signature");
    }
}
