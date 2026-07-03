package io.github.cyfko.veridot.trustroots.tad.server;

import io.github.cyfko.veridot.trustroots.tad.server.controller.TadController;
import io.github.cyfko.veridot.trustroots.tad.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.tad.server.raft.TadStateMachine;
import io.github.cyfko.veridot.trustroots.tad.server.store.TadRocksDbStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Point d'entrée principal pour le démarrage du serveur d'autorité de confiance TAD (Trust Authority Directory) répliqué.
 * <p>
 * Cette application Spring Boot orchestre le cycle de vie de la base de données RocksDB
 * et du moteur de consensus Raft SOFAJRaft. Elle déclare explicitement tous les Beans
 * pour contourner les limitations de classpath scanning de Spring 6 sous Java 25.
 */
@SpringBootApplication
public class TadServerApplication {

    /**
     * Constructeur par défaut.
     */
    public TadServerApplication() {
    }

    /**
     * Démarre l'application Spring Boot du serveur TAD.
     *
     * @param args Arguments de la ligne de commande.
     */
    public static void main(String[] args) {
        SpringApplication.run(TadServerApplication.class, args);
    }

    /**
     * Enregistre le Bean pour le contrôleur REST d'API.
     *
     * @param raftEngine Moteur Raft.
     * @param stateMachine Machine d'état Raft.
     * @param store Stockage persistant RocksDB.
     * @return L'instance {@link TadController} du contrôleur REST.
     */
    @Bean
    public TadController tadController(RaftServerEngine raftEngine, TadStateMachine stateMachine, TadRocksDbStore store) {
        return new TadController(raftEngine, stateMachine, store);
    }

    /**
     * Enregistre le Bean pour le stockage RocksDB du serveur.
     *
     * @param directory Répertoire physique de base de données configurable via {@code veridot.tad-server.storage.directory}.
     * @return L'instance {@link TadRocksDbStore} du stockage persistant.
     */
    @Bean(destroyMethod = "close")
    public TadRocksDbStore tadRocksDbStore(
            @Value("${veridot.tad-server.storage.directory:/tmp/veridot-tad}") String directory) {
        return new TadRocksDbStore(directory);
    }

    /**
     * Enregistre le Bean pour la machine d'état finie Raft.
     *
     * @param store Stockage RocksDB local.
     * @return L'instance {@link TadStateMachine} de la machine d'état.
     */
    @Bean
    public TadStateMachine tadStateMachine(TadRocksDbStore store) {
        return new TadStateMachine(store);
    }

    /**
     * Enregistre le Bean pour le moteur Raft de SOFAJRaft et orchestre son cycle de vie (start/stop).
     *
     * @param nodeId Adresse d'écoute IP:Port de ce nœud (ex: "127.0.0.1:9443").
     * @param groupId Identifiant unique du groupe Raft.
     * @param initialPeers Liste des nœuds initiaux du groupe.
     * @param directory Répertoire de stockage local.
     * @param stateMachine La machine d'état Raft.
     * @return L'instance {@link RaftServerEngine} du moteur Raft.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public RaftServerEngine raftServerEngine(
            @Value("${veridot.tad-server.node-id}") String nodeId,
            @Value("${veridot.tad-server.raft-group-id:veridot-tad}") String groupId,
            @Value("${veridot.tad-server.initial-peers}") String initialPeers,
            @Value("${veridot.tad-server.storage.directory:/tmp/veridot-tad}") String directory,
            TadStateMachine stateMachine) {
        
        return new RaftServerEngine(nodeId, groupId, initialPeers, directory + "/raft", stateMachine);
    }
}
