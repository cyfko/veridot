package io.github.cyfko.veridot.core.impl;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.Signer;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

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
 *   [mode]:[base64-encoded public key]:[expiry timestamp in seconds]:[optional token (if uuid mode)]
 * </code>
 * </p>
 *
 * <p>This implementation is well-suited for stateless verification in distributed systems
 * where private keys are short-lived and verification relies on previously published public key metadata.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
public class GenericSignerVerifier implements Signer, Verifier {

    private static final Logger logger = Logger.getLogger(GenericSignerVerifier.class.getName());
    private static final KeyFactory keyFactory;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Broker broker;
    private final ScheduledExecutorService scheduler;
    private final String generatedIdSalt;
    private final DataTransformer dataTransformer;
    private KeyPair keyPair;
    private long lastExecutionTime = 0;

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
     * @param broker the broker used to publish public key metadata to consumers. Must not be {@code null}.
     * @param salt salt a static salt used to improve the unpredictability of token IDs. Must not be {@code null} nor blank.
     * @param dataTransformer the transformer used to respectively serialize or deserialize the data to sign or verify. Must not be {@code null}.
     */
    public GenericSignerVerifier(Broker broker, String salt, DataTransformer dataTransformer) {
        this.broker = broker;
        this.generatedIdSalt = salt;
        this.dataTransformer = dataTransformer;

        if (salt == null || salt.isBlank()) {
            throw new IllegalArgumentException("Salt cannot be null or empty");
        }

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
        this(broker, salt, new JacksonDataTransformer(objectMapper));
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
        this(broker, "secured-app", new JacksonDataTransformer(objectMapper));
    }

    @Override
    public String sign(Object data, long seconds, TokenMode mode, long trackingId) throws DataSerializationException {
        if (data == null || seconds < 0) {
            throw new IllegalArgumentException("data must not be null and duration must be positive");
        }

        final String keyId;
        try {
            keyId = generateId(trackingId, generatedIdSalt);
        } catch (Exception e) {
            logger.severe("Unable to generate a secured unique ID from the tracking identifier " + trackingId);
            throw new IllegalStateException(e.getMessage());
        }

        String token;
        try {
            Instant now = Instant.now();
            Date issuedAt = Date.from(now);
            Date expiration = Date.from(now.plus(Duration.ofSeconds(seconds)));

            // Serialize the payload and sign the JWT
            token = JwtMaker.builder()
                    .subject(keyId)
                    .claim("data", dataTransformer.serialize(data))
                    .issuedAt(issuedAt.toInstant())
                    .expiration(expiration.toInstant())
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
            logger.severe("Failed to send metadata of key ID " + keyId + " to broker.");
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode or sign data: " + e.getMessage());
        }
    }

    @Override
    public Object verify(String token) throws BrokerExtractionException {
        try {
            String keyId = getKeyId(token);
            Map<String, Object> claims = getClaims(keyId, token);
            String data = (String) claims.get("data");
            return dataTransformer.deserialize(data);
        } catch (BrokerExtractionException | IndexOutOfBoundsException | DataDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Failed to extract or deserialize data from token -> " + e.getMessage());
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
            JsonNode node = objectMapper.readValue(payloadJson, JsonNode.class);
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
    private Map<String, Object> getClaims(String keyId, String token) {
        String message = broker.get(keyId);
        logger.info("Observed token of key ID " + keyId + " is " + message);

        String[] parts = message.split(":");
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(parts[1]);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKeyBytes));

            return switch (TokenMode.valueOf(parts[0])) {
                case jwt -> JwtVerifier
                        .verifyWith(publicKey)
                        .parseSignedClaims(token);

                case id -> JwtVerifier
                        .verifyWith(publicKey)
                        .parseSignedClaims(parts[3]);
            };
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unexpected token format or metadata: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild public key: " + e.getMessage(), e);
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
                logger.info("Ephemeral key pair rotated.");
            } catch (Exception e) {
                logger.severe("Failed to generate key pair: " + e.getMessage());
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
