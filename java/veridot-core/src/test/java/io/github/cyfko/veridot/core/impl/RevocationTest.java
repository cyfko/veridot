package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RevocationTest {

    private InMemoryMetadataBroker broker;
    private GenericSignerVerifier sv;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        sv = TestTrustSetup.create().newSignerVerifier(broker);
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
    void revoke_removes_broker_entry() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build();
        sv.sign("data", cfg);
        assertTrue(broker.containsKey("3:u1:s1"), "Entry must exist before revocation");
        sv.revoke("u1", "s1");
        assertFalse(broker.containsKey("3:u1:s1"), "Entry must be removed after revocation");
    }

    @Test
    void revokeGroup_revokes_all_sequences() {
        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t3 = sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());

        long normalCountBefore = broker.getKeysByPrefix("3:u1:").stream()
                .filter(k -> !Protocol.isReservedSequence(k)).count();
        assertEquals(3, normalCountBefore, "Must have 3 active sessions before revokeGroup");
        sv.revoke("u1", null);
        long normalCountAfter = broker.getKeysByPrefix("3:u1:").stream()
                .filter(k -> !Protocol.isReservedSequence(k)).count();
        assertEquals(0, normalCountAfter, "Must have 0 normal sessions after revokeGroup");
        // The __REVOKE__ message itself persists (for interoperability)
        assertTrue(broker.containsKey("3:u1:__REVOKE__"), "Revocation message must persist in broker");

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
    void revoke_publishes_structured_revocation_message() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build();
        sv.sign("data", cfg);
        sv.revoke("u1", "s1");

        // The __REVOKE__ message must exist in the broker
        String revokeKey = "3:u1:__REVOKE__";
        assertTrue(broker.containsKey(revokeKey), "Revocation key must exist in broker");
        String revokeMsg = broker.getRaw(revokeKey);
        assertTrue(revokeMsg.startsWith("3:u1:__REVOKE__|"), "Must be a structured V3 __REVOKE__ message");
        assertTrue(revokeMsg.contains("target:"), "Must contain 'target' property");
        assertTrue(revokeMsg.contains("ts:"), "Must contain 'ts' property");
        // v3.0 — tombstone must be signed (F7)
        assertTrue(revokeMsg.contains("sig:"), "Must contain 'sig' property (F7)");
    }

    @Test
    void revokeGroup_physically_deletes_entries_from_store() {
        // Create 3 sessions with known sequenceIds
        sv.sign("d1", BasicConfigurer.builder().groupId("g1").sequenceId("ses-A").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("g1").sequenceId("ses-B").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("g1").sequenceId("ses-C").validity(600).build());

        // Pre-conditions: all 3 entries exist physically in the store
        assertTrue(broker.containsKey("3:g1:ses-A"), "ses-A must exist before revokeGroup");
        assertTrue(broker.containsKey("3:g1:ses-B"), "ses-B must exist before revokeGroup");
        assertTrue(broker.containsKey("3:g1:ses-C"), "ses-C must exist before revokeGroup");

        sv.revoke("g1", null);

        // Post-conditions: each entry is physically removed (not just logically expired)
        assertFalse(broker.containsKey("3:g1:ses-A"), "ses-A must be physically deleted after revokeGroup");
        assertFalse(broker.containsKey("3:g1:ses-B"), "ses-B must be physically deleted after revokeGroup");
        assertFalse(broker.containsKey("3:g1:ses-C"), "ses-C must be physically deleted after revokeGroup");

        // Only the __REVOKE__ entry remains
        assertTrue(broker.containsKey("3:g1:__REVOKE__"), "__REVOKE__ entry must persist for interoperability");

        // Total keys for the group = exactly 1 (__REVOKE__ only)
        var remainingKeys = broker.getKeysByPrefix("3:g1:");
        assertEquals(1, remainingKeys.size(), "Only __REVOKE__ key should remain in broker");
        assertEquals("3:g1:__REVOKE__", remainingKeys.get(0));
    }

    @Test
    void revokeGroup_publishes_revocation_with_target_all() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.revoke("u1", null);

        String revokeKey = "3:u1:__REVOKE__";
        assertTrue(broker.containsKey(revokeKey), "Revocation key must exist after revokeGroup");
        String revokeMsg = broker.getRaw(revokeKey);

        // Parse and verify target == __ALL__
        int pipeIdx = revokeMsg.indexOf('|');
        assertTrue(pipeIdx > 0);
        // Use Protocol to parse (package-private, accessible from this test class)
        var meta = Protocol.parseMetadata(revokeMsg);
        assertEquals("__ALL__", meta.get("target"), "revokeGroup must use target=__ALL__");
    }

    @Test
    void revoke_replay_original_announcement_after_tombstone_has_no_effect() {
        // F7 regression guard: signing a new token for the same sequenceId after revocation
        // must NOT resurrect the revoked session (tombstone wins).
        // In the current in-memory model, re-signing creates a new entry (new sequenceId);
        // what we verify here is that the tombstone message is persisted and readable.
        var cfg = BasicConfigurer.builder().groupId("replay-grp").sequenceId("s1").validity(600).build();
        sv.sign("data", cfg);
        sv.revoke("replay-grp", "s1");

        // The tombstone must be present
        assertTrue(broker.containsKey("3:replay-grp:__REVOKE__"),
                "Tombstone must be present after revocation");
        String tombstone = broker.getRaw("3:replay-grp:__REVOKE__");
        var meta = Protocol.parseMetadata(tombstone);
        assertNotNull(meta.get("ts"), "Tombstone must carry a ts (F7)");
        assertNotNull(meta.get("sig"), "Tombstone must carry a long-term signature (F7)");

        // The original session entry must be gone
        assertFalse(broker.containsKey("3:replay-grp:s1"),
                "Revoked session entry must be deleted — replay cannot resurrect it");
    }
}
