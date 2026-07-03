package io.github.cyfko.veridot.trustroots.api;

/**
 * Algorithmes de clé publique supportés.
 */
public enum KeyAlgorithm {

    ED25519("Ed25519", "EdDSA", "Ed25519"),
    EC_P256("EC/P-256", "SHA256withECDSA", "EC"),
    EC_P384("EC/P-384", "SHA384withECDSA", "EC"),
    RSA_2048("RSA-2048", "SHA256withRSA", "RSA"),
    RSA_4096("RSA-4096", "SHA256withRSA", "RSA");

    private final String identifier;      // Identifiant dans TrustEntry
    private final String jcaSignAlgorithm; // Algorithme JCA pour la vérification
    private final String jcaKeyAlgorithm;  // Algorithme JCA pour la reconstruction de clé

    KeyAlgorithm(String identifier, String jcaSignAlgorithm, String jcaKeyAlgorithm) {
        this.identifier = identifier;
        this.jcaSignAlgorithm = jcaSignAlgorithm;
        this.jcaKeyAlgorithm = jcaKeyAlgorithm;
    }

    public String identifier() { return identifier; }
    public String jcaSignAlgorithm() { return jcaSignAlgorithm; }
    public String jcaKeyAlgorithm() { return jcaKeyAlgorithm; }

    public static KeyAlgorithm fromIdentifier(String identifier) {
        for (KeyAlgorithm alg : values()) {
            if (alg.identifier.equals(identifier)) return alg;
        }
        throw new IllegalArgumentException("Unknown algorithm identifier: " + identifier);
    }
}
