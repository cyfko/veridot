package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCacheTest {

    private InMemoryBroker broker;
    private TestTrustSetup trust;
    private CapabilityVerifier capabilityVerifier;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        trust = TestTrustSetup.create();
        capabilityVerifier = new CapabilityVerifier();
    }

    @Test
    void config_ttl_values_are_resolved_correctly() {
        assertEquals(60, Config.CAPABILITY_CACHE_TTL_SECONDS);
        assertEquals(5, Config.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS);
    }

    @Test
    void capability_is_cached_and_bypassed_by_invalidation() throws Exception {
        // Prepare capability for subject "subject1" covering "group1"
        CapabilityPayload capPayload = new CapabilityPayload("subject1", java.util.List.of("group:group1"), (byte) 2, System.currentTimeMillis() + 3600000L);
        byte[] payloadBytes = capPayload.encode();

        EntryPublisher publisher = new EntryPublisher();
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "subject1",
                1L,
                payloadBytes,
                trust.longTermKeyPair.getPrivate(),
                (byte) 0x01,
                trust.signerId,
                broker
        ).join();

        // 1. Initial assertion - should succeed (fetches from broker)
        assertDoesNotThrow(() -> capabilityVerifier.assertAuthorized("subject1", Scope.group("group1"), broker, trust.trustRoot));

        // 2. Delete capability from broker
        EntryId entryId = new EntryId(Scope.group("group1"), EntryType.CAPABILITY, "subject1");
        broker.put(entryId.storageKey(), null).join(); // delete from broker

        // 3. Assert again - should STILL succeed because of cache
        assertDoesNotThrow(() -> capabilityVerifier.assertAuthorized("subject1", Scope.group("group1"), broker, trust.trustRoot));

        // 4. Invalidate specific authorization
        capabilityVerifier.invalidateAuthorization("subject1", Scope.group("group1"));

        // 5. Assert again - should FAIL because cache is invalidated and entry was deleted from broker
        assertThrows(Exception.class, () -> capabilityVerifier.assertAuthorized("subject1", Scope.group("group1"), broker, trust.trustRoot));
    }

    @Test
    void invalidate_authorizations_for_issuer_clears_all_scopes_for_that_issuer() throws Exception {
        // Prepare capabilities
        CapabilityPayload capPayload = new CapabilityPayload("subject2", java.util.List.of("group:*"), (byte) 2, System.currentTimeMillis() + 3600000L);
        byte[] payloadBytes = capPayload.encode();

        EntryPublisher publisher = new EntryPublisher();
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "subject2",
                1L,
                payloadBytes,
                trust.longTermKeyPair.getPrivate(),
                (byte) 0x01,
                trust.signerId,
                broker
        ).join();

        assertDoesNotThrow(() -> capabilityVerifier.assertAuthorized("subject2", Scope.group("group1"), broker, trust.trustRoot));

        // Delete from broker
        EntryId entryId = new EntryId(Scope.group("group1"), EntryType.CAPABILITY, "subject2");
        broker.put(entryId.storageKey(), null).join();

        // Invalidate for issuer
        capabilityVerifier.invalidateAuthorizationsForIssuer("subject2");

        // Should query broker and fail
        assertThrows(Exception.class, () -> capabilityVerifier.assertAuthorized("subject2", Scope.group("group1"), broker, trust.trustRoot));
    }

    @Test
    void clear_cache_clears_everything() throws Exception {
        CapabilityPayload capPayload = new CapabilityPayload("subject3", java.util.List.of("group:*"), (byte) 2, System.currentTimeMillis() + 3600000L);
        byte[] payloadBytes = capPayload.encode();

        EntryPublisher publisher = new EntryPublisher();
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "subject3",
                1L,
                payloadBytes,
                trust.longTermKeyPair.getPrivate(),
                (byte) 0x01,
                trust.signerId,
                broker
        ).join();

        assertDoesNotThrow(() -> capabilityVerifier.assertAuthorized("subject3", Scope.group("group1"), broker, trust.trustRoot));

        // Delete from broker
        EntryId entryId = new EntryId(Scope.group("group1"), EntryType.CAPABILITY, "subject3");
        broker.put(entryId.storageKey(), null).join();

        // Clear cache
        capabilityVerifier.clearCache();

        // Should query broker and fail
        assertThrows(Exception.class, () -> capabilityVerifier.assertAuthorized("subject3", Scope.group("group1"), broker, trust.trustRoot));
    }
}
