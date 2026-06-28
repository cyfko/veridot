package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DelegatedTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Verifies envelope signatures using TrustRoot resolution (§13.1).
 */
final class SignatureVerifier {

    public void verify(Envelope envelope, TrustRoot trustRoot) {
        if (envelope == null) {
            throw new IllegalArgumentException("Envelope cannot be null");
        }
        if (trustRoot == null) {
            throw new IllegalArgumentException("TrustRoot cannot be null");
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
            publicKey = trustRoot.resolve(envelope.issuer);
        } catch (VeridotException e) {
            throw e;
        } catch (Exception e) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "TrustRoot resolution failed", e);
        }

        if (publicKey == null) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, envelope.entryId().loggable(), "Resolved public key is null");
        }

        // 3. Verify key type consistency
        String keyAlg = publicKey.getAlgorithm();
        if (envelope.sigAlg == 0x01) { // RSA-SHA256
            if (!"RSA".equalsIgnoreCase(keyAlg)) {
                throw new VeridotException(ErrorCode.SIGALG_KEY_MISMATCH, envelope.entryId().loggable(), "RSA signature requires an RSA key, got: " + keyAlg);
            }
        } else if (envelope.sigAlg == 0x02) { // Ed25519
            if (!"Ed25519".equalsIgnoreCase(keyAlg) && !"EdDSA".equalsIgnoreCase(keyAlg)) {
                throw new VeridotException(ErrorCode.SIGALG_KEY_MISMATCH, envelope.entryId().loggable(), "Ed25519 signature requires Ed25519 key, got: " + keyAlg);
            }
        } else {
            throw new VeridotException(ErrorCode.SIGALG_KEY_MISMATCH, envelope.entryId().loggable(), "Unknown signature algorithm: " + envelope.sigAlg);
        }

        // 4. Verify cryptographic signature
        try {
            Signature sig;
            if (envelope.sigAlg == 0x01) {
                sig = Signature.getInstance("SHA256withRSA");
            } else {
                sig = Signature.getInstance("Ed25519");
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
}
