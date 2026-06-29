package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

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
        Algorithm defaultAlg = Algorithm.RSA_SHA256;
        if (publicKey != null) {
            String keyAlg = publicKey.getAlgorithm();
            if ("EC".equalsIgnoreCase(keyAlg)) {
                defaultAlg = Algorithm.ECDSA_SHA256;
            } else if ("Ed25519".equalsIgnoreCase(keyAlg)) {
                defaultAlg = Algorithm.ED25519;
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

        // 2. Map the expected Algorithm enum to expected JWT alg string
        String expectedJwtAlg;
        if (expectedAlg == Algorithm.RSA_SHA256) {
            expectedJwtAlg = "RS256";
        } else if (expectedAlg == Algorithm.ECDSA_SHA256) {
            expectedJwtAlg = "ES256";
        } else if (expectedAlg == Algorithm.RSA_PSS) {
            expectedJwtAlg = "PS256";
        } else if (expectedAlg == Algorithm.ED25519) {
            expectedJwtAlg = "EdDSA";
        } else {
            throw new SecurityException("Unsupported expected algorithm: " + expectedAlg);
        }

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

        if (verifyExpiration && claims.containsKey("exp")) {
            long exp = ((Number) claims.get("exp")).longValue();
            if (Instant.now().getEpochSecond() > exp) {
                throw new SecurityException("JWT has expired");
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
