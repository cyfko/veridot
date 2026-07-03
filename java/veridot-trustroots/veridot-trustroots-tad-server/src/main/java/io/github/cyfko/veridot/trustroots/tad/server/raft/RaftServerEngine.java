package io.github.cyfko.veridot.trustroots.tad.server.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;

import java.io.File;
import java.io.IOException;

/**
 * Moteur de démarrage et gestion du nœud Raft SOFAJRaft.
 */
public class RaftServerEngine {
    private final String serverId;
    private final String raftGroupId;
    private final String peersStr;
    private final String dataPath;
    private final TadStateMachine stateMachine;
    
    private Node node;
    private RaftGroupService raftGroupService;

    public RaftServerEngine(String serverId, String raftGroupId, String peersStr, String dataPath, TadStateMachine stateMachine) {
        this.serverId = serverId;
        this.raftGroupId = raftGroupId;
        this.peersStr = peersStr;
        this.dataPath = dataPath;
        this.stateMachine = stateMachine;
    }

    public void start() throws IOException {
        File dir = new File(dataPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create Raft directory: " + dataPath);
        }
        
        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setLogUri(dataPath + "/log");
        nodeOptions.setRaftMetaUri(dataPath + "/meta");
        nodeOptions.setSnapshotUri(dataPath + "/snapshot");
        nodeOptions.setFsm(stateMachine);
        
        Configuration initConf = new Configuration();
        if (!initConf.parse(peersStr)) {
            throw new IllegalArgumentException("Fail to parse initConf: " + peersStr);
        }
        nodeOptions.setInitialConf(initConf);
        
        PeerId serverPeer = new PeerId();
        if (!serverPeer.parse(serverId)) {
            throw new IllegalArgumentException("Fail to parse serverId: " + serverId);
        }
        
        this.raftGroupService = new RaftGroupService(raftGroupId, serverPeer, nodeOptions);
        this.node = raftGroupService.start();
    }

    public Node getNode() {
        return node;
    }

    public void stop() {
        if (raftGroupService != null) {
            raftGroupService.shutdown();
        }
    }
}
