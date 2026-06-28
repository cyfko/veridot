package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;


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

    private final Broker broker;
    private final TrustRoot trustRoot;
    private final String signerId;
    private final PrivateKey longTermPrivateKey;
    private final byte envelopeSigAlg;

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
                                 PrivateKey longTermKey, byte envelopeSigAlg) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, -1, EvictionPolicy.FIFO, Config.RECONCILIATION_INTERVAL_MINUTES);
    }

    public GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                                 PrivateKey longTermKey, byte envelopeSigAlg,
                                 int maxSessions, EvictionPolicy policy) {
        this(broker, trustRoot, issuerId, longTermKey, envelopeSigAlg, maxSessions, policy, Config.RECONCILIATION_INTERVAL_MINUTES);
    }

    // Constructor package-private for test override
    GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String issuerId, 
                          PrivateKey longTermKey, byte envelopeSigAlg,
                          int maxSessions, EvictionPolicy policy,
                          long reconciliationIntervalMinutesOverride) {
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

        this.defaultConfig = new ConfigPayload(
            maxSessions == -1 ? OptionalInt.empty() : OptionalInt.of(maxSessions),
            ConfigPayload.fromEvictionPolicy(policy),
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty()
        );

        byte ephemeralAlg = (byte) (longTermKey.getAlgorithm().equalsIgnoreCase("RSA") ? 0x01 : 0x02);
        this.keyRotationService = new KeyRotationService(ephemeralAlg);
        this.livenessManager = new LivenessManager(entryPublisher, broker, longTermKey, envelopeSigAlg, issuerId);
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

            // 2. Ephemeral Key Rotation Snapshot (F-04)
            KeyRotationService.KeySnapshot keySnapshot = keyRotationService.snapshot();

            // 3. Enforce capacity limits
            capacityManager.enforceCapacity(scope, config, signerId, broker, trustRoot, entryPublisher, watermark, livenessChecker, longTermPrivateKey, envelopeSigAlg, signerId);

            // 4. Serialize data
            String serializedData;
            try {
                serializedData = configurer.getSerializer().apply(data);
            } catch (DataSerializationException e) {
                throw e;
            } catch (Exception e) {
                throw new DataSerializationException("Failed to serialize payload", e);
            }

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
            if (!trustRoot.isRootIdentity(signerId)) {
                capabilityVerifier.assertAuthorized(signerId, targetScope, broker, trustRoot);
            }

            entryPublisher.publish(EntryType.CONFIG, targetScope, "", version, payload.encode(), longTermPrivateKey, envelopeSigAlg, signerId, broker)
                          .join();
            watermark.accept(new EntryId(targetScope, EntryType.CONFIG, ""), version);
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
                envelopeSigAlg
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
}
