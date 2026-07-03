package io.github.cyfko.veridot.trustroots.tad.server;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.tad.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.tad.server.raft.TadStateMachine;
import io.github.cyfko.veridot.trustroots.tad.server.store.TadRocksDbStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration de résilience et de réplication sur un cluster TAD à 3 nœuds.
 */
class TadClusterResilienceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private TadRocksDbStore store1;
    private TadRocksDbStore store2;
    private TadRocksDbStore store3;

    private TadStateMachine fsm1;
    private TadStateMachine fsm2;
    private TadStateMachine fsm3;

    private RaftServerEngine engine1;
    private RaftServerEngine engine2;
    private RaftServerEngine engine3;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        String peers = "127.0.0.1:19451,127.0.0.1:19452,127.0.0.1:19453";

        // Node 1
        store1 = new TadRocksDbStore(tempDir.resolve("node1").toString());
        fsm1 = new TadStateMachine(store1);
        engine1 = new RaftServerEngine("127.0.0.1:19451", "veridot-tad-test", peers, tempDir.resolve("node1/raft").toString(), fsm1);

        // Node 2
        store2 = new TadRocksDbStore(tempDir.resolve("node2").toString());
        fsm2 = new TadStateMachine(store2);
        engine2 = new RaftServerEngine("127.0.0.1:19452", "veridot-tad-test", peers, tempDir.resolve("node2/raft").toString(), fsm2);

        // Node 3
        store3 = new TadRocksDbStore(tempDir.resolve("node3").toString());
        fsm3 = new TadStateMachine(store3);
        engine3 = new RaftServerEngine("127.0.0.1:19453", "veridot-tad-test", peers, tempDir.resolve("node3/raft").toString(), fsm3);

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

    @Test
    void testReplicationAndLeaderFailure() throws Exception {
        // 1. Attente de l'élection d'un leader
        Node leaderNode = null;
        TadRocksDbStore leaderStore = null;
        
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

        // 2. Publication d'une clé initiale au leader
        String subject1 = "service-alpha";
        Instant now = Instant.now();
        TrustEntry entry1 = new TrustEntry(
            1, subject1, "key-alpha", KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(24)),
            1, "finger-alpha", "sig-alpha", now, false, Collections.emptyMap()
        );

        CompletableFuture<Status> future1 = new CompletableFuture<>();
        Task task1 = new Task();
        task1.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(entry1)));
        task1.setDone(future1::complete);
        
        leaderNode.apply(task1);
        Status status1 = future1.get(5, TimeUnit.SECONDS);
        assertTrue(status1.isOk(), "La transaction sur le leader doit réussir");

        // Laisser le temps à la réplication de s'effectuer sur les followers
        Thread.sleep(1500);

        // Validation de la réplication sur les 3 stores locaux RocksDB
        Optional<TrustEntry> r1 = store1.get(subject1);
        Optional<TrustEntry> r2 = store2.get(subject1);
        Optional<TrustEntry> r3 = store3.get(subject1);

        assertTrue(r1.isPresent());
        assertTrue(r2.isPresent());
        assertTrue(r3.isPresent());
        assertEquals("key-alpha", r1.get().publicKeyEncoded());
        assertEquals("key-alpha", r2.get().publicKeyEncoded());
        assertEquals("key-alpha", r3.get().publicKeyEncoded());

        // 3. Arrêt du nœud 2 (un follower) pour tester le quorum
        engine2.stop();
        Thread.sleep(1000);

        // Publication d'une deuxième clé au leader
        String subject2 = "service-beta";
        TrustEntry entry2 = new TrustEntry(
            1, subject2, "key-beta", KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(24)),
            1, "finger-beta", "sig-beta", now, false, Collections.emptyMap()
        );

        CompletableFuture<Status> future2 = new CompletableFuture<>();
        Task task2 = new Task();
        task2.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(entry2)));
        task2.setDone(future2::complete);

        leaderNode.apply(task2);
        Status status2 = future2.get(5, TimeUnit.SECONDS);
        assertTrue(status2.isOk(), "La transaction doit réussir car le quorum majoritaire (2/3) est maintenu");

        Thread.sleep(1500);

        // Le nœud 1 et le nœud 3 doivent avoir la clé, mais pas le nœud 2 arrêté
        assertTrue(store1.get(subject2).isPresent());
        assertTrue(store3.get(subject2).isPresent());

        // Redémarrer le nœud 2 pour que le cluster ait à nouveau une majorité lors de l'arrêt du leader
        engine2.start();
        Thread.sleep(3000); // Laisser le temps au nœud 2 de rattraper et réintégrer le cluster

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
        String subject3 = "service-gamma";
        TrustEntry entry3 = new TrustEntry(
            1, subject3, "key-gamma", KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(24)),
            1, "finger-gamma", "sig-gamma", now, false, Collections.emptyMap()
        );

        CompletableFuture<Status> future3 = new CompletableFuture<>();
        Task task3 = new Task();
        task3.setData(ByteBuffer.wrap(objectMapper.writeValueAsBytes(entry3)));
        task3.setDone(future3::complete);

        newLeaderNode.apply(task3);
        Status status3 = future3.get(5, TimeUnit.SECONDS);
        assertTrue(status3.isOk(), "L'écriture sur le nouveau leader doit réussir");
    }
}
