package io.github.cyfko.veridot.trustroots.core.validation;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.exception.InvalidSignatureException;

import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Composant de sécurité chargé de valider l'authenticité d'une {@link TrustEntry}.
 * Il décode la clé publique et vérifie la signature de l'émetteur (issuerSignature) par rapport à sa charge utile canonique.
 */
public final class SignatureVerifier {

    /** Décodeur partagé pour le format Base64 URL-safe. */
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    /**
     * Constructeur par défaut.
     */
    public SignatureVerifier() {
    }

    /**
     * Vérifie la signature cryptographique (issuerSignature) de la {@link TrustEntry}.
     *
     * @param entry L'entrée de confiance à valider.
     * @throws InvalidSignatureException si la signature est incorrecte, ou si la clé publique n'est pas décodable.
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

    /**
     * Reconstruit l'objet {@link PublicKey} Java à partir de la représentation Base64 et des spécifications X509.
     *
     * @param entry L'entrée contenant la clé encodée.
     * @return La clé publique décodée.
     * @throws InvalidSignatureException si la clé ne peut pas être analysée.
     */
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
     * Calcule l'empreinte SHA-256 (fingerprint) d'une clé publique au format hexadécimal minuscules.
     *
     * @param publicKeyEncoded Clé publique brute encodée en Base64 URL-safe.
     * @return L'empreinte hexadécimale SHA-256 sous forme de String.
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
