package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionCapacityTest {

    private InMemoryMetadataBroker broker;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
    }

    @Test
    void maxSessions_fifo_evicts_oldest() throws InterruptedException {
        var sv = new GenericSignerVerifier(broker, "salt", 2, GenericSignerVerifier.EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100); // wait > 1s so timestamps differ at second precision
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("2:u1:");
        assertEquals(2, activeKeys.size(), "Only 2 sessions must remain after FIFO eviction");
        assertFalse(broker.containsKey("2:u1:s1"), "s1 must be evicted (oldest, FIFO)");
        assertTrue(broker.containsKey("2:u1:s2"), "s2 must still be active");
        assertTrue(broker.containsKey("2:u1:s3"), "s3 must be active (newest)");
    }

    @Test
    void maxSessions_lifo_evicts_newest() throws InterruptedException {
        var sv = new GenericSignerVerifier(broker, "salt", 2, GenericSignerVerifier.EvictionPolicy.LIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        Thread.sleep(1100); // wait > 1s so timestamps differ at second precision
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());
        Thread.sleep(1100);
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("2:u1:");
        assertEquals(2, activeKeys.size(), "Only 2 sessions must remain after LIFO eviction");
        assertTrue(broker.containsKey("2:u1:s1"), "s1 must still be active (oldest)");
        assertFalse(broker.containsKey("2:u1:s2"), "s2 must be evicted (newest at time of 3rd sign, LIFO)");
        assertTrue(broker.containsKey("2:u1:s3"), "s3 must be active (just signed)");
    }

    @Test
    void maxSessions_no_limit_keeps_all_sessions() {
        var sv = new GenericSignerVerifier(broker, "salt"); // no maxSessions

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d4", BasicConfigurer.builder().groupId("u1").validity(600).build());
        sv.sign("d5", BasicConfigurer.builder().groupId("u1").validity(600).build());

        var activeKeys = broker.getKeysByPrefix("2:u1:");
        assertEquals(5, activeKeys.size(), "All 5 sessions must be retained when no maxSessions limit is set");
    }

    @Test
    void maxSessions_after_revoke_does_not_evict_unnecessarily() {
        var sv = new GenericSignerVerifier(broker, "salt", 2, GenericSignerVerifier.EvictionPolicy.FIFO);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // After revocation, count = 1 (below maxSessions)
        sv.revoke(t1);

        // This 3rd sign should NOT trigger eviction (count < maxSessions)
        sv.sign("d3", BasicConfigurer.builder().groupId("u1").sequenceId("s3").validity(600).build());

        assertTrue(broker.containsKey("2:u1:s2"), "s2 must still be active");
        assertTrue(broker.containsKey("2:u1:s3"), "s3 must be active");
        assertFalse(broker.containsKey("2:u1:s1"), "s1 must remain revoked");
    }

    @Test
    void maxSessions_groups_are_isolated() {
        var sv = new GenericSignerVerifier(broker, "salt", 1, GenericSignerVerifier.EvictionPolicy.FIFO);

        sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u2").sequenceId("s2").validity(600).build());
        sv.sign("d3", BasicConfigurer.builder().groupId("u2").sequenceId("s3").validity(600).build());

        // u1 should still have its 1 session
        assertTrue(broker.containsKey("2:u1:s1"), "u1:s1 must not be affected by u2 eviction");
        // u2 should have exactly 1 session (s3 evicted s2)
        assertEquals(1, broker.getKeysByPrefix("2:u2:").size(), "u2 must have exactly 1 active session");
    }

    @Test
    void maxSessions_evicted_session_cannot_be_verified() throws Exception {
        var sv = new GenericSignerVerifier(broker, "salt", 1, GenericSignerVerifier.EvictionPolicy.FIFO);

        String t1 = sv.sign("d1", BasicConfigurer.builder().groupId("u1").sequenceId("s1").validity(600).build());
        sv.sign("d2", BasicConfigurer.builder().groupId("u1").sequenceId("s2").validity(600).build());

        // s1 should have been evicted when s2 was signed
        assertThrows(BrokerExtractionException.class, () -> sv.verify(t1, s -> s),
                "Evicted session token must not be verifiable");
    }
}
