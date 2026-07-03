package io.github.cyfko.veridot.trustroots.tad.server.raft;

import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.tad.server.store.TadRocksDbStore;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Machine d'état finie (FSM) Raft pour le serveur TAD répliqué.
 * <p>
 * Hérite de {@link StateMachineAdapter} de SOFAJRaft. Elle applique de manière déterministe et séquentielle
 * les écritures de transactions de clés publiques (proposées par les clients) dans la base locale {@link TadRocksDbStore}.
 */
public class TadStateMachine extends StateMachineAdapter {
    
    /** Le stockage RocksDB local associé à ce nœud du cluster. */
    private final TadRocksDbStore store;
    
    /** Sérialiseur Jackson thread-safe. */
    private final ObjectMapper objectMapper;
    
    /** Terme d'élection Raft actuel si ce nœud est le leader actif. Vaut -1 si ce nœud est un follower. */
    private final AtomicLong leaderTerm = new AtomicLong(-1);

    /**
     * Initialise la machine d'état.
     *
     * @param store Le stockage local RocksDB.
     */
    public TadStateMachine(TadRocksDbStore store) {
        this.store = store;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Applique un lot de logs validés et répliqués par consensus Raft sur le stockage RocksDB.
     * <p>
     * C'est ici que la transaction est rendue officielle et définitive au sein du cluster. Si l'opération
     * a été soumise par ce nœud en tant que leader, la fermeture {@code iter.done()} (done callback) est invoquée
     * pour répondre de manière asynchrone au client HTTP appelant.
     *
     * @param iter Itérateur sur le lot d'entrées validées.
     */
    @Override
    public void onApply(Iterator iter) {
        while (iter.hasNext()) {
            Status status = Status.OK();
            try {
                ByteBuffer data = iter.getData();
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                
                TrustEntry entry = objectMapper.readValue(bytes, TrustEntry.class);
                store.put(entry);
            } catch (Exception e) {
                status = new Status(RaftError.EINTERNAL, "Failed to apply log to State Machine: " + e.getMessage());
            }
            
            // Notification de la complétion de la tâche au client (callback de fermeture d'RPC)
            com.alipay.sofa.jraft.Closure done = iter.done();
            if (done != null) {
                done.run(status);
            }
            iter.next();
        }
    }

    @Override
    public void onLeaderStart(long term) {
        leaderTerm.set(term);
    }

    @Override
    public void onLeaderStop(Status status) {
        leaderTerm.set(-1);
    }

    /**
     * Indique si ce nœud est actuellement le leader actif élu de son groupe Raft.
     *
     * @return {@code true} si ce nœud est leader, sinon {@code false}.
     */
    public boolean isLeader() {
        return leaderTerm.get() > 0;
    }
}
