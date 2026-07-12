package io.github.cyfko.veridot.trustroots.taas.server.raft;

import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.core.VeridotMetrics;
import io.github.cyfko.veridot.trustroots.api.AttestationRecord;
import io.github.cyfko.veridot.trustroots.api.SecurityAlert;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;

import io.github.cyfko.veridot.trustroots.taas.server.attestation.AttestationService;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationContext;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationResult;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Machine d'état finie (FSM) Raft pour le serveur TAAS répliqué (V5).
 * <p>
 * Hérite de {@link StateMachineAdapter} de SOFAJRaft. Elle applique de manière déterministe et séquentielle
 * les écritures de transactions de clés publiques (proposées par les clients) dans la base locale {@link TaasRocksDbStore}.
 * <p>
 * V5 changes: Deserializes {@link TaasProposal} (entry + attestation proof), re-validates attestation
 * on each follower, and logs attestation records and security alerts.
 */
public class TaasStateMachine extends StateMachineAdapter {
    
    /** Le stockage RocksDB local associé à ce nœud du cluster. */
    private final TaasRocksDbStore store;

    /** The attestation verifier for re-validating proofs on apply. */
    private final AttestationService attestationService;
    
    /** Sérialiseur Jackson thread-safe. */
    private final ObjectMapper objectMapper;
    
    /** Terme d'élection Raft actuel si ce nœud est le leader actif. Vaut -1 si ce nœud est un follower. */
    private final AtomicLong leaderTerm = new AtomicLong(-1);

    /**
     * Wrapper record for Raft proposals containing both the TrustEntry and the attestation proof.
     *
     * @param entry            The TrustEntry to publish.
     * @param attestationProof The attestation proof string.
     */
    public record TaasProposal(TrustEntry entry, String attestationProof) {}

    /**
     * Initialise la machine d'état V5.
     *
     * @param store               Le stockage local RocksDB.
     * @param attestationVerifier The attestation verifier for re-validating proofs.
     */
    public TaasStateMachine(TaasRocksDbStore store, AttestationService attestationService) {
        this.store = store;
        this.attestationService = attestationService;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * Applique un lot de logs validés et répliqués par consensus Raft sur le stockage RocksDB.
     * <p>
     * V5: Deserializes {@link TaasProposal} containing both the TrustEntry and attestation proof.
     * Re-validates the attestation proof before applying. If valid, stores the entry and logs
     * the attestation. If invalid during a rotation (version > 1), logs a security alert.
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
                
                TaasProposal proposal = objectMapper.readValue(bytes, TaasProposal.class);
                TrustEntry entry = proposal.entry();
                String proof = proposal.attestationProof();

                // Re-validate attestation on each follower
                AttestationContext ctx = new AttestationContext(entry.subject().split("@")[0], java.util.Base64.getDecoder().decode(entry.publicKeyEncoded()), entry.algorithm().code());
                AttestationResult result = attestationService.verify(
                    entry.attestationPlugin(), proof != null ? proof.getBytes(java.nio.charset.StandardCharsets.UTF_8) : new byte[0], ctx);
                VeridotMetrics.ATTESTATION_VERIFICATIONS.increment();

                if (result.valid()) {
                    store.put(entry);
                    store.logAttestation(new AttestationRecord(
                        entry.subject(),
                        entry.version(),
                        entry.attestationPlugin(),
                        result.authorityRef(),
                        Instant.now()
                    ));
                } else {
                    // Only log security alert for rotations (not first registration)
                    boolean isRotation = entry.version() > 1;
                    if (isRotation) {
                        VeridotMetrics.SECURITY_ALERTS.increment();
                        store.logSecurityAlert(new SecurityAlert(
                            entry.subject(),
                            entry.version(),
                            "ATTESTATION_FAILED",
                            result.reason(),
                            Instant.now()
                        ));
                    }
                    status = new Status(RaftError.EINTERNAL,
                        "Attestation verification failed: " + result.reason());
                }
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
