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
 * Machine d'état Raft pour le serveur TAD répliqué.
 */
public class TadStateMachine extends StateMachineAdapter {
    private final TadRocksDbStore store;
    private final ObjectMapper objectMapper;
    private final AtomicLong leaderTerm = new AtomicLong(-1);

    public TadStateMachine(TadRocksDbStore store) {
        this.store = store;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

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

    public boolean isLeader() {
        return leaderTerm.get() > 0;
    }
}
