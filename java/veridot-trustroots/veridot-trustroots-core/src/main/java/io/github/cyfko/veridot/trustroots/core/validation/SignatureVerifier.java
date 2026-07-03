package io.github.cyfko.veridot.trustroots.core.validation;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.exception.InvalidSignatureException;

import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Vérifie l'authenticité d'une TrustEntry en contrôlant sa issuerSignature.
 */
public final class SignatureVerifier {

    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    /**
     * Vérifie la issuerSignature de l'entrée.
     */
    public void verify(TrustEntry entry) throws InvalidSignatureException {
        try {
            PublicKey publicKey = reconstructPublicKey(entry);
            byte[] payload = entry.canonicalPayload();
            byte[] signature = BASE64_URL_DECODER.decode(entry.issuerSignature());

            Signature sig = Signature.getInstance(entry.algorithm().jcaSignAlgorithm());
            sig.initVerify(publicKey);
            sig.update(payload);

            if (!sig.verify(signature)) {
                throw new InvalidSignatureException(
                        "Signature verification failed for subject: " + entry.subject());
            }
        } catch (NoSuchAlgorithmException e) {
            throw new InvalidSignatureException(
                    "Unsupported algorithm: " + entry.algorithm(), e);
        } catch (InvalidKeyException | SignatureException e) {
            throw new InvalidSignatureException(
                    "Cryptographic error for subject: " + entry.subject(), e);
        }
    }

    private PublicKey reconstructPublicKey(TrustEntry entry) throws InvalidSignatureException {
        try {
            byte[] keyBytes = BASE64_URL_DECODER.decode(entry.publicKeyEncoded());
            KeyFactory kf = KeyFactory.getInstance(entry.algorithm().jcaKeyAlgorithm());
            return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new InvalidSignatureException(
                    "Cannot reconstruct public key for subject: " + entry.subject(), e);
        }
    }

    /**
     * Calcule le fingerprint SHA-256 de la clé publique (hex lowercase).
     */
    public static String computeFingerprint(String publicKeyEncoded) {
        try {
            byte[] keyBytes = BASE64_URL_DECODER.decode(publicKeyEncoded);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(keyBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
