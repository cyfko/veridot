package io.github.cyfko.veridok.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.cyfko.dverify.*;
import io.github.cyfko.veridok.kafka.Broker;
import io.github.cyfko.veridok.kafka.Signer;
import io.github.cyfko.veridok.kafka.TokenMode;
import io.github.cyfko.veridok.kafka.Verifier;
import io.github.cyfko.veridok.kafka.exceptions.DataExtractionException;
import io.github.cyfko.veridok.kafka.exceptions.JsonEncodingException;
import io.github.cyfko.veridok.kafka.util.JacksonUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.*;

/**
 * Default implementation of both {@link Signer} and {@link Verifier} interfaces using ephemeral asymmetric keys.
 *
 * <p>This class handles:</p>
 * <ul>
 *   <li>Signing objects into time-bound JWT tokens (or UUIDs, depending on {@link TokenMode})</li>
 *   <li>Periodic generation (rotation) of RSA key pairs used for signing</li>
 *   <li>Publishing cryptographic metadata to a {@link Broker}, for verification by distributed consumers</li>
 *   <li>Verifying tokens using the public key advertised by the token issuer</li>
 * </ul>
 *
 * <p>
 * The metadata pushed to the broker includes:
 * <code>
 *   [mode]:[base64-encoded public key]:[expiry timestamp in millis]:[optional token (if uuid mode)]
 * </code>
 * </p>
 *
 * <p>This implementation is well-suited for stateless verification in distributed systems
 * where private keys are short-lived and verification relies on previously published public key metadata.
 * </p>
 *
 * @author Frank KOSSI
 * @since 3.0.0
 */
public class GenericSignerVerifier implements Signer, Verifier {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GenericSignerVerifier.class);
    private static final KeyFactory keyFactory;

    private final Broker broker;
    private final ScheduledExecutorService scheduler;
    private KeyPair keyPair;
    private long lastExecutionTime = 0;
    private String generatedIdSalt = "secured-app";

    static {
        try {
            keyFactory = KeyFactory.getInstance(Constant.ASYMMETRIC_KEYPAIR_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KeyFactory: " + e.getMessage(), e);
        }
    }

    /**
     * Constructs a new {@code GenericSignerVerifier} with the given metadata broker.
     *
     * <p>
     * Upon creation, a key pair is immediately generated, and a recurring task is scheduled
     * to rotate keys at fixed intervals.
     * </p>
     *
     * @param broker the broker used to publish public key metadata to consumers
     * @param salt salt a static salt used to improve the unpredictability of token IDs
     */
    public GenericSignerVerifier(Broker broker, String salt) {
        this(broker);
        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("Salt cannot be null or empty");
        }
        this.generatedIdSalt = salt;
    }

    /**
     * Constructs a new {@code GenericSignerVerifier} with the given metadata broker.
     *
     * <p>
     * On creation, a key pair is immediately generated, and a recurring task is scheduled
     * to rotate keys at fixed intervals.
     * </p>
     *
     * @param broker the broker used to publish public key metadata to consumers
     */
    public GenericSignerVerifier(Broker broker) {
        this.broker = broker;

        // Generate initial key pair
        generatedKeysPair();

        // Schedule periodic key rotation
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::generatedKeysPair,
                0,
                Constant.KEYS_ROTATION_MINUTES,
                TimeUnit.MINUTES
        );
    }

    @Override
    public String sign(Object data, long seconds, TokenMode mode, long trackingId) throws JsonEncodingException {
        if (data == null || seconds < 0) {
            throw new IllegalArgumentException("data must not be null and duration must be positive");
        }

        final String keyId;
        try {
            keyId = generateId(trackingId, generatedIdSalt);
        } catch (Exception e) {
            log.error("Unable to generate a secured unique ID from the tracking identifier {}", trackingId);
            throw new JsonEncodingException(e.getMessage());
        }

        String token;
        try {
            Instant now = Instant.now();
            Date issuedAt = Date.from(now);
            Date expiration = Date.from(now.plus(Duration.ofSeconds(seconds)));

            // Serialize the payload and sign the JWT
            token = Jwts.builder()
                    .subject(keyId)
                    .claim("data", JacksonUtil.toJson(data))
                    .issuedAt(issuedAt)
                    .expiration(expiration)
                    .signWith(keyPair.getPrivate())
                    .compact();

            // Publish metadata to broker
            String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String metadata = switch (mode) {
                case jwt -> String.format("%s:%s:%d:", mode.name(), publicKeyBase64, expiration.getTime());
                case id -> String.format("%s:%s:%d:%s", mode.name(), publicKeyBase64, expiration.getTime(), token);
            };

            broker.send(keyId, metadata).get(3, TimeUnit.MINUTES);

            return (mode == TokenMode.jwt) ? token : keyId;
        } catch (ExecutionException | InterruptedException e) {
            log.error("Failed to send metadata to broker for keyId {}: {}", keyId, e.getMessage());
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new JsonEncodingException("Failed to encode or sign data: " + e.getMessage());
        }
    }

    @Override
    public <T> T verify(String token, Class<T> clazz) throws DataExtractionException {
        try {
            String keyId = getKeyId(token);
            Claims claims = getClaims(keyId, token);
            String json = claims.get("data", String.class);
            return JacksonUtil.fromJson(json, clazz);
        } catch (DataExtractionException | IndexOutOfBoundsException e) {
            throw e;
        } catch (Exception e) {
            throw new DataExtractionException("Failed to extract or deserialize data from token -> " + e.getMessage());
        }
    }

    /**
     * Determines the key identifier for a given token.
     * <p>
     * If the token appears to be a JWT (i.e., contains dot separators), it extracts the {@code sub} field
     * from the payload. Otherwise, it assumes the token itself *is* the key ID (UUID mode).
     *
     * @param token the token whose key ID is to be determined
     * @return the resolved key ID
     * @throws Exception if the JWT is malformed or payload decoding fails
     */
    private static String getKeyId(String token) throws Exception {
        if (token.contains(".")) {
            String payloadBase64 = token.split("\\.")[1];
            String payloadJson = new String(Base64.getDecoder().decode(payloadBase64));
            JsonNode node = JacksonUtil.fromJson(payloadJson, JsonNode.class);
            return node.get("sub").asText();
        }
        return token;
    }

    /**
     * Retrieves and parses the JWT claims using metadata fetched from the broker.
     *
     * @param keyId the key ID used to retrieve metadata
     * @param token the input token (either the JWT or the UUID)
     * @return parsed claims from the appropriate token
     */
    private Claims getClaims(String keyId, String token) {
        String message = broker.get(keyId);
        log.info("Observed token for keyId {} is {}", keyId, message);

        String[] parts = message.split(":");
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(parts[1]);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            return switch (TokenMode.valueOf(parts[0])) {
                case jwt -> Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                case id -> Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(parts[3])
                        .getPayload();
            };
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException("Failed to rebuild public key: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unexpected token format or metadata: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a new ephemeral asymmetric key pair.
     * <p>
     * This method is invoked on a schedule, and will only rotate the key pair
     * if sufficient time has elapsed since the last generation.
     * </p>
     */
    private void generatedKeysPair() {
        long now = System.currentTimeMillis();

        if (now - lastExecutionTime >= Constant.KEYS_ROTATION_MINUTES * 60 * 1000 || lastExecutionTime == 0) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(Constant.ASYMMETRIC_KEYPAIR_ALGORITHM);
                keyPair = generator.generateKeyPair();
                log.info("Ephemeral key pair rotated.");
            } catch (Exception e) {
                log.error("Failed to generate key pair: {}", e.getMessage());
            } finally {
                lastExecutionTime = now;
            }
        }
    }

    private static String generateId(long trackingId, String salt) throws Exception {
        String toHash = salt + "-" + trackingId;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(toHash.getBytes("UTF-8"));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        return b64.substring(0, 22);
    }
}
