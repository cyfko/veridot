package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DelegatedTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Verifies envelope signatures using TrustRoot resolution (§13.1).
 */
final class SignatureVerifier {

    private static final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(SignatureVerifier.class.getName());

    public void verify(Envelope envelope, TrustRoot trustRoot) {
        if (envelope == null) {
            throw new IllegalArgumentException("Envelope cannot be null");
        }
        if (trustRoot == null) {
            throw new IllegalArgumentException("TrustRoot cannot be null");
        }

        if (!Config.ALLOWED_SIG_ALGS.contains(envelope.sigAlg)) {
            throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(),
                "Signature algorithm " + envelope.sigAlg + " is not allowed by configuration");
        }

        // 1. Check if trustRoot is delegated
        if (trustRoot instanceof DelegatedTrustRoot delegated) {
            boolean ok;
            try {
                ok = delegated.verifySignature(envelope.issuer, envelope.canonicalSigningBytes(), envelope.signature, envelope.sigAlg);
            } catch (Exception e) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Delegated trust root failed to resolve or verify", e);
            }
            if (!ok) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Signature verification failed via delegated trust root");
            }
            return;
        }

        // 2. Resolve PublicKey via PublicKeyTrustRoot
        PublicKey publicKey;
        try {
            TrustIdentity identity = trustRoot.resolve(envelope.issuer);
            if (identity == null) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Resolved identity is null");
            }
            publicKey = identity.publicKey();
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "TrustRoot resolution failed", e);
        }

        // 3. Verify key type consistency and constraints
        String keyAlg = publicKey.getAlgorithm();
        if (envelope.sigAlg.getJcaKeyAlg().equalsIgnoreCase("RSA")) {
            if (!"RSA".equalsIgnoreCase(keyAlg)) {
                throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(), "RSA signature requires an RSA key, got: " + keyAlg);
            }
            if (publicKey instanceof java.security.interfaces.RSAPublicKey rsaKey) {
                int keyLen = rsaKey.getModulus().bitLength();
                if (keyLen < Config.MIN_RSA_KEY_LENGTH) {
                    throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(),
                        "RSA key size must be at least " + Config.MIN_RSA_KEY_LENGTH + " bits, got: " + keyLen);
                }
            }
        } else if (envelope.sigAlg == Algorithm.ED25519) {
            if (!"Ed25519".equalsIgnoreCase(keyAlg) && !"EdDSA".equalsIgnoreCase(keyAlg)) {
                throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(), "Ed25519 signature requires Ed25519 key, got: " + keyAlg);
            }
        } else if (envelope.sigAlg == Algorithm.ECDSA_P256) {
            if (!"EC".equalsIgnoreCase(keyAlg)) {
                throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(), "ECDSA signature requires an EC key, got: " + keyAlg);
            }
        } else if (envelope.sigAlg.isPostQuantum() || envelope.sigAlg.isHybrid()) {
            // PQ/Hybrid algorithms: key type check is implementation-specific (bouncy castle, etc.)
            // For now, just verify the signature works
        } else {
            throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, envelope.entryId().loggable(), "Unknown signature algorithm: " + envelope.sigAlg);
        }

        // 4. Verify cryptographic signature
        try {
            if (envelope.sigAlg != Algorithm.ED25519) {
                logger.warning("Non-constant-time signature algorithm in use: " 
                    + envelope.sigAlg + " for entry " + envelope.entryId().loggable() 
                    + ". §14.1 recommends Ed25519 for timing-safe verification.");
            }
            Signature sig = Signature.getInstance(envelope.sigAlg.getJcaSignatureAlg());
            if (envelope.sigAlg == Algorithm.RSA_PSS) {
                try {
                    sig.setParameter(new java.security.spec.PSSParameterSpec(
                        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
                    ));
                } catch (Exception ignored) {}
            }
            sig.initVerify(publicKey);
            sig.update(envelope.canonicalSigningBytes());
            if (!sig.verify(envelope.signature)) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Cryptographic signature verification failed");
            }
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Signature verification threw an exception", e);
        }
    }

    /**
     * Verifies a raw signature against a public key (used by GenericSignerVerifier for JWT verification).
     */
    public void verifyRaw(byte[] signedBytes, byte[] signatureBytes, PublicKey publicKey, Algorithm alg) {
        try {
            Signature sig = Signature.getInstance(alg.getJcaSignatureAlg());
            if (alg == Algorithm.RSA_PSS) {
                try {
                    sig.setParameter(new java.security.spec.PSSParameterSpec(
                        "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
                    ));
                } catch (Exception ignored) {}
            }
            sig.initVerify(publicKey);
            sig.update(signedBytes);
            if (!sig.verify(signatureBytes)) {
                throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Cryptographic signature verification failed");
            }
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Signature verification threw an exception", e);
        }
    }
}
