package io.github.cyfko.veridot.core.impl;

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
        header.put("alg", "RS256");
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

        String signature = signRS256(unsignedToken, privateKey);
        return unsignedToken + "." + signature;
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String signRS256(String data, PrivateKey privateKey) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signed = signature.sign();
        return base64UrlEncode(signed);
    }
}
