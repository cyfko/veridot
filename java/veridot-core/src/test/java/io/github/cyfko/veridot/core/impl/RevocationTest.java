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
        sv = new GenericSignerVerifier(broker, "test-salt");
    }

    @Test
    void revoke_by_direct_token_then_verify_fails() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("data", cfg);
        sv.revoke(jwt);
        assertThrows(BrokerExtractionException.class, () -> sv.verify(jwt, s -> s),
                "Verify must fail after revoking via JWT token");
    }

    @Test
    void revoke_by_indirect_messageId_then_verify_fails() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("data", cfg);
        sv.revoke(messageId);
        assertThrows(BrokerExtractionException.class, () -> sv.verify(messageId, s -> s),
                "Verify must fail after revoking via messageId");
    }

    @Test
    void revoke_removes_broker_entry() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build();
        String jwt = sv.sign("data", cfg);
        assertTrue(broker.containsKey("2:u1:s1"), "Entry must exist before revocation");
        sv.revoke(jwt);
        assertFalse(broker.containsKey("2:u1:s1"), "Entry must be removed after revocation");
    }

    @Test
    void revokeGroup_revokes_all_sequences() {
        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t3 = sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());

        assertEquals(3, broker.getKeysByPrefix("2:u1:").size(), "Must have 3 active sessions before revokeGroup");
        sv.revokeGroup("u1");
        assertEquals(0, broker.getKeysByPrefix("2:u1:").size(), "Must have 0 sessions after revokeGroup");

        assertThrows(BrokerExtractionException.class, () -> sv.verify(t1, s -> s));
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t2, s -> s));
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t3, s -> s));
    }

    @Test
    void revokeGroup_other_groups_unaffected() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("u2").validity(600).build());

        sv.revokeGroup("u1");

        // u2 must still be verifiable
        assertDoesNotThrow(() -> sv.verify(t2, s -> s),
                "Other groups must not be affected by revokeGroup");
    }

    @Test
    void revokeGroup_unknown_group_no_error() {
        assertDoesNotThrow(() -> sv.revokeGroup("nonexistent-group"),
                "Revoking a nonexistent group must not throw");
    }

    @Test
    void revoke_unsupported_type_throws() {
        assertThrows(IllegalArgumentException.class, () -> sv.revoke(42L),
                "Non-String targets must throw IllegalArgumentException");
    }
}
