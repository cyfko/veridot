package io.github.cyfko.veridot.trustroots.tad.server.controller;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.tad.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.tad.server.raft.TadStateMachine;
import io.github.cyfko.veridot.trustroots.tad.server.store.TadRocksDbStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Contrôleur REST Spring Boot exposant l'API du registre d'autorité TAD.
 * <p>
 * Gère la publication, la rotation, la résolution unitaire/batch et la synchronisation différentielle des clés de confiance.
 * Si une opération d'écriture (POST/PUT) arrive sur un nœud suiveur (Follower), ce contrôleur redirige automatiquement le client
 * vers l'adresse HTTP du leader actif élu (HTTP 307 Temporary Redirect).
 */
@RestController
@RequestMapping
public class TadController {

    /** Moteur Raft de consensus. */
    private final RaftServerEngine raftEngine;
    
    /** Machine d'état Raft pour vérifier le rôle du nœud. */
    private final TadStateMachine stateMachine;
    
    /** Stockage RocksDB local. */
    private final TadRocksDbStore store;
    
    /** Sérialiseur Jackson. */
    private final ObjectMapper objectMapper;

    /**
     * Initialise le contrôleur.
     *
     * @param raftEngine Moteur Raft.
     * @param stateMachine Machine d'état Raft.
     * @param store Stockage persistant.
     */
    public TadController(RaftServerEngine raftEngine, TadStateMachine stateMachine, TadRocksDbStore store) {
        this.raftEngine = raftEngine;
        this.stateMachine = stateMachine;
        this.store = store;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Publie une nouvelle entrée de confiance.
     * L'écriture est soumise au protocole de consensus Raft et validée uniquement après réplication majoritaire.
     * Si ce nœud n'est pas le leader actuel, redirige vers le leader.
     *
     * @param entry L'entrée {@link TrustEntry} à publier.
     * @return Réponse HTTP (201 Created si succès, 307 Redirect vers leader, ou codes d'erreur).
     */
    @PostMapping("/v1/trust-entries")
    public ResponseEntity<?> publish(@RequestBody TrustEntry entry) {
        if (!stateMachine.isLeader()) {
            PeerId leader = raftEngine.getNode().getLeaderId();
            if (leader == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "RAFT_UNAVAILABLE", "detail", "Leader not elected yet"));
            }
            // Redirection HTTP 307 temporaire vers le leader Raft actif
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create("http://" + leader.getEndpoint().toString() + "/v1/trust-entries"))
                    .build();
        }

        CompletableFuture<Status> future = new CompletableFuture<>();
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(entry);
            Task task = new Task();
            task.setData(ByteBuffer.wrap(bytes));
            task.setDone(future::complete);
            
            raftEngine.getNode().apply(task);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "detail", e.getMessage()));
        }

        try {
            Status status = future.get();
            if (status.isOk()) {
                return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                        "subject", entry.subject(),
                        "version", entry.version(),
                        "fingerprint", entry.fingerprint(),
                        "publishedAt", Instant.now().toString()
                ));
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "INVALID_REQUEST", "detail", status.getErrorMsg()));
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "detail", e.getMessage()));
        }
    }

    /**
     * Effectue une rotation de clé pour le sujet donné.
     * Vérifie que le sujet correspond bien à l'entrée fournie, puis applique la publication standard.
     *
     * @param subject Identifiant du sujet.
     * @param entry Nouvelle version de clé signée.
     * @return Réponse HTTP de publication.
     */
    @PutMapping("/v1/trust-entries/{subject}")
    public ResponseEntity<?> rotate(@PathVariable("subject") String subject, @RequestBody TrustEntry entry) {
        if (!subject.equals(entry.subject())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "SUBJECT_MISMATCH", "detail", "Subject in path does not match entry"));
        }
        return publish(entry);
    }

    /**
     * Résout la clé publique la plus récente pour le sujet donné en lisant directement du stockage local.
     *
     * @param subject Identifiant du sujet.
     * @return L'entrée {@link TrustEntry} correspondante (200 OK) ou une erreur 404.
     */
    @GetMapping("/v1/trust-entries/{subject}")
    public ResponseEntity<?> resolve(@PathVariable("subject") String subject) {
        Optional<TrustEntry> entryOpt = store.get(subject);
        if (entryOpt.isPresent()) {
            return ResponseEntity.ok(entryOpt.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "SUBJECT_NOT_FOUND", "detail", "Subject not registered"));
    }

    /**
     * Résout par lot plusieurs sujets en une seule requête.
     *
     * @param request JSON contenant la liste des identifiants (ex: {"subjects": ["s1", "s2"]}).
     * @return Map des clés trouvées et liste des clés non trouvées.
     */
    @PostMapping("/v1/trust-entries/batch")
    public ResponseEntity<?> batchResolve(@RequestBody Map<String, List<String>> request) {
        List<String> subjects = request.get("subjects");
        if (subjects == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "detail", "Missing subjects field"));
        }

        Map<String, TrustEntry> found = new HashMap<>();
        List<String> notFound = new ArrayList<>();
        
        for (String subject : subjects) {
            Optional<TrustEntry> entryOpt = store.get(subject);
            if (entryOpt.isPresent()) {
                found.put(subject, entryOpt.get());
            } else {
                notFound.add(subject);
            }
        }
        
        return ResponseEntity.ok(Map.of("found", found, "notFound", notFound));
    }

    /**
     * Récupère toutes les modifications intervenues depuis un instant donné (synchronisation incrémentale).
     *
     * @param modifiedSince Instant de référence au format ISO-8601 (UTC).
     * @return La liste des {@link TrustEntry} modifiées.
     */
    @GetMapping("/v1/trust-entries")
    public ResponseEntity<?> sync(@RequestParam("modifiedSince") String modifiedSince) {
        try {
            Instant since = Instant.parse(modifiedSince);
            List<TrustEntry> entries = store.getModifiedSince(since);
            return ResponseEntity.ok(Map.of(
                    "entries", entries,
                    "nextSyncToken", Instant.now().toString(),
                    "truncated", false
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "detail", e.getMessage()));
        }
    }

    /**
     * Endpoint de surveillance de santé (Healthcheck).
     * Indique le rôle Raft local (LEADER/FOLLOWER) et l'ID du leader reconnu.
     *
     * @return Statut général du nœud.
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        boolean isLeader = stateMachine.isLeader();
        PeerId leaderId = raftEngine.getNode().getLeaderId();
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "role", isLeader ? "LEADER" : "FOLLOWER",
                "leaderId", leaderId != null ? leaderId.toString() : null
        ));
    }
}
