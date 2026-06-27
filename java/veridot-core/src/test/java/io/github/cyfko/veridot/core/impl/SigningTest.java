package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SigningTest {

    private InMemoryMetadataBroker broker;
    private GenericSignerVerifier signer;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        signer = TestTrustSetup.create().newSignerVerifier(broker);
    }

    @Test
    void sign_direct_returns_jwt() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.DIRECT).build();
        String result = signer.sign("data", cfg);
        assertTrue(result.contains("."), "DIRECT mode must return a JWT (contains dots)");
        assertFalse(result.startsWith("3:"), "DIRECT mode must NOT return a messageId");
    }

    @Test
    void sign_indirect_returns_messageId() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.INDIRECT).build();
        String result = signer.sign("data", cfg);
        assertTrue(result.startsWith("3:"), "INDIRECT mode must return a messageId starting with '3:'");
        assertFalse(result.contains("."), "INDIRECT mode must NOT return a JWT");
    }

    @Test
    void sign_stores_v3_message_in_broker() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data", cfg);

        var keys = broker.getKeysByPrefix("3:user1:");
        assertEquals(1, keys.size(), "Exactly one broker entry must be created");

        String stored = broker.getRaw(keys.get(0));
        assertNotNull(stored);
        assertTrue(stored.contains("|"), "Stored message must contain V3 metadata separator '|'");
        assertTrue(stored.contains("alg:"), "Stored message must contain 'alg' property");
        assertTrue(stored.contains("pk:"), "Stored message must contain 'pk' property");
        assertTrue(stored.contains("ts:"), "Stored message must contain 'ts' property");
        assertTrue(stored.contains("ttl:"), "Stored message must contain 'ttl' property");
        // v3.0 — trust-anchor fields must be present
        assertTrue(stored.contains("sid:"), "Stored message must contain 'sid' property (F1)");
        assertTrue(stored.contains("sig:"), "Stored message must contain 'sig' property (F1)");
    }

    @Test
    void sign_indirect_stores_token_in_metadata() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.INDIRECT).build();
        signer.sign("data", cfg);

        var keys = broker.getKeysByPrefix("3:user1:");
        String stored = broker.getRaw(keys.get(0));
        assertTrue(stored.contains("token:"), "INDIRECT mode must store 'token' property in metadata");
    }

    @Test
    void sign_direct_does_not_store_token_in_metadata() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.DIRECT).build();
        signer.sign("data", cfg);

        var keys = broker.getKeysByPrefix("3:user1:");
        String stored = broker.getRaw(keys.get(0));
        assertFalse(stored.contains("token:"), "DIRECT mode must NOT store 'token' property in metadata");
    }

    @Test
    void sign_null_data_throws() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60).build();
        assertThrows(IllegalArgumentException.class, () -> signer.sign(null, cfg));
    }

    @Test
    void sign_negative_duration_throws() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(-5).build();
        assertThrows(IllegalArgumentException.class, () -> signer.sign("data", cfg));
    }

    @Test
    void sign_custom_sequenceId_used_as_broker_key() {
        var cfg = BasicConfigurer.builder().groupId("user1").sequenceId("my-session").validity(60).build();
        signer.sign("data", cfg);
        assertTrue(broker.containsKey("3:user1:my-session"),
                "Broker must contain key with custom sequenceId");
    }

    @Test
    void sign_auto_sequenceId_creates_unique_entries() {
        var cfg1 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        var cfg2 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data1", cfg1);
        signer.sign("data2", cfg2);

        var keys = broker.getKeysByPrefix("3:user1:");
        assertEquals(2, keys.size(), "Two signs with auto-sequenceId must create 2 distinct broker entries");
        assertNotEquals(keys.get(0), keys.get(1), "Auto-generated sequenceIds must be unique");
    }
}
