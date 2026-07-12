package io.github.cyfko.veridot.trustroots.taas.server;

import io.github.cyfko.veridot.trustroots.taas.server.attestation.AttestationService;

import io.github.cyfko.veridot.trustroots.taas.server.controller.TaasController;
import io.github.cyfko.veridot.trustroots.taas.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.taas.server.raft.TaasStateMachine;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Point d'entrée principal pour le démarrage du serveur d'autorité de confiance TAAS (Trust Authority as a Service) répliqué (V5).
 * <p>
 * Cette application Spring Boot orchestre le cycle de vie de la base de données RocksDB
 * et du moteur de consensus Raft SOFAJRaft. Elle déclare explicitement tous les Beans
 * pour contourner les limitations de classpath scanning de Spring 6 sous Java 25.
 */
@SpringBootApplication
public class TaasServerApplication {

    /**
     * Constructeur par défaut.
     */
    public TaasServerApplication() {
    }

    /**
     * Démarre l'application Spring Boot du serveur TAAS.
     *
     * @param args Arguments de la ligne de commande.
     */
    public static void main(String[] args) {
        SpringApplication.run(TaasServerApplication.class, args);
    }

    /**
     * Enregistre le Bean pour le vérificateur d'attestation.
     * Defaults to NoneAttestor; in production, a more specific verifier should be provided.
     *
     * @return L'instance {@link AttestationService}.
     */
    @Bean
    public AttestationService attestationVerifier() {
        return new AttestationService();
    }

    /**
     * Enregistre le Bean pour le contrôleur REST d'API V5.
     *
     * @param raftEngine           Moteur Raft.
     * @param stateMachine         Machine d'état Raft.
     * @param store                Stockage persistant RocksDB.
     * @param attestationVerifier  Vérificateur d'attestation.
     * @param digestService        Service de digest pour la transparence d'état (§18.2).
     * @return L'instance {@link TaasController} du contrôleur REST.
     */
    @Bean
    public TaasController taasController(RaftServerEngine raftEngine, TaasStateMachine stateMachine,
                                          TaasRocksDbStore store, AttestationService attestationVerifier,
                                          TaasDigestService digestService) {
        return new TaasController(raftEngine, stateMachine, store, attestationVerifier, digestService);
    }

    /**
     * Enregistre le Bean pour le service de digest State Transparency (§18.2).
     *
     * <p>In production, the signing key should be loaded from a persistent key store.
     * This default uses a generated Ed25519 key pair.
     *
     * @param store  Stockage RocksDB local.
     * @param nodeId Identifiant du nœud TAAS.
     * @return L'instance {@link TaasDigestService}.
     */
    @Bean
    public TaasDigestService taasDigestService(
            TaasRocksDbStore store,
            @Value("${veridot.taas-server.node-id}") String nodeId) {
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new TaasDigestService(store, kp.getPrivate(), nodeId);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available for TAAS digest signing", e);
        }
    }

    /**
     * Enregistre le Bean pour le stockage RocksDB du serveur.
     *
     * @param directory Répertoire physique de base de données configurable via {@code veridot.taas-server.storage.directory}.
     * @return L'instance {@link TaasRocksDbStore} du stockage persistant.
     */
    @Bean(destroyMethod = "close")
    public TaasRocksDbStore taasRocksDbStore(
            @Value("${veridot.taas-server.storage.directory:/tmp/veridot-taas}") String directory) {
        return new TaasRocksDbStore(directory);
    }

    /**
     * Enregistre le Bean pour la machine d'état finie Raft V5.
     *
     * @param store                Stockage RocksDB local.
     * @param attestationVerifier  Vérificateur d'attestation.
     * @return L'instance {@link TaasStateMachine} de la machine d'état.
     */
    @Bean
    public TaasStateMachine taasStateMachine(TaasRocksDbStore store, AttestationService attestationVerifier) {
        return new TaasStateMachine(store, attestationVerifier);
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
            @Value("${veridot.taas-server.node-id}") String nodeId,
            @Value("${veridot.taas-server.raft-group-id:veridot-taas}") String groupId,
            @Value("${veridot.taas-server.initial-peers}") String initialPeers,
            @Value("${veridot.taas-server.storage.directory:/tmp/veridot-taas}") String directory,
            TaasStateMachine stateMachine) {
        
        return new RaftServerEngine(nodeId, groupId, initialPeers, directory + "/raft", stateMachine);
    }
}
