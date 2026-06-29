package io.github.cyfko.veridot.core;

/**
 * Registry of supported cryptographic algorithms in the Veridot protocol.
 * Unifies signature algorithms and key types to prevent implementation confusion.
 */
public enum Algorithm {
    RSA_SHA256((byte) 0x01, "SHA256withRSA", "RSA"),
    ECDSA_SHA256((byte) 0x02, "SHA256withECDSA", "EC"),
    RSA_PSS((byte) 0x03, "RSASSA-PSS", "RSA"),
    ED25519((byte) 0x04, "Ed25519", "Ed25519");

    private final byte code;
    private final String jcaSignatureAlg;
    private final String jcaKeyAlg;

    Algorithm(byte code, String jcaSignatureAlg, String jcaKeyAlg) {
        this.code = code;
        this.jcaSignatureAlg = jcaSignatureAlg;
        this.jcaKeyAlg = jcaKeyAlg;
    }

    public byte getCode() {
        return code;
    }

    public String getJcaSignatureAlg() {
        return jcaSignatureAlg;
    }

    public String getJcaKeyAlg() {
        return jcaKeyAlg;
    }

    public static Algorithm fromCode(byte code) {
        for (Algorithm alg : values()) {
            if (alg.code == code) {
                return alg;
            }
        }
        throw new IllegalArgumentException("Unknown cryptographic algorithm code: " + code);
    }
}
