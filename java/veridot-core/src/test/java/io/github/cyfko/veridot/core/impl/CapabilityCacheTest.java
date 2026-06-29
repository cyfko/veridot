package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.TrustIdentity;
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

    @Test
    void circular_delegation_is_detected_and_rejected() throws Exception {
        // We will register three issuers: "issuerA", "issuerB"
        // Issuer A delegates to Issuer B:
        // Envelope: issuer = "issuerA", key = "issuerB" (capability for subject "issuerB" signed by "issuerA")
        // Issuer B delegates to Issuer A:
        // Envelope: issuer = "issuerB", key = "issuerA" (capability for subject "issuerA" signed by "issuerB")
        
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair kpA = gen.generateKeyPair();
        java.security.KeyPair kpB = gen.generateKeyPair();
        
        java.util.Map<String, java.security.PublicKey> keyStore = new java.util.HashMap<>();
        keyStore.put("issuerA", kpA.getPublic());
        keyStore.put("issuerB", kpB.getPublic());
        
        io.github.cyfko.veridot.core.TrustRoot trustRoot = new io.github.cyfko.veridot.core.PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                java.security.PublicKey pk = keyStore.get(issuer);
                return pk != null ? new TrustIdentity(pk, false) : null;
            }
        };

        // Capability B signed by A:
        CapabilityPayload capB = new CapabilityPayload("issuerB", java.util.List.of("group:*"), (byte) 5, System.currentTimeMillis() + 3600000L);
        EntryPublisher publisher = new EntryPublisher();
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "issuerB",
                1L,
                capB.encode(),
                kpA.getPrivate(),
                (byte) 0x01,
                "issuerA",
                broker
        ).join();

        // Capability A signed by B:
        CapabilityPayload capA = new CapabilityPayload("issuerA", java.util.List.of("group:*"), (byte) 5, System.currentTimeMillis() + 3600000L);
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "issuerA",
                1L,
                capA.encode(),
                kpB.getPrivate(),
                (byte) 0x01,
                "issuerB",
                broker
        ).join();

        // Checking authorization should fail with DELEGATION_DEPTH_EXCEEDED (due to depth > 10 check in circular loop)
        VeridotException ex = assertThrows(VeridotException.class, () -> 
            capabilityVerifier.assertAuthorized("issuerB", Scope.group("group1"), broker, trustRoot)
        );
        assertEquals(ErrorCode.DELEGATION_DEPTH_EXCEEDED, ex.getErrorCode());
    }

    @Test
    void delegation_depth_exceeded_is_rejected() throws Exception {
        // We will build a chain: root -> issuerA -> issuerB
        // root is the root identity.
        // root delegates to issuerA (maxDelegationDepth = 0)
        // issuerA delegates to issuerB (maxDelegationDepth = 5)
        // Let's see: total depth for issuerB is 1 hop from A (issuerB -> issuerA -> root, depth = 1 from A).
        // Since A's capability has maxDelegationDepth = 0, it will check if totalDepth (1) > maxDelegationDepth (0) -> should fail!
        
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair kpRoot = gen.generateKeyPair();
        java.security.KeyPair kpA = gen.generateKeyPair();
        
        java.util.Map<String, java.security.PublicKey> keyStore = new java.util.HashMap<>();
        keyStore.put("root", kpRoot.getPublic());
        keyStore.put("issuerA", kpA.getPublic());
        
        io.github.cyfko.veridot.core.TrustRoot trustRoot = new io.github.cyfko.veridot.core.PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                java.security.PublicKey pk = keyStore.get(issuer);
                return pk != null ? new TrustIdentity(pk, "root".equals(issuer)) : null;
            }
        };

        // Capability for issuerA signed by root (maxDelegationDepth = 0)
        CapabilityPayload capA = new CapabilityPayload("issuerA", java.util.List.of("group:*"), (byte) 0, System.currentTimeMillis() + 3600000L);
        EntryPublisher publisher = new EntryPublisher();
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "issuerA",
                1L,
                capA.encode(),
                kpRoot.getPrivate(),
                (byte) 0x01,
                "root",
                broker
        ).join();

        // Capability for issuerB signed by issuerA (maxDelegationDepth = 5)
        CapabilityPayload capB = new CapabilityPayload("issuerB", java.util.List.of("group:*"), (byte) 5, System.currentTimeMillis() + 3600000L);
        publisher.publish(
                EntryType.CAPABILITY,
                Scope.group("group1"),
                "issuerB",
                1L,
                capB.encode(),
                kpA.getPrivate(),
                (byte) 0x01,
                "issuerA",
                broker
        ).join();

        // Should throw DELEGATION_DEPTH_EXCEEDED because total depth is 2, but capA allows max 1!
        VeridotException ex = assertThrows(VeridotException.class, () -> 
            capabilityVerifier.assertAuthorized("issuerB", Scope.group("group1"), broker, trustRoot)
        );
        assertEquals(ErrorCode.DELEGATION_DEPTH_EXCEEDED, ex.getErrorCode());
    }
}
