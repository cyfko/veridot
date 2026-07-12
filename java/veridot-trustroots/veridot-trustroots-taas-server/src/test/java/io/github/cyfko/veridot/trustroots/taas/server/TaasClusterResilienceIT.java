package io.github.cyfko.veridot.trustroots.taas.server;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.taas.server.attestation.NoneAttestor;
import io.github.cyfko.veridot.trustroots.taas.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.taas.server.raft.TaasStateMachine;
import io.github.cyfko.veridot.trustroots.taas.server.raft.TaasStateMachine.TaasProposal;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration de résilience et de réplication sur un cluster TAAS à 3 nœuds.
 */
public class TaasClusterResilienceIT {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TaasRocksDbStore store1;
    private TaasRocksDbStore store2;
    private TaasRocksDbStore store3;

    private TaasStateMachine fsm1;
    private TaasStateMachine fsm2;
    private TaasStateMachine fsm3;

    private RaftServerEngine engine1;
    private RaftServerEngine engine2;
    private RaftServerEngine engine3;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        String peers = "127.0.0.1:19451,127.0.0.1:19452,127.0.0.1:19453";
        NoneAttestor attestor = new NoneAttestor(true);

        // Node 1
        store1 = new TaasRocksDbStore(tempDir.resolve("node1").toString());
        fsm1 = new TaasStateMachine(store1, attestor);
        engine1 = new RaftServerEngine("127.0.0.1:19451", "veridot-taas-test", peers, tempDir.resolve("node1/raft").toString(), fsm1);

        // Node 2
        store2 = new TaasRocksDbStore(tempDir.resolve("node2").toString());
        fsm2 = new TaasStateMachine(store2, attestor);
        engine2 = new RaftServerEngine("127.0.0.1:19452", "veridot-taas-test", peers, tempDir.resolve("node2/raft").toString(), fsm2);

        // Node 3
        store3 = new TaasRocksDbStore(tempDir.resolve("node3").toString());
        fsm3 = new TaasStateMachine(store3, attestor);
        engine3 = new RaftServerEngine("127.0.0.1:19453", "veridot-taas-test", peers, tempDir.resolve("node3/raft").toString(), fsm3);

        // Démarrage des instances du cluster
        engine1.start();
        engine2.start();
        engine3.start();
    }

    @AfterEach
    void tearDown() {
        if (engine1 != null) engine1.stop();
        if (engine2 != null) engine2.stop();
        if (engine3 != null) engine3.stop();

        if (store1 != null) store1.close();
        if (store2 != null) store2.close();
        if (store3 != null) store3.close();
    }

    private TrustEntry createTestEntry(String subject, String key, String fingerprint, String sig) {
        Instant now = Instant.now();
        return TrustEntry.builder()
            .subject(subject)
            .publicKeyEncoded(key)
            .algorithm(KeyAlgorithm.ED25519)
            .notBefore(now.minus(Duration.ofHours(1)))
            .notAfter(now.plus(Duration.ofHours(24)))
            .version(1)
            .fingerprint(fingerprint)
            .issuerSignature(sig)
            .publishedAt(now)
            .isRoot(false)
            .isInstanceScoped(false)
            .attestationPlugin("none")
            .build();
    }

    @Test
    void testReplicationAndLeaderFailure() throws Exception {
        // 1. Attente de l'élection d'un leader
        Node leaderNode = null;
        TaasRocksDbStore leaderStore = null;
        
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1000);
            if (fsm1.isLeader()) {
                leaderNode = engine1.getNode();
                leaderStore = store1;
                break;
            } else if (fsm2.isLeader()) {
                leaderNode = engine2.getNode();
                leaderStore = store2;
                break;
            } else if (fsm3.isLeader()) {
                leaderNode = engine3.getNode();
                leaderStore = store3;
                break;
            }
        }

        assertNotNull(leaderNode, "Un leader doit être élu dans les 15 secondes");

        // 2. Publication d'une clé initiale au leader (V5: wrapping in TaasProposal)
        TrustEntry entry1 = createTestEntry("service-alpha", "key-alpha", "finger-alpha", "sig-alpha");
        TaasProposal proposal1 = new TaasProposal(entry1, "test-proof-1");

        CompletableFuture<Status> future1 = new CompletableFuture<>();
        Task task1 = new Task();
        task1.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(proposal1)));
        task1.setDone(future1::complete);
        
        leaderNode.apply(task1);
        Status status1 = future1.get(5, TimeUnit.SECONDS);
        assertTrue(status1.isOk(), "La transaction sur le leader doit réussir");

        // Laisser le temps à la réplication de s'effectuer sur les followers
        Thread.sleep(1500);

        // Validation de la réplication sur les 3 stores locaux RocksDB
        Optional<TrustEntry> r1 = store1.get("service-alpha");
        Optional<TrustEntry> r2 = store2.get("service-alpha");
        Optional<TrustEntry> r3 = store3.get("service-alpha");

        assertTrue(r1.isPresent());
        assertTrue(r2.isPresent());
        assertTrue(r3.isPresent());
        assertEquals("key-alpha", r1.get().publicKeyEncoded());
        assertEquals("key-alpha", r2.get().publicKeyEncoded());
        assertEquals("key-alpha", r3.get().publicKeyEncoded());

        // 3. Arrêt d'un nœud follower pour tester le quorum
        RaftServerEngine stoppedFollowerEngine;
        TaasRocksDbStore stoppedFollowerStore;
        if (leaderStore == store1) {
            stoppedFollowerEngine = engine2;
            stoppedFollowerStore = store2;
        } else if (leaderStore == store2) {
            stoppedFollowerEngine = engine3;
            stoppedFollowerStore = store3;
        } else {
            stoppedFollowerEngine = engine1;
            stoppedFollowerStore = store1;
        }
        stoppedFollowerEngine.stop();
        Thread.sleep(1000);

        // Publication d'une deuxième clé au leader
        TrustEntry entry2 = createTestEntry("service-beta", "key-beta", "finger-beta", "sig-beta");
        TaasProposal proposal2 = new TaasProposal(entry2, "test-proof-2");

        CompletableFuture<Status> future2 = new CompletableFuture<>();
        Task task2 = new Task();
        task2.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(proposal2)));
        task2.setDone(future2::complete);

        leaderNode.apply(task2);
        Status status2 = future2.get(5, TimeUnit.SECONDS);
        assertTrue(status2.isOk(), "La transaction doit réussir car le quorum majoritaire (2/3) est maintenu");

        Thread.sleep(1500);

        // Le leader et le follower restant doivent avoir la clé, mais pas le follower arrêté
        for (var store : List.of(store1, store2, store3)) {
            if (store == stoppedFollowerStore) {
                assertFalse(store.get("service-beta").isPresent(), "Le follower arrêté ne doit pas avoir reçu la clé");
            } else {
                assertTrue(store.get("service-beta").isPresent(), "Les nœuds actifs doivent avoir reçu la clé");
            }
        }

        // Redémarrer le follower pour que le cluster ait à nouveau une majorité lors de l'arrêt du leader
        stoppedFollowerEngine.start();
        Thread.sleep(3000); // Laisser le temps au nœud de rattraper et réintégrer le cluster

        // 4. Arrêt du leader pour déclencher une élection
        if (leaderNode == engine1.getNode()) {
            engine1.stop();
        } else if (leaderNode == engine2.getNode()) {
            engine2.stop();
        } else {
            engine3.stop();
        }

        // Attente du timeout de vote et de la nouvelle élection
        Thread.sleep(5000);

        // Détection du nouveau leader parmi les survivants
        Node newLeaderNode = null;
        if (fsm1.isLeader() && leaderNode != engine1.getNode()) {
            newLeaderNode = engine1.getNode();
        } else if (fsm2.isLeader() && leaderNode != engine2.getNode()) {
            newLeaderNode = engine2.getNode();
        } else if (fsm3.isLeader() && leaderNode != engine3.getNode()) {
            newLeaderNode = engine3.getNode();
        }

        assertNotNull(newLeaderNode, "Un nouveau leader doit être élu parmi les survivants");

        // Publication d'une troisième clé sur le nouveau leader
        TrustEntry entry3 = createTestEntry("service-gamma", "key-gamma", "finger-gamma", "sig-gamma");
        TaasProposal proposal3 = new TaasProposal(entry3, "test-proof-3");

        CompletableFuture<Status> future3 = new CompletableFuture<>();
        Task task3 = new Task();
        task3.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(proposal3)));
        task3.setDone(future3::complete);

        newLeaderNode.apply(task3);
        Status status3 = future3.get(5, TimeUnit.SECONDS);
        assertTrue(status3.isOk(), "L'écriture sur le nouveau leader doit réussir");
    }
}
