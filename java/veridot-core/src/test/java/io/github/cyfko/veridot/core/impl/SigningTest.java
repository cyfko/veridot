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
    void sign_stores_v4_envelope_in_broker() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        // Two entries: KEY_EPOCH and LIVENESS(ACTIVE)
        assertEquals(2, entries.size(), "Two broker entries (KEY_EPOCH and LIVENESS) must be created");

        var keyEpochEntry = entries.stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .findFirst().orElseThrow();

        Envelope parsed = Envelope.parse(keyEpochEntry.envelopeBytes());
        assertEquals(Envelope.PROTO_VERSION, parsed.protoVersion);
        assertEquals(EntryType.KEY_EPOCH, parsed.entryType);
        assertEquals(scope, parsed.scope);
        assertEquals("test-signer", parsed.issuer);

        KeyEpochPayload payload = KeyEpochPayload.decode(parsed.payload);
        assertNotNull(payload.pk());
        assertTrue(payload.validUntil() > payload.validFrom());
    }

    @Test
    void sign_indirect_stores_token_in_payload() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.INDIRECT).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        var keyEpochEntry = entries.stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .findFirst().orElseThrow();

        Envelope parsed = Envelope.parse(keyEpochEntry.envelopeBytes());
        KeyEpochPayload payload = KeyEpochPayload.decode(parsed.payload);
        assertNotNull(payload.token(), "INDIRECT mode must store token in KeyEpochPayload");
    }

    @Test
    void sign_direct_does_not_store_token_in_payload() {
        var cfg = BasicConfigurer.builder().groupId("user1").validity(60)
                .distribution(DistributionMode.DIRECT).build();
        signer.sign("data", cfg);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        var keyEpochEntry = entries.stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .findFirst().orElseThrow();

        Envelope parsed = Envelope.parse(keyEpochEntry.envelopeBytes());
        KeyEpochPayload payload = KeyEpochPayload.decode(parsed.payload);
        assertNull(payload.token(), "DIRECT mode must NOT store token in KeyEpochPayload");
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

        EntryId expectedId = new EntryId(Scope.group("user1"), EntryType.KEY_EPOCH, "my-session");
        assertTrue(broker.containsKey(expectedId.storageKey()), "Broker must contain key with custom sequenceId");
    }

    @Test
    void sign_auto_sequenceId_creates_unique_entries() {
        var cfg1 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        var cfg2 = BasicConfigurer.builder().groupId("user1").validity(60).build();
        signer.sign("data1", cfg1);
        signer.sign("data2", cfg2);

        Scope scope = Scope.group("user1");
        var entries = broker.snapshot(scope);
        long keyEpochCount = entries.stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .count();
        assertEquals(2, keyEpochCount, "Two signs with auto-sequenceId must create 2 distinct KEY_EPOCH entries");
    }
}
