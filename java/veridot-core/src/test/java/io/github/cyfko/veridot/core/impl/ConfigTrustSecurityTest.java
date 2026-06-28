package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.ConfigScope;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTrustSecurityTest {

    private InMemoryBroker broker;
    private TestTrustSetup trust;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        trust = TestTrustSetup.create();
    }

    private boolean hasKeyEpoch(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.KEY_EPOCH, sessionKey);
        return broker.containsKey(id.storageKey());
    }

    @Test
    void forged_config_without_signature_is_ignored() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Attacker writes an unsigned/malformed config with maxSessions = 1 directly into the broker
        EntryId configId = new EntryId(Scope.group("group1"), EntryType.CONFIG, "");
        // Just put random invalid bytes to simulate invalid signature/forged config
        broker.put(configId.storageKey(), new byte[]{1, 2, 3, 4}).join();

        // Sign two sessions. Since unsigned config is ignored, no exception should be thrown
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(hasKeyEpoch("group1", "s1"));
        assertTrue(hasKeyEpoch("group1", "s2"));
    }

    @Test
    void forged_config_with_random_signature_is_ignored() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Attacker writes a config with a fake signature
        EntryId configId = new EntryId(Scope.group("group1"), EntryType.CONFIG, "");
        ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L));
        
        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.CONFIG)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("attacker-sid")
            .payload(payload.encode())
            .sigAlg((byte) 0x01);

        byte[] badSignature = new byte[256]; // Fake signature
        byte[] encoded = Envelope.encode(builder, badSignature);
        broker.put(configId.storageKey(), encoded).join();

        // Sign two sessions. The forged config must be ignored.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(hasKeyEpoch("group1", "s1"));
        assertTrue(hasKeyEpoch("group1", "s2"));
    }

    @Test
    void config_with_unavailable_trust_anchor_falls_back_without_blocking_sign() throws InterruptedException {
        PublicKeyTrustRoot badTrustRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "KMS is down");
            }
            @Override
            public boolean isRootIdentity(String issuer) {
                return false;
            }
        };

        try (var sv = new GenericSignerVerifier(broker, badTrustRoot, trust.signerId, trust.longTermKeyPair.getPrivate(), (byte) 0x01)) {

            // Publish a config signed with a valid signature format
            long now = System.currentTimeMillis();
            EntryId configId = new EntryId(Scope.group("group1"), EntryType.CONFIG, "");
            ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L));
            
            EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.CONFIG)
                .flags((byte) 0x00)
                .scope(Scope.group("group1"))
                .key("")
                .version(1L)
                .timestamp(now)
                .issuer(trust.signerId)
                .payload(payload.encode())
                .sigAlg((byte) 0x01);

            // Sign it with valid long term key
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(trust.longTermKeyPair.getPrivate());
                Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.CONFIG, (byte) 0x00, Scope.group("group1"), "", 1L, now, trust.signerId, payload.encode(), (byte) 0x01, new byte[0]);
                sig.update(tempEnv.canonicalSigningBytes());
                byte[] signature = sig.sign();
                byte[] encoded = Envelope.encode(builder, signature);
                broker.put(configId.storageKey(), encoded).join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Sign a session. It should not throw because badTrustRoot throwing on config check
            // should be caught and fallback to default (unlimited), not throw.
            assertDoesNotThrow(() -> {
                sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
                Thread.sleep(100);
                sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
            });

            assertTrue(hasKeyEpoch("group1", "s1"));
            assertTrue(hasKeyEpoch("group1", "s2"));
        }
    }

    @Test
    void expired_signed_config_is_ignored_even_with_valid_signature() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Publish a config with 1 second validity
        sv.publishConfig(ConfigScope.LOCAL, "group1", 1, EvictionPolicy.REJECT, 600, 1);

        // Wait 3 seconds so it becomes expired
        Thread.sleep(3000);

        // Sign two sessions. Since it is expired, it should be ignored.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(hasKeyEpoch("group1", "s1"));
        assertTrue(hasKeyEpoch("group1", "s2"));
    }

    @Test
    void publishConfig_then_verify_round_trip() {
        var sv = trust.newSignerVerifier(broker);

        // Publish local config with maxSessions = 1, REJECT policy
        sv.publishConfig(ConfigScope.LOCAL, "group1", 1, EvictionPolicy.REJECT, 600, 3600);

        // Sign first session
        assertDoesNotThrow(() -> sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build()));

        // Sign second session should throw capacity exceeded exception
        assertThrows(io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException.class, () -> {
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });
    }

    @Test
    void isAuthorizedForScope_custom_denial_blocks_config() {
        // Create custom TrustRoot that is not root identity
        PublicKeyTrustRoot authRoot = new PublicKeyTrustRoot() {
            @Override
            public PublicKey resolve(String issuer) {
                return trust.longTermKeyPair.getPublic();
            }

            @Override
            public boolean isRootIdentity(String issuer) {
                return false; // Not a root identity, so needs capability to publish config!
            }
        };

        try (var sv = new GenericSignerVerifier(broker, authRoot, trust.signerId, trust.longTermKeyPair.getPrivate(), (byte) 0x01)) {

            // Publish config for group-denied directly to broker
            try {
                long now = System.currentTimeMillis();
                EntryId configId = new EntryId(Scope.group("group-denied"), EntryType.CONFIG, "");
                ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L));
                
                EnvelopeBuilder builder = new EnvelopeBuilder()
                    .entryType(EntryType.CONFIG)
                    .flags((byte) 0x00)
                    .scope(Scope.group("group-denied"))
                    .key("")
                    .version(1L)
                    .timestamp(now)
                    .issuer(trust.signerId)
                    .payload(payload.encode())
                    .sigAlg((byte) 0x01);

                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(trust.longTermKeyPair.getPrivate());
                Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.CONFIG, (byte) 0x00, Scope.group("group-denied"), "", 1L, now, trust.signerId, payload.encode(), (byte) 0x01, new byte[0]);
                sig.update(tempEnv.canonicalSigningBytes());
                byte[] signature = sig.sign();
                byte[] encoded = Envelope.encode(builder, signature);
                broker.put(configId.storageKey(), encoded).join();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // Sign two sessions. Since the issuer is not authorized (no capability in broker), the config is ignored.
            assertDoesNotThrow(() -> {
                sv.sign("d1", BasicConfigurer.builder().groupId("group-denied").sequenceId("s1").validity(600).build());
                sv.sign("d2", BasicConfigurer.builder().groupId("group-denied").sequenceId("s2").validity(600).build());
            });
        }
    }

    @Test
    void valid_signed_local_config_overrides_site_and_global() throws Exception {
        var sv = trust.newSignerVerifier(broker);

        // 1. Publish global config: maxSessions = 5, FIFO
        sv.publishConfig(ConfigScope.GLOBAL, null, 5, EvictionPolicy.FIFO, 600, 3600);

        // 2. Publish site config for "site-A": maxSessions = 3, FIFO
        sv.publishConfig(ConfigScope.SITE, "site-A", 3, EvictionPolicy.FIFO, 600, 3600);

        // 3. Publish local config for "group-X": maxSessions = 2, REJECT
        sv.publishConfig(ConfigScope.LOCAL, "group-X", 2, EvictionPolicy.REJECT, 600, 3600);

        // Sign first session with siteId = site-A. Since we resolve siteId dynamically, we can sign the first session
        // using a configurer or manually publish a KEY_EPOCH with site-A.
        // Let's manually publish a valid KEY_EPOCH envelope for group-X, session s1 containing site-A
        long now = System.currentTimeMillis();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair groupKp = gen.generateKeyPair();

        KeyEpochPayload announcePayload = new KeyEpochPayload(
            (byte) 0x01, 1L, groupKp.getPublic().getEncoded(), now, now + 3600000L, "site-A", null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("group-X"))
            .key("s1")
            .version(1L)
            .timestamp(now)
            .issuer(trust.signerId)
            .payload(announcePayload.encode())
            .sigAlg((byte) 0x01);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(trust.longTermKeyPair.getPrivate());
        Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, Scope.group("group-X"), "s1", 1L, now, trust.signerId, announcePayload.encode(), (byte) 0x01, new byte[0]);
        sig.update(tempEnv.canonicalSigningBytes());
        byte[] signature = sig.sign();
        byte[] encoded = Envelope.encode(builder, signature);
        
        EntryId id = new EntryId(Scope.group("group-X"), EntryType.KEY_EPOCH, "s1");
        broker.put(id.storageKey(), encoded).join();

        // Also we need to publish liveness for s1 so it is counted as active
        EntryId liveId = new EntryId(Scope.group("group-X"), EntryType.LIVENESS, "s1");
        // Create active liveness payload
        LivenessPayload livePayload = new LivenessPayload((byte) 0x01, now, now + 3600000L);
        EnvelopeBuilder liveBuilder = new EnvelopeBuilder()
            .entryType(EntryType.LIVENESS)
            .flags((byte) 0x00)
            .scope(Scope.group("group-X"))
            .key("s1")
            .version(1L)
            .timestamp(now)
            .issuer(trust.signerId)
            .payload(livePayload.encode())
            .sigAlg((byte) 0x01);
            
        Envelope tempLive = new Envelope(Envelope.PROTO_VERSION, EntryType.LIVENESS, (byte) 0x00, Scope.group("group-X"), "s1", 1L, now, trust.signerId, livePayload.encode(), (byte) 0x01, new byte[0]);
        sig.initSign(trust.longTermKeyPair.getPrivate());
        sig.update(tempLive.canonicalSigningBytes());
        byte[] liveSig = sig.sign();
        byte[] liveEncoded = Envelope.encode(liveBuilder, liveSig);
        broker.put(liveId.storageKey(), liveEncoded).join();

        // Since local config maxSessions = 2, REJECT is the highest priority:
        // Sign 2nd session: ok (uses/extends the resolved site config structure internally)
        sv.sign("d2", BasicConfigurer.builder().groupId("group-X").sequenceId("s2").validity(600).build());

        // Sign 3rd session: should throw SessionCapacityExceededException because local config (max=2) overrides site (max=3) and global (max=5)
        assertThrows(io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException.class, () -> {
            sv.sign("d3", BasicConfigurer.builder().groupId("group-X").sequenceId("s3").validity(600).build());
        });
    }
}
