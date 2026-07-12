package io.github.cyfko.veridot.core.impl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Deterministic subject identifier computation for Protocol V5 (§5.1).
 *
 * <p>Subject format: {@code CN@base64url(SHA-256(publicKey.getEncoded()))[0:32]}
 *
 * <p>The hash portion is exactly 32 characters of base64url (no padding),
 * corresponding to 192 bits of SHA-256 output (24 bytes → 32 base64url chars).
 * This provides 2^{96} collision resistance under birthday attack, sufficient
 * for instance identification in operational deployments.
 */
public final class SubjectComputer {

    private static final int HASH_DISPLAY_LENGTH = 32; // 32 base64url chars = 24 bytes = 192 bits
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private SubjectComputer() {} // non-instantiable

    /**
     * Computes the deterministic V5 subject identifier.
     *
     * @param cn the common name (human-readable service identifier, e.g. "orders-service")
     * @param publicKey the instance's public key
     * @return the subject string in the format {@code cn@<32-char-base64url-hash>}
     * @throws IllegalArgumentException if cn is null/blank or publicKey is null
     */
    public static String compute(String cn, PublicKey publicKey) {
        if (cn == null || cn.isBlank()) {
            throw new IllegalArgumentException("CN must not be null or blank");
        }
        if (cn.contains("@")) {
            throw new IllegalArgumentException("CN must not contain '@': " + cn);
        }
        if (publicKey == null) {
            throw new IllegalArgumentException("PublicKey must not be null");
        }

        byte[] pkBytes = publicKey.getEncoded();
        byte[] sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256").digest(pkBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }

        // base64url(sha256) produces 43 chars for 32 bytes; we take the first 32
        String fullHash = B64URL.encodeToString(sha256);
        String truncatedHash = fullHash.substring(0, HASH_DISPLAY_LENGTH);

        return cn + "@" + truncatedHash;
    }

    /**
     * Extracts the CN portion from a V5 subject identifier.
     *
     * @param subject the full subject string (e.g. "orders@abc123...")
     * @return the CN portion before the '@'
     * @throws IllegalArgumentException if the subject does not contain '@'
     */
    public static String extractCn(String subject) {
        int at = requireAt(subject);
        return subject.substring(0, at);
    }

    /**
     * Extracts the hash portion from a V5 subject identifier.
     *
     * @param subject the full subject string
     * @return the base64url hash portion after the '@'
     * @throws IllegalArgumentException if the subject does not contain '@'
     */
    public static String extractHash(String subject) {
        int at = requireAt(subject);
        return subject.substring(at + 1);
    }

    /**
     * Returns {@code true} if the subject follows the instance-scoped format
     * ({@code CN@hash}), indicating single-key-per-instance identity (§5.1).
     *
     * @param subject the subject string to check
     * @return true if the subject contains '@'
     */
    public static boolean isInstanceScoped(String subject) {
        return subject != null && subject.contains("@");
    }

    private static int requireAt(String subject) {
        if (subject == null) {
            throw new IllegalArgumentException("Subject must not be null");
        }
        int at = subject.indexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("Subject does not contain '@': " + subject);
        }
        return at;
    }
}
