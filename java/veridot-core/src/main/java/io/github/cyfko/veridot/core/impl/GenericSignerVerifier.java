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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Main orchestrator implementing DataSigner, TokenVerifier, TokenRevoker, and TokenTracker for V4 (§12.1).
 */
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker, AutoCloseable {

    private static final Logger logger = Logger.getLogger(GenericSignerVerifier.class.getName());
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        // Enforce secure deserialization by disabling default typing (Finding R4 mitigation)
        objectMapper.deactivateDefaultTyping();
    }

    private final Broker broker;
    private final TrustRoot trustRoot;
    private final String signerId;
    private final PrivateKey longTermPrivateKey;
    private final Algorithm envelopeSigAlg;
    private final WatermarkStore watermarkStore;

    private final ConfigPayload defaultConfig;

    // Delegates
    private final KeyRotationService keyRotationService;
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

    // ═══ Constructeur V4 (recommandé) ═══
    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                                 PrivateKey longTermKey, Algorithm envelopeSigAlg) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, -1, EvictionPolicy.FIFO, Config.RECONCILIATION_INTERVAL_MINUTES, null);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                                 PrivateKey longTermKey, Algorithm envelopeSigAlg,
                                 WatermarkStore watermarkStore) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, -1, EvictionPolicy.FIFO, Config.RECONCILIATION_INTERVAL_MINUTES, watermarkStore);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                                 PrivateKey longTermKey, Algorithm envelopeSigAlg,
                                 int maxSessions, EvictionPolicy policy) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, maxSessions, policy, Config.RECONCILIATION_INTERVAL_MINUTES, null);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                                 PrivateKey longTermKey, Algorithm envelopeSigAlg,
                                 int maxSessions, EvictionPolicy policy,
                                 WatermarkStore watermarkStore) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, maxSessions, policy, Config.RECONCILIATION_INTERVAL_MINUTES, watermarkStore);
    }



    // Constructor package-private for test override
    GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                          PrivateKey longTermKey, Algorithm envelopeSigAlg,
                          int maxSessions, EvictionPolicy policy,
                          long reconciliationIntervalMinutesOverride,
                          io.github.cyfko.veridot.core.WatermarkStore watermarkStore) {
        if (broker == null) throw new IllegalArgumentException("Broker cannot be null");
        if (trustRoot == null) throw new IllegalArgumentException("TrustRoot cannot be null");
        if (issuerId == null || issuerId.isBlank()) throw new IllegalArgumentException("issuerId cannot be null or blank");
        if (longTermKey == null) throw new IllegalArgumentException("longTermKey cannot be null");
        if (policy == null) throw new IllegalArgumentException("EvictionPolicy cannot be null");

        this.broker = broker;
        this.trustRoot = trustRoot;
        this.signerId = issuerId;
        this.longTermPrivateKey = longTermKey;
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
                    hmacKey = deriveHmacKey(longTermKey);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to initialize FileWatermarkStore: " + e.getMessage(), e);
                }
                this.watermarkStore = new FileWatermarkStore(new java.io.File(path), hmacKey);
            } else if (broker instanceof io.github.cyfko.veridot.core.WatermarkStore) {
                this.watermarkStore = (io.github.cyfko.veridot.core.WatermarkStore) broker;
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
            OptionalLong.empty()
        );

        Algorithm ephemeralAlg = Algorithm.ED25519; // Default to Ed25519 (NIST SP 800-186)
        if (!Config.ALLOWED_SIG_ALGS.contains(Algorithm.ED25519)) {
            if (Config.ALLOWED_SIG_ALGS.contains(Algorithm.ECDSA_SHA256)) {
                ephemeralAlg = Algorithm.ECDSA_SHA256;
            } else {
                ephemeralAlg = Algorithm.RSA_SHA256;
            }
        }
        this.keyRotationService = new KeyRotationService(ephemeralAlg);
        this.livenessManager = new LivenessManager(entryPublisher, broker, longTermKey, envelopeSigAlg, issuerId);
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
            // HKDF-Extract (RFC 5869) with fixed salt for the watermark
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec("veridot-watermark-v4".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] prk = hmac.doFinal(encoded);

            // HKDF-Expand
            hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            hmac.update("watermark-integrity-key".getBytes(StandardCharsets.UTF_8));
            hmac.update((byte) 1);
            return hmac.doFinal();
        } finally {
            Arrays.fill(encoded, (byte) 0); // clear memory of the key encoding copy
        }
    }

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
            // V4-01: garantir qu'une réconciliation périodique tourne pour ce scope de groupe
            ensureReconciliationStarted(scope);

            // Resolve siteId from active sessions if any
            String siteId = null;
            try {
                List<SessionCounter.SessionInfo> activeSessions = sessionCounter.listActive(scope, broker, trustRoot, watermark, livenessChecker, now);
                if (!activeSessions.isEmpty()) {
                    EntryId keyEpochId = new EntryId(scope, EntryType.KEY_EPOCH, activeSessions.get(0).sessionKey());
                    byte[] epochBytes = broker.get(keyEpochId.storageKey());
                    if (epochBytes != null) {
                        Envelope epochEnv = Envelope.parse(epochBytes);
                        KeyEpochPayload epochPayload = KeyEpochPayload.decode(epochEnv.payload);
                        siteId = epochPayload.site();
                    }
                }
            } catch (Exception ignored) {}

            // 1. Resolve Config
            ConfigPayload config = configResolver.resolve(scope, siteId, broker, trustRoot, capabilityVerifier, watermark);
            if (config == null) {
                config = defaultConfig;
            }

            // 2. Enforce capacity limits
            capacityManager.enforceCapacity(scope, config, signerId, broker, trustRoot, entryPublisher, watermark, livenessChecker, longTermPrivateKey, envelopeSigAlg, signerId);

            // 3. Serialize data
            String serializedData;
            try {
                serializedData = configurer.getSerializer().apply(data);
            } catch (DataSerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new DataSerializationException("Failed to serialize payload", e);
            }

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

            // 4. Ephemeral Key Rotation Snapshot (F-04)
            KeyRotationService.KeySnapshot keySnapshot = keyRotationService.snapshot();

            // 5. Build signed JWT
            String messageId = Protocol.buildMessageId(groupId, sequenceId);
            String jwt;
            try {
                jwt = JwtMaker.builder()
                        .subject(messageId)
                        .claim("data", serializedData)
                        .issuedAt(Instant.ofEpochMilli(now))
                        .expiration(Instant.ofEpochMilli(now + durationMs))
                        .signWith(keySnapshot.privateKey())
                        .alg(keySnapshot.alg()) // V-03 / V-05: Pass alg for header coherence
                        .compact();
            } catch (Exception e) {
                throw new RuntimeException("Failed to build signed JWT", e);
            }

            // 6. Publish KEY_EPOCH
            EntryId epochId = new EntryId(scope, EntryType.KEY_EPOCH, sequenceId);
            long epochVersion = Math.max(watermark.current(epochId) + 1, 1);
            KeyEpochPayload epochPayload = new KeyEpochPayload(
                keySnapshot.alg(),
                epochVersion,
                keySnapshot.publicKey().getEncoded(),
                now,
                now + durationMs,
                null,
                configurer.getDistribution() == DistributionMode.INDIRECT ? jwt : null // Store token in KeyEpoch if indirect
            );
            try {
                entryPublisher.publish(EntryType.KEY_EPOCH, scope, sequenceId, epochVersion, epochPayload.encode(), longTermPrivateKey, envelopeSigAlg, signerId, broker)
                              .join();
                watermark.accept(epochId, epochVersion);
            } catch (Exception e) {
                throw new RuntimeException("Broker publication failed for KEY_EPOCH", e);
            }

            // 7. Publish LIVENESS(ACTIVE)
            EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
            livenessManager.publishActive(liveEntryId, durationMs, watermark);

            // 8. Start Renewal Loop
            livenessManager.startRenewalLoop(liveEntryId, durationMs, watermark, scheduler);

            saveWatermark();
            return configurer.getDistribution() == DistributionMode.DIRECT ? jwt : messageId;
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

    @Override
    public <T> VerifiedData<T> verify(String token, Function<String, T> deserializer) throws BrokerExtractionException {
        try {
            if (token != null && token.startsWith("7:")) {
                // Secure payload token
                // Format: "7:scope:key"
                int firstColon = token.indexOf(':');
                int lastColon = token.lastIndexOf(':');
                if (firstColon == -1 || lastColon == -1 || firstColon == lastColon) {
                    throw new BrokerExtractionException("Malformed SECURE_PAYLOAD token: " + token);
                }
                String scopeStr = token.substring(firstColon + 1, lastColon);
                String keyStr = token.substring(lastColon + 1);
                Scope scope = Scope.parse(scopeStr);
                
                ensureReconciliationStarted(scope);
                checkReconciliationStaleness(scope);
                
                // Retrieve and decrypt secure payload
                byte[] plaintextBytes;
                try {
                    plaintextBytes = retrieveSecurePayload(scope, keyStr);
                } catch (VeridotException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BrokerExtractionException("Failed to retrieve/decrypt SECURE_PAYLOAD", e);
                }
                
                String plaintext = new String(plaintextBytes, StandardCharsets.UTF_8);
                T deserialized = deserializer.apply(plaintext);
                
                // Load envelope for version/issuer metadata
                EntryId entryId = new EntryId(scope, EntryType.SECURE_PAYLOAD, keyStr);
                byte[] envelopeBytes = broker.get(entryId.storageKey());
                if (envelopeBytes == null) {
                    throw new BrokerExtractionException("SECURE_PAYLOAD entry absent from broker");
                }
                Envelope envelope = Envelope.parse(envelopeBytes);
                
                return new VerifiedData<>(scope.groupId(), keyStr, deserialized);
            }

            final String messageId;
            final String jwtToken;

            if (Protocol.isMessageId(token)) {
                messageId = token;
                jwtToken = null;
            } else if (Protocol.isJwt(token)) {
                messageId = extractSubFromJwt(token);
                jwtToken = token;
            } else {
                throw new BrokerExtractionException("Unrecognized token format: " + token);
            }

            String[] parts = Protocol.parseMessageId(messageId);
            String groupId = parts[1];
            String sequenceId = parts[2];

            Scope scope = Scope.group(groupId);
            ensureReconciliationStarted(scope);
            checkReconciliationStaleness(scope);

            EntryId keyEpochId = new EntryId(scope, EntryType.KEY_EPOCH, sequenceId);

            // Run verification pipeline (Steps 1-7)
            KeyEpochPayload epochPayload = entryVerifier.verifyKeyEpoch(
                keyEpochId, broker, trustRoot, watermark, capabilityVerifier, livenessChecker, System.currentTimeMillis()
            );

            String resolvedJwt = jwtToken;
            if (resolvedJwt == null) {
                resolvedJwt = epochPayload.token();
                if (resolvedJwt == null) {
                    throw new BrokerExtractionException("No token found in broker metadata for: " + messageId);
                }
            }

            // Step 8: Verify JWT Cryptographic Signature using Ephemeral Key
            String[] jwtParts = resolvedJwt.split("\\.");
            if (jwtParts.length < 3) {
                throw new BrokerExtractionException("Malformed JWT: expected 3 parts");
            }

            // V-03 / V-05: Verify JWT header "alg" matches KEY_EPOCH alg to prevent algorithm confusion
            String headerJson = new String(Base64.getUrlDecoder().decode(jwtParts[0]), StandardCharsets.UTF_8);
            JsonNode headerNode = objectMapper.readTree(headerJson);
            if (headerNode == null || !headerNode.has("alg")) {
                throw new BrokerExtractionException("JWT header missing 'alg'");
            }
            String jwtAlg = headerNode.get("alg").asText();
            String expectedAlg;
            if (epochPayload.alg() == Algorithm.RSA_SHA256) {
                expectedAlg = "RS256";
            } else if (epochPayload.alg() == Algorithm.ECDSA_SHA256) {
                expectedAlg = "ES256";
            } else if (epochPayload.alg() == Algorithm.RSA_PSS) {
                expectedAlg = "PS256";
            } else if (epochPayload.alg() == Algorithm.ED25519) {
                expectedAlg = "EdDSA";
            } else {
                throw new BrokerExtractionException("Unsupported ephemeral key algorithm in KEY_EPOCH: " + epochPayload.alg());
            }
            if (!expectedAlg.equals(jwtAlg)) {
                throw new BrokerExtractionException("JWT algorithm mismatch: header=" + jwtAlg + " expected=" + expectedAlg);
            }

            byte[] signedBytes = (jwtParts[0] + "." + jwtParts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(jwtParts[2]);

            entryVerifier.verifyCryptographic(signedBytes, signatureBytes, epochPayload);

            // Decode Payload & deserialize
            String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payloadJson);
            String data = node.get("data").asText();
            T deserialized = deserializer.apply(data);

            return new VerifiedData<>(groupId, sequenceId, deserialized);

        } catch (BrokerExtractionException | DataDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Failed to verify token: " + e.getMessage(), e);
        }
    }

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
                // Revoke all active sessions in V4
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
            OptionalLong.of(validitySeconds * 1000L)
        );

        try {
            // Verify our own capability before publishing config (§7.4)
            capabilityVerifier.assertAuthorized(signerId, targetScope, broker, trustRoot);

            entryPublisher.publish(EntryType.CONFIG, targetScope, "", version, payload.encode(), longTermPrivateKey, envelopeSigAlg, signerId, broker)
                          .join();
            watermark.accept(new EntryId(targetScope, EntryType.CONFIG, ""), version);
            saveWatermark();
        } catch (Exception e) {
            logger.severe("Failed to publish config at " + targetScope.value() + ": " + e.getMessage());
            throw new RuntimeException("Config publication failed: " + e.getMessage(), e);
        }

        configResolver.invalidateCache(targetScope);
    }

    @Override
    public boolean hasActiveToken(Object target) {
        if (!(target instanceof String s)) {
            throw new IllegalArgumentException("hasActiveToken target must be a String, got: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        long now = System.currentTimeMillis();
        if (Protocol.isMessageId(s)) {
            String[] parts = Protocol.parseMessageId(s);
            EntryId liveEntryId = new EntryId(Scope.group(parts[1]), EntryType.LIVENESS, parts[2]);
            try {
                Scope scope = Scope.group(parts[1]);
                checkReconciliationStaleness(scope);
                livenessChecker.assertLive(liveEntryId, broker, trustRoot, watermark, capabilityVerifier, now);
                return true;
            } catch (Exception e) {
                return false;
            }
        } else if (Protocol.isJwt(s)) {
            try {
                String messageId = extractSubFromJwt(s);
                return hasActiveToken(messageId);
            } catch (Exception e) {
                return false;
            }
        } else {
            // Treat as groupId
            try {
                Scope groupScope = Scope.group(s);
                return sessionCounter.countActive(groupScope, broker, trustRoot, watermark, livenessChecker, now) > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

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
                longTermPrivateKey,
                envelopeSigAlg,
                capabilityVerifier,
                this::saveWatermark
            );
        }
    }



    @Override
    public void close() {
        if (keyRotationService != null) {
            keyRotationService.close();
        }
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
                throw new VeridotException(ErrorCode.RECONCILIATION_STALE, scope.value(),
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

    /**
     * Publishes an encrypted SECURE_PAYLOAD entry (§12) to the broker.
     */
    private String publishSecurePayloadInternal(
        Scope scope,
        String key,
        byte[] plaintext,
        List<String> recipientSids,
        String payloadType
    ) throws Exception {
        byte[] dataToPublish;
        byte[] nonce = null;
        Byte encAlg = null;
        byte[] recipientsBytes = null;

        if (recipientSids != null && !recipientSids.isEmpty()) {
            // 1. Generate key_sym and nonce
            java.security.SecureRandom sr = new java.security.SecureRandom();
            byte[] keySym = new byte[32];
            sr.nextBytes(keySym);
            nonce = new byte[12];
            sr.nextBytes(nonce);

            // 2. Encrypt plaintext
            dataToPublish = HybridEncryptor.encryptSymmetric(plaintext, keySym, nonce);

            // 3. Prepare recipients block
            encAlg = (byte) 0x01; // AES-256-GCM
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (String sid : recipientSids) {
                // Resolve public key from TrustRoot
                TrustIdentity identity = trustRoot.resolve(sid);
                if (identity == null) {
                    throw new IllegalArgumentException("Recipient not found in TrustRoot: " + sid);
                }
                java.security.PublicKey pubKey = identity.publicKey();
                long epochId = 0L; // Use 0L for long-term key reference

                // Encrypt key_sym with recipient public key
                byte[] encKey = HybridEncryptor.encryptAsymmetric(keySym, pubKey);

                // Serialize block
                RecipientBlock block = new RecipientBlock(sid, epochId, encKey);
                baos.write(block.encode());
            }
            recipientsBytes = baos.toByteArray();
        } else {
            // Broadcast Public Mode: data in plaintext
            dataToPublish = plaintext;
        }

        // 4. Build SecurePayload
        SecurePayload payload = new SecurePayload(
            encAlg,
            nonce,
            recipientsBytes,
            dataToPublish,
            payloadType
        );

        // 5. Encapsulate in envelope, sign and publish to Broker
        EntryId entryId = new EntryId(scope, EntryType.SECURE_PAYLOAD, key);
        long version = Math.max(watermark.current(entryId) + 1, 1);

        byte[] payloadBytes = payload.encode();
        entryPublisher.publish(
            EntryType.SECURE_PAYLOAD,
            scope,
            key,
            version,
            payloadBytes,
            longTermPrivateKey,
            envelopeSigAlg,
            signerId,
            broker
        ).join();

        watermark.accept(entryId, version);
        saveWatermark();

        return String.format("%d:%s:%s", EntryType.SECURE_PAYLOAD.code, scope.value(), key);
    }

    /**
     * Retrieves and decrypts a SECURE_PAYLOAD entry (§12) from the broker.
     */
    private byte[] retrieveSecurePayload(
        Scope scope,
        String key
    ) throws Exception {
        EntryId entryId = new EntryId(scope, EntryType.SECURE_PAYLOAD, key);
        byte[] bytes = broker.get(entryId.storageKey());
        if (bytes == null) {
            throw new BrokerExtractionException("SECURE_PAYLOAD entry absent from broker");
        }

        Envelope envelope = Envelope.parse(bytes);
        
        // 1. Verify structure and signature
        signatureVerifier.verify(envelope, trustRoot);
        
        // 2. Validate capability
        capabilityVerifier.assertAuthorized(envelope.issuer, envelope.scope, broker, trustRoot);

        // 3. Verify version monotone
        if (envelope.version == 0) {
            throw new VeridotException(ErrorCode.STALE_VERSION, entryId.loggable(),
                "Entry version 0 is unconditionally rejected (§11.1 V4201)");
        }
        long currentWatermark = watermark.current(entryId);
        if (envelope.version < currentWatermark) {
            throw new VeridotException(ErrorCode.STALE_VERSION, entryId.loggable(),
                "SECURE_PAYLOAD version is stale. Watermark is " + currentWatermark);
        }
        if (envelope.version > currentWatermark) {
            watermark.accept(entryId, envelope.version);
            saveWatermark();
        }

        // 4. Decode payload
        SecurePayload payload = SecurePayload.decode(envelope.payload);

        // 5. Determine mode
        if (payload.recipients() == null || payload.recipients().length == 0) {
            // Broadcast Public mode: data is in plaintext
            return payload.data();
        }

        // Multicast/Unicast mode: decrypt symmetric key
        List<RecipientBlock> blocks = RecipientBlock.decodeList(payload.recipients());
        RecipientBlock myBlock = null;
        for (RecipientBlock block : blocks) {
            if (signerId.equals(block.recipientSid())) {
                myBlock = block;
                break;
            }
        }

        if (myBlock == null) {
            throw new VeridotException(ErrorCode.CAPABILITY_NOT_FOUND, entryId.loggable(),
                "Access Denied: Processor " + signerId + " is not an authorized recipient");
        }

        // Decrypt symmetric key
        byte[] keySym = HybridEncryptor.decryptAsymmetric(myBlock.encryptedKey(), longTermPrivateKey);

        // Decrypt data payload
        return HybridEncryptor.decryptSymmetric(payload.data(), keySym, payload.nonce());
    }
}
