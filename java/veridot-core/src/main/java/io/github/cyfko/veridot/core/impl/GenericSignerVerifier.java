package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.WatermarkStore;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Main orchestrator implementing DataSigner, TokenVerifier, TokenRevoker, and TokenTracker for V5 (§12.1).
 *
 * <p>V5 changes from V4:
 * <ul>
 *   <li>Key-epoch entry type eliminated — signing uses the instance's own key directly</li>
 *   <li>Subject computed via {@link SubjectComputer} (CN@hash)</li>
 *   <li>NATIVE distribution mode publishes SIGNED_DATA entries</li>
 *   <li>Verification resolves identity via {@code kid} in JWT header → TrustRoot</li>
 * </ul>
 */
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker, AutoCloseable {

    private static final Logger logger = Logger.getLogger(GenericSignerVerifier.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        objectMapper.deactivateDefaultTyping();
    }

    private final Broker broker;
    private final TrustRoot trustRoot;
    private final String signerId;           // V5: CN@hash(instancePublicKey)
    private final PrivateKey instancePrivateKey;
    private final Algorithm envelopeSigAlg;
    private final WatermarkStore watermarkStore;

    private final ConfigPayload defaultConfig;

    // Delegates — V5: no more key-rotation service
    final ReconciliationManager reconciliationManager = new ReconciliationManager();
    final CapabilityVerifier capabilityVerifier = new CapabilityVerifier();
    private final EntryPublisher entryPublisher = new EntryPublisher();
    private final ConfigResolver configResolver = new ConfigResolver();
    private final LivenessChecker livenessChecker = new LivenessChecker();
    private final LivenessManager livenessManager;
    private final CapacityManager capacityManager = new CapacityManager();
    private final EntryVerifier entryVerifier = new EntryVerifier();
    private final SignatureVerifier signatureVerifier = new SignatureVerifier();
    private final VersionWatermark watermark = new VersionWatermark();
    private final SessionCounter sessionCounter = new SessionCounter();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private static final class RefCountedLock {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int refCount = 0;
    }

    private final ConcurrentHashMap<String, RefCountedLock> groupLocks = new ConcurrentHashMap<>();
    private final long reconciliationIntervalMinutes;

    // ═══ V5 Constructors ═══

    /**
     * V5 primary constructor.
     *
     * @param broker the message broker
     * @param trustRoot the trust root resolver
     * @param cn the common name (service identifier, e.g. "orders-service")
     * @param instanceKey the instance's private key
     * @param instancePublicKey the instance's public key
     * @param envelopeSigAlg the signature algorithm for envelopes
     */
    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String cn,
                                 PrivateKey instanceKey, PublicKey instancePublicKey,
                                 Algorithm envelopeSigAlg) {
        this(broker, trustRoot, cn, instanceKey, instancePublicKey, envelopeSigAlg,
             -1, EvictionPolicy.FIFO, Config.RECONCILIATION_INTERVAL_MINUTES, null);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String cn,
                                 PrivateKey instanceKey, PublicKey instancePublicKey,
                                 Algorithm envelopeSigAlg, WatermarkStore watermarkStore) {
        this(broker, trustRoot, cn, instanceKey, instancePublicKey, envelopeSigAlg,
             -1, EvictionPolicy.FIFO, Config.RECONCILIATION_INTERVAL_MINUTES, watermarkStore);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String cn,
                                 PrivateKey instanceKey, PublicKey instancePublicKey,
                                 Algorithm envelopeSigAlg,
                                 int maxSessions, EvictionPolicy policy) {
        this(broker, trustRoot, cn, instanceKey, instancePublicKey, envelopeSigAlg,
             maxSessions, policy, Config.RECONCILIATION_INTERVAL_MINUTES, null);
    }

    // Package-private for tests
    GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String cn,
                          PrivateKey instanceKey, PublicKey instancePublicKey,
                          Algorithm envelopeSigAlg,
                          int maxSessions, EvictionPolicy policy,
                          long reconciliationIntervalMinutesOverride,
                          WatermarkStore watermarkStore) {
        if (broker == null) throw new IllegalArgumentException("Broker cannot be null");
        if (trustRoot == null) throw new IllegalArgumentException("TrustRoot cannot be null");
        if (cn == null || cn.isBlank()) throw new IllegalArgumentException("cn cannot be null or blank");
        if (instanceKey == null) throw new IllegalArgumentException("instanceKey cannot be null");
        if (instancePublicKey == null) throw new IllegalArgumentException("instancePublicKey cannot be null");
        if (policy == null) throw new IllegalArgumentException("EvictionPolicy cannot be null");

        this.broker = broker;
        this.trustRoot = trustRoot;
        this.signerId = SubjectComputer.compute(cn, instancePublicKey); // V5: CN@hash
        this.instancePrivateKey = instanceKey;
        this.envelopeSigAlg = envelopeSigAlg;
        this.reconciliationIntervalMinutes = reconciliationIntervalMinutesOverride;

        // Resolve WatermarkStore
        if (watermarkStore != null) {
            this.watermarkStore = watermarkStore;
        } else {
            String path = Config.WATERMARK_PERSISTENCE_FILE;
            if (path != null && !path.isBlank()) {
                byte[] hmacKey;
                try {
                    hmacKey = deriveHmacKey(instanceKey);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to initialize FileWatermarkStore: " + e.getMessage(), e);
                }
                this.watermarkStore = new FileWatermarkStore(new java.io.File(path), hmacKey);
            } else if (broker instanceof WatermarkStore) {
                this.watermarkStore = (WatermarkStore) broker;
            } else {
                this.watermarkStore = null;
            }
        }

        // Load watermark snapshot
        if (this.watermarkStore != null) {
            try {
                byte[] snap = this.watermarkStore.load();
                if (snap != null && snap.length > 0) {
                    this.watermark.restore(snap);
                }
            } catch (Exception e) {
                logger.warning("Failed to load watermark snapshot: " + e.getMessage());
            }
        }

        this.defaultConfig = new ConfigPayload(
            maxSessions == -1 ? OptionalInt.empty() : OptionalInt.of(maxSessions),
            ConfigPayload.fromEvictionPolicy(policy),
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            OptionalLong.empty(),      // maxInstanceLifetime (V5)
            Optional.empty()           // attestationPlugin (V5)
        );

        this.livenessManager = new LivenessManager(entryPublisher, broker, instanceKey, envelopeSigAlg, signerId);
    }

    private static byte[] deriveHmacKey(PrivateKey key) throws Exception {
        byte[] encoded = key.getEncoded();
        if (encoded == null) {
            throw new IllegalStateException(
                "Cannot derive HMAC key: PrivateKey.getEncoded() returned null. " +
                "Provide an explicit WatermarkStore with a pre-configured HMAC key."
            );
        }
        try {
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(
                Config.WATERMARK_HKDF_SALT.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prk = hmac.doFinal(encoded);

            hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            hmac.update("watermark-integrity-key".getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 1);
            return hmac.doFinal();
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    // ═══ sign() — V5 ═══

    @Override
    public String sign(Object data, Configurer configurer) throws DataSerializationException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (configurer.getDuration() <= 0) {
            throw new IllegalArgumentException("duration must be positive");
        }

        String groupId = configurer.getGroupId();
        Protocol.validateIdentifier(groupId, "groupId");

        String sequenceId = configurer.getSequenceId();
        if (sequenceId != null) {
            Protocol.validateIdentifier(sequenceId, "sequenceId");
        } else {
            sequenceId = UUID.randomUUID().toString();
        }

        Scope scope = Scope.group(groupId);
        long now = System.currentTimeMillis();
        long durationMs = configurer.getDuration() * 1000L;

        RefCountedLock refLock = groupLocks.compute(groupId, (key, val) -> {
            if (val == null) {
                val = new RefCountedLock();
            }
            val.refCount++;
            return val;
        });

        refLock.lock.lock();
        try {
            ensureReconciliationStarted(scope);

            // 1. Resolve Config
            ConfigPayload config = configResolver.resolve(scope, null, broker, trustRoot, capabilityVerifier, watermark);
            if (config == null) {
                config = defaultConfig;
            }

            // 2. Enforce capacity limits
            capacityManager.enforceCapacity(scope, config, signerId, broker, trustRoot, entryPublisher, watermark, livenessChecker, instancePrivateKey, envelopeSigAlg, signerId);

            // 3. Serialize data
            String serializedData;
            try {
                serializedData = configurer.getSerializer().apply(data);
            } catch (DataSerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new DataSerializationException("Failed to serialize payload", e);
            }

            // 4. PRIVATE mode — encrypted SECURE_PAYLOAD
            if (configurer.getDistribution() == DistributionMode.PRIVATE) {
                byte[] plaintext = serializedData.getBytes(StandardCharsets.UTF_8);
                try {
                    String secureToken = publishSecurePayloadInternal(scope, sequenceId, plaintext, configurer.getRecipients(), configurer.getMimeType());
                    saveWatermark();
                    return secureToken;
                } catch (Exception e) {
                    throw new RuntimeException("Broker publication failed for SECURE_PAYLOAD", e);
                }
            }

            // 5. Build signed JWT with instance key (V5: no more ephemeral key rotation)
            String messageId = Protocol.buildMessageId(groupId, sequenceId);
            String jwt;
            try {
                jwt = JwtMaker.builder()
                        .subject(messageId)
                        .claim("data", serializedData)
                        .issuedAt(Instant.ofEpochMilli(now))
                        .expiration(Instant.ofEpochMilli(now + durationMs))
                        .signWith(instancePrivateKey)
                        .alg(envelopeSigAlg)
                        .header("kid", signerId)  // V5: kid = instance subject
                        .compact();
            } catch (Exception e) {
                throw new RuntimeException("Failed to build signed JWT", e);
            }

            // 6. NATIVE mode — publish SIGNED_DATA entry
            if (configurer.getDistribution() == DistributionMode.NATIVE) {
                SignedDataPayload signedDataPayload = new SignedDataPayload(
                    configurer.getMimeType() != null ? configurer.getMimeType() : "application/jwt",
                    jwt.getBytes(StandardCharsets.UTF_8),
                    null,
                    now,
                    messageId
                );
                EntryId signedDataId = new EntryId(scope, EntryType.SIGNED_DATA, sequenceId);
                long version = Math.max(watermark.current(signedDataId) + 1, 1);
                try {
                    entryPublisher.publish(EntryType.SIGNED_DATA, scope, sequenceId, version,
                        signedDataPayload.encode(), instancePrivateKey, envelopeSigAlg, signerId, broker).join();
                    watermark.accept(signedDataId, version);
                } catch (Exception e) {
                    throw new RuntimeException("Broker publication failed for SIGNED_DATA", e);
                }
            }

            // 7. Publish LIVENESS(ACTIVE)
            EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
            livenessManager.publishActive(liveEntryId, durationMs, watermark);

            // 8. Start Renewal Loop
            livenessManager.startRenewalLoop(liveEntryId, durationMs, watermark, scheduler);

            saveWatermark();

            // V5: DIRECT returns JWT, NATIVE returns "8:scope:key"
            return switch (configurer.getDistribution()) {
                case DIRECT -> jwt;
                case NATIVE -> String.format("8:%s:%s", scope.value(), sequenceId);
                default -> jwt;
            };
        } finally {
            refLock.lock.unlock();
            groupLocks.compute(groupId, (key, val) -> {
                if (val != null) {
                    val.refCount--;
                    if (val.refCount == 0) {
                        return null;
                    }
                }
                return val;
            });
        }
    }

    // ═══ verify() — V5 ═══

    @Override
    public <T> VerifiedData<T> verify(String token, Function<String, T> deserializer) throws BrokerExtractionException {
        try {
            TokenParser.TokenInfo tokenInfo = TokenParser.parse(token);

            VerifiedData<T> result = switch (tokenInfo.format()) {
                case SECURE_PAYLOAD -> verifySecurePayloadToken(tokenInfo, deserializer);
                case NATIVE -> verifyNativeToken(tokenInfo, deserializer);
                case JWT -> verifyJwtToken(tokenInfo, deserializer);
            };
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_ACCEPTED.increment();
            return result;
        } catch (BrokerExtractionException | DataDeserializationException e) {
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.increment();
            throw e;
        } catch (Exception e) {
            io.github.cyfko.veridot.core.VeridotMetrics.ENVELOPE_REJECTED.increment();
            throw new BrokerExtractionException("Failed to verify token: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies a SECURE_PAYLOAD token (7:scope:key).
     */
    private <T> VerifiedData<T> verifySecurePayloadToken(TokenParser.TokenInfo tokenInfo, Function<String, T> deserializer) throws Exception {
        Scope scope = tokenInfo.scope();
        String keyStr = tokenInfo.key();

        ensureReconciliationStarted(scope);
        checkReconciliationStaleness(scope);

        byte[] plaintextBytes = retrieveSecurePayload(scope, keyStr);
        String plaintext = new String(plaintextBytes, StandardCharsets.UTF_8);
        T deserialized = deserializer.apply(plaintext);

        return new VerifiedData<>(scope.groupId(), keyStr, deserialized);
    }

    /**
     * Verifies a NATIVE token (8:scope:key) — V5 SIGNED_DATA.
     */
    private <T> VerifiedData<T> verifyNativeToken(TokenParser.TokenInfo tokenInfo, Function<String, T> deserializer) throws Exception {
        Scope scope = tokenInfo.scope();
        String keyStr = tokenInfo.key();

        ensureReconciliationStarted(scope);
        checkReconciliationStaleness(scope);

        // Verify liveness
        EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, keyStr);
        livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, System.currentTimeMillis());

        // 1. Fetch SIGNED_DATA from broker
        EntryId entryId = new EntryId(scope, EntryType.SIGNED_DATA, keyStr);
        byte[] bytes = broker.get(entryId.storageKey());
        if (bytes == null) {
            throw new BrokerExtractionException("SIGNED_DATA entry absent from broker: " + entryId.loggable());
        }

        Envelope envelope = Envelope.parse(bytes);

        // 2. Verify envelope signature
        signatureVerifier.verify(envelope, trustRoot);

        // 3. Verify capability
        capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);

        // 4. Version watermark check
        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(),
                "Entry version 0 is unconditionally rejected");
        }
        long currentWatermark = watermark.current(entryId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(),
                "SIGNED_DATA version is stale. Watermark is " + currentWatermark);
        }
        if (envelope.version > currentWatermark) {
            watermark.accept(entryId, envelope.version);
            saveWatermark();
        }

        // 5. Decode SIGNED_DATA payload and extract the embedded JWT
        SignedDataPayload sdPayload = SignedDataPayload.decode(envelope.payload);
        String embeddedJwt = new String(sdPayload.data(), StandardCharsets.UTF_8);

        // 6. Verify JWT using TrustRoot (same as direct JWT verification)
        return verifyJwtDirect(embeddedJwt, scope.groupId(), keyStr, deserializer);
    }

    /**
     * Verifies a JWT token (direct mode).
     */
    private <T> VerifiedData<T> verifyJwtToken(TokenParser.TokenInfo tokenInfo, Function<String, T> deserializer) throws Exception {
        String jwt = tokenInfo.rawToken();

        String messageId = extractSubFromJwt(jwt);
        String[] parts = Protocol.parseMessageId(messageId);
        String groupId = parts[1];
        String sequenceId = parts[2];

        Scope scope = Scope.group(groupId);
        ensureReconciliationStarted(scope);
        checkReconciliationStaleness(scope);

        // Verify liveness
        EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
        livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, System.currentTimeMillis());

        return verifyJwtDirect(jwt, groupId, sequenceId, deserializer);
    }

    /**
     * V5 JWT verification pipeline — resolves identity via kid header → TrustRoot.
     */
    private <T> VerifiedData<T> verifyJwtDirect(String jwt, String groupId, String sequenceId,
                                                  Function<String, T> deserializer) throws Exception {
        String[] jwtParts = jwt.split("\\.");
        if (jwtParts.length < 3) {
            throw new BrokerExtractionException("Malformed JWT: expected 3 parts");
        }

        // 1. Parse JWT header
        String headerJson = new String(Base64.getUrlDecoder().decode(jwtParts[0]), StandardCharsets.UTF_8);
        JsonNode headerNode = objectMapper.readTree(headerJson);
        if (headerNode == null || !headerNode.has("alg")) {
            throw new BrokerExtractionException("JWT header missing 'alg'");
        }
        String jwtAlg = headerNode.get("alg").asText();

        // 2. Extract kid → resolve identity via TrustRoot
        if (!headerNode.has("kid")) {
            throw new BrokerExtractionException("JWT header missing 'kid' (required in V5)");
        }
        String kid = headerNode.get("kid").asText();
        TrustIdentity identity = trustRoot.resolve(kid);
        if (identity == null) {
            throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null,
                "TrustRoot resolution failed for kid: " + kid);
        }

        // 3. Algorithm confusion check (§8.2 step 4): jwtAlg must match identity algorithm
        String expectedAlg = identity.algorithm().jwtAlg();
        if (!expectedAlg.equals(jwtAlg)) {
            throw new VeridotException(ErrorCode.ALGORITHM_MISMATCH, null,
                "JWT algorithm mismatch: header=" + jwtAlg + " expected=" + expectedAlg);
        }

        // 4. Verify cryptographic signature
        byte[] signedBytes = (jwtParts[0] + "." + jwtParts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] signatureBytes = Base64.getUrlDecoder().decode(jwtParts[2]);
        signatureVerifier.verifyRaw(signedBytes, signatureBytes, identity.publicKey(), identity.algorithm());

        // 5. Check expiration with clock drift tolerance (§8.2)
        String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
        JsonNode payloadNode = objectMapper.readTree(payloadJson);
        if (payloadNode.has("exp")) {
            long expEpochSec = payloadNode.get("exp").asLong();
            long expMillis = expEpochSec * 1000L + Config.MAX_CLOCK_DRIFT_SECONDS * 1000L;
            if (System.currentTimeMillis() > expMillis) {
                throw new VeridotException(ErrorCode.ENTRY_EXPIRED, null,
                    "JWT expired. exp=" + expEpochSec + ", clock drift tolerance=" + Config.MAX_CLOCK_DRIFT_SECONDS + "s");
            }
        }

        // 6. Decode data
        String dataStr = payloadNode.get("data").asText();
        T deserialized = deserializer.apply(dataStr);

        return new VerifiedData<>(groupId, sequenceId, deserialized);
    }

    // ═══ revoke() ═══

    @Override
    public void revoke(String groupId, String sequenceId) {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be null or blank");
        }
        Protocol.validateIdentifier(groupId, "groupId");
        if (sequenceId != null) {
            Protocol.validateIdentifier(sequenceId, "sequenceId");
        }

        Scope scope = Scope.group(groupId);
        long now = System.currentTimeMillis();
        ensureReconciliationStarted(scope);

        try {
            if (sequenceId == null) {
                List<SessionCounter.SessionInfo> active = sessionCounter.listActive(scope, broker, trustRoot, watermark, livenessChecker, now);
                for (SessionCounter.SessionInfo session : active) {
                    EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, session.sessionKey());
                    livenessManager.publishRevoked(liveEntryId, watermark);
                    livenessManager.stopRenewalLoop(liveEntryId);
                }
            } else {
                EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
                livenessManager.publishRevoked(liveEntryId, watermark);
                livenessManager.stopRenewalLoop(liveEntryId);
            }
            saveWatermark();
        } catch (Exception e) {
            logger.severe("Failed to revoke target/group: " + e.getMessage());
            throw new RuntimeException("Revocation failed: " + e.getMessage(), e);
        }
    }

    // ═══ publishConfig() ═══

    public void publishConfig(ConfigScope scope, String scopeId,
                              int maxSessions, EvictionPolicy policy,
                              long defaultTtlSeconds, long validitySeconds) {
        if (scope == null) throw new IllegalArgumentException("scope cannot be null");
        if (scope != ConfigScope.GLOBAL && (scopeId == null || scopeId.isBlank())) {
            throw new IllegalArgumentException("scopeId is required for scope=" + scope);
        }
        if (policy == null) throw new IllegalArgumentException("policy cannot be null");
        if (validitySeconds <= 0) throw new IllegalArgumentException("validitySeconds must be positive");
        if (maxSessions != -1 && maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be -1 or positive");
        }
        if (defaultTtlSeconds != -1 && defaultTtlSeconds <= 0) {
            throw new IllegalArgumentException("defaultTtlSeconds must be -1 or positive");
        }

        Scope targetScope = switch (scope) {
            case LOCAL  -> Scope.group(scopeId);
            case SITE   -> Scope.site(scopeId);
            case GLOBAL -> Scope.global();
        };

        ensureReconciliationStarted(targetScope);

        long version = Math.max(watermark.current(new EntryId(targetScope, EntryType.CONFIG, "")) + 1, 1);

        ConfigPayload payload = new ConfigPayload(
            maxSessions == -1 ? OptionalInt.empty() : OptionalInt.of(maxSessions),
            ConfigPayload.fromEvictionPolicy(policy),
            defaultTtlSeconds == -1 ? OptionalLong.empty() : OptionalLong.of(defaultTtlSeconds * 1000L),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.of(validitySeconds * 1000L),
            OptionalLong.empty(),
            Optional.empty()
        );

        try {
            capabilityVerifier.assertAuthorized(signerId, targetScope, broker, trustRoot);

            entryPublisher.publish(EntryType.CONFIG, targetScope, "", version, payload.encode(), instancePrivateKey, envelopeSigAlg, signerId, broker)
                          .join();
            watermark.accept(new EntryId(targetScope, EntryType.CONFIG, ""), version);
            saveWatermark();
        } catch (Exception e) {
            logger.severe("Failed to publish config at " + targetScope.value() + ": " + e.getMessage());
            throw new RuntimeException("Config publication failed: " + e.getMessage(), e);
        }

        configResolver.invalidateCache(targetScope);
    }

    // ═══ hasActiveToken() ═══

    @Override
    public boolean hasActiveToken(Object target) {
        if (!(target instanceof String s)) {
            throw new IllegalArgumentException("hasActiveToken target must be a String, got: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        long now = System.currentTimeMillis();

        try {
            TokenParser.TokenInfo tokenInfo = TokenParser.parse(s);
            return switch (tokenInfo.format()) {
                case SECURE_PAYLOAD, NATIVE -> {
                    Scope scope = tokenInfo.scope();
                    EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, tokenInfo.key());
                    checkReconciliationStaleness(scope);
                    livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, now);
                    yield true;
                }
                case JWT -> {
                    String messageId = extractSubFromJwt(s);
                    String[] parts = Protocol.parseMessageId(messageId);
                    EntryId liveEntryId = new EntryId(Scope.group(parts[1]), EntryType.LIVENESS, parts[2]);
                    checkReconciliationStaleness(Scope.group(parts[1]));
                    livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, now);
                    yield true;
                }
            };
        } catch (Exception e) {
            // Try as groupId
            try {
                Scope groupScope = Scope.group(s);
                return sessionCounter.countActive(groupScope, broker, trustRoot, watermark, livenessChecker, now) > 0;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    // ═══ Reconciliation ═══

    private final java.util.Set<Scope> reconciledScopes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void ensureReconciliationStarted(Scope scope) {
        if (reconciledScopes.add(scope)) {
            reconciliationManager.startPeriodicReconciliation(
                scope,
                java.time.Duration.ofMinutes(reconciliationIntervalMinutes),
                scheduler,
                broker,
                watermark,
                trustRoot,
                entryPublisher,
                signerId,
                instancePrivateKey,
                envelopeSigAlg,
                capabilityVerifier,
                this::saveWatermark
            );
        }
    }

    @Override
    public void close() {
        if (livenessManager != null) {
            livenessManager.stopAll();
        }
        if (reconciliationManager != null) {
            reconciliationManager.close();
        }
        saveWatermark();
        scheduler.shutdownNow();
    }

    private static String extractSubFromJwt(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Malformed JWT: less than 2 parts");
        }
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        JsonNode node = objectMapper.readTree(payloadJson);
        JsonNode subNode = node.get("sub");
        if (subNode == null) {
            throw new IllegalArgumentException("JWT has no 'sub' claim");
        }
        return subNode.asText();
    }

    // Visible for testing
    VersionWatermark watermarkForTest() {
        return watermark;
    }

    private void checkReconciliationStaleness(Scope scope) {
        if (reconciledScopes.contains(scope)) {
            long last = reconciliationManager.getLastReconciled(scope);
            long now = System.currentTimeMillis();
            long maxStalenessMs = Config.RECONCILIATION_MAX_STALENESS_MINUTES * 60 * 1000L;
            if (last > 0 && (now - last) > maxStalenessMs) {
                throw new VeridotException(ErrorCode.VERSION_REJECTED, scope.value(),
                    "Reconciliation staleness limit exceeded. Last reconciled: " + last + ", now: " + now);
            }
        }
    }

    private void saveWatermark() {
        if (watermarkStore != null) {
            try {
                watermarkStore.save(watermark.snapshot());
            } catch (Exception e) {
                logger.warning("Failed to save watermark snapshot: " + e.getMessage());
            }
        }
    }

    @Override
    public long getReconciliationStalenessMs(String scope) {
        if (scope == null) return -1L;
        try {
            Scope parsedScope = Scope.parse(scope);
            long last = reconciliationManager.getLastReconciled(parsedScope);
            if (last <= 0) {
                return -1L;
            }
            return System.currentTimeMillis() - last;
        } catch (Exception e) {
            return -1L;
        }
    }

    // ═══ SECURE_PAYLOAD publish/retrieve ═══

    private String publishSecurePayloadInternal(
        Scope scope, String key, byte[] plaintext,
        List<String> recipientSids, String payloadType
    ) throws Exception {
        byte[] dataToPublish;
        byte[] nonce = null;
        Byte encAlg = null;
        byte[] recipientsBytes = null;

        if (recipientSids != null && !recipientSids.isEmpty()) {
            java.security.SecureRandom sr = new java.security.SecureRandom();
            byte[] keySym = new byte[32];
            sr.nextBytes(keySym);
            nonce = new byte[12];
            sr.nextBytes(nonce);

            dataToPublish = HybridEncryptor.encryptSymmetric(plaintext, keySym, nonce);

            encAlg = (byte) 0x01; // AES-256-GCM
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (String sid : recipientSids) {
                TrustIdentity identity = trustRoot.resolve(sid);
                if (identity == null) {
                    throw new IllegalArgumentException("Recipient not found in TrustRoot: " + sid);
                }
                java.security.PublicKey pubKey = identity.publicKey();
                byte[] encKey = HybridEncryptor.encryptAsymmetric(keySym, pubKey);
                RecipientBlock block = new RecipientBlock(sid, 0L, encKey);
                baos.write(block.encode());
            }
            recipientsBytes = baos.toByteArray();
        } else {
            dataToPublish = plaintext;
        }

        SecurePayload payload = new SecurePayload(encAlg, nonce, recipientsBytes, dataToPublish, payloadType);

        EntryId entryId = new EntryId(scope, EntryType.SECURE_PAYLOAD, key);
        long version = Math.max(watermark.current(entryId) + 1, 1);

        byte[] payloadBytes = payload.encode();
        entryPublisher.publish(
            EntryType.SECURE_PAYLOAD, scope, key, version, payloadBytes,
            instancePrivateKey, envelopeSigAlg, signerId, broker
        ).join();

        watermark.accept(entryId, version);
        saveWatermark();

        return String.format("%d:%s:%s", EntryType.SECURE_PAYLOAD.code, scope.value(), key);
    }

    private byte[] retrieveSecurePayload(Scope scope, String key) throws Exception {
        EntryId entryId = new EntryId(scope, EntryType.SECURE_PAYLOAD, key);
        byte[] bytes = broker.get(entryId.storageKey());
        if (bytes == null) {
            throw new BrokerExtractionException("SECURE_PAYLOAD entry absent from broker");
        }

        Envelope envelope = Envelope.parse(bytes);

        signatureVerifier.verify(envelope, trustRoot);
        capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);

        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(),
                "Entry version 0 is unconditionally rejected");
        }
        long currentWatermark = watermark.current(entryId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.VERSION_REJECTED, entryId.loggable(),
                "SECURE_PAYLOAD version is stale. Watermark is " + currentWatermark);
        }
        if (envelope.version > currentWatermark) {
            watermark.accept(entryId, envelope.version);
            saveWatermark();
        }

        SecurePayload payload = SecurePayload.decode(envelope.payload);

        if (payload.recipients() == null || payload.recipients().length == 0) {
            return payload.data();
        }

        List<RecipientBlock> blocks = RecipientBlock.decodeList(payload.recipients());
        RecipientBlock myBlock = null;
        for (RecipientBlock block : blocks) {
            if (signerId.equals(block.recipientSid())) {
                myBlock = block;
                break;
            }
        }

        if (myBlock == null) {
            throw new VeridotException(ErrorCode.NOT_A_RECIPIENT, entryId.loggable(),
                "Access Denied: Processor " + signerId + " is not an authorized recipient");
        }

        byte[] keySym = HybridEncryptor.decryptAsymmetric(myBlock.encryptedKey(), instancePrivateKey);
        return HybridEncryptor.decryptSymmetric(payload.data(), keySym, payload.nonce());
    }
}
