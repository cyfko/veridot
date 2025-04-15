package io.github.cyfko.veridok.core.impl;

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
    private boolean verifyExpiration = true;

    private JwtVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    public static JwtVerifier verifyWith(PublicKey publicKey) {
        return new JwtVerifier(publicKey);
    }

    public JwtVerifier ignoreExpiration(boolean ignore) {
        this.verifyExpiration = !ignore;
        return this;
    }

    public Map<String, Object> parseSignedClaims(String token) throws Exception {
        if (publicKey == null) throw new IllegalStateException("Public key must be set");

        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid JWT format");

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
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        return signature.verify(signatureBytes);
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
