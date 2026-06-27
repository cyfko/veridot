package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTrustSecurityTest {

    private InMemoryMetadataBroker broker;
    private TestTrustSetup trust;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        trust = TestTrustSetup.create();
    }

    @Test
    void forged_config_without_signature_is_ignored() throws InterruptedException {
        // Create signer/verifier with default maxSessions = -1 (unlimited)
        var sv = trust.newSignerVerifier(broker);

        // Attacker writes an unsigned config with maxSessions = 1
        long now = Instant.now().getEpochSecond();
        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_MAX, "1");
        props.put(Protocol.PROP_POL, GenericSignerVerifier.EvictionPolicy.REJECT.name());
        props.put(Protocol.PROP_DTTL, "600");
        props.put(Protocol.PROP_TS, String.valueOf(now));
        props.put(Protocol.PROP_EXP, String.valueOf(now + 3600));

        String key = Protocol.buildLocalConfigKey("group1");
        String unsignedMsg = Protocol.buildMessage(key, props);
        broker.send(key, unsignedMsg);

        // Sign two sessions. Since unsigned config is ignored, no exception should be thrown
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        // The default limit should be used (no eviction, both remain)
        assertTrue(broker.containsKey("3:group1:s1"));
        assertTrue(broker.containsKey("3:group1:s2"));
    }

    @Test
    void forged_config_with_random_signature_is_ignored() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Attacker writes a config with a fake signature
        long now = Instant.now().getEpochSecond();
        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_MAX, "1");
        props.put(Protocol.PROP_POL, GenericSignerVerifier.EvictionPolicy.REJECT.name());
        props.put(Protocol.PROP_DTTL, "600");
        props.put(Protocol.PROP_TS, String.valueOf(now));
        props.put(Protocol.PROP_EXP, String.valueOf(now + 3600));
        props.put(Protocol.PROP_SID, "attacker-sid");
        props.put(Protocol.PROP_SIG, "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY"); // fake b64 signature

        String key = Protocol.buildLocalConfigKey("group1");
        String fakeSignedMsg = Protocol.buildMessage(key, props);
        broker.send(key, fakeSignedMsg);

        // Sign two sessions. The forged config must be ignored.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(broker.containsKey("3:group1:s1"));
        assertTrue(broker.containsKey("3:group1:s2"));
    }

    @Test
    void config_with_unavailable_trust_anchor_falls_back_without_blocking_sign() throws InterruptedException {
        // Create custom TrustAnchor that throws UNAVAILABLE
        TrustAnchor badAnchor = new TrustAnchor.PublicKeyResolver() {
            @Override
            public PublicKey resolve(String sid) throws TrustResolutionException {
                throw new TrustResolutionException.Unavailable("KMS is down");
            }
        };

        var sv = new GenericSignerVerifier(broker, badAnchor, trust.signerId, trust.longTermKeyPair.getPrivate());

        // Publish a config signed with a valid signature format
        long now = Instant.now().getEpochSecond();
        Map<String, String> props = new LinkedHashMap<>();
        props.put(Protocol.PROP_MAX, "1");
        props.put(Protocol.PROP_POL, GenericSignerVerifier.EvictionPolicy.REJECT.name());
        props.put(Protocol.PROP_DTTL, "600");
        props.put(Protocol.PROP_TS, String.valueOf(now));
        props.put(Protocol.PROP_EXP, String.valueOf(now + 3600));
        props.put(Protocol.PROP_SID, trust.signerId);
        
        String key = Protocol.buildLocalConfigKey("group1");
        String signedMsg = Protocol.buildMessage(key, props);
        broker.send(key, signedMsg);

        // Sign a session. It should not throw because badAnchor throwing Unavailable
        // on config check should be caught and fallback to default (unlimited), not throw.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(broker.containsKey("3:group1:s1"));
        assertTrue(broker.containsKey("3:group1:s2"));
    }

    @Test
    void expired_signed_config_is_ignored_even_with_valid_signature() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Publish a config with 1 second validity
        sv.publishConfig(GenericSignerVerifier.ConfigScope.LOCAL, "group1", 1, GenericSignerVerifier.EvictionPolicy.REJECT, 600, 1);

        // Wait 3 seconds so it becomes expired
        Thread.sleep(3000);

        // Sign two sessions. Since it is expired, it should be ignored.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(broker.containsKey("3:group1:s1"));
        assertTrue(broker.containsKey("3:group1:s2"));
    }

    @Test
    void publishConfig_then_verify_round_trip() {
        var sv = trust.newSignerVerifier(broker);

        // Publish local config with maxSessions = 1, REJECT policy
        sv.publishConfig(GenericSignerVerifier.ConfigScope.LOCAL, "group1", 1, GenericSignerVerifier.EvictionPolicy.REJECT, 600, 3600);

        // Sign first session
        assertDoesNotThrow(() -> sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build()));

        // Sign second session should throw capacity exceeded exception
        assertThrows(io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException.class, () -> {
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });
    }

    @Test
    void isAuthorizedForScope_custom_denial_blocks_config() {
        // Create custom TrustAnchor that denies authorization for group "group-denied"
        TrustAnchor authAnchor = new TrustAnchor.PublicKeyResolver() {
            @Override
            public PublicKey resolve(String sid) throws TrustResolutionException {
                return trust.longTermKeyPair.getPublic();
            }

            @Override
            public boolean isAuthorizedForScope(String sid, String scopeKey) {
                // Deny if scope key contains "group-denied"
                return !scopeKey.contains("group-denied");
            }
        };

        var sv = new GenericSignerVerifier(broker, authAnchor, trust.signerId, trust.longTermKeyPair.getPrivate());

        // Publish config for group-denied
        sv.publishConfig(GenericSignerVerifier.ConfigScope.LOCAL, "group-denied", 1, GenericSignerVerifier.EvictionPolicy.REJECT, 600, 3600);

        // Sign two sessions. Since isAuthorizedForScope returned false, the config is ignored,
        // so no SessionCapacityExceededException is thrown.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group-denied").sequenceId("s1").validity(600).build());
            sv.sign("d2", BasicConfigurer.builder().groupId("group-denied").sequenceId("s2").validity(600).build());
        });
    }

    @Test
    void valid_signed_local_config_overrides_site_and_global() throws Exception {
        var sv = trust.newSignerVerifier(broker);

        // 1. Publish global config: maxSessions = 5, FIFO
        sv.publishConfig(GenericSignerVerifier.ConfigScope.GLOBAL, null, 5, GenericSignerVerifier.EvictionPolicy.FIFO, 600, 3600);

        // 2. Publish site config for "site-A": maxSessions = 3, FIFO
        sv.publishConfig(GenericSignerVerifier.ConfigScope.SITE, "site-A", 3, GenericSignerVerifier.EvictionPolicy.FIFO, 600, 3600);

        // 3. Publish local config for "group-X": maxSessions = 2, REJECT
        sv.publishConfig(GenericSignerVerifier.ConfigScope.LOCAL, "group-X", 2, GenericSignerVerifier.EvictionPolicy.REJECT, 600, 3600);

        // 4. Manually publish a valid signed key announcement containing the PROP_SITE = site-A property
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair groupKp = gen.generateKeyPair();
        String pubKeyBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(groupKp.getPublic().getEncoded());

        long now = Instant.now().getEpochSecond();
        Map<String, String> announceProps = new LinkedHashMap<>();
        announceProps.put(Protocol.PROP_ALG, "rsa");
        announceProps.put(Protocol.PROP_PK, pubKeyBase64);
        announceProps.put(Protocol.PROP_TS, String.valueOf(now));
        announceProps.put(Protocol.PROP_TTL, "600");
        announceProps.put(Protocol.PROP_SID, trust.signerId);
        announceProps.put(Protocol.PROP_SITE, "site-A");

        String msgId = Protocol.buildMessageId("group-X", "s1");
        String sigB64 = TrustedAnnouncement.sign(msgId, announceProps, trust.longTermKeyPair.getPrivate());
        announceProps.put(Protocol.PROP_SIG, sigB64);

        String message = Protocol.buildMessage("group-X", "s1", announceProps);
        broker.send(msgId, message);

        // Since local config maxSessions = 2, REJECT is the highest priority:
        // Sign 2nd session: ok (reuses/extends the resolved site config structure internally)
        sv.sign("d2", BasicConfigurer.builder().groupId("group-X").sequenceId("s2").validity(600).build());

        // Sign 3rd session: should throw SessionCapacityExceededException because local config (max=2) overrides site (max=3) and global (max=5)
        assertThrows(io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException.class, () -> {
            sv.sign("d3", BasicConfigurer.builder().groupId("group-X").sequenceId("s3").validity(600).build());
        });
    }
}
