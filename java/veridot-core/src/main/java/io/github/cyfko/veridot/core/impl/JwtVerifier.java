package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * JWT verifier for Protocol V5.
 *
 * <p>V5 changes: uses {@link Algorithm#jwtAlg()} for alg matching,
 * expiration check includes clock drift tolerance (§8.2).
 */
class JwtVerifier {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PublicKey publicKey;
    private final Algorithm expectedAlg;
    private boolean verifyExpiration = true;

    private JwtVerifier(PublicKey publicKey, Algorithm expectedAlg) {
        this.publicKey = publicKey;
        this.expectedAlg = expectedAlg;
    }

    public static JwtVerifier verifyWith(PublicKey publicKey) {
        Algorithm defaultAlg = Algorithm.ED25519; // V5 default
        if (publicKey != null) {
            String keyAlg = publicKey.getAlgorithm();
            if ("EC".equalsIgnoreCase(keyAlg)) {
                defaultAlg = Algorithm.ECDSA_P256;
            } else if ("RSA".equalsIgnoreCase(keyAlg)) {
                defaultAlg = Algorithm.RSA_SHA256;
            }
        }
        return new JwtVerifier(publicKey, defaultAlg);
    }

    public static JwtVerifier verifyWith(PublicKey publicKey, Algorithm expectedAlg) {
        return new JwtVerifier(publicKey, expectedAlg);
    }

    public JwtVerifier ignoreExpiration(boolean ignore) {
        this.verifyExpiration = !ignore;
        return this;
    }

    public Map<String, Object> parseSignedClaims(String token) throws Exception {
        if (publicKey == null) throw new IllegalStateException("Public key must be set");

        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT format");

        // 1. Read and validate the JWT header BEFORE verifying the signature to prevent algorithm confusion
        String headerJson = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> header = objectMapper.readValue(headerJson, Map.class);
        if (header == null || !header.containsKey("alg")) {
            throw new SecurityException("JWT header missing 'alg'");
        }
        String jwtAlg = (String) header.get("alg");

        // 2. V5: Use Algorithm.jwtAlg() for expected alg mapping
        String expectedJwtAlg = expectedAlg.jwtAlg();
        if (!expectedJwtAlg.equals(jwtAlg)) {
            throw new SecurityException("JWT alg mismatch: expected " + expectedJwtAlg + ", got " + jwtAlg);
        }

        String unsignedToken = parts[0] + "." + parts[1];
        byte[] signature = base64UrlDecode(parts[2]);

        if (!verifySignature(unsignedToken, signature)) {
            throw new SecurityException("Invalid JWT signature");
        }

        String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = objectMapper.readValue(payloadJson, Map.class);

        // V5: Expiration check with clock drift tolerance (§8.2)
        if (verifyExpiration && claims.containsKey("exp")) {
            long exp = ((Number) claims.get("exp")).longValue();
            long nowSec = Instant.now().getEpochSecond();
            if (nowSec > exp + Config.MAX_CLOCK_DRIFT_SECONDS) {
                throw new SecurityException("JWT has expired (exp=" + exp
                    + ", now=" + nowSec + ", drift=" + Config.MAX_CLOCK_DRIFT_SECONDS + "s)");
            }
        }

        return claims;
    }

    private boolean verifySignature(String data, byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance(expectedAlg.getJcaSignatureAlg());
        if (expectedAlg == Algorithm.RSA_PSS) {
            try {
                signature.setParameter(new java.security.spec.PSSParameterSpec(
                    "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
                ));
            } catch (Exception ignored) {}
        }
        signature.initVerify(publicKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        return signature.verify(signatureBytes);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
