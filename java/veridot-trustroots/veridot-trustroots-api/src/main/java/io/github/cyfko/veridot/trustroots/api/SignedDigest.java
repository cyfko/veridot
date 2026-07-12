package io.github.cyfko.veridot.trustroots.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;
import java.util.Objects;

/**
 * Signed state digest for TAAS-Proactive Verification (§18.2).
 *
 * <p>Contains the Sparse Merkle Tree root computed over all TAAS-committed entries
 * (TrustEntry and TRUST_REVOCATION) for a given scope, along with metadata and
 * a cryptographic signature by the TAAS node.
 *
 * <p>Wire format (canonical signing bytes):
 * <pre>
 *   scope_len (4 bytes, big-endian) || scope (UTF-8) ||
 *   root (32 bytes) ||
 *   entryCount (4 bytes, big-endian) ||
 *   timestamp (8 bytes, big-endian) ||
 *   nodeId_len (4 bytes, big-endian) || nodeId (UTF-8)
 * </pre>
 *
 * <p>Full serialization format adds:
 * <pre>
 *   signing_bytes || sig_len (4 bytes, big-endian) || signature
 * </pre>
 *
 * @param scope       The scope this digest covers.
 * @param root        32-byte SMT root hash.
 * @param entryCount  Number of leaves in the SMT.
 * @param timestamp   Epoch milliseconds when the digest was computed.
 * @param taasNodeId  Identifier of the TAAS node that computed this digest.
 * @param signature   Digital signature over the signing bytes (Ed25519).
 */
public record SignedDigest(
    String scope,
    byte[] root,
    int entryCount,
    long timestamp,
    String taasNodeId,
    byte[] signature
) {

    /** Expected root hash length in bytes. */
    private static final int ROOT_LENGTH = 32;

    /**
     * Canonical constructor with validation and defensive copies.
     */
    public SignedDigest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(taasNodeId, "taasNodeId");

        if (root.length != ROOT_LENGTH) {
            throw new IllegalArgumentException(
                "root must be " + ROOT_LENGTH + " bytes, got " + root.length);
        }
        if (entryCount < 0) {
            throw new IllegalArgumentException("entryCount must be non-negative");
        }

        // Defensive copies
        root = root.clone();
        signature = signature != null ? signature.clone() : null;
    }

    /**
     * Returns the canonical byte representation used for signing/verification.
     *
     * <p>Format:
     * {@code scope_len(4) || scope(UTF-8) || root(32) || entryCount(4) || timestamp(8) || nodeId_len(4) || nodeId(UTF-8)}
     *
     * @return the canonical signing bytes
     */
    public byte[] toSigningBytes() {
        byte[] scopeBytes = scope.getBytes(StandardCharsets.UTF_8);
        byte[] nodeIdBytes = taasNodeId.getBytes(StandardCharsets.UTF_8);

        int totalLen = 4 + scopeBytes.length + ROOT_LENGTH + 4 + 8 + 4 + nodeIdBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        buf.putInt(scopeBytes.length);
        buf.put(scopeBytes);
        buf.put(root);
        buf.putInt(entryCount);
        buf.putLong(timestamp);
        buf.putInt(nodeIdBytes.length);
        buf.put(nodeIdBytes);

        return buf.array();
    }

    /**
     * Verifies the signature using the given TAAS public key (Ed25519).
     *
     * @param taasKey the public key of the TAAS node
     * @return {@code true} if the signature is valid, {@code false} otherwise
     */
    public boolean verify(PublicKey taasKey) {
        if (signature == null || signature.length == 0) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(taasKey);
            sig.update(toSigningBytes());
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            return false;
        }
    }

    /**
     * Serializes this digest to bytes, including the signature.
     *
     * <p>Format: {@code signingBytes || sig_len(4) || signature}
     *
     * @return full serialized bytes
     * @throws IllegalStateException if the digest is not signed
     */
    public byte[] toBytes() {
        if (signature == null) {
            throw new IllegalStateException("Cannot serialize unsigned digest");
        }
        byte[] signingBytes = toSigningBytes();
        ByteBuffer buf = ByteBuffer.allocate(signingBytes.length + 4 + signature.length);
        buf.put(signingBytes);
        buf.putInt(signature.length);
        buf.put(signature);
        return buf.array();
    }

    /**
     * Deserializes a {@link SignedDigest} from its byte representation.
     *
     * @param data the serialized bytes
     * @return the deserialized {@link SignedDigest}
     * @throws IllegalArgumentException if the data is malformed
     */
    public static SignedDigest fromBytes(byte[] data) {
        Objects.requireNonNull(data, "data");
        ByteBuffer buf = ByteBuffer.wrap(data);

        try {
            int scopeLen = buf.getInt();
            byte[] scopeBytes = new byte[scopeLen];
            buf.get(scopeBytes);
            String scope = new String(scopeBytes, StandardCharsets.UTF_8);

            byte[] root = new byte[ROOT_LENGTH];
            buf.get(root);

            int entryCount = buf.getInt();
            long timestamp = buf.getLong();

            int nodeIdLen = buf.getInt();
            byte[] nodeIdBytes = new byte[nodeIdLen];
            buf.get(nodeIdBytes);
            String taasNodeId = new String(nodeIdBytes, StandardCharsets.UTF_8);

            int sigLen = buf.getInt();
            byte[] signature = new byte[sigLen];
            buf.get(signature);

            return new SignedDigest(scope, root, entryCount, timestamp, taasNodeId, signature);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed SignedDigest data", e);
        }
    }

    /**
     * Signs this digest with the given TAAS private key (Ed25519) and returns a new
     * signed copy.
     *
     * @param privateKey the TAAS node's private key
     * @return a new {@link SignedDigest} with the signature populated
     */
    public SignedDigest sign(PrivateKey privateKey) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(toSigningBytes());
            byte[] signatureBytes = sig.sign();
            return new SignedDigest(scope, root, entryCount, timestamp, taasNodeId, signatureBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
            throw new IllegalStateException("Failed to sign digest", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignedDigest that)) return false;
        return entryCount == that.entryCount
            && timestamp == that.timestamp
            && scope.equals(that.scope)
            && Arrays.equals(root, that.root)
            && taasNodeId.equals(that.taasNodeId)
            && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(scope, entryCount, timestamp, taasNodeId);
        result = 31 * result + Arrays.hashCode(root);
        result = 31 * result + Arrays.hashCode(signature);
        return result;
    }

    @Override
    public String toString() {
        return "SignedDigest[scope=" + scope
            + ", entryCount=" + entryCount
            + ", timestamp=" + timestamp
            + ", taasNodeId=" + taasNodeId
            + ", rootLen=" + (root != null ? root.length : 0)
            + ", sigLen=" + (signature != null ? signature.length : 0)
            + "]";
    }
}
