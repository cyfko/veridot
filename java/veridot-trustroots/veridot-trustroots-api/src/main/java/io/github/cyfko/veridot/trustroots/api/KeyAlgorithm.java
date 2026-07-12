package io.github.cyfko.veridot.trustroots.api;

/**
 * Algorithmes de clé publique supportés par Veridot pour la vérification des signatures.
 * Cette énumération mappe les algorithmes avec leurs identifiants de sérialisation et les noms standards JCA.
 */
public enum KeyAlgorithm {

    /** Algorithme Ed25519 utilisant EdDSA. Recommandé pour la sécurité temporelle constante. */
    ED25519("Ed25519", "EdDSA", "Ed25519"),
    
    /** Courbe elliptique NIST P-256 avec signature SHA256withECDSA. */
    EC_P256("EC/P-256", "SHA256withECDSA", "EC"),
    
    /** Courbe elliptique NIST P-384 avec signature SHA384withECDSA. */
    EC_P384("EC/P-384", "SHA384withECDSA", "EC"),
    
    /** RSA avec clé de 2048 bits et signature SHA256withRSA. */
    RSA_2048("RSA-2048", "SHA256withRSA", "RSA"),
    
    /** RSA avec clé de 4096 bits et signature SHA256withRSA. */
    RSA_4096("RSA-4096", "SHA256withRSA", "RSA"),

    // ── V5 Post-Quantum and Hybrid algorithms (require a PQ JCA provider, e.g. Bouncy Castle) ──

    /** Hybrid Ed25519 + ML-DSA-65 composite signature (§6, Appendix C.2). */
    ED25519_MLDSA65("Ed25519+ML-DSA-65", "Ed25519+ML-DSA-65", "Ed25519+ML-DSA-65"),

    /** Hybrid ECDSA P-256 + ML-DSA-65 composite signature (§6, Appendix C.2). */
    ECDSA_P256_MLDSA65("ECDSA-P256+ML-DSA-65", "ECDSA-P256+ML-DSA-65", "EC+ML-DSA-65"),

    /** Standalone ML-DSA-65 post-quantum signature (§6, Appendix C.2). */
    MLDSA65("ML-DSA-65", "ML-DSA-65", "ML-DSA-65");

    /** Identifiant unique de l'algorithme utilisé dans la structure JSON de {@link TrustEntry}. */
    private final String identifier;
    
    /** Nom standard de l'algorithme de signature JCA (ex: "EdDSA", "SHA256withECDSA"). */
    private final String jcaSignAlgorithm;
    
    /** Nom de la famille d'algorithmes JCA pour le décodeur de clés (ex: "Ed25519", "EC", "RSA"). */
    private final String jcaKeyAlgorithm;

    /**
     * Constructeur interne.
     *
     * @param identifier Identifiant de sérialisation.
     * @param jcaSignAlgorithm Algorithme de signature JCA.
     * @param jcaKeyAlgorithm Algorithme de clé JCA.
     */
    KeyAlgorithm(String identifier, String jcaSignAlgorithm, String jcaKeyAlgorithm) {
        this.identifier = identifier;
        this.jcaSignAlgorithm = jcaSignAlgorithm;
        this.jcaKeyAlgorithm = jcaKeyAlgorithm;
    }

    /**
     * Retourne l'identifiant de sérialisation de l'algorithme.
     *
     * @return L'identifiant de l'algorithme.
     */
    public String identifier() { return identifier; }

    /**
     * Retourne l'algorithme de signature JCA.
     *
     * @return Le nom standard JCA de l'algorithme de signature.
     */
    public String jcaSignAlgorithm() { return jcaSignAlgorithm; }

    /**
     * Retourne l'algorithme de clé JCA.
     *
     * @return Le nom standard JCA de l'algorithme de clé.
     */
    public String jcaKeyAlgorithm() { return jcaKeyAlgorithm; }

    /**
     * Résout une instance d'algorithme à partir de son identifiant de sérialisation.
     *
     * @param identifier Identifiant à analyser.
     * @return L'instance {@link KeyAlgorithm} correspondante.
     * @throws IllegalArgumentException si l'identifiant n'est pas reconnu.
     */
    public static KeyAlgorithm fromIdentifier(String identifier) {
        for (KeyAlgorithm alg : values()) {
            if (alg.identifier.equals(identifier)) return alg;
        }
        throw new IllegalArgumentException("Unknown algorithm identifier: " + identifier);
    }
}
