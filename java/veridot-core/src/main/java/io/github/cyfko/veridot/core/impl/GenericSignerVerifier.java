package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link DataSigner}, {@link TokenVerifier}, {@link TokenRevoker},
 * and {@link TokenTracker} conforming to Protocol Veridot V2.
 *
 * <p>This class handles:</p>
 * <ul>
 *   <li>Signing objects into time-bound JWT tokens using ephemeral RSA key pairs</li>
 *   <li>Publishing V2-formatted metadata to a {@link MetadataBroker}</li>
 *   <li>Verifying tokens by fetching and parsing V2 metadata from the broker</li>
 *   <li>Revoking specific sequences or entire groups via structured __REVOKE__ messages (§5)</li>
 *   <li>Querying whether active tokens exist for a group, token, or messageId</li>
 *   <li>Enforcing session capacity limits with configurable eviction policies</li>
 *   <li>Resolving distributed configuration from broker hierarchy: local → site → global → default (§4)</li>
 *   <li>Validating clock drift ±5 minutes (§9.1)</li>
 * </ul>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 */
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    // ── Eviction policy ───────────────────────────────────────────────────────

    /**
     * Eviction policy for session capacity management.
     * Applied when {@code maxSessions} is reached and a new sequence must be added.
     */
    public enum EvictionPolicy {
        /** First In, First Out — evicts the oldest sequence (lowest timestamp). */
        FIFO,
        /** Last In, First Out — evicts the newest sequence (highest timestamp). */
        LIFO,
        /** Least Recently Used — evicts the oldest sequence (same as FIFO in this implementation). */
        LRU,
        /** Reject — refuses the signing attempt instead of evicting an existing session. */
        REJECT
    }

    // ── Static state ──────────────────────────────────────────────────────────

    private static final Logger logger = Logger.getLogger(GenericSignerVerifier.class.getName());
    private static final KeyFactory keyFactory;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Maximum allowed clock drift between signer and verifier (§9.1). */
    private static final long MAX_CLOCK_DRIFT_SECONDS = 300; // 5 minutes

    /** How long resolved configs are cached before re-querying the broker. */
    private static final long CONFIG_CACHE_TTL_SECONDS = 60;

    static {
        try {
            keyFactory = KeyFactory.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KeyFactory: " + e.getMessage(), e);
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private final MetadataBroker metadataBroker;
    private final String salt;
    private final EffectiveConfig defaultConfig;
    private final ScheduledExecutorService scheduler;
    private volatile KeyPair keyPair;
    private long lastExecutionTime = 0;

    /** Config cache: groupId → resolved config with timestamp. */
    private final ConcurrentHashMap<String, CachedConfig> configCache = new ConcurrentHashMap<>();

    private record CachedConfig(EffectiveConfig config, long resolvedAt) {
        boolean isExpired() {
            return Instant.now().getEpochSecond() - resolvedAt > CONFIG_CACHE_TTL_SECONDS;
        }
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Constructs a {@code GenericSignerVerifier} with no session limit.
     *
     * @param metadataBroker broker for publishing/retrieving metadata; must not be {@code null}
     * @param salt           static salt for additional unpredictability; must not be {@code null} or blank
     */
    public GenericSignerVerifier(MetadataBroker metadataBroker, String salt) {
        this(metadataBroker, salt, -1, EvictionPolicy.FIFO);
    }

    /**
     * Constructs a {@code GenericSignerVerifier} with optional session capacity management.
     *
     * @param metadataBroker broker for publishing/retrieving metadata; must not be {@code null}
     * @param salt           static salt for unpredictability; must not be {@code null} or blank
     * @param maxSessions    maximum concurrent active sequences per group; {@code -1} means unlimited
     * @param policy         eviction policy to apply when {@code maxSessions} is reached; must not be {@code null}
     */
    public GenericSignerVerifier(MetadataBroker metadataBroker, String salt, int maxSessions, EvictionPolicy policy) {
        if (metadataBroker == null) throw new IllegalArgumentException("MetadataBroker cannot be null");
        if (salt == null || salt.isBlank()) throw new IllegalArgumentException("Salt cannot be null or empty");
        if (policy == null) throw new IllegalArgumentException("EvictionPolicy cannot be null");

        this.metadataBroker = metadataBroker;
        this.salt = salt;
        this.defaultConfig = new EffectiveConfig(maxSessions, policy, -1);

        generatedKeysPair();

        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::generatedKeysPair,
                0,
                Config.KEYS_ROTATION_MINUTES,
                TimeUnit.MINUTES
        );
    }

    // ── DataSigner ────────────────────────────────────────────────────────────

    @Override
    public String sign(Object data, Configurer configurer) throws DataSerializationException {
        // 1. Validate inputs
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (configurer.getDuration() <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }

        // 2. Validate and resolve groupId
        String groupId = configurer.getGroupId();
        ProtocolV2.validateIdentifier(groupId, "groupId");

        // 3. Resolve sequenceId (auto-generate if not provided)
        String sequenceId = configurer.getSequenceId();
        if (sequenceId != null) {
            ProtocolV2.validateIdentifier(sequenceId, "sequenceId");
        } else {
            // UUID contains only hex digits and '-', fully compatible with identifier pattern
            sequenceId = UUID.randomUUID().toString();
        }

        // 4. Build messageId
        String messageId = ProtocolV2.buildMessageId(groupId, sequenceId);

        // 5. Build and sign JWT
        Instant now = Instant.now();
        long expiryEpochSecond = now.getEpochSecond() + configurer.getDuration();

        String serializedData;
        try {
            serializedData = configurer.getSerializer().apply(data);
        } catch (DataSerializationException e) {
            throw e;
        } catch (Exception e) {
            throw new DataSerializationException("Failed to serialize payload", e);
        }

        String jwt;
        try {
            jwt = JwtMaker.builder()
                    .subject(messageId)
                    .claim("data", serializedData)
                    .issuedAt(now)
                    .expiration(Instant.ofEpochSecond(expiryEpochSecond))
                    .signWith(keyPair.getPrivate())
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build signed JWT: " + e.getMessage(), e);
        }

        // 6. Build V2 metadata properties map
        String pubKeyBase64 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(keyPair.getPublic().getEncoded());

        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_MODE, Config.DEFAULT_CRYPTO_MODE);
        props.put(ProtocolV2.PROP_PUBKEY, pubKeyBase64);
        props.put(ProtocolV2.PROP_TIMESTAMP, String.valueOf(now.getEpochSecond()));
        props.put(ProtocolV2.PROP_TTL, String.valueOf(configurer.getDuration()));

        if (configurer.getDistribution() == DistributionMode.INDIRECT) {
            props.put(ProtocolV2.PROP_TOKEN, jwt);
        }

        // 7. Resolve effective config from broker hierarchy (§4) and enforce maxSessions
        EffectiveConfig effectiveConfig = resolveConfig(groupId);
        if (effectiveConfig.maxSessions() > 0) {
            enforceSessionLimit(groupId, effectiveConfig);
        }

        // 8. Publish V2 message to broker
        String v2Message = ProtocolV2.buildMessage(groupId, sequenceId, props);
        try {
            metadataBroker.send(messageId, v2Message).get(3, TimeUnit.MINUTES);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.severe("Failed to send metadata for messageId " + messageId + " to broker: " + e.getMessage());
            throw new RuntimeException("Broker publication failed", e);
        }

        // 9. Return token based on distribution mode
        return configurer.getDistribution() == DistributionMode.DIRECT ? jwt : messageId;
    }

    // ── TokenVerifier ─────────────────────────────────────────────────────────

    @Override
    public <T> T verify(String token, Function<String, T> deserializer) throws BrokerExtractionException {
        try {
            // 1. Resolve messageId and jwtToken
            final String messageId;
            final String jwtToken;

            if (ProtocolV2.isJwt(token)) {
                // DIRECT mode: token is the JWT itself; extract messageId from "sub" claim
                messageId = extractSubFromJwt(token);
                jwtToken = token;
            } else if (ProtocolV2.isMessageId(token)) {
                // INDIRECT mode: token is the messageId
                messageId = token;
                jwtToken = null; // will be resolved from broker
            } else {
                throw new BrokerExtractionException("Unrecognized token format: " + token);
            }

            // 2. Fetch V2 message from broker
            String message = metadataBroker.get(messageId); // throws BrokerExtractionException if absent/revoked

            // 3. Parse metadata
            Map<String, String> meta = ProtocolV2.parseMetadata(message);

            // 4. Validate clock drift (§9.1)
            validateClockDrift(meta);

            // 5. Validate temporal validity (TTL)
            validateTtl(meta);

            // 6. Resolve JWT for INDIRECT mode
            String resolvedJwt = jwtToken;
            if (resolvedJwt == null) {
                resolvedJwt = meta.get(ProtocolV2.PROP_TOKEN);
                if (resolvedJwt == null) {
                    throw new BrokerExtractionException("No token found in broker metadata for: " + messageId);
                }
            }

            // 7. Rebuild public key
            String pubkeyEncoded = meta.get(ProtocolV2.PROP_PUBKEY);
            byte[] pubKeyBytes = Base64.getUrlDecoder().decode(pubkeyEncoded);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            // 8. Verify JWT signature + expiration
            Map<String, Object> claims = JwtVerifier.verifyWith(publicKey).parseSignedClaims(resolvedJwt);

            // 9. Deserialize and return payload
            String data = (String) claims.get("data");
            return deserializer.apply(data);

        } catch (BrokerExtractionException | DataDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Failed to verify token: " + e.getMessage());
        }
    }

    // ── TokenRevoker ──────────────────────────────────────────────────────────

    @Override
    public void revoke(Object target) {
        if (!(target instanceof String s)) {
            throw new IllegalArgumentException("revoke target must be a String (JWT or messageId), got: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        final String messageId;
        try {
            if (ProtocolV2.isJwt(s)) {
                messageId = extractSubFromJwt(s);
            } else if (ProtocolV2.isMessageId(s)) {
                messageId = s;
            } else {
                throw new IllegalArgumentException("Unrecognized revocation target format: " + s);
            }

            String[] parts = ProtocolV2.parseMessageId(messageId);
            String groupId = parts[1];
            String sequenceId = parts[2];

            // 1. Publish formal V2 __REVOKE__ message (§5.2 — interoperability)
            String revokeKey = ProtocolV2.buildRevocationKey(groupId);
            String revokeMsg = ProtocolV2.buildRevocationMessage(groupId, sequenceId);
            metadataBroker.send(revokeKey, revokeMsg);

            // 2. Delete the actual sequence entry (immediate local effect)
            metadataBroker.send(messageId, "");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.severe("Failed to revoke target: " + e.getMessage());
            throw new RuntimeException("Revocation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void revokeGroup(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be null or blank");
        }
        try {
            // 1. Publish formal V2 __REVOKE__ message with target=__ALL__ (§5.4)
            String revokeKey = ProtocolV2.buildRevocationKey(groupId);
            String revokeMsg = ProtocolV2.buildRevocationMessage(groupId, ProtocolV2.SEQ_ALL);
            metadataBroker.send(revokeKey, revokeMsg);

            // 2. Delete all individual sequence entries
            String prefix = ProtocolV2.groupPrefix(groupId);
            List<String> keys = metadataBroker.getKeysByPrefix(prefix);
            for (String key : keys) {
                // Skip reserved keys (__REVOKE__, __CONFIG__)
                if (ProtocolV2.isReservedSequence(key)) continue;
                try {
                    metadataBroker.send(key, "");
                } catch (Exception e) {
                    logger.severe("Failed to revoke key " + key + " during group revocation: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.severe("Failed to enumerate keys for group revocation [" + groupId + "]: " + e.getMessage());
        }
    }

    // ── TokenTracker ──────────────────────────────────────────────────────────

    @Override
    public boolean hasActiveToken(Object target) {
        if (!(target instanceof String s)) {
            throw new IllegalArgumentException("hasActiveToken target must be a String, got: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        if (ProtocolV2.isJwt(s)) {
            // Extract messageId from JWT sub claim and check that specific sequence
            try {
                String messageId = extractSubFromJwt(s);
                return isMessageIdActive(messageId);
            } catch (Exception e) {
                return false;
            }
        } else if (ProtocolV2.isMessageId(s)) {
            return isMessageIdActive(s);
        } else {
            // Treat as groupId: check if any normal sequence within the group is active
            try {
                String prefix = ProtocolV2.groupPrefix(s);
                List<String> keys = metadataBroker.getKeysByPrefix(prefix);
                for (String key : keys) {
                    if (ProtocolV2.isReservedSequence(key)) continue; // skip __REVOKE__, __CONFIG__
                    if (isMessageIdActive(key)) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                logger.severe("Error checking active token for group [" + s + "]: " + e.getMessage());
                return false;
            }
        }
    }

    // ── Distributed configuration resolution (§4) ─────────────────────────────

    /**
     * Resolves the effective configuration for a group using the broker's config hierarchy:
     * <ol>
     *   <li>Local: {@code 2:<groupId>:__CONFIG__}</li>
     *   <li>Site: {@code 2:__CONFIG__:<siteId>} (if the group declares a site)</li>
     *   <li>Global: {@code 2:__CONFIG__:__ALL__}</li>
     *   <li>Default: constructor parameters</li>
     * </ol>
     */
    private EffectiveConfig resolveConfig(String groupId) {
        CachedConfig cached = configCache.get(groupId);
        if (cached != null && !cached.isExpired()) {
            return cached.config();
        }

        EffectiveConfig resolved = resolveFromBroker(groupId);
        configCache.put(groupId, new CachedConfig(resolved, Instant.now().getEpochSecond()));
        return resolved;
    }

    private EffectiveConfig resolveFromBroker(String groupId) {
        // Priority 1: Local config
        EffectiveConfig local = tryParseConfig(ProtocolV2.buildLocalConfigKey(groupId));
        if (local != null) return local;

        // Priority 2: Site config (if the group declares a site in its messages)
        String siteId = resolveSiteForGroup(groupId);
        if (siteId != null) {
            EffectiveConfig site = tryParseConfig(ProtocolV2.buildSiteConfigKey(siteId));
            if (site != null) return site;
        }

        // Priority 3: Global config
        EffectiveConfig global = tryParseConfig(ProtocolV2.buildGlobalConfigKey());
        if (global != null) return global;

        // Priority 4: Constructor defaults
        return defaultConfig;
    }

    /**
     * Attempts to read and parse a config message from the broker.
     * Returns {@code null} if the key is absent, the message is malformed,
     * or the configuration has expired ({@code validUntil < now}).
     */
    private EffectiveConfig tryParseConfig(String configKey) {
        try {
            String msg = metadataBroker.get(configKey);
            if (msg == null || msg.isBlank()) return null;
            Map<String, String> meta = ProtocolV2.parseMetadata(msg);

            // Validate: timestamp and validUntil present and config not expired (§4.2.4)
            String tsStr = meta.get(ProtocolV2.PROP_TIMESTAMP);
            String vuStr = meta.get(ProtocolV2.PROP_VALID_UNTIL);
            if (tsStr == null || vuStr == null) return null;
            long validUntil = Long.parseLong(vuStr);
            if (Instant.now().getEpochSecond() > validUntil) return null; // config expired

            // Parse optional properties, falling back to constructor defaults
            int ms = meta.containsKey(ProtocolV2.PROP_MAX_SESSIONS)
                    ? Integer.parseInt(meta.get(ProtocolV2.PROP_MAX_SESSIONS))
                    : defaultConfig.maxSessions();
            EvictionPolicy pol = meta.containsKey(ProtocolV2.PROP_POLICY)
                    ? EvictionPolicy.valueOf(meta.get(ProtocolV2.PROP_POLICY))
                    : defaultConfig.policy();
            long dttl = meta.containsKey(ProtocolV2.PROP_DEFAULT_TTL)
                    ? Long.parseLong(meta.get(ProtocolV2.PROP_DEFAULT_TTL))
                    : defaultConfig.defaultTTL();

            return new EffectiveConfig(ms, pol, dttl);
        } catch (Exception e) {
            return null; // malformed config → skip
        }
    }

    /**
     * Looks at existing messages for a group to find a declared {@code site} property.
     */
    private String resolveSiteForGroup(String groupId) {
        try {
            String prefix = ProtocolV2.groupPrefix(groupId);
            List<String> keys = metadataBroker.getKeysByPrefix(prefix);
            for (String key : keys) {
                if (ProtocolV2.isReservedSequence(key)) continue;
                try {
                    String msg = metadataBroker.get(key);
                    if (msg == null || msg.isBlank()) continue;
                    Map<String, String> meta = ProtocolV2.parseMetadata(msg);
                    String site = meta.get(ProtocolV2.PROP_SITE);
                    if (site != null && !site.isBlank()) return site;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the {@code sub} claim value from a JWT without verifying its signature.
     */
    private static String extractSubFromJwt(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed JWT: less than 2 parts");
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode node = objectMapper.readValue(payloadJson, JsonNode.class);
        JsonNode subNode = node.get("sub");
        if (subNode == null) {
            throw new IllegalArgumentException("JWT has no 'sub' claim");
        }
        return subNode.asText();
    }

    /**
     * Validates that the message timestamp is not in the future by more than 5 minutes.
     * This detects clock skew between signer and verifier (§9.1).
     */
    private static void validateClockDrift(Map<String, String> meta) throws BrokerExtractionException {
        String tsStr = meta.get(ProtocolV2.PROP_TIMESTAMP);
        if (tsStr == null) return;
        long timestamp = Long.parseLong(tsStr);
        long now = Instant.now().getEpochSecond();
        if (timestamp > now + MAX_CLOCK_DRIFT_SECONDS) {
            throw new BrokerExtractionException(
                    "Message timestamp is " + (timestamp - now) + "s in the future (max drift: ±5min)");
        }
    }

    /**
     * Validates the temporal validity of a V2 metadata map.
     * Throws {@link BrokerExtractionException} if the TTL has elapsed.
     */
    private static void validateTtl(Map<String, String> meta) throws BrokerExtractionException {
        String ttlStr = meta.get(ProtocolV2.PROP_TTL);
        if (ttlStr == null) return; // no TTL = no expiration

        String tsStr = meta.get(ProtocolV2.PROP_TIMESTAMP);
        if (tsStr == null) return;

        long timestamp = Long.parseLong(tsStr);
        long ttl = Long.parseLong(ttlStr);
        if (Instant.now().getEpochSecond() >= timestamp + ttl) {
            throw new BrokerExtractionException("Token metadata has expired");
        }
    }

    /**
     * Returns true if the broker entry for the given messageId exists, is not expired,
     * and does not exhibit clock drift beyond ±5 minutes.
     */
    private boolean isMessageIdActive(String messageId) {
        try {
            String message = metadataBroker.get(messageId);
            if (message == null || message.isBlank()) return false;
            Map<String, String> meta = ProtocolV2.parseMetadata(message);

            // Clock drift check (§9.1)
            String tsStr = meta.get(ProtocolV2.PROP_TIMESTAMP);
            if (tsStr != null) {
                long ts = Long.parseLong(tsStr);
                if (ts > Instant.now().getEpochSecond() + MAX_CLOCK_DRIFT_SECONDS) return false;
            }

            // TTL check
            String ttlStr = meta.get(ProtocolV2.PROP_TTL);
            if (ttlStr == null) return true; // no TTL = never expires
            if (tsStr == null) return true;
            long timestamp = Long.parseLong(tsStr);
            long ttl = Long.parseLong(ttlStr);
            return Instant.now().getEpochSecond() < timestamp + ttl;
        } catch (BrokerExtractionException e) {
            return false; // key not found = not active
        } catch (Exception e) {
            logger.severe("Error checking isMessageIdActive for [" + messageId + "]: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enforces the maxSessions limit for a group before a new signing.
     * If the active session count is at or above the limit, evicts one sequence.
     */
    private void enforceSessionLimit(String groupId, EffectiveConfig config) {
        try {
            String prefix = ProtocolV2.groupPrefix(groupId);
            List<String> allKeys = metadataBroker.getKeysByPrefix(prefix);

            // Filter to non-expired, non-reserved active keys; garbage-collect expired entries
            List<String> validKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (ProtocolV2.isReservedSequence(key)) continue;
                if (isMessageIdActive(key)) {
                    validKeys.add(key);
                } else {
                    // Lazy GC: remove expired entries from the broker to prevent stale accumulation
                    try {
                        metadataBroker.send(key, "");
                    } catch (Exception gc) {
                        logger.fine("Failed to GC expired key " + key + ": " + gc.getMessage());
                    }
                }
            }

            while (validKeys.size() >= config.maxSessions()) {
                if (config.policy() == EvictionPolicy.REJECT) {
                    throw new SessionCapacityExceededException(groupId, config.maxSessions());
                }
                String toEvict = selectEvictionTarget(validKeys, config.policy());
                try {
                    // Publish formal revocation, then delete
                    String[] parts = ProtocolV2.parseMessageId(toEvict);
                    String revokeKey = ProtocolV2.buildRevocationKey(groupId);
                    String revokeMsg = ProtocolV2.buildRevocationMessage(groupId, parts[2]);
                    metadataBroker.send(revokeKey, revokeMsg);
                    metadataBroker.send(toEvict, "").get(3, TimeUnit.MINUTES);
                } catch (Exception e) {
                    logger.severe("Failed to evict key " + toEvict + ": " + e.getMessage());
                }
                validKeys.remove(toEvict);
            }
        } catch (SessionCapacityExceededException e) {
            throw e; // must propagate to caller
        } catch (Exception e) {
            logger.severe("Error enforcing session limit for group [" + groupId + "]: " + e.getMessage());
        }
    }

    /**
     * Selects the key to evict from a list of active keys according to the given policy.
     * FIFO and LRU both select the key with the smallest {@code timestamp}.
     * LIFO selects the key with the largest {@code timestamp}.
     */
    private String selectEvictionTarget(List<String> keys, EvictionPolicy policy) {
        String selected = keys.get(0);
        long selectedTs = getTimestampForKey(selected);

        for (int i = 1; i < keys.size(); i++) {
            long ts = getTimestampForKey(keys.get(i));
            switch (policy) {
                case FIFO, LRU -> {
                    if (ts < selectedTs) { selected = keys.get(i); selectedTs = ts; }
                }
                case LIFO -> {
                    if (ts > selectedTs) { selected = keys.get(i); selectedTs = ts; }
                }
            }
        }
        return selected;
    }

    /**
     * Retrieves the {@code timestamp} property from the broker entry for the given key.
     * Returns {@code 0} if the key is unavailable or malformed.
     */
    private long getTimestampForKey(String key) {
        try {
            String msg = metadataBroker.get(key);
            Map<String, String> meta = ProtocolV2.parseMetadata(msg);
            return Long.parseLong(meta.getOrDefault(ProtocolV2.PROP_TIMESTAMP, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Generates a new ephemeral RSA key pair if the rotation interval has elapsed.
     * Invoked once at construction and then on the scheduler interval.
     */
    private void generatedKeysPair() {
        long now = System.currentTimeMillis();
        if (now - lastExecutionTime >= Config.KEYS_ROTATION_MINUTES * 60 * 1000L || lastExecutionTime == 0) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
                keyPair = generator.generateKeyPair();
                logger.log(Level.FINEST, "Rotating ephemeral RSA keys.");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to generate key pair: " + e.getMessage());
            } finally {
                lastExecutionTime = now;
            }
        }
    }
}
