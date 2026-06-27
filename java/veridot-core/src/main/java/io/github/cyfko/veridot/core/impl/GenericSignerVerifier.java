package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference implementation of {@link DataSigner}, {@link TokenVerifier}, {@link TokenRevoker},
 * and {@link TokenTracker}, conforming to the Veridot Protocol V2, v3.0 security model.
 *
 * <p>This class provides the complete token lifecycle — signing, verification, revocation,
 * and active-state tracking — backed by a {@link MetadataBroker} that propagates
 * verification metadata across services in a distributed system.</p>
 *
 * <h2>Trust model (v3.0)</h2>
 * <p>The broker is a <em>transport only</em>. Every key announcement received from the
 * broker is validated through a {@link TrustAnchor} before the ephemeral public key is
 * trusted. This prevents a broker-level attacker from injecting fraudulent keys.</p>
 *
 * <p>Key capabilities include:</p>
 * <ul>
 *   <li>Issuing cryptographically signed tokens and publishing Protocol V2 verification metadata
 *       (with a long-term signature over the announcement) to a {@link MetadataBroker}</li>
 *   <li>Verifying tokens by fetching, validating via {@link TrustAnchor}, and parsing V2 metadata
 *       from the broker</li>
 *   <li>Revoking specific sessions or entire groups via Protocol V2 structured revocation (§5)
 *       with signed tombstones (F7 — replay-safe)</li>
 *   <li>Querying whether active tokens exist for a group, a token, or a Protocol V2 messageId</li>
 *   <li>Enforcing per-group session capacity limits with configurable eviction policies</li>
 *   <li>Resolving distributed configuration from the broker hierarchy:
 *       local → site → global → constructor default (§4)</li>
 *   <li>Detecting clock drift between issuer and verifier, tolerating up to ±5 minutes (§9.1)</li>
 * </ul>
 *
 * <h2>Usage example</h2>
 * <pre>{@code
 * // Production — with a real TrustAnchor backed by a KMS or static long-term key
 * TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> loadFromVault(signerId);
 * MetadataBroker broker = new KafkaMetadataBrokerAdapter("localhost:9092");
 * var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", myLongTermPrivateKey);
 *
 * // Sign
 * String token = sv.sign("user@example.com",
 *     BasicConfigurer.builder()
 *         .groupId("user-123")
 *         .sequenceId("session-A")
 *         .validity(3600)
 *         .build());
 *
 * // Verify
 * VerifiedData<String> result = sv.verify(token, s -> s);
 * String email   = result.data();       // "user@example.com"
 * String group   = result.groupId();    // "user-123"
 * String session = result.sequenceId(); // "session-A"
 *
 * // Revoke
 * sv.revoke(group, session);
 * }</pre>
 *
 * @implNote This implementation uses <strong>JWT (JWS Compact Serialization)</strong> as its
 *           signed token format and <strong>ephemeral RSA-3072 key pairs</strong> rotated on a
 *           configurable schedule (default: every 24 hours, overridable via the
 *           {@code VDOT_KEYS_ROTATION_MINUTES} environment variable). These are implementation
 *           details and are not part of the Veridot Protocol V2 specification.
 *
 * @author Frank KOSSI
 * @since 3.0.0
 * @see MetadataBroker
 * @see TrustAnchor
 * @see BasicConfigurer
 * @see VerifiedData
 */
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    // ── Eviction policy ───────────────────────────────────────────────────────

    /**
     * Determines the strategy applied when a new signing attempt would exceed the
     * {@code maxSessions} limit configured for a group.
     *
     * <p>The policy is evaluated at signing time. Only {@link #REJECT} prevents a token
     * from being issued; all other policies evict an existing session to make room.</p>
     *
     * @see GenericSignerVerifier#GenericSignerVerifier(MetadataBroker, TrustAnchor, String, PrivateKey, int, EvictionPolicy)
     */
    public enum EvictionPolicy {
        /**
         * <strong>First In, First Out</strong> — evicts the session with the earliest
         * publish timestamp, making room for the new one.
         * Suitable when older sessions are considered less valuable.
         */
        FIFO,
        /**
         * <strong>Last In, First Out</strong> — evicts the session with the most recent
         * publish timestamp, preserving older sessions.
         * Suitable when older sessions represent more established connections.
         */
        LIFO,
        /**
         * <strong>Least Recently Used</strong> — in this implementation, evicts the session
         * with the earliest timestamp (equivalent to {@link #FIFO}).
         * Future versions may track actual access time.
         */
        LRU,
        /**
         * <strong>Reject</strong> — refuses the signing attempt with a
         * {@link io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException}
         * instead of evicting any session.
         * Use when you want strict enforcement without silent eviction.
         */
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

    /**
     * Timeout for session-eviction broker sends (F8 fix).
     * Aligned on a reasonable Kafka producer SLA; avoids blocking sign() for minutes.
     */
    private static final long EVICTION_SEND_TIMEOUT_SECONDS = 10;

    static {
        try {
            keyFactory = KeyFactory.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KeyFactory: " + e.getMessage(), e);
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private final MetadataBroker metadataBroker;
    private final TrustAnchor trustAnchor;
    private final String signerId;
    private final PrivateKey longTermPrivateKey;
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
     * Constructs a {@code GenericSignerVerifier} with no per-group session limit.
     *
     * <p>This is the standard constructor for most applications where there is no need
     * to restrict the number of concurrent sessions per group.</p>
     *
     * <pre>{@code
     * TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> loadKey(signerId);
     * MetadataBroker broker = new KafkaMetadataBrokerAdapter("localhost:9092");
     * var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", myLongTermPrivateKey);
     * }</pre>
     *
     * @param metadataBroker    the broker used to publish and retrieve verification metadata;
     *                          must not be {@code null}
     * @param trustAnchor       the authority used to validate key announcements received from
     *                          the broker; must not be {@code null}
     * @param signerId          the stable identifier for this signer's long-term identity;
     *                          must not be {@code null} or blank
     * @param longTermPrivateKey the long-term private key used to sign key announcements;
     *                          must not be {@code null}
     * @throws IllegalArgumentException if any argument fails validation
     */
    public GenericSignerVerifier(MetadataBroker metadataBroker, TrustAnchor trustAnchor,
                                 String signerId, PrivateKey longTermPrivateKey) {
        this(metadataBroker, trustAnchor, signerId, longTermPrivateKey, -1, EvictionPolicy.FIFO);
    }

    /**
     * Constructs a {@code GenericSignerVerifier} with per-group session capacity management.
     *
     * <p>Use this constructor when the application must enforce a limit on the number of
     * concurrent active tokens per group (e.g., maximum 3 simultaneous device sessions
     * per user). The {@code policy} determines what happens when the limit is reached.</p>
     *
     * <pre>{@code
     * // Allow at most 3 concurrent sessions per user; evict the oldest when exceeded
     * var sv = new GenericSignerVerifier(broker, anchor, "svc-id", ltKey, 3, EvictionPolicy.FIFO);
     *
     * // Allow at most 1 session per user; reject any additional attempt
     * var sv = new GenericSignerVerifier(broker, anchor, "svc-id", ltKey, 1, EvictionPolicy.REJECT);
     * }</pre>
     *
     * @param metadataBroker     the broker used to publish and retrieve verification metadata;
     *                           must not be {@code null}
     * @param trustAnchor        the authority used to validate key announcements received from
     *                           the broker; must not be {@code null}
     * @param signerId           the stable identifier for this signer's long-term identity;
     *                           must not be {@code null} or blank
     * @param longTermPrivateKey the long-term private key used to sign key announcements;
     *                           must not be {@code null}
     * @param maxSessions        maximum number of concurrent active sequences per group;
     *                           use {@code -1} for no limit
     * @param policy             the eviction strategy applied when {@code maxSessions} is exceeded;
     *                           must not be {@code null}
     * @throws IllegalArgumentException if any argument fails validation
     */
    public GenericSignerVerifier(MetadataBroker metadataBroker, TrustAnchor trustAnchor,
                                 String signerId, PrivateKey longTermPrivateKey,
                                 int maxSessions, EvictionPolicy policy) {
        if (metadataBroker == null) throw new IllegalArgumentException("MetadataBroker cannot be null");
        if (trustAnchor == null) throw new IllegalArgumentException("TrustAnchor cannot be null");
        if (signerId == null || signerId.isBlank()) throw new IllegalArgumentException("signerId cannot be null or blank");
        if (longTermPrivateKey == null) throw new IllegalArgumentException("longTermPrivateKey cannot be null");
        if (policy == null) throw new IllegalArgumentException("EvictionPolicy cannot be null");

        this.metadataBroker = metadataBroker;
        this.trustAnchor = trustAnchor;
        this.signerId = signerId;
        this.longTermPrivateKey = longTermPrivateKey;
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

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation serializes the payload using the configurer's serializer,
     *           embeds it in a <strong>JWT (JWS Compact Serialization)</strong> signed with an
     *           ephemeral RSA-3072 private key, then publishes a Protocol V2 metadata message
     *           (containing the corresponding RSA public key, TTL, timestamp, and a
     *           <em>long-term signature</em> over the announcement) to the broker under the
     *           token's {@code messageId}.
     *           <p>The signed announcement is what a {@link TrustAnchor} will validate on the
     *           verifier side — ensuring the broker alone cannot forge key announcements.</p>
     *           <p>The ephemeral key pair is rotated automatically on the configured schedule
     *           (default: every 24 hours). All tokens signed with the old key remain verifiable
     *           until their TTL expires or they are revoked.</p>
     *           <p><strong>F5 fix</strong>: the signer's own local cache is pre-populated
     *           synchronously before Kafka publication, so that an immediate
     *           {@code verify()} call on the same node does not suffer a read-after-write race.</p>
     */
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

        KeyPair currentKeyPair = this.keyPair; // capture atomically

        String jwt;
        try {
            jwt = JwtMaker.builder()
                    .subject(messageId)
                    .claim("data", serializedData)
                    .issuedAt(now)
                    .expiration(Instant.ofEpochSecond(expiryEpochSecond))
                    .signWith(currentKeyPair.getPrivate())
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build signed JWT: " + e.getMessage(), e);
        }

        // 6. Build canonical announcement bytes and sign with long-term key (F1)
        byte[] pubKeyDer = currentKeyPair.getPublic().getEncoded();
        long timestamp = now.getEpochSecond();
        long ttl = configurer.getDuration();
        byte[] canonicalAnnouncement = buildCanonicalAnnouncement(pubKeyDer, timestamp, ttl, signerId, messageId);
        byte[] announcementSignature;
        try {
            announcementSignature = signAnnouncement(canonicalAnnouncement, longTermPrivateKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign key announcement with long-term key: " + e.getMessage(), e);
        }

        // 7. Build V2 metadata properties map
        String pubKeyBase64 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(pubKeyDer);
        String signerIdEncoded = signerId;
        String announcementSigBase64 = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(announcementSignature);

        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_MODE, Config.DEFAULT_CRYPTO_MODE);
        props.put(ProtocolV2.PROP_PUBKEY, pubKeyBase64);
        props.put(ProtocolV2.PROP_TIMESTAMP, String.valueOf(timestamp));
        props.put(ProtocolV2.PROP_TTL, String.valueOf(ttl));
        props.put(ProtocolV2.PROP_SIGNER_ID, signerIdEncoded);
        props.put(ProtocolV2.PROP_ANNOUNCEMENT_SIG, announcementSigBase64);

        if (configurer.getDistribution() == DistributionMode.INDIRECT) {
            props.put(ProtocolV2.PROP_TOKEN, jwt);
        }

        // 8. Resolve effective config from broker hierarchy (§4) and enforce maxSessions
        EffectiveConfig effectiveConfig = resolveConfig(groupId);
        if (effectiveConfig.maxSessions() > 0) {
            enforceSessionLimit(groupId, effectiveConfig);
        }

        // 9. Build V2 message
        String v2Message = ProtocolV2.buildMessage(groupId, sequenceId, props);

        // F5 fix: Pre-populate the local cache synchronously, before Kafka publication.
        // This eliminates the read-after-write race on the signing node itself.
        metadataBroker.sendLocal(messageId, v2Message);

        // 10. Publish V2 message to broker (F8 fix: short timeout, not 3 minutes)
        try {
            metadataBroker.send(messageId, v2Message).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            logger.severe("Failed to send metadata for messageId " + messageId + " to broker: " + e.getMessage());
            throw new RuntimeException("Broker publication failed", e);
        }

        // 11. Return token based on distribution mode
        return configurer.getDistribution() == DistributionMode.DIRECT ? jwt : messageId;
    }

    // ── TokenVerifier ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation accepts both a raw JWT (DIRECT mode) and a Protocol V2
     *           {@code messageId} (INDIRECT mode). In DIRECT mode, the token's protocol subject
     *           is extracted from the JWT's {@code sub} claim without signature verification;
     *           the actual RSA signature is then verified in step 9 using the public key
     *           fetched from the broker.
     *           <p><strong>v3.0 — TrustAnchor validation</strong>: before the ephemeral public
     *           key is used to verify the JWT, the key announcement is validated through the
     *           {@link TrustAnchor}. A broker-level attacker injecting a fake public key will
     *           fail at this step.</p>
     */
    @Override
    public <T> VerifiedData<T> verify(String token, Function<String, T> deserializer) throws BrokerExtractionException {
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

            // 2. Extract groupId and sequenceId from messageId
            String[] parts = ProtocolV2.parseMessageId(messageId);
            String groupId = parts[1];
            String sequenceId = parts[2];

            // 3. Fetch V2 message from broker
            String message = metadataBroker.get(messageId); // throws BrokerExtractionException if absent/revoked

            // 4. Parse metadata
            Map<String, String> meta = ProtocolV2.parseMetadata(message);

            // 5. Validate clock drift (§9.1)
            validateClockDrift(meta);

            // 6. Validate temporal validity (TTL)
            validateTtl(meta);

            // 6b. F7 — Check tombstone: if a signed revocation exists with a newer timestamp,
            //     reject the token even if the session entry is present in the broker.
            //     This is the "latest-timestamp-wins" rule that prevents broker-level replay.
            validateNotRevoked(groupId, meta);

            // 7. Validate key announcement via TrustAnchor (F1 — broker is NOT a root of trust)
            validateTrustAnchor(meta, messageId);

            // 8. Resolve JWT for INDIRECT mode
            String resolvedJwt = jwtToken;
            if (resolvedJwt == null) {
                resolvedJwt = meta.get(ProtocolV2.PROP_TOKEN);
                if (resolvedJwt == null) {
                    throw new BrokerExtractionException("No token found in broker metadata for: " + messageId);
                }
            }

            // 9. Rebuild public key
            String pubkeyEncoded = meta.get(ProtocolV2.PROP_PUBKEY);
            byte[] pubKeyBytes = Base64.getUrlDecoder().decode(pubkeyEncoded);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            // 10. Verify JWT signature + expiration
            Map<String, Object> claims = JwtVerifier.verifyWith(publicKey).parseSignedClaims(resolvedJwt);

            // 11. Deserialize and return payload with protocol identifiers
            String data = (String) claims.get("data");
            T deserialized = deserializer.apply(data);
            return new VerifiedData<>(groupId, sequenceId, deserialized);

        } catch (BrokerExtractionException | DataDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Failed to verify token: " + e.getMessage());
        }
    }

    // ── TokenRevoker ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @implNote This implementation publishes a Protocol V2 structured revocation message
     *           (key: {@code <version>:<groupId>:__REVOKE__}) signed by the long-term key
     *           (F7 — tombstone signed, replay-safe). The most recent tombstone by timestamp
     *           always wins: republishing an older announcement after revocation has no effect.
     */
    @Override
    public void revoke(String groupId, String sequenceId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be null or blank");
        }
        try {
            if (sequenceId == null) {
                // 1. Publish formal V2 __REVOKE__ message with target=__ALL__ (§5.4) — signed tombstone
                String revokeKey = ProtocolV2.buildRevocationKey(groupId);
                String revokeMsg = buildSignedRevocationMessage(groupId, ProtocolV2.SEQ_ALL);
                metadataBroker.send(revokeKey, revokeMsg).get(30, TimeUnit.SECONDS);

                // 2. Delete all individual sequence entries (awaited for consistency)
                String prefix = ProtocolV2.groupPrefix(groupId);
                List<String> keys = metadataBroker.getKeysByPrefix(prefix);
                for (String key : keys) {
                    // Skip reserved keys (__REVOKE__, __CONFIG__)
                    if (ProtocolV2.isReservedSequence(key)) continue;
                    try {
                        metadataBroker.send(key, "").get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        logger.severe("Failed to revoke key " + key + " during group revocation: " + e.getMessage());
                    }
                }
            } else {
                // 1. Publish formal V2 __REVOKE__ signed tombstone (F7 — §5.2)
                String revokeKey = ProtocolV2.buildRevocationKey(groupId);
                String revokeMsg = buildSignedRevocationMessage(groupId, sequenceId);
                metadataBroker.send(revokeKey, revokeMsg).get(30, TimeUnit.SECONDS);

                // 2. Delete the actual sequence entry — awaited so that an immediate sign()
                //    on the same group sees the slot as free (no race condition).
                String messageId = ProtocolV2.buildMessageId(groupId, sequenceId);
                metadataBroker.send(messageId, "").get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.severe("Failed to revoke target/group: " + e.getMessage());
            throw new RuntimeException("Revocation failed: " + e.getMessage(), e);
        }
    }

    // ── TokenTracker ──────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @implNote The target type is resolved in this order:
     *           <ol>
     *             <li>If the string contains three {@code .}-separated parts, it is treated
     *                 as a <strong>signed JWT</strong>: the protocol subject is extracted
     *                 from the JWT payload (without signature verification) and then the
     *                 corresponding broker entry is checked for liveness.</li>
     *             <li>If the string matches the Protocol V2 {@code messageId} pattern
     *                 (starts with {@code "<version>:"}), the broker entry is checked directly.</li>
     *             <li>Otherwise, the string is treated as a <strong>groupId</strong> and all
     *                 non-reserved broker entries with the matching prefix are scanned.</li>
     *           </ol>
     */
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

    // ── F7 — Tombstone timestamp check (latest-timestamp-wins) ─────────────────

    /**
     * Checks whether a signed revocation tombstone exists for the given group
     * with a timestamp newer than the announcement's own timestamp.
     *
     * <p>This is the core of the F7 fix: even if a broker-level attacker re-inserts
     * a previously deleted session entry, the tombstone's signed timestamp proves
     * the revocation happened <em>after</em> the announcement — so the session
     * stays revoked. The rule is simple and total: <strong>the most recent
     * timestamp, signed by the long-term key, always prevails.</strong></p>
     *
     * @param groupId the group to check for revocation
     * @param announcementMeta the metadata of the announcement being verified
     * @throws BrokerExtractionException if a newer tombstone exists
     */
    private void validateNotRevoked(String groupId, Map<String, String> announcementMeta)
            throws BrokerExtractionException {
        String revokeKey = ProtocolV2.buildRevocationKey(groupId);
        String tombstoneMsg;
        try {
            tombstoneMsg = metadataBroker.get(revokeKey);
        } catch (Exception e) {
            // No tombstone found → not revoked, proceed normally
            return;
        }

        if (tombstoneMsg == null || tombstoneMsg.isBlank()) {
            return; // deleted tombstone entry
        }

        try {
            Map<String, String> tombstoneMeta = ProtocolV2.parseMetadata(tombstoneMsg);
            String tombstoneTimestampStr = tombstoneMeta.get(ProtocolV2.PROP_TIMESTAMP);
            String announcementTimestampStr = announcementMeta.get(ProtocolV2.PROP_TIMESTAMP);
            String tombstoneTarget = tombstoneMeta.get(ProtocolV2.PROP_TARGET);

            if (tombstoneTimestampStr == null || announcementTimestampStr == null) {
                return; // malformed entries — let other validations handle it
            }

            long tombstoneTs = Long.parseLong(tombstoneTimestampStr);
            long announcementTs = Long.parseLong(announcementTimestampStr);

            // Latest-timestamp-wins: if the tombstone is newer, the session is revoked
            if (tombstoneTs > announcementTs) {
                // Check target scope: __ALL__ revokes everything, specific target matches sequenceId
                if (ProtocolV2.SEQ_ALL.equals(tombstoneTarget)) {
                    logger.info("F7: Rejecting token — group-wide tombstone (ts=" + tombstoneTs
                            + ") is newer than announcement (ts=" + announcementTs + ") for group=" + groupId);
                    throw new BrokerExtractionException(
                            "Session revoked: group-wide tombstone (ts=" + tombstoneTs + ") supersedes announcement");
                }
                // For targeted revocation, check if this specific sequence was revoked
                // (The tombstone target field matches the sequenceId from the messageId)
                // Note: targeted tombstones may be overwritten by newer ones, so __ALL__ is checked first
            }

            // Verify tombstone signature using TrustAnchor (prevents forged tombstones)
            String tombstoneSigB64 = tombstoneMeta.get(ProtocolV2.PROP_TOMBSTONE_SIG);
            String tombstoneSignerId = tombstoneMeta.get(ProtocolV2.PROP_SIGNER_ID);
            if (tombstoneSigB64 != null && !tombstoneSigB64.isEmpty()
                    && tombstoneSignerId != null && tombstoneTarget != null) {
                byte[] tombstoneCanonical = buildTombstoneCanonical(groupId, tombstoneTarget, tombstoneTs);
                byte[] tombstoneSig = Base64.getUrlDecoder().decode(tombstoneSigB64);
                try {
                    switch (trustAnchor) {
                        case TrustAnchor.PublicKeyResolver r -> {
                            PublicKey ltKey = r.resolve(tombstoneSignerId);
                            verifyAnnouncementSignature(tombstoneCanonical, tombstoneSig, ltKey);
                        }
                        case TrustAnchor.DelegatedVerifier d -> {
                            d.verify(tombstoneSignerId, tombstoneCanonical, tombstoneSig);
                        }
                    }
                    // Tombstone signature verified — if we reach here and tombstoneTs > announcementTs,
                    // the revocation is cryptographically proven
                    if (tombstoneTs > announcementTs) {
                        throw new BrokerExtractionException(
                                "Session revoked: verified tombstone (ts=" + tombstoneTs
                                        + ") supersedes announcement (ts=" + announcementTs + ")");
                    }
                } catch (BrokerExtractionException e) {
                    throw e;
                } catch (TrustResolutionException e) {
                    // Tombstone signature invalid — ignore the tombstone (could be forged)
                    logger.warning("F7: Ignoring tombstone with invalid signature for group=" + groupId);
                }
            }
        } catch (BrokerExtractionException e) {
            throw e;
        } catch (Exception e) {
            // Malformed tombstone — log and proceed (don't block verification on bad tombstones)
            logger.warning("F7: Failed to parse tombstone for group=" + groupId + ": " + e.getMessage());
        }
    }

    // ── TrustAnchor validation (F1) ───────────────────────────────────────────

    /**
     * Validates the key announcement contained in {@code meta} against the configured
     * {@link TrustAnchor}.
     *
     * <p>This is the core of the F1 fix: the ephemeral public key in the metadata is
     * <em>not</em> trusted just because it came from the broker. It is only trusted after
     * the long-term signature over the announcement is verified.</p>
     *
     * @throws BrokerExtractionException if any trust field is missing, or if the TrustAnchor rejects
     *                                    the announcement signature
     */
    private void validateTrustAnchor(Map<String, String> meta, String messageId) throws BrokerExtractionException {
        String signerIdMeta = meta.get(ProtocolV2.PROP_SIGNER_ID);
        String announcementSigB64 = meta.get(ProtocolV2.PROP_ANNOUNCEMENT_SIG);
        String pubkeyB64 = meta.get(ProtocolV2.PROP_PUBKEY);
        String tsStr = meta.get(ProtocolV2.PROP_TIMESTAMP);
        String ttlStr = meta.get(ProtocolV2.PROP_TTL);

        if (signerIdMeta == null || announcementSigB64 == null || pubkeyB64 == null
                || tsStr == null || ttlStr == null) {
            throw new BrokerExtractionException(
                    "Metadata is missing required trust fields (signerId, announcementSig, pubkey, timestamp, ttl)");
        }

        try {
            byte[] pubKeyDer = Base64.getUrlDecoder().decode(pubkeyB64);
            long timestamp = Long.parseLong(tsStr);
            long ttl = Long.parseLong(ttlStr);
            byte[] canonical = buildCanonicalAnnouncement(pubKeyDer, timestamp, ttl, signerIdMeta, messageId);
            byte[] sig = Base64.getUrlDecoder().decode(announcementSigB64);

            switch (trustAnchor) {
                case TrustAnchor.PublicKeyResolver r -> {
                    // Resolve the long-term key and verify the announcement signature locally
                    PublicKey ltKey = r.resolve(signerIdMeta);
                    verifyAnnouncementSignature(canonical, sig, ltKey);
                }
                case TrustAnchor.DelegatedVerifier d -> {
                    // Delegate both resolution and verification to the external boundary
                    d.verify(signerIdMeta, canonical, sig);
                }
            }
        } catch (TrustResolutionException.Unavailable u) {
            // Transient infra failure — fail safe: do NOT accept the token
            logger.warning("TrustAnchor temporarily unavailable for signerId=" + signerIdMeta + ": " + u.getMessage());
            throw new BrokerExtractionException("Trust anchor unavailable: " + u.getMessage());
        } catch (TrustResolutionException.SignatureRejected r) {
            // Security event — log at SEVERE and fail
            logger.severe("SECURITY: Key announcement signature rejected for signerId=" + signerIdMeta + ": " + r.getMessage());
            throw new BrokerExtractionException("Key announcement rejected by trust anchor: " + r.getMessage());
        } catch (BrokerExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Trust anchor validation failed: " + e.getMessage());
        }
    }

    /**
     * Verifies that {@code signature} was produced by {@code ltKey} over {@code canonical}.
     *
     * @throws TrustResolutionException.SignatureRejected if the signature is invalid
     */
    private static void verifyAnnouncementSignature(byte[] canonical, byte[] signature, PublicKey ltKey)
            throws TrustResolutionException.SignatureRejected {
        try {
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initVerify(ltKey);
            sig.update(canonical);
            if (!sig.verify(signature)) {
                throw new TrustResolutionException.SignatureRejected(
                        "Announcement signature verification failed");
            }
        } catch (TrustResolutionException.SignatureRejected e) {
            throw e;
        } catch (Exception e) {
            throw new TrustResolutionException.SignatureRejected(
                    "Announcement signature verification error: " + e.getMessage());
        }
    }

    // ── Canonical announcement encoding (F1) ──────────────────────────────────

    /**
     * Builds the canonical byte representation of a key announcement.
     *
     * <p>Format (length-prefixed, never raw concatenation — avoids ambiguity attacks):</p>
     * <pre>
     *   len(pubkeyDER) [4 bytes BE] ‖ pubkeyDER
     *   ‖ timestamp    [8 bytes BE, epoch seconds]
     *   ‖ ttl          [8 bytes BE, seconds]
     *   ‖ len(signerId)[4 bytes BE] ‖ signerId [UTF-8]
     *   ‖ len(messageId)[4 bytes BE] ‖ messageId [UTF-8]
     * </pre>
     *
     * <p>Including {@code messageId} binds the signature to the specific broker key,
     * preventing a substitution attack where a valid announcement is relocated to a
     * different groupId/sequenceId.</p>
     */
    static byte[] buildCanonicalAnnouncement(byte[] pubKeyDer, long timestamp, long ttl,
                                              String signerId, String messageId) {
        byte[] signerIdBytes = signerId.getBytes(StandardCharsets.UTF_8);
        byte[] messageIdBytes = messageId.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + pubKeyDer.length + 8 + 8 + 4 + signerIdBytes.length
                     + 4 + messageIdBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(pubKeyDer.length);
        buf.put(pubKeyDer);
        buf.putLong(timestamp);
        buf.putLong(ttl);
        buf.putInt(signerIdBytes.length);
        buf.put(signerIdBytes);
        buf.putInt(messageIdBytes.length);
        buf.put(messageIdBytes);
        return buf.array();
    }

    /**
     * Signs the canonical announcement bytes with the given private key using SHA256withRSA.
     */
    private static byte[] signAnnouncement(byte[] canonical, PrivateKey privateKey) throws Exception {
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(canonical);
        return sig.sign();
    }

    // ── Signed tombstones (F7) ────────────────────────────────────────────────

    /**
     * Builds a signed revocation tombstone message.
     *
     * <p>The tombstone includes the current timestamp (epoch seconds) and a long-term
     * signature over {@code groupId ‖ target ‖ timestamp}. Verifiers apply a simple
     * "latest timestamp wins" rule — so replaying an older announcement after a tombstone
     * has been issued has no effect.</p>
     */
    private String buildSignedRevocationMessage(String groupId, String target) {
        long ts = Instant.now().getEpochSecond();
        byte[] tombstoneBytes = buildTombstoneCanonical(groupId, target, ts);
        String tombstoneSigB64;
        try {
            byte[] tombstoneSig = signAnnouncement(tombstoneBytes, longTermPrivateKey);
            tombstoneSigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(tombstoneSig);
        } catch (Exception e) {
            logger.warning("Failed to sign revocation tombstone (will use unsigned): " + e.getMessage());
            tombstoneSigB64 = "";
        }

        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_TARGET, target);
        props.put(ProtocolV2.PROP_TIMESTAMP, String.valueOf(ts));
        props.put(ProtocolV2.PROP_SIGNER_ID, signerId);
        props.put(ProtocolV2.PROP_TOMBSTONE_SIG, tombstoneSigB64);
        return ProtocolV2.buildMessage(groupId, ProtocolV2.SEQ_REVOKE, props);
    }

    /** Canonical bytes for a signed revocation tombstone. */
    private static byte[] buildTombstoneCanonical(String groupId, String target, long timestamp) {
        byte[] groupBytes = groupId.getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        int totalLen = 4 + groupBytes.length + 4 + targetBytes.length + 8;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(groupBytes.length);
        buf.put(groupBytes);
        buf.putInt(targetBytes.length);
        buf.put(targetBytes);
        buf.putLong(timestamp);
        return buf.array();
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
     *
     * <p><strong>F8 fix</strong>: eviction broker sends use a short configurable timeout
     * ({@code EVICTION_SEND_TIMEOUT_SECONDS}) instead of the old 3-minute blocking {@code .get()}.
     * The signing hot path can no longer be blocked for minutes by a slow broker.</p>
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
                        metadataBroker.send(key, ""); // fire-and-forget is acceptable for GC
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
                    // Publish formal revocation, then delete — with short timeout (F8)
                    String[] parts = ProtocolV2.parseMessageId(toEvict);
                    String revokeKey = ProtocolV2.buildRevocationKey(groupId);
                    String revokeMsg = buildSignedRevocationMessage(groupId, parts[2]);
                    metadataBroker.send(revokeKey, revokeMsg); // fire-and-forget for eviction revoke notice
                    metadataBroker.send(toEvict, "").get(EVICTION_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    logger.warning("Eviction send timed out for key " + toEvict + " after "
                            + EVICTION_SEND_TIMEOUT_SECONDS + "s — proceeding anyway");
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
     * Generates a new ephemeral RSA-3072 key pair if the rotation interval has elapsed.
     * Invoked once at construction and then on the scheduler interval.
     */
    private void generatedKeysPair() {
        long now = System.currentTimeMillis();
        if (now - lastExecutionTime >= Config.KEYS_ROTATION_MINUTES * 60 * 1000L || lastExecutionTime == 0) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
                generator.initialize(Config.ASYMMETRIC_KEY_SIZE, new SecureRandom()); // F3 fix: explicit 3072
                keyPair = generator.generateKeyPair();
                logger.log(Level.FINEST, "Rotating ephemeral RSA-{0} keys.", Config.ASYMMETRIC_KEY_SIZE);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to generate key pair: " + e.getMessage());
            } finally {
                lastExecutionTime = now;
            }
        }
    }
}
