package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Orchestrator implementing {@link DataSigner}, {@link TokenVerifier}, {@link TokenRevoker},
 * and {@link TokenTracker}, conforming to the Veridot Protocol V3 security model.
 *
 * <p>Delegates specialized tasks to {@link KeyRotationService}, {@link MetadataPublisher},
 * {@link ConfigurationResolver}, {@link SessionManager}, {@link MetadataVerifier},
 * and {@link RevocationManager}.</p>
 */
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    private static final Logger logger = Logger.getLogger(GenericSignerVerifier.class.getName());
    private static final KeyFactory keyFactory;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        try {
            keyFactory = KeyFactory.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize KeyFactory: " + e.getMessage(), e);
        }
    }


    private final MetadataBroker metadataBroker;
    private final TrustAnchor trustAnchor;
    private final String signerId;
    private final PrivateKey longTermPrivateKey;
    private final EffectiveConfig defaultConfig;

    // Delegates
    private final KeyRotationService keyRotationService;
    private final MetadataPublisher metadataPublisher;
    private final ConfigurationResolver configResolver;
    private final SessionManager sessionManager;
    private final MetadataVerifier metadataVerifier;
    private final RevocationManager revocationManager;

    private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();

    public GenericSignerVerifier(MetadataBroker metadataBroker, TrustAnchor trustAnchor,
                                 String signerId, PrivateKey longTermPrivateKey) {
        this(metadataBroker, trustAnchor, signerId, longTermPrivateKey, -1, EvictionPolicy.FIFO);
    }

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

        this.metadataBroker.setTrustAnchor(trustAnchor);

        // Instantiate delegates
        this.keyRotationService = new KeyRotationService();
        this.metadataPublisher = new MetadataPublisher(metadataBroker, signerId, longTermPrivateKey);
        this.configResolver = new ConfigurationResolver(metadataBroker, trustAnchor, this.defaultConfig);
        this.sessionManager = new SessionManager(metadataBroker, configResolver, metadataPublisher);
        this.metadataVerifier = new MetadataVerifier(metadataBroker, trustAnchor);
        this.revocationManager = new RevocationManager(metadataBroker, trustAnchor);

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
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

        String messageId = Protocol.buildMessageId(groupId, sequenceId);
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
                    .signWith(keyRotationService.getPrivateKey())
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build signed JWT: " + e.getMessage(), e);
        }

        Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
        synchronized (groupLock) {
            sessionManager.enforceSessionLimit(groupId);
            try {
                metadataPublisher.publishKeyAnnouncement(groupId, sequenceId,
                        configurer.getDistribution() == DistributionMode.INDIRECT ? jwt : null,
                        keyRotationService.getPublicKey(), configurer.getDuration());
            } catch (Exception e) {
                logger.severe("Failed to send metadata for messageId " + messageId + " to broker: " + e.getMessage());
                throw new RuntimeException("Broker publication failed", e);
            }
        }

        return configurer.getDistribution() == DistributionMode.DIRECT ? jwt : messageId;
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

            Map<String, String> meta = metadataVerifier.verifyKeyAnnouncement(messageId);
            revocationManager.validateNotRevoked(groupId, sequenceId, meta);

            String resolvedJwt = jwtToken;
            if (resolvedJwt == null) {
                resolvedJwt = meta.get(Protocol.PROP_TOKEN);
                if (resolvedJwt == null) {
                    throw new BrokerExtractionException("No token found in broker metadata for: " + messageId);
                }
            }

            String pubkeyEncoded = meta.get(Protocol.PROP_PK);
            byte[] pubKeyBytes = Base64.getUrlDecoder().decode(pubkeyEncoded);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(pubKeyBytes));

            Map<String, Object> claims = JwtVerifier.verifyWith(publicKey).parseSignedClaims(resolvedJwt);
            String data = (String) claims.get("data");
            T deserialized = deserializer.apply(data);
            return new VerifiedData<>(groupId, sequenceId, deserialized);

        } catch (BrokerExtractionException | DataDeserializationException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerExtractionException("Failed to verify token: " + e.getMessage());
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
        try {
            if (sequenceId == null) {
                metadataPublisher.publishRevocationTombstone(groupId, Protocol.SEQ_ALL);

                String prefix = Protocol.groupPrefix(groupId);
                List<String> keys = metadataBroker.getKeysByPrefix(prefix);
                for (String key : keys) {
                    if (Protocol.isReservedSequence(key)) continue;
                    try {
                        metadataBroker.send(key, "").get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        logger.severe("Failed to revoke key " + key + " during group revocation: " + e.getMessage());
                    }
                }
            } else {
                metadataPublisher.publishRevocationTombstone(groupId, sequenceId);
                String messageId = Protocol.buildMessageId(groupId, sequenceId);
                metadataBroker.send(messageId, "").get(30, TimeUnit.SECONDS);
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

        String key = switch (scope) {
            case LOCAL  -> Protocol.buildLocalConfigKey(scopeId);
            case SITE   -> Protocol.buildSiteConfigKey(scopeId);
            case GLOBAL -> Protocol.buildGlobalConfigKey();
        };

        try {
            metadataPublisher.publishConfig(key, maxSessions, policy, defaultTtlSeconds, validitySeconds);
        } catch (Exception e) {
            logger.severe("Failed to publish config at " + key + ": " + e.getMessage());
            throw new RuntimeException("Config publication failed: " + e.getMessage(), e);
        }

        if (scope == ConfigScope.LOCAL) {
            configResolver.invalidateLocal(scopeId);
        } else {
            configResolver.invalidateAll();
        }
    }

    @Override
    public boolean hasActiveToken(Object target) {
        if (!(target instanceof String s)) {
            throw new IllegalArgumentException("hasActiveToken target must be a String, got: "
                    + (target == null ? "null" : target.getClass().getName()));
        }

        if (Protocol.isMessageId(s)) {
            return sessionManager.isMessageIdActive(s);
        } else if (Protocol.isJwt(s)) {
            try {
                String messageId = extractSubFromJwt(s);
                return sessionManager.isMessageIdActive(messageId);
            } catch (Exception e) {
                return false;
            }
        } else {
            try {
                String prefix = Protocol.groupPrefix(s);
                List<String> keys = metadataBroker.getKeysByPrefix(prefix);
                for (String key : keys) {
                    if (Protocol.isReservedSequence(key)) continue;
                    if (sessionManager.isMessageIdActive(key)) {
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

    public void close() {
        if (keyRotationService != null) {
            keyRotationService.close();
        }
    }

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
}
