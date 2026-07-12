package io.github.cyfko.veridot.trustroots.taas.server.raft;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.option.NodeOptions;

import java.io.File;
import java.io.IOException;

/**
 * Moteur d'initialisation et de gestion du nœud consensus SOFAJRaft pour un serveur TAAS.
 * <p>
 * Configure les répertoires de stockage de métadonnées, de snapshots et de logs Raft,
 * puis démarre le service de groupe de consensus distribué.
 */
public class RaftServerEngine {
    
    /** Adresse d'écoute IP:Port de ce nœud pour le protocole de consensus (ex: "127.0.0.1:9443"). */
    private final String serverId;
    
    /** Identifiant unique du groupe Raft (ex: "veridot-taas"). */
    private final String raftGroupId;
    
    /** Liste des pairs du cluster Raft initial sous forme de chaîne de caractères séparés par des virgules. */
    private final String peersStr;
    
    /** Répertoire local parent de stockage des fichiers liés à Raft. */
    private final String dataPath;
    
    /** La machine d'état associée au nœud de consensus. */
    private final TaasStateMachine stateMachine;
    
    /** Le nœud Raft représenté par SOFAJRaft. */
    private Node node;
    
    /** Service d'orchestration globale de SOFAJRaft. */
    private RaftGroupService raftGroupService;

    /**
     * Initialise la structure du moteur Raft.
     *
     * @param serverId Identifiant du serveur.
     * @param raftGroupId Identifiant du groupe Raft.
     * @param peersStr Liste des pairs initiaux.
     * @param dataPath Répertoire local de données.
     * @param stateMachine Machine d'état finie.
     */
    public RaftServerEngine(String serverId, String raftGroupId, String peersStr, String dataPath, TaasStateMachine stateMachine) {
        this.serverId = serverId;
        this.raftGroupId = raftGroupId;
        this.peersStr = peersStr;
        this.dataPath = dataPath;
        this.stateMachine = stateMachine;
    }

    /**
     * Démarre le nœud de consensus Raft localement.
     * Crée les dossiers nécessaires si absents.
     *
     * @throws IOException si un dossier ne peut pas être créé ou si le port est indisponible.
     */
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

    /**
     * Retourne le nœud Raft sous-jacent.
     *
     * @return Le nœud {@link Node} Raft.
     */
    public Node getNode() {
        return node;
    }

    /**
     * Arrête proprement le nœud Raft et libère les ressources réseau et de stockage associées.
     */
    public void stop() {
        if (raftGroupService != null) {
            raftGroupService.shutdown();
        }
    }
}
