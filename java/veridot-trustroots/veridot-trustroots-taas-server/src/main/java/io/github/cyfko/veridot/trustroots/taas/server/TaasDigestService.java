package io.github.cyfko.veridot.trustroots.taas.server;

import io.github.cyfko.veridot.core.impl.SparseMerkleTree;
import io.github.cyfko.veridot.trustroots.api.SignedDigest;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TAAS Digest Service for State Transparency (§18.2).
 *
 * <p>Periodically computes a signed state digest over all TAAS-committed entries
 * (TrustEntry and TRUST_REVOCATION) for each scope. The digest is a
 * {@link SignedDigest} containing the Sparse Merkle Tree root, entry count,
 * timestamp, and a cryptographic signature by this TAAS node.
 *
 * <p>Instances retrieve digests via {@code GET /v2/digest?scope=<scope>} directly
 * from TAAS (not via the broker) to avoid the circular dependency where the broker
 * filters the very mechanism designed to detect its omissions.
 *
 * <p>Default publication interval: {@code TAAS_DIGEST_INTERVAL_SECONDS} = 3600.
 *
 * @see SparseMerkleTree
 * @see SignedDigest
 */
public class TaasDigestService {

    /** Entry type label for TrustEntry leaves in the SMT. */
    private static final String TRUST_ENTRY_TYPE = "TRUST_ENTRY";

    /** Entry type label for TRUST_REVOCATION leaves in the SMT. */
    private static final String TRUST_REVOCATION_TYPE = "TRUST_REVOCATION";

    /** The RocksDB store containing all TAAS-committed entries. */
    private final TaasRocksDbStore store;

    /** The TAAS node's private signing key (Ed25519). */
    private final PrivateKey taasSigningKey;

    /** Identifier of this TAAS node. */
    private final String nodeId;

    /** Cache of the latest computed digest per scope. */
    private final Map<String, SignedDigest> latestDigests = new ConcurrentHashMap<>();

    /**
     * Creates a new TaasDigestService.
     *
     * @param store          the RocksDB store for TAAS-committed entries
     * @param taasSigningKey the TAAS node's private signing key (Ed25519)
     * @param nodeId         identifier of this TAAS node
     */
    public TaasDigestService(TaasRocksDbStore store, PrivateKey taasSigningKey, String nodeId) {
        this.store = Objects.requireNonNull(store, "store");
        this.taasSigningKey = Objects.requireNonNull(taasSigningKey, "taasSigningKey");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * Computes a signed state digest for the given scope (§18.2).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Load all active TrustEntry for scope from store</li>
     *   <li>For each entry: leafKey = SHA-256(subject || entryType), leafVal = SHA-256(subject || version || publicKeyHash)</li>
     *   <li>Compute SMT root via {@link SparseMerkleTree#computeRoot}</li>
     *   <li>Sign the digest with this TAAS node's key</li>
     *   <li>Cache in latestDigests</li>
     * </ol>
     *
     * @param scope the scope to compute the digest for
     * @return the signed digest
     */
    public SignedDigest computeTaasDigest(String scope) {
        Objects.requireNonNull(scope, "scope");

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        List<TrustEntry> allEntries = store.getAllEntries();
        List<Map.Entry<byte[], byte[]>> leaves = new ArrayList<>();

        for (TrustEntry entry : allEntries) {
            // §18.2: leafKey = SHA-256(subject || "TRUST_ENTRY")
            md.reset();
            md.update(entry.subject().getBytes(StandardCharsets.UTF_8));
            md.update(TRUST_ENTRY_TYPE.getBytes(StandardCharsets.UTF_8));
            byte[] leafKey = md.digest();

            // §18.2: leafVal = SHA-256(subject || version || publicKeyHash)
            md.reset();
            md.update(entry.subject().getBytes(StandardCharsets.UTF_8));
            md.update(longToBytes(entry.version()));
            md.update(entry.fingerprint().getBytes(StandardCharsets.UTF_8));
            byte[] leafVal = md.digest();

            leaves.add(Map.entry(leafKey, leafVal));
        }

        // Note: TRUST_REVOCATION entries would be added here if the store tracked them.
        // Currently, revocations are published as broker entries and not persisted in
        // the TAAS RocksDB store. When revocation persistence is added, this method
        // will include them per §18.2:
        //   leafKey = SHA-256(rev.subject || "TRUST_REVOCATION")
        //   leafVal = SHA-256(rev.subject || rev.version || rev.reason)

        byte[] root = SparseMerkleTree.computeRoot(leaves);
        long timestamp = Instant.now().toEpochMilli();

        // Create unsigned digest, then sign it
        SignedDigest unsigned = new SignedDigest(scope, root, leaves.size(), timestamp, nodeId, new byte[64]);
        SignedDigest signedDigest = unsigned.sign(taasSigningKey);

        // Cache
        latestDigests.put(scope, signedDigest);

        return signedDigest;
    }

    /**
     * Returns the latest computed digest for the given scope, if available.
     *
     * @param scope the scope to look up
     * @return the latest {@link SignedDigest} if computed, otherwise empty
     */
    public Optional<SignedDigest> getLatestDigest(String scope) {
        return Optional.ofNullable(latestDigests.get(scope));
    }

    /**
     * Converts a long to an 8-byte big-endian representation.
     */
    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }
}
