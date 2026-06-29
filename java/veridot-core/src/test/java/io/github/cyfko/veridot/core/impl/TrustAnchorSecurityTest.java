package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.VeridotException;

import org.junit.jupiter.api.Test;

import java.security.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TrustAnchorSecurityTest {

    @Test
    void forged_announcement_without_trust_anchor_signature_is_rejected() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair attackerKp = gen.generateKeyPair();

        long ts = System.currentTimeMillis();
        KeyEpochPayload payload = new KeyEpochPayload(
            (byte) 0x01, 1L, attackerKp.getPublic().getEncoded(), ts, ts + 3600000L, null, null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("victim-group"))
            .key("evil-session")
            .version(1L)
            .timestamp(ts)
            .issuer(trust.signerId)
            .payload(payload.encode())
            .sigAlg((byte) 0x01);

        byte[] emptySignature = new byte[0];
        byte[] encoded = Envelope.encode(builder, emptySignature);
        
        EntryId id = new EntryId(Scope.group("victim-group"), EntryType.KEY_EPOCH, "evil-session");
        broker.put(id.storageKey(), encoded).join();

        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim-group:evil-session", s -> s),
                "Missing sig must cause rejection (F1)");
    }

    @Test
    void forged_announcement_with_random_signature_is_rejected() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair attackerKp = gen.generateKeyPair();

        long ts = System.currentTimeMillis();
        KeyEpochPayload payload = new KeyEpochPayload(
            (byte) 0x01, 1L, attackerKp.getPublic().getEncoded(), ts, ts + 3600000L, null, null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("victim-group2"))
            .key("evil-session2")
            .version(1L)
            .timestamp(ts)
            .issuer(trust.signerId)
            .payload(payload.encode())
            .sigAlg((byte) 0x01);

        byte[] fakeSignature = new byte[256];
        new SecureRandom().nextBytes(fakeSignature);
        byte[] encoded = Envelope.encode(builder, fakeSignature);
        
        EntryId id = new EntryId(Scope.group("victim-group2"), EntryType.KEY_EPOCH, "evil-session2");
        broker.put(id.storageKey(), encoded).join();

        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim-group2:evil-session2", s -> s),
                "Forged announcement signature must be rejected");
    }

    @Test
    void unavailable_trust_anchor_fails_safe() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();

        PublicKeyTrustRoot flakeyRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                throw new VeridotException(ErrorCode.TRANSPORT_UNAVAILABLE, null, "KMS is down");
            }
        };

        try (var sv = new GenericSignerVerifier(broker, flakeyRoot, trust.signerId,
                trust.longTermKeyPair.getPrivate(), (byte) 0x01)) {

            String token = sv.sign("data",
                    BasicConfigurer.builder().groupId("infra-test").validity(600).build());

            assertThrows(BrokerExtractionException.class,
                    () -> sv.verify(token, s -> s),
                    "Unavailable TrustRoot must fail safe — cannot verify without trust");
        }
    }

    @Test
    void unknown_signerId_is_rejected() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        long ts = System.currentTimeMillis();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair fakeKp = gen.generateKeyPair();

        KeyEpochPayload payload = new KeyEpochPayload(
            (byte) 0x01, 1L, fakeKp.getPublic().getEncoded(), ts, ts + 3600000L, null, null
        );

        EnvelopeBuilder builder = new EnvelopeBuilder()
            .entryType(EntryType.KEY_EPOCH)
            .flags((byte) 0x00)
            .scope(Scope.group("victim"))
            .key("unknown-attack")
            .version(1L)
            .timestamp(ts)
            .issuer("unknown-signer")
            .payload(payload.encode())
            .sigAlg((byte) 0x01);

        Envelope tempEnv = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, Scope.group("victim"), "unknown-attack", 1L, ts, "unknown-signer", payload.encode(), (byte) 0x01, new byte[0]);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(fakeKp.getPrivate());
        sig.update(tempEnv.canonicalSigningBytes());
        byte[] fakeSig = sig.sign();
        byte[] encoded = Envelope.encode(builder, fakeSig);
        
        EntryId id = new EntryId(Scope.group("victim"), EntryType.KEY_EPOCH, "unknown-attack");
        broker.put(id.storageKey(), encoded).join();

        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim:unknown-attack", s -> s),
                "Unknown signerId must be rejected by TrustRoot");
    }

    @Test
    void canonical_announcement_is_deterministic() {
        Scope scope = Scope.group("test");
        byte[] payload = new byte[]{1, 2, 3, 4};
        Envelope a1 = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "seq1", 1L, 1706712000L, "svc-A", payload, (byte) 0x01, new byte[0]);
        Envelope a2 = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "seq1", 1L, 1706712000L, "svc-A", payload, (byte) 0x01, new byte[0]);
        assertArrayEquals(a1.canonicalSigningBytes(), a2.canonicalSigningBytes(), "canonicalSigningBytes must be deterministic");
    }

    @Test
    void canonical_announcement_changes_when_any_field_changes() {
        Scope scope = Scope.group("g");
        byte[] payload = new byte[]{1, 2, 3};
        Envelope base = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "s", 1L, 1706712000L, "svc-A", payload, (byte) 0x01, new byte[0]);

        Envelope diffPayload = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "s", 1L, 1706712000L, "svc-A", new byte[]{1, 2, 4}, (byte) 0x01, new byte[0]);
        Envelope diffTs = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "s", 1L, 1706712001L, "svc-A", payload, (byte) 0x01, new byte[0]);
        Envelope diffIssuer = new Envelope(Envelope.PROTO_VERSION, EntryType.KEY_EPOCH, (byte) 0x00, scope, "s", 1L, 1706712000L, "svc-B", payload, (byte) 0x01, new byte[0]);

        assertFalse(Arrays.equals(base.canonicalSigningBytes(), diffPayload.canonicalSigningBytes()));
        assertFalse(Arrays.equals(base.canonicalSigningBytes(), diffTs.canonicalSigningBytes()));
        assertFalse(Arrays.equals(base.canonicalSigningBytes(), diffIssuer.canonicalSigningBytes()));
    }

    @Test
    void valid_announcement_passes_trust_anchor_and_verifies_successfully() {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        String token = sv.sign("hello",
                BasicConfigurer.builder().groupId("g1").validity(600).build());

        assertDoesNotThrow(() -> {
            var result = sv.verify(token, s -> s);
            assertEquals("hello", result.data());
        }, "Valid token with correct TrustRoot must verify successfully");
    }
}
