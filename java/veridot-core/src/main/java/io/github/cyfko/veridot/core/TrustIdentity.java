package io.github.cyfko.veridot.core;

import java.security.PublicKey;

/**
 * Represents a resolved identity from the TrustRoot (V5 §5.5).
 *
 * <p>V5 adds the {@code algorithm} field so that verifiers can enforce
 * algorithm coherence without relying on key introspection.
 *
 * @param publicKey the identity's public key
 * @param isRoot    whether this identity is a root-level trust anchor
 * @param algorithm the expected signing algorithm for this identity
 */
public record TrustIdentity(PublicKey publicKey, boolean isRoot, Algorithm algorithm) {

    /**
     * V5 primary constructor — all three fields required.
     */
    public TrustIdentity {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey cannot be null");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("algorithm cannot be null");
        }
    }

    /**
     * V4-compatible convenience constructor — infers algorithm from key type.
     * <p><strong>Deprecated</strong>: prefer the 3-arg constructor in V5 code.
     */
    public TrustIdentity(PublicKey publicKey, boolean isRoot) {
        this(publicKey, isRoot, inferAlgorithm(publicKey));
    }

    private static Algorithm inferAlgorithm(PublicKey pk) {
        if (pk == null) throw new IllegalArgumentException("publicKey cannot be null");
        String keyAlg = pk.getAlgorithm();
        if ("Ed25519".equalsIgnoreCase(keyAlg) || "EdDSA".equalsIgnoreCase(keyAlg)) {
            return Algorithm.ED25519;
        } else if ("EC".equalsIgnoreCase(keyAlg)) {
            return Algorithm.ECDSA_P256;
        } else if ("RSA".equalsIgnoreCase(keyAlg)) {
            return Algorithm.RSA_SHA256;
        }
        throw new IllegalArgumentException("Cannot infer algorithm from key type: " + keyAlg);
    }
}

