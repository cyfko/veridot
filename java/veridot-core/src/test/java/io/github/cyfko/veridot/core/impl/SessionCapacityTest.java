package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.ConfigScope;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

class SessionCapacityTest {

    private InMemoryBroker broker;
    private TestTrustSetup trust;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        trust = TestTrustSetup.create();
    }

    private boolean hasKeyEpoch(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.KEY_EPOCH, sessionKey);
        byte[] bytes = broker.get(id.storageKey());
        if (bytes == null) return false;
        try {
            Envelope env = Envelope.parse(bytes);
            return env.payload != null && env.payload.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasActiveLiveness(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.LIVENESS, sessionKey);
        byte[] bytes = broker.get(id.storageKey());
        if (bytes == null) return false;
        try {
            Envelope env = Envelope.parse(bytes);
            LivenessPayload payload = LivenessPayload.decode(env.payload);
            return payload.isActive();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void maxSessions_fifo_evicts_oldest() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100); // wait > 1s so timestamps differ at second precision
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        Scope scope = Scope.group("u1");
        var activeEntries = broker.snapshot(scope).stream()
                .filter(e -> {
                    Envelope env = Envelope.parse(e.envelopeBytes());
                    return env.entryType == EntryType.KEY_EPOCH && env.payload != null && env.payload.length > 0;
                })
                .toList();

        assertEquals(2, activeEntries.size(), "Only 2 sessions must remain after FIFO eviction");
        assertFalse(hasKeyEpoch("u1", "s1"), "s1 must be evicted (oldest, FIFO)");
        assertTrue(hasKeyEpoch("u1", "s2"), "s2 must still be active");
        assertTrue(hasKeyEpoch("u1", "s3"), "s3 must be active (newest)");
    }

    @Test
    void maxSessions_lifo_evicts_newest() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.LIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        Scope scope = Scope.group("u1");
        var activeEntries = broker.snapshot(scope).stream()
                .filter(e -> {
                    Envelope env = Envelope.parse(e.envelopeBytes());
                    return env.entryType == EntryType.KEY_EPOCH && env.payload != null && env.payload.length > 0;
                })
                .toList();

        assertEquals(2, activeEntries.size(), "Only 2 sessions must remain after LIFO eviction");
        assertTrue(hasKeyEpoch("u1", "s1"), "s1 must still be active (oldest)");
        assertFalse(hasKeyEpoch("u1", "s2"), "s2 must be evicted (newest at time of 3rd sign, LIFO)");
        assertTrue(hasKeyEpoch("u1", "s3"), "s3 must be active (just signed)");
    }

    @Test
    void maxSessions_no_limit_keeps_all_sessions() {
        var sv = trust.newSignerVerifier(broker); // no maxSessions

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());
        sv.sign("d4", BasicConfigurer.builder().groupId("u1").sequenceId("s4").validity(600).build());
        sv.sign("d5", BasicConfigurer.builder().groupId("u1").sequenceId("s5").validity(600).build());

        Scope scope = Scope.group("u1");
        var activeEntries = broker.snapshot(scope).stream()
                .filter(e -> {
                    Envelope env = Envelope.parse(e.envelopeBytes());
                    return env.entryType == EntryType.KEY_EPOCH && env.payload != null && env.payload.length > 0;
                })
                .toList();

        assertEquals(5, activeEntries.size(), "All 5 sessions must be retained when no maxSessions limit is set");
    }

    @Test
    void maxSessions_after_revoke_does_not_evict_unnecessarily() {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // After revocation, count = 1 (below maxSessions)
        sv.revoke("u1", "s1");

        // This 3rd sign should NOT trigger eviction (count < maxSessions)
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        assertTrue(hasKeyEpoch("u1", "s2"), "s2 must still be active");
        assertTrue(hasKeyEpoch("u1", "s3"), "s3 must be active");
        assertFalse(hasActiveLiveness("u1", "s1"), "s1 must remain revoked");
    }

    @Test
    void maxSessions_groups_are_isolated() {
        var sv = trust.newSignerVerifier(broker, 1, EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u2").sequenceId("s2").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u2").sequenceId("s3").validity(600).build());

        // u1 should still have its 1 session
        assertTrue(hasKeyEpoch("u1", "s1"), "u1:s1 must not be affected by u2 eviction");
        
        // u2 should have exactly 1 session (s3 evicted s2)
        Scope scope = Scope.group("u2");
        long u2Count = broker.snapshot(scope).stream()
                .filter(e -> {
                    Envelope env = Envelope.parse(e.envelopeBytes());
                    return env.entryType == EntryType.KEY_EPOCH && env.payload != null && env.payload.length > 0;
                })
                .count();
        assertEquals(1, u2Count, "u2 must have exactly 1 active session");
    }

    @Test
    void maxSessions_evicted_session_cannot_be_verified() throws Exception {
        var sv = trust.newSignerVerifier(broker, 1, EvictionPolicy.FIFO);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // s1 should have been evicted when s2 was signed
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t1, s -> s),
                "Evicted session token must not be verifiable");
    }

    @Test
    void resolveConfig_localOverridesDefault() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Publish a local config with maxSessions=1
        sv.publishConfig(ConfigScope.LOCAL, "u1", 1, EvictionPolicy.FIFO, -1, 3600);

        // Sign 2 sessions — config should enforce maxSessions=1
        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // s1 should be evicted (FIFO), only s2 remains
        assertFalse(hasKeyEpoch("u1", "s1"), "s1 must be evicted per local config");
        assertTrue(hasKeyEpoch("u1", "s2"), "s2 must be active");
    }

    @Test
    void resolveConfig_globalFallback() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Publish a global config with maxSessions=1
        sv.publishConfig(ConfigScope.GLOBAL, null, 1, EvictionPolicy.FIFO, -1, 3600);

        // Sign 2 sessions for a group that has NO local config
        sv.sign("d1", BasicConfigurer.builder().groupId("u2").sequenceId("s1").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d2", BasicConfigurer.builder().groupId("u2").sequenceId("s2").validity(600).build());

        // Global config should apply: s1 evicted
        assertFalse(hasKeyEpoch("u2", "s1"), "s1 must be evicted per global config");
        assertTrue(hasKeyEpoch("u2", "s2"), "s2 must remain active");
    }

    @Test
    void reject_policy_throws_when_limit_reached() {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.REJECT);

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
        var sv = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT);

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

    @Test
    void revoke_then_sign_immediately_no_race_condition() {
        var sv = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT);
        String groupId = "race-condition-regression";

        String oldToken = sv.sign("data",
                BasicConfigurer.builder()
                        .groupId(groupId)
                        .sequenceId("old-session")
                        .validity(3600)
                        .build());

        assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("data",
                        BasicConfigurer.builder()
                                .groupId(groupId)
                                .sequenceId("should-fail")
                                .validity(3600)
                                .build()),
                "Slot must be full before revoke");

        sv.revoke(groupId, "old-session");

        String newToken = assertDoesNotThrow(
                () -> sv.sign("data",
                        BasicConfigurer.builder()
                                .groupId(groupId)
                                .sequenceId("new-session")
                                .validity(3600)
                                .build()),
                "sign() immediately after revoke() must not throw — no race condition");

        assertThrows(Exception.class,
                () -> sv.verify(oldToken, s -> s),
                "Revoked token must not be verifiable");

        assertDoesNotThrow(
                () -> sv.verify(newToken, s -> s),
                "New token issued after revoke must be verifiable");
    }

    @Test
    void reject_policy_does_not_evict_existing_sessions() {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.REJECT);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("rej3").sequenceId("s1").validity(600).build());
        String t2 = sv.sign("d2", BasicConfigurer.builder().groupId("rej3").sequenceId("s2").validity(600).build());

        assertThrows(SessionCapacityExceededException.class,
                () -> sv.sign("d3", BasicConfigurer.builder().groupId("rej3").sequenceId("s3").validity(600).build()));

        assertDoesNotThrow(() -> sv.verify(t1, s -> s), "Session s1 must survive REJECT");
        assertDoesNotThrow(() -> sv.verify(t2, s -> s), "Session s2 must survive REJECT");
    }

    @Test
    void reject_policy_allows_signing_when_all_sessions_expired() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT);

        sv.sign("d1", BasicConfigurer.builder().groupId("rej-exp").sequenceId("s1").validity(1).build());

        Thread.sleep(2500);

        assertDoesNotThrow(
                () -> sv.sign("d2", BasicConfigurer.builder().groupId("rej-exp").sequenceId("s2").validity(600).build()),
                "Signing must succeed when existing sessions have expired, even with REJECT policy");
    }

    @Test
    void expired_sessions_are_garbage_collected_on_next_sign() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker, 2, EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("gc").sequenceId("s1").validity(1).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("gc").sequenceId("s2").validity(1).build());

        assertTrue(hasKeyEpoch("gc", "s1"), "s1 must exist before expiry");
        assertTrue(hasKeyEpoch("gc", "s2"), "s2 must exist before expiry");

        Thread.sleep(2500);

        sv.sign("d3", BasicConfigurer.builder().groupId("gc").sequenceId("s3").validity(600).build());

        assertFalse(hasKeyEpoch("gc", "s1"), "Expired s1 must be GC'd after next sign");
        assertFalse(hasKeyEpoch("gc", "s2"), "Expired s2 must be GC'd after next sign");
        assertTrue(hasKeyEpoch("gc", "s3"), "New s3 must exist");
    }
}
