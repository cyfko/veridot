package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RevocationDoesNotTouchKeyEpochTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier sv;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        sv = TestTrustSetup.create().newSignerVerifier(broker);
    }

    @Test
    void revoke_session_leaves_key_epoch_envelope_intact_but_verify_still_fails() throws Exception {
        var cfg = BasicConfigurer.builder()
                .groupId("group1")
                .sequenceId("seq1")
                .validity(3600)
                .distribution(DistributionMode.DIRECT)
                .build();
        String token = sv.sign("payload", cfg);

        EntryId keyEpochId = new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "seq1");
        byte[] before = broker.get(keyEpochId.storageKey());
        assertNotNull(before, "Key Epoch must exist before revocation");

        sv.revoke("group1", "seq1");

        // Verify Key Epoch is still in broker
        byte[] after = broker.get(keyEpochId.storageKey());
        assertNotNull(after, "Key Epoch must NOT be deleted after revocation (V4-02)");
        assertArrayEquals(before, after, "Key Epoch envelope must remain completely unchanged");

        // Verify that token verification still fails (due to LIVENESS = REVOKED)
        assertThrows(BrokerExtractionException.class, () -> sv.verify(token, s -> s));
    }

    @Test
    void revoke_group_leaves_all_key_epoch_envelopes_intact_but_all_verify_fail() throws Exception {
        var cfg1 = BasicConfigurer.builder()
                .groupId("group1")
                .sequenceId("seq1")
                .validity(3600)
                .distribution(DistributionMode.DIRECT)
                .build();
        String token1 = sv.sign("payload1", cfg1);

        var cfg2 = BasicConfigurer.builder()
                .groupId("group1")
                .sequenceId("seq2")
                .validity(3600)
                .distribution(DistributionMode.DIRECT)
                .build();
        String token2 = sv.sign("payload2", cfg2);

        EntryId keyEpoch1 = new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "seq1");
        EntryId keyEpoch2 = new EntryId(Scope.group("group1"), EntryType.KEY_EPOCH, "seq2");

        byte[] before1 = broker.get(keyEpoch1.storageKey());
        byte[] before2 = broker.get(keyEpoch2.storageKey());
        assertNotNull(before1);
        assertNotNull(before2);

        sv.revoke("group1", null);

        // Verify Key Epochs are still in broker
        byte[] after1 = broker.get(keyEpoch1.storageKey());
        byte[] after2 = broker.get(keyEpoch2.storageKey());
        assertNotNull(after1, "Key Epoch 1 must NOT be deleted after group revocation (V4-02)");
        assertNotNull(after2, "Key Epoch 2 must NOT be deleted after group revocation (V4-02)");

        assertArrayEquals(before1, after1);
        assertArrayEquals(before2, after2);

        // Verify verification fails for both
        assertThrows(BrokerExtractionException.class, () -> sv.verify(token1, s -> s));
        assertThrows(BrokerExtractionException.class, () -> sv.verify(token2, s -> s));
    }
}
