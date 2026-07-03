package io.github.cyfko.veridot.trustroots.tad.server;

import io.github.cyfko.veridot.trustroots.tad.server.controller.TadController;
import io.github.cyfko.veridot.trustroots.tad.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.tad.server.raft.TadStateMachine;
import io.github.cyfko.veridot.trustroots.tad.server.store.TadRocksDbStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/**
 * Point d'entrée pour le serveur TAD répliqué.
 */
@SpringBootApplication
public class TadServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TadServerApplication.class, args);
    }

    @Bean
    public TadController tadController(RaftServerEngine raftEngine, TadStateMachine stateMachine, TadRocksDbStore store) {
        return new TadController(raftEngine, stateMachine, store);
    }

    @Bean(destroyMethod = "close")
    public TadRocksDbStore tadRocksDbStore(
            @Value("${veridot.tad-server.storage.directory:/tmp/veridot-tad}") String directory) {
        return new TadRocksDbStore(directory);
    }

    @Bean
    public TadStateMachine tadStateMachine(TadRocksDbStore store) {
        return new TadStateMachine(store);
    }

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
