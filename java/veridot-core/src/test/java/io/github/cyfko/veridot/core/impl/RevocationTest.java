package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RevocationTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier sv;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        sv = TestTrustSetup.create().newSignerVerifier(broker);
    }

    private boolean hasKeyEpoch(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.KEY_EPOCH, sessionKey);
        return broker.containsKey(id.storageKey());
    }

    private LivenessPayload getLivenessPayload(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.LIVENESS, sessionKey);
        byte[] bytes = broker.get(id.storageKey());
        if (bytes == null) return null;
        Envelope env = Envelope.parse(bytes);
        return LivenessPayload.decode(env.payload);
    }

    @Test
    void revoke_by_direct_token_then_verify_fails() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("data", cfg);
        sv.revoke("u1", "s1");
        assertThrows(BrokerExtractionException.class, () -> sv.verify(jwt, s -> s),
                "Verify must fail after revoking via JWT token");
    }

    @Test
    void revoke_by_indirect_messageId_then_verify_fails() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("data", cfg);
        sv.revoke("u1", "s2");
        assertThrows(BrokerExtractionException.class, () -> sv.verify(messageId, s -> s),
                "Verify must fail after revoking via messageId");
    }

    @Test
    void revoke_updates_liveness_to_revoked_but_retains_key_epoch() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build();
        sv.sign("data", cfg);
        assertTrue(hasKeyEpoch("u1", "s1"), "KeyEpoch must exist before revocation");
        
        sv.revoke("u1", "s1");
        
        assertTrue(hasKeyEpoch("u1", "s1"), "KeyEpoch must NOT be removed after revocation (V4-02)");
        
        LivenessPayload liveness = getLivenessPayload("u1", "s1");
        assertNotNull(liveness);
        assertFalse(liveness.isActive(), "Liveness status must be REVOKED");
    }

    @Test
    void revokeGroup_revokes_all_sequences() {
        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        String t3 = sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        Scope scope = Scope.group("u1");
        long keyEpochCountBefore = broker.snapshot(scope).stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .count();
        assertEquals(3, keyEpochCountBefore, "Must have 3 active sessions before revokeGroup");
        
        sv.revoke("u1", null);
        
        long keyEpochCountAfter = broker.snapshot(scope).stream()
                .filter(e -> Envelope.parse(e.envelopeBytes()).entryType == EntryType.KEY_EPOCH)
                .count();
        assertEquals(3, keyEpochCountAfter, "Must have 3 KeyEpoch sessions after revokeGroup (V4-02)");

        assertThrows(BrokerExtractionException.class, () -> sv.verify(t1, s -> s));
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t2, s -> s));
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t3, s -> s));
    }

    @Test
    void revokeGroup_other_groups_unaffected() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("u2").validity(600).build());

        sv.revoke("u1", null);

        // u2 must still be verifiable
        assertDoesNotThrow(() -> sv.verify(t2, s -> s),
                "Other groups must not be affected by revokeGroup");
    }

    @Test
    void revokeGroup_unknown_group_no_error() {
        assertDoesNotThrow(() -> sv.revoke("nonexistent-group", null),
                "Revoking a nonexistent group must not throw");
    }

    @Test
    void revokeGroup_retains_key_epoch_but_updates_liveness_to_revoked() {
        sv.sign("d1", BasicConfigurer.builder().groupId("g1").sequenceId("ses-A").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("g1").sequenceId("ses-B").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("g1").sequenceId("ses-C").validity(600).build());

        assertTrue(hasKeyEpoch("g1", "ses-A"), "ses-A must exist before revokeGroup");
        assertTrue(hasKeyEpoch("g1", "ses-B"), "ses-B must exist before revokeGroup");
        assertTrue(hasKeyEpoch("g1", "ses-C"), "ses-C must exist before revokeGroup");

        sv.revoke("g1", null);

        assertTrue(hasKeyEpoch("g1", "ses-A"), "ses-A must NOT be physically deleted after revokeGroup");
        assertTrue(hasKeyEpoch("g1", "ses-B"), "ses-B must NOT be physically deleted after revokeGroup");
        assertTrue(hasKeyEpoch("g1", "ses-C"), "ses-C must NOT be physically deleted after revokeGroup");

        assertFalse(getLivenessPayload("g1", "ses-A").isActive());
        assertFalse(getLivenessPayload("g1", "ses-B").isActive());
        assertFalse(getLivenessPayload("g1", "ses-C").isActive());
    }
}
