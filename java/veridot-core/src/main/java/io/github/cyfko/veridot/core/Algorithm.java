package io.github.cyfko.veridot.core;

/**
 * Registry of supported cryptographic algorithms in Veridot Protocol V5 (§6, Appendix C.2).
 *
 * <p>Algorithms are assigned stable byte codes for wire encoding. The registry supports
 * classical, post-quantum (PQ), and hybrid (classical + PQ) signature algorithms.
 *
 * <p>Code 0x08 is reserved for FROST threshold signatures (future RFC).
 * Codes 0x09+ are unassigned and MUST be rejected.
 */
public enum Algorithm {

    // ── Classical algorithms ─────────────────────────────────────────
    ED25519((byte) 0x01, "Ed25519", "Ed25519", "EdDSA"),
    ECDSA_P256((byte) 0x02, "SHA256withECDSA", "EC", "ES256"),
    RSA_SHA256((byte) 0x03, "SHA256withRSA", "RSA", "RS256"),
    RSA_PSS((byte) 0x04, "RSASSA-PSS", "RSA", "PS256"),

    // ── Hybrid (classical + PQ composite) ────────────────────────────
    ED25519_MLDSA65((byte) 0x05, "Ed25519+ML-DSA-65", "Composite", "EdDSA+ML-DSA-65"),
    ECDSA_P256_MLDSA65((byte) 0x06, "ECDSA-P256+ML-DSA-65", "Composite", "ES256+ML-DSA-65"),

    // ── Standalone post-quantum ──────────────────────────────────────
    MLDSA65((byte) 0x07, "ML-DSA-65", "ML-DSA", "ML-DSA-65");

    // 0x08 RESERVED — FROST threshold signatures (§6, Appendix C.2)

    private final byte code;
    private final String jcaSignatureAlg;
    private final String jcaKeyAlg;
    private final String jwtAlg;

    Algorithm(byte code, String jcaSignatureAlg, String jcaKeyAlg, String jwtAlg) {
        this.code = code;
        this.jcaSignatureAlg = jcaSignatureAlg;
        this.jcaKeyAlg = jcaKeyAlg;
        this.jwtAlg = jwtAlg;
    }

    public byte getCode() {
        return code;
    }

    /** Returns the JCA signature algorithm name (e.g., {@code "Ed25519"}, {@code "SHA256withECDSA"}). */
    public String getJcaSignatureAlg() {
        return jcaSignatureAlg;
    }

    /** Returns the JCA key algorithm family (e.g., {@code "Ed25519"}, {@code "EC"}, {@code "RSA"}). */
    public String getJcaKeyAlg() {
        return jcaKeyAlg;
    }

    /** Returns the JWT {@code alg} header value (e.g., {@code "EdDSA"}, {@code "ES256"}). */
    public String jwtAlg() {
        return jwtAlg;
    }

    /**
     * Returns {@code true} if this algorithm uses compact (fixed-length) signature encoding.
     * Only Ed25519-based algorithms produce fixed 64-byte signatures.
     */
    public boolean isCompactSig() {
        return this == ED25519 || this == ED25519_MLDSA65;
    }

    /**
     * Returns {@code true} if this algorithm is a hybrid (classical + PQ composite).
     * Hybrid signatures carry both a classical and a post-quantum component.
     */
    public boolean isHybrid() {
        return this == ED25519_MLDSA65 || this == ECDSA_P256_MLDSA65;
    }

    /**
     * Returns {@code true} if this algorithm includes a post-quantum component
     * (either standalone or hybrid).
     */
    public boolean isPostQuantum() {
        return this == ED25519_MLDSA65 || this == ECDSA_P256_MLDSA65 || this == MLDSA65;
    }

    /**
     * Resolves an algorithm from its wire code.
     *
     * @param code the single-byte algorithm code from the envelope
     * @return the corresponding {@link Algorithm}
     * @throws IllegalArgumentException if the code is unknown or reserved (0x08)
     */
    public static Algorithm fromCode(byte code) {
        if (code == 0x08) {
            throw new IllegalArgumentException(
                "Algorithm code 0x08 is reserved for FROST (not yet standardized)"
            );
        }
        for (Algorithm alg : values()) {
            if (alg.code == code) {
                return alg;
            }
        }
        throw new IllegalArgumentException("Unknown cryptographic algorithm code: 0x" + String.format("%02X", code));
    }
}
