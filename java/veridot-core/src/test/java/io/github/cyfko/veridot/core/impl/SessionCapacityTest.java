package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCapacityTest {

    private InMemoryMetadataBroker broker;
    private TestTrustSetup trust;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        trust = TestTrustSetup.create();
    }

    @Test
    void maxSessions_fifo_evicts_oldest() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100); // wait > 1s so timestamps differ at second precision
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("3:u1:").stream()
                .filter(k -> !ProtocolV2.isReservedSequence(k)).toList();
        assertEquals(2, activeKeys.size(), "Only 2 sessions must remain after FIFO eviction");
        assertFalse(broker.containsKey("3:u1:s1"), "s1 must be evicted (oldest, FIFO)");
        assertTrue(broker.containsKey("3:u1:s2"), "s2 must still be active");
        assertTrue(broker.containsKey("3:u1:s3"), "s3 must be active (newest)");
    }

    @Test
    void maxSessions_lifo_evicts_newest() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.LIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100); // wait > 1s so timestamps differ at second precision
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("3:u1:").stream()
                .filter(k -> !ProtocolV2.isReservedSequence(k)).toList();
        assertEquals(2, activeKeys.size(), "Only 2 sessions must remain after LIFO eviction");
        assertTrue(broker.containsKey("3:u1:s1"), "s1 must still be active (oldest)");
        assertFalse(broker.containsKey("3:u1:s2"), "s2 must be evicted (newest at time of 3rd sign, LIFO)");
        assertTrue(broker.containsKey("3:u1:s3"), "s3 must be active (just signed)");
    }

    @Test
    void maxSessions_no_limit_keeps_all_sessions() {
        var sv = trust.newSignerVerifier(broker); // no maxSessions

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d4", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d5", BasicConfigurer.builder().groupId("u1").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("3:u1:");
        assertEquals(5, activeKeys.size(), "All 5 sessions must be retained when no maxSessions limit is set");
    }

    @Test
    void maxSessions_after_revoke_does_not_evict_unnecessarily() {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // After revocation, count = 1 (below maxSessions)
        sv.revoke("u1", "s1");

        // This 3rd sign should NOT trigger eviction (count < maxSessions)
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        assertTrue(broker.containsKey("3:u1:s2"), "s2 must still be active");
        assertTrue(broker.containsKey("3:u1:s3"), "s3 must be active");
        assertFalse(broker.containsKey("3:u1:s1"), "s1 must remain revoked");
    }

    @Test
    void maxSessions_groups_are_isolated() {
        var sv = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u2").sequenceId("s2").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u2").sequenceId("s3").validity(600).build());

        // u1 should still have its 1 session
        assertTrue(broker.containsKey("3:u1:s1"), "u1:s1 must not be affected by u2 eviction");
        // u2 should have exactly 1 session (s3 evicted s2)
        long normalCount = broker.getKeysByPrefix("3:u2:").stream()
                .filter(k -> !ProtocolV2.isReservedSequence(k)).count();
        assertEquals(1, normalCount, "u2 must have exactly 1 active session");
    }

    @Test
    void maxSessions_evicted_session_cannot_be_verified() throws Exception {
        var sv = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.FIFO);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // s1 should have been evicted when s2 was signed
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t1, s -> s),
                "Evicted session token must not be verifiable");
    }

    // ── Distributed config resolution tests (§4) ─────────────────────────────

    @Test
    void resolveConfig_localOverridesDefault() throws InterruptedException {
        // Default: no limit (maxSessions=-1)
        var sv = trust.newSignerVerifier(broker);

        // Publish a local config with maxSessions=1
        long now = java.time.Instant.now().getEpochSecond();
        var cfgProps = new java.util.LinkedHashMap<String, String>();
        cfgProps.put("ts", String.valueOf(now));
        cfgProps.put("exp", String.valueOf(now + 3600));
        cfgProps.put("max", "1");
        cfgProps.put("pol", "FIFO");
        String configMsg = ProtocolV2.buildMessage("u1", "__CONFIG__", cfgProps);
        broker.send("3:u1:__CONFIG__", configMsg);

        // Sign 2 sessions — config should enforce maxSessions=1
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // s1 should be evicted (FIFO), only s2 remains
        assertFalse(broker.containsKey("3:u1:s1"), "s1 must be evicted per local config");
        assertTrue(broker.containsKey("3:u1:s2"), "s2 must be active");
    }

    @Test
    void resolveConfig_globalFallback() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Publish a global config with maxSessions=1
        long now = java.time.Instant.now().getEpochSecond();
        var cfgProps = new java.util.LinkedHashMap<String, String>();
        cfgProps.put("ts", String.valueOf(now));
        cfgProps.put("exp", String.valueOf(now + 3600));
        cfgProps.put("max", "1");
        cfgProps.put("pol", "FIFO");
        String configMsg = ProtocolV2.buildMessage("__CONFIG__", "__ALL__", cfgProps);
        broker.send("3:__CONFIG__:__ALL__", configMsg);

        // Sign 2 sessions for a group that has NO local config
        sv.sign("d1", BasicConfigurer.builder().groupId("u2").sequenceId("s1").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d2", BasicConfigurer.builder().groupId("u2").sequenceId("s2").validity(600).build());

        // Global config should apply: s1 evicted
        assertFalse(broker.containsKey("3:u2:s1"), "s1 must be evicted per global config");
        assertTrue(broker.containsKey("3:u2:s2"), "s2 must remain active");
    }

    // ── REJECT policy ────────────────────────────────────────────────────────

    @Test
    void reject_policy_throws_when_limit_reached() {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.REJECT);

        sv.sign("d1", BasicConfigurer.builder().groupId("rej").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("rej").sequenceId("s2").validity(600).build());

        var ex = assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("d3", BasicConfigurer.builder().groupId("rej").sequenceId("s3").validity(600).build()),
                "3rd sign must throw when REJECT policy is active and limit is reached");

        assertEquals("rej", ex.getGroupId());
        assertEquals(2, ex.getMaxSessions());
    }

    @Test
    void reject_policy_allows_signing_after_manual_revocation() {
        var sv = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.REJECT);

        sv.sign("d1", BasicConfigurer.builder().groupId("rej2").sequenceId("s1").validity(600).build());

        // Limit reached → 2nd sign throws
        assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("d2", BasicConfigurer.builder().groupId("rej2").sequenceId("s2").validity(600).build()));

        // Manually revoke → slot freed
        sv.revoke("rej2", "s1");

        // Now signing works again
        assertDoesNotThrow(
                () -> sv.sign("d3", BasicConfigurer.builder().groupId("rej2").sequenceId("s3").validity(600).build()),
                "Signing must succeed after manually revoking a session");
    }

    /**
     * Regression test for the revoke() async race condition.
     *
     * <p>This test proves that {@link GenericSignerVerifier#revoke(String, String)} is
     * fully synchronous: a {@code sign()} call issued <em>immediately</em> after
     * {@code revoke()} — with zero sleep — must succeed because the broker deletion
     * is guaranteed to be committed before {@code enforceSessionLimit} runs.</p>
     */
    @Test
    void revoke_then_sign_immediately_no_race_condition() {
        // maxSessions=1, REJECT: strictly one session at a time
        var sv = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.REJECT);
        String groupId = "race-condition-regression";

        // ── Step 1: fill the slot ────────────────────────────────────────────
        String oldToken = sv.sign("data",
                BasicConfigurer.builder()
                        .groupId(groupId)
                        .sequenceId("old-session")
                        .validity(3600)
                        .build());

        // ── Step 2: confirm slot is full ─────────────────────────────────────
        assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("data",
                        BasicConfigurer.builder()
                                .groupId(groupId)
                                .sequenceId("should-fail")
                                .validity(3600)
                                .build()),
                "Slot must be full before revoke");

        // ── Step 3: revoke + immediate sign with NO Thread.sleep() ───────────
        sv.revoke(groupId, "old-session");

        String newToken = assertDoesNotThrow(
                () -> sv.sign("data",
                        BasicConfigurer.builder()
                                .groupId(groupId)
                                .sequenceId("new-session")
                                .validity(3600)
                                .build()),
                "sign() immediately after revoke() must not throw — no race condition");

        // ── Step 4: old token is gone, new token is verifiable ───────────────
        assertThrows(Exception.class,
                () -> sv.verify(oldToken, s -> s),
                "Revoked token must not be verifiable");

        assertDoesNotThrow(
                () -> sv.verify(newToken, s -> s),
                "New token issued after revoke must be verifiable");
    }

    @Test
    void reject_policy_does_not_evict_existing_sessions() {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.REJECT);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("rej3").sequenceId("s1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("rej3").sequenceId("s2").validity(600).build());

        // 3rd sign rejected
        assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("d3", BasicConfigurer.builder().groupId("rej3").sequenceId("s3").validity(600).build()));

        // Both original sessions must still be verifiable
        assertDoesNotThrow(() -> sv.verify(t1, s -> s), "Session s1 must survive REJECT");
        assertDoesNotThrow(() -> sv.verify(t2, s -> s), "Session s2 must survive REJECT");
    }

    @Test
    void reject_policy_allows_signing_when_all_sessions_expired() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.REJECT);

        // Sign with TTL=1 second
        sv.sign("d1", BasicConfigurer.builder().groupId("rej-exp").sequenceId("s1").validity(1).build());

        // Wait for expiration
        Thread.sleep(2500);

        // The expired session should NOT count against the limit
        assertDoesNotThrow(
                () -> sv.sign("d2", BasicConfigurer.builder().groupId("rej-exp").sequenceId("s2").validity(600).build()),
                "Signing must succeed when existing sessions have expired, even with REJECT policy");
    }

    @Test
    void expired_sessions_are_garbage_collected_on_next_sign() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, GenericSignerVerifier.EvictionPolicy.FIFO);

        // Sign 2 sessions with very short TTL
        sv.sign("d1", BasicConfigurer.builder().groupId("gc").sequenceId("s1").validity(1).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("gc").sequenceId("s2").validity(1).build());

        // Both entries exist in broker
        assertTrue(broker.containsKey("3:gc:s1"), "s1 must exist before expiry");
        assertTrue(broker.containsKey("3:gc:s2"), "s2 must exist before expiry");

        // Wait for expiration
        Thread.sleep(2500);

        // Sign a new session — this triggers enforceSessionLimit which should GC expired entries
        sv.sign("d3", BasicConfigurer.builder().groupId("gc").sequenceId("s3").validity(600).build());

        // Expired entries must be physically deleted from the broker (lazy GC)
        assertFalse(broker.containsKey("3:gc:s1"), "Expired s1 must be GC'd after next sign");
        assertFalse(broker.containsKey("3:gc:s2"), "Expired s2 must be GC'd after next sign");
        assertTrue(broker.containsKey("3:gc:s3"), "New s3 must exist");
    }
}
