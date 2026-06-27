package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier.EvictionPolicy;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles the construction, canonical serialization, signing, and publication of
 * key announcements, configurations, and revocation tombstones.
 */
class MetadataPublisher {
    private final MetadataBroker metadataBroker;
    private final String signerId;
    private final PrivateKey longTermPrivateKey;

    public MetadataPublisher(MetadataBroker metadataBroker, String signerId, PrivateKey longTermPrivateKey) {
        this.metadataBroker = metadataBroker;
        this.signerId = signerId;
        this.longTermPrivateKey = longTermPrivateKey;
    }

    public void publishKeyAnnouncement(String groupId, String sequenceId, String jwt, PublicKey pubKey, long ttl) throws Exception {
        String messageId = Protocol.buildMessageId(groupId, sequenceId);
        long timestamp = Instant.now().getEpochSecond();
        String pubKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(pubKey.getEncoded());

        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        props.put(Protocol.PROP_PK, pubKeyBase64);
        props.put(Protocol.PROP_TS, String.valueOf(timestamp));
        props.put(Protocol.PROP_TTL, String.valueOf(ttl));
        props.put(Protocol.PROP_SID, signerId);

        if (jwt != null) {
            props.put(Protocol.PROP_TOKEN, jwt);
        }

        // Canonicalize (without sig), sign, then add sig
        String sigB64 = TrustedAnnouncement.sign(messageId, props, longTermPrivateKey);
        props.put(Protocol.PROP_SIG, sigB64);

        String v2Message = Protocol.buildMessage(groupId, sequenceId, props);

        // F5: Pre-populate local cache synchronously before remote publish
        metadataBroker.sendLocal(messageId, v2Message);
        
        // Remote publish
        metadataBroker.send(messageId, v2Message);
    }

    public void publishConfig(String key, int maxSessions, EvictionPolicy policy, long defaultTtlSeconds, long validitySeconds) throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_MAX, String.valueOf(maxSessions));
        props.put(Protocol.PROP_POL, policy.name());
        props.put(Protocol.PROP_DTTL, String.valueOf(defaultTtlSeconds));
        props.put(Protocol.PROP_TS, String.valueOf(now));
        props.put(Protocol.PROP_EXP, String.valueOf(now + validitySeconds));
        props.put(Protocol.PROP_SID, signerId);

        String sigB64 = TrustedAnnouncement.sign(key, props, longTermPrivateKey);
        props.put(Protocol.PROP_SIG, sigB64);

        String message = Protocol.buildMessage(key, props);
        metadataBroker.send(key, message);
    }

    public void publishRevocationTombstone(String groupId, String target) throws Exception {
        long ts = Instant.now().getEpochSecond();
        String messageId = Protocol.buildRevocationKey(groupId);

        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_TARGET, target);
        props.put(Protocol.PROP_TS, String.valueOf(ts));
        props.put(Protocol.PROP_SID, signerId);

        String tombstoneSigB64 = TrustedAnnouncement.sign(messageId, props, longTermPrivateKey);
        props.put(Protocol.PROP_SIG, tombstoneSigB64);
        
        String revokeMsg = Protocol.buildMessage(groupId, Protocol.SEQ_REVOKE, props);
        metadataBroker.send(messageId, revokeMsg);
    }
}
