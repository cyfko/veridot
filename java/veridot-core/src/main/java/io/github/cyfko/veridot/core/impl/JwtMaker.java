package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

class JwtMaker {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object> claims = new HashMap<>();
    private String subject;
    private Instant issuedAt;
    private Instant expiration;
    private PrivateKey privateKey;

    public static JwtMaker builder() {
        return new JwtMaker();
    }

    public JwtMaker subject(String sub) {
        this.subject = sub;
        return this;
    }

    public JwtMaker claim(String name, Object value) {
        this.claims.put(name, value);
        return this;
    }

    public JwtMaker issuedAt(Instant iat) {
        this.issuedAt = iat;
        return this;
    }

    private Algorithm alg = Algorithm.RSA_SHA256; // default to RSA

    public JwtMaker alg(Algorithm alg) {
        this.alg = alg;
        return this;
    }

    public JwtMaker expiration(Instant exp) {
        this.expiration = exp;
        return this;
    }

    public JwtMaker signWith(PrivateKey privateKey) {
        this.privateKey = privateKey;
        return this;
    }

    public String compact() throws Exception {
        if (privateKey == null) throw new IllegalStateException("Private key must be set");

        Map<String, Object> header = new HashMap<>();
        String algStr = "RS256";
        if (alg == Algorithm.ECDSA_SHA256) {
            algStr = "ES256";
        } else if (alg == Algorithm.RSA_PSS) {
            algStr = "PS256";
        } else if (alg == Algorithm.ED25519) {
            algStr = "EdDSA";
        }
        header.put("alg", algStr);
        header.put("typ", "JWT");

        Map<String, Object> payload = new HashMap<>(claims);
        if (subject != null) payload.put("sub", subject);
        if (issuedAt != null) payload.put("iat", issuedAt.getEpochSecond());
        if (expiration != null) payload.put("exp", expiration.getEpochSecond());

        String headerJson = objectMapper.writeValueAsString(header);
        String payloadJson = objectMapper.writeValueAsString(payload);

        String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String unsignedToken = encodedHeader + "." + encodedPayload;

        String signature = signToken(unsignedToken, privateKey, alg);
        return unsignedToken + "." + signature;
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String signToken(String data, PrivateKey privateKey, Algorithm alg) throws Exception {
        String jcaAlg = alg.getJcaSignatureAlg();
        Signature signature = Signature.getInstance(jcaAlg);
        if (alg == Algorithm.RSA_PSS) {
            try {
                signature.setParameter(new java.security.spec.PSSParameterSpec(
                    "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, 32, 1
                ));
            } catch (Exception ignored) {}
        }
        signature.initSign(privateKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signed = signature.sign();
        return base64UrlEncode(signed);
    }
}
