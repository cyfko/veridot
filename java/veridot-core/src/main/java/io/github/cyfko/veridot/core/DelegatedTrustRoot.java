package io.github.cyfko.veridot.core;

/**
 * Interface for verifying signatures through a delegated trust system (e.g. external KMS).
 */
public non-sealed interface DelegatedTrustRoot extends TrustRoot {

    /**
     * Verifies the signature of the data under the resolved issuer's identity.
     *
     * @param issuer the identity of the signer
     * @param data the canonical bytes that were signed
     * @param signature the raw signature bytes
     * @param sigAlg the signature algorithm (0x01 = RSA-SHA256, 0x02 = Ed25519, 0x03 = RSA-PSS)
     * @return true if signature is valid, false otherwise
     */
    boolean verifySignature(String issuer, byte[] data, byte[] signature, byte sigAlg);
}
