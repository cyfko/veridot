package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryBroker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SigningTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier signer;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        signer = TestTrustSetup.create().newSignerVerifier(broker);
    }

    @Test
    void sign_direct_returns_jwt() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.DIRECT).build();
        String result = signer.sign("data", cfg);
        assertTrue(result.contains("."), "DIRECT mode must return a JWT (contains dots)");
        assertFalse(result.startsWith("8:"), "DIRECT mode must NOT return a native reference");
    }

    @Test
    void sign_native_returns_reference() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.NATIVE).build();
        String result = signer.sign("data", cfg);
        assertTrue(result.startsWith("8:"), "NATIVE mode must return a reference starting with '8:'");
        assertFalse(result.contains("."), "NATIVE mode must NOT return a JWT");
    }

    @Test
    void sign_stores_v5_envelope_in_broker() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        // V5: LIVENESS entry is published (no KEY_EPOCH)
        assertTrue(entries.stream()
                .anyMatch(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.LIVENESS),
                "LIVENESS entry must be created in broker");
    }

    @Test
    void sign_native_stores_signed_data_in_broker() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.NATIVE).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        assertTrue(entries.stream()
                .anyMatch(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.SIGNED_DATA),
                "NATIVE mode must store a SIGNED_DATA entry in broker");
    }

    @Test
    void sign_direct_does_not_store_signed_data_in_broker() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.DIRECT).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        assertTrue(entries.stream()
                .noneMatch(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.SIGNED_DATA),
                "DIRECT mode must NOT store SIGNED_DATA in broker");
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

        EntryId expectedId = new EntryId(Scope.group("user1"), EntryType.LIVENESS, "my-session");
        assertTrue(broker.containsKey(expectedId.storageKey()), "Broker must contain LIVENESS entry with custom sequenceId");
    }

    @Test
    void sign_auto_sequenceId_creates_unique_entries() {
        var cfg1 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        var cfg2 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data1", cfg1);
        signer.sign("data2", cfg2);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        long livenessCount = entries.stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.LIVENESS)
                .count();
        assertEquals(2, livenessCount, "Two signs with auto-sequenceId must create 2 distinct LIVENESS entries");
    }
}
