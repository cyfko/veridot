package io.github.cyfko.veridot.trustroots.taas.server.controller;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.AttestationRecord;
import io.github.cyfko.veridot.trustroots.api.SecurityAlert;
import io.github.cyfko.veridot.trustroots.api.SignedDigest;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.taas.server.TaasDigestService;
import io.github.cyfko.veridot.trustroots.taas.server.attestation.AttestationResult;
import io.github.cyfko.veridot.trustroots.taas.server.attestation.AttestationVerifier;
import io.github.cyfko.veridot.trustroots.taas.server.raft.RaftServerEngine;
import io.github.cyfko.veridot.trustroots.taas.server.raft.TaasStateMachine;
import io.github.cyfko.veridot.trustroots.taas.server.raft.TaasStateMachine.TaasProposal;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Contrôleur REST Spring Boot exposant l'API du registre d'autorité TAAS (V5).
 * <p>
 * Gère la publication, la rotation, la résolution unitaire/batch et la synchronisation différentielle des clés de confiance.
 * Si une opération d'écriture (POST/PUT) arrive sur un nœud suiveur (Follower), ce contrôleur redirige automatiquement le client
 * vers l'adresse HTTP du leader actif élu (HTTP 307 Temporary Redirect).
 * <p>
 * V5 changes: API prefix moved to /v2/, attestation validation before Raft propose,
 * new audit endpoints, bootstrap endpoint.
 */
@RestController
@RequestMapping
public class TaasController {

    /** Moteur Raft de consensus. */
    private final RaftServerEngine raftEngine;
    
    /** Machine d'état Raft pour vérifier le rôle du nœud. */
    private final TaasStateMachine stateMachine;
    
    /** Stockage RocksDB local. */
    private final TaasRocksDbStore store;

    /** Attestation verifier for pre-validating proofs before Raft propose. */
    private final AttestationVerifier attestationVerifier;

    /** Digest service for State Transparency (§18.2). */
    private final TaasDigestService digestService;
    
    /** Sérialiseur Jackson. */
    private final ObjectMapper objectMapper;

    /**
     * Request body for publishing a TrustEntry with attestation proof.
     *
     * @param entry            The TrustEntry to publish.
     * @param attestationProof The attestation proof string.
     */
    public record PublishRequest(TrustEntry entry, String attestationProof) {}

    /**
     * Response body for a successful publish operation.
     *
     * @param subject     Subject identifier.
     * @param version     Version number.
     * @param fingerprint SHA-256 fingerprint of the public key.
     * @param publishedAt Publication timestamp.
     */
    public record PublishResponse(String subject, long version, String fingerprint, Instant publishedAt) {}

    /**
     * Initialise le contrôleur V5.
     *
     * @param raftEngine           Moteur Raft.
     * @param stateMachine         Machine d'état Raft.
     * @param store                Stockage persistant.
     * @param attestationVerifier  Attestation verifier for pre-validation.
     * @param digestService        Digest service for State Transparency (§18.2).
     */
    public TaasController(RaftServerEngine raftEngine, TaasStateMachine stateMachine,
                           TaasRocksDbStore store, AttestationVerifier attestationVerifier,
                           TaasDigestService digestService) {
        this.raftEngine = raftEngine;
        this.stateMachine = stateMachine;
        this.store = store;
        this.attestationVerifier = attestationVerifier;
        this.digestService = digestService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Publie une nouvelle entrée de confiance (V5).
     * Validates attestation proof BEFORE submitting to Raft consensus.
     * L'écriture est soumise au protocole de consensus Raft et validée uniquement après réplication majoritaire.
     * Si ce nœud n'est pas le leader actuel, redirige vers le leader.
     *
     * @param request The publish request containing entry and attestation proof.
     * @return Réponse HTTP (201 Created si succès, 307 Redirect vers leader, ou codes d'erreur).
     */
    @PostMapping("/v2/trust-entries")
    public ResponseEntity<?> publish(@RequestBody PublishRequest request) {
        // V5: attestation proof is required
        if (request.attestationProof() == null || request.attestationProof().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "ATTESTATION_REQUIRED",
                                 "code", "V5105",
                                 "detail", "attestationProof is required"));
        }

        TrustEntry entry = request.entry();
        if (entry == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "INVALID_REQUEST", "detail", "entry is required"));
        }

        if (!stateMachine.isLeader()) {
            PeerId leader = raftEngine.getNode().getLeaderId();
            if (leader == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "RAFT_UNAVAILABLE", "detail", "Leader not elected yet"));
            }
            // Redirection HTTP 307 temporaire vers le leader Raft actif
            return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT)
                    .location(URI.create("http://" + leader.getEndpoint().toString() + "/v2/trust-entries"))
                    .build();
        }

        // V5: Validate attestation BEFORE Raft propose
        AttestationResult attestResult = attestationVerifier.verify(
            entry, request.attestationProof().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!attestResult.valid()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "ATTESTATION_FAILED",
                                 "code", "V5106",
                                 "detail", attestResult.reason()));
        }

        CompletableFuture<Status> future = new CompletableFuture<>();
        try {
            // V5: Serialize the full proposal (entry + proof) for Raft
            TaasProposal proposal = new TaasProposal(entry, request.attestationProof());
            byte[] bytes = objectMapper.writeValueAsBytes(proposal);
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
                PublishResponse response = new PublishResponse(
                    entry.subject(),
                    entry.version(),
                    entry.fingerprint(),
                    Instant.now()
                );
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
     * Effectue une rotation de clé pour le sujet donné (V5).
     * Vérifie que le sujet correspond bien à l'entrée fournie, puis applique la publication standard.
     *
     * @param subject Identifiant du sujet.
     * @param request The publish request containing entry and attestation proof.
     * @return Réponse HTTP de publication.
     */
    @PutMapping("/v2/trust-entries/{subject}")
    public ResponseEntity<?> rotate(@PathVariable("subject") String subject, @RequestBody PublishRequest request) {
        if (request.entry() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "INVALID_REQUEST", "detail", "entry is required"));
        }
        if (!subject.equals(request.entry().subject())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "SUBJECT_MISMATCH", "detail", "Subject in path does not match entry"));
        }
        return publish(request);
    }

    /**
     * Résout la clé publique la plus récente pour le sujet donné en lisant directement du stockage local.
     *
     * @param subject Identifiant du sujet.
     * @return L'entrée {@link TrustEntry} correspondante (200 OK) ou une erreur 404.
     */
    @GetMapping("/v2/trust-entries/{subject}")
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
    @PostMapping("/v2/trust-entries/batch")
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
    @GetMapping("/v2/trust-entries")
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
     * Retrieves attestation records for audit purposes (V5).
     *
     * @param subject Optional subject filter.
     * @param since   Optional instant from which to retrieve records (ISO-8601).
     * @return List of attestation records.
     */
    @GetMapping("/v2/audit/attestations")
    public ResponseEntity<?> getAttestations(
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "since", required = false) String since) {
        try {
            Instant sinceInstant = since != null ? Instant.parse(since) : null;
            List<AttestationRecord> records = store.getAttestations(subject, sinceInstant);
            return ResponseEntity.ok(Map.of("attestations", records));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "detail", e.getMessage()));
        }
    }

    /**
     * Retrieves security alerts for audit purposes (V5).
     *
     * @param since Optional instant from which to retrieve alerts (ISO-8601).
     * @return List of security alerts.
     */
    @GetMapping("/v2/audit/alerts")
    public ResponseEntity<?> getAlerts(
            @RequestParam(value = "since", required = false) String since) {
        try {
            Instant sinceInstant = since != null ? Instant.parse(since) : null;
            List<SecurityAlert> alerts = store.getAlerts(sinceInstant);
            return ResponseEntity.ok(Map.of("alerts", alerts));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "detail", e.getMessage()));
        }
    }

    /**
     * Bootstrap endpoint for initial root trust anchor creation (V5, §5.7).
     * Protected by the {@code VDOT_BOOTSTRAP_ENABLED} environment variable.
     * Rejected if entries already exist in the store.
     *
     * @param request The publish request containing the root TrustEntry.
     * @return 201 Created if bootstrap succeeds, 403/409 otherwise.
     */
    @PostMapping("/v2/bootstrap")
    public ResponseEntity<?> bootstrap(@RequestBody PublishRequest request) {
        // Check if bootstrap is enabled
        String bootstrapEnabled = System.getenv("VDOT_BOOTSTRAP_ENABLED");
        if (!"true".equalsIgnoreCase(bootstrapEnabled)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "BOOTSTRAP_DISABLED",
                                 "detail", "Set VDOT_BOOTSTRAP_ENABLED=true to enable bootstrap"));
        }

        // Reject if entries already exist
        if (store.count() > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "BOOTSTRAP_REJECTED",
                                 "detail", "Cannot bootstrap: store already contains entries"));
        }

        if (request.entry() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "INVALID_REQUEST", "detail", "entry is required"));
        }

        try {
            store.putDirect(request.entry());
            PublishResponse response = new PublishResponse(
                request.entry().subject(),
                request.entry().version(),
                request.entry().fingerprint(),
                Instant.now()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "INTERNAL_ERROR", "detail", e.getMessage()));
        }
    }

    /**
     * Returns the latest TAAS state digest for the given scope (V5, §18.2).
     *
     * <p>Instances fetch digests directly from TAAS (not via the broker) to avoid
     * circular dependencies where the broker filters the very mechanism designed
     * to detect its omissions.
     *
     * @param scope The scope to query the digest for.
     * @return 200 OK with the {@link SignedDigest}, or 404 if no digest has been computed yet.
     */
    @GetMapping("/v2/digest")
    public ResponseEntity<?> getDigest(@RequestParam("scope") String scope) {
        Optional<SignedDigest> digestOpt = digestService.getLatestDigest(scope);
        if (digestOpt.isPresent()) {
            return ResponseEntity.ok(digestOpt.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "DIGEST_NOT_AVAILABLE", "detail", "No digest computed yet for scope: " + scope));
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
                "leaderId", leaderId != null ? leaderId.toString() : "unknown"
        ));
    }
}
