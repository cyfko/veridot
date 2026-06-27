package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenTrackerTest {

    private InMemoryMetadataBroker broker;
    private GenericSignerVerifier sv;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        sv = TestTrustSetup.create().newSignerVerifier(broker);
    }

    @Test
    void hasActiveToken_after_sign_returns_true_by_groupId() {
        sv.sign("data", BasicConfigurer.builder().groupId("u1").validity(600).build());
        assertTrue(sv.hasActiveToken("u1"), "Must return true for groupId with active session");
    }

    @Test
    void hasActiveToken_unknown_group_returns_false() {
        assertFalse(sv.hasActiveToken("nonexistent"),
                "Must return false for a groupId with no sessions");
    }

    @Test
    void hasActiveToken_after_revoke_by_token_returns_false() {
        var cfg = BasicConfigurer.builder().groupId("u1").sequenceId("seq1").validity(600).build();
        sv.sign("data", cfg);
        sv.revoke("u1", "seq1");
        assertFalse(sv.hasActiveToken("u1"), "Must return false after revoking the only session");
    }

    @Test
    void hasActiveToken_after_revokeGroup_returns_false() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.revoke("u1", null);
        assertFalse(sv.hasActiveToken("u1"), "Must return false after revoking the entire group");
    }

    @Test
    void hasActiveToken_after_expiry_returns_false() throws InterruptedException {
        sv.sign("data", BasicConfigurer.builder().groupId("u1").validity(1).build());
        Thread.sleep(2000); // wait for TTL to pass
        assertFalse(sv.hasActiveToken("u1"), "Must return false after token TTL has passed");
    }

    @Test
    void hasActiveToken_by_direct_jwt_returns_true() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("data", cfg);
        assertTrue(sv.hasActiveToken(jwt), "Must return true when querying by valid JWT");
    }

    @Test
    void hasActiveToken_by_messageId_returns_true() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("data", cfg);
        assertTrue(sv.hasActiveToken(messageId), "Must return true when querying by valid messageId");
    }

    @Test
    void hasActiveToken_partial_revoke_still_returns_true() {
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        sv.revoke("u1", "s2");  // revoke only s2
        assertTrue(sv.hasActiveToken("u1"),
                "Must return true if at least one session remains active in the group");
    }
}
