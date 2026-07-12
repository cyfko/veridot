package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.ConfigScope;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
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

    private boolean hasActiveLiveness(String groupId, String sessionKey) {
        EntryId id = new EntryId(Scope.group(groupId), EntryType.LIVENESS, sessionKey);
        byte[] bytes = broker.get(id.storageKey());
        if (bytes == null) return false;
        try {
            Envelope env = Envelope.parse(bytes);
            LivenessPayload payload = LivenessPayload.decode(env.payload);
            return payload.isActive();
        } catch (Exception e) {
            return false;
        }
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

        assertTrue(hasActiveLiveness("group1", "s1"));
        assertTrue(hasActiveLiveness("group1", "s2"));
    }

    @Test
    void forged_config_with_random_signature_is_ignored() throws InterruptedException {
        var sv = trust.newSignerVerifier(broker);

        // Attacker writes a config with a fake signature
        EntryId configId = new EntryId(Scope.group("group1"), EntryType.CONFIG, "");
        ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L), OptionalLong.empty(), Optional.empty());
        
        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.CONFIG)
            .flags((byte) 0x00)
            .scope(Scope.group("group1"))
            .key("")
            .version(1L)
            .timestamp(System.currentTimeMillis())
            .issuer("attacker-sid")
            .payload(payload.encode())
            .sigAlg(Algorithm.ED25519);

        byte[] badSignature = new byte[64]; // Fake Ed25519 signature
        byte[] encoded = Envelope.encode(builder, badSignature);
        broker.put(configId.storageKey(), encoded).join();

        // Sign two sessions. The forged config must be ignored.
        assertDoesNotThrow(() -> {
            sv.sign("d1", BasicConfigurer.builder().groupId("group1").sequenceId("s1").validity(600).build());
            Thread.sleep(100);
            sv.sign("d2", BasicConfigurer.builder().groupId("group1").sequenceId("s2").validity(600).build());
        });

        assertTrue(hasActiveLiveness("group1", "s1"));
        assertTrue(hasActiveLiveness("group1", "s2"));
    }

    @Test
    void config_with_unavailable_trust_anchor_falls_back_without_blocking_sign() throws InterruptedException {
        PublicKeyTrustRoot badTrustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                throw new VeridotException(ErrorCode.BROKER_UNREACHABLE, null, "KMS is down");
            }
        };

        try (var sv = new GenericSignerVerifier(broker, badTrustRoot, trust.cn,
                trust.longTermKeyPair.getPrivate(), trust.longTermKeyPair.getPublic(), Algorithm.ED25519)) {

            // Publish a config signed with a valid signature format
            long now = System.currentTimeMillis();
            EntryId configId = new EntryId(Scope.group("group1"), EntryType.CONFIG, "");
            ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L), OptionalLong.empty(), Optional.empty());
            
            EnvelopeBuilder builder = new EnvelopeBuilder()
                .entryType(EntryType.CONFIG)
                .flags((byte) 0x00)
                .scope(Scope.group("group1"))
                .key("")
                .version(1L)
                .timestamp(now)
                .issuer(trust.signerId)
                .payload(payload.encode())
                .sigAlg(Algorithm.ED25519);

            // Sign it with valid long term key
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(trust.longTermKeyPair.getPrivate());
                Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.CONFIG, (byte) 0x00, Scope.group("group1"), "", 1L, now, trust.signerId, payload.encode(), Algorithm.ED25519, new byte[0]);
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

            assertTrue(hasActiveLiveness("group1", "s1"));
            assertTrue(hasActiveLiveness("group1", "s2"));
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

        assertTrue(hasActiveLiveness("group1", "s1"));
        assertTrue(hasActiveLiveness("group1", "s2"));
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
            public TrustIdentity resolve(String issuer) {
                return new TrustIdentity(trust.longTermKeyPair.getPublic(), false, Algorithm.ED25519);
            }
        };

        try (var sv = new GenericSignerVerifier(broker, authRoot, trust.cn,
                trust.longTermKeyPair.getPrivate(), trust.longTermKeyPair.getPublic(), Algorithm.ED25519)) {

            // Publish config for group-denied directly to broker
            try {
                long now = System.currentTimeMillis();
                EntryId configId = new EntryId(Scope.group("group-denied"), EntryType.CONFIG, "");
                ConfigPayload payload = new ConfigPayload(OptionalInt.of(1), (byte) 0x04, OptionalLong.empty(), Optional.empty(), Optional.empty(), OptionalLong.of(3600000L), OptionalLong.empty(), Optional.empty());
                
                EnvelopeBuilder builder = new EnvelopeBuilder()
                    .entryType(EntryType.CONFIG)
                    .flags((byte) 0x00)
                    .scope(Scope.group("group-denied"))
                    .key("")
                    .version(1L)
                    .timestamp(now)
                    .issuer(trust.signerId)
                    .payload(payload.encode())
                    .sigAlg(Algorithm.ED25519);

                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(trust.longTermKeyPair.getPrivate());
                Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.CONFIG, (byte) 0x00, Scope.group("group-denied"), "", 1L, now, trust.signerId, payload.encode(), Algorithm.ED25519, new byte[0]);
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

        // V5: Sign sessions using the standard sign API. No KEY_EPOCH manipulation needed.
        // Since local config maxSessions = 2, REJECT is the highest priority:
        // Sign 1st session
        sv.sign("d1", BasicConfigurer.builder().groupId("group-X").sequenceId("s1").validity(600).build());

        // Sign 2nd session: ok
        sv.sign("d2", BasicConfigurer.builder().groupId("group-X").sequenceId("s2").validity(600).build());

        // Sign 3rd session: should throw SessionCapacityExceededException because local config (max=2) overrides site (max=3) and global (max=5)
        assertThrows(io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException.class, () -> {
            sv.sign("d3", BasicConfigurer.builder().groupId("group-X").sequenceId("s3").validity(600).build());
        });
    }
}
