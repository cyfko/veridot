package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-focused tests for the TrustAnchor integration (F1).
 *
 * <p>These tests verify that the broker is truly a transport only: key announcements
 * received from the broker are validated through the TrustAnchor before any
 * cryptographic verification takes place.</p>
 *
 * <p>Threat models covered:</p>
 * <ul>
 *   <li>Broker write: an attacker injects a fraudulent key announcement without a valid
 *       long-term signature → must be rejected (SignatureRejected path)</li>
 *   <li>Broker write: an attacker injects metadata missing trust-anchor fields → must be
 *       rejected (missing-fields path)</li>
 *   <li>KMS unavailability: TrustAnchor temporarily unreachable → must fail safe
 *       (Unavailable path)</li>
 *   <li>Unknown signerId: announcement claims an identity the TrustAnchor doesn't know
 *       → must be rejected</li>
 *   <li>Wrong key: correct signerId but wrong public key in announcement → must be rejected
 *       (cryptographic mismatch)</li>
 *   <li>Canonical announcement format: buildCanonicalAnnouncement is deterministic and
 *       length-prefixed (no raw concatenation attack surface)</li>
 * </ul>
 */
class TrustAnchorSecurityTest {

    // ── Forge tests (F1 — broker-injection attacks) ───────────────────────────

    /**
     * An attacker writes a key announcement to the broker without a valid long-term signature.
     * The TrustAnchor rejects it → {@code verify()} must throw.
     */
    @Test
    void forged_announcement_without_trust_anchor_signature_is_rejected() throws Exception {
        InMemoryMetadataBroker broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        // Attacker generates their own ephemeral key pair
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair attackerKp = gen.generateKeyPair();

        // Attacker builds a forged V2 metadata message with no announcementSig
        long ts = java.time.Instant.now().getEpochSecond();
        String attackerPubKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(attackerKp.getPublic().getEncoded());
        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        props.put(ProtocolV2.PROP_PK, attackerPubKey);
        props.put(ProtocolV2.PROP_TS, String.valueOf(ts));
        props.put(ProtocolV2.PROP_TTL, "3600");
        props.put(ProtocolV2.PROP_SID, trust.signerId); // claims to be the legitimate signer
        // Note: no announcementSig!
        String forgedMsg = ProtocolV2.buildMessage("victim-group", "evil-session", props);
        broker.sendLocal("3:victim-group:evil-session", forgedMsg);

        // Verify must reject because sig is absent
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim-group:evil-session", s -> s),
                "Missing sig must cause rejection (F1)");
    }

    /**
     * An attacker injects a key announcement with a random (invalid) signature over the
     * announcement bytes. The TrustAnchor performs RSA signature verification → fails →
     * {@code verify()} must throw.
     */
    @Test
    void forged_announcement_with_random_signature_is_rejected() throws Exception {
        InMemoryMetadataBroker broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        // Attacker generates their own ephemeral key pair
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair attackerKp = gen.generateKeyPair();

        long ts = java.time.Instant.now().getEpochSecond();
        long ttl = 3600L;
        String attackerPubKeyB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(attackerKp.getPublic().getEncoded());

        // Forge a random signature (not signed with the legitimate long-term key)
        byte[] fakeSignature = new byte[256];
        new SecureRandom().nextBytes(fakeSignature);
        String fakeSigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(fakeSignature);

        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        props.put(ProtocolV2.PROP_PK, attackerPubKeyB64);
        props.put(ProtocolV2.PROP_TS, String.valueOf(ts));
        props.put(ProtocolV2.PROP_TTL, String.valueOf(ttl));
        props.put(ProtocolV2.PROP_SID, trust.signerId); // claims to be the legitimate signer
        props.put(ProtocolV2.PROP_SIG, fakeSigB64); // fake signature
        String forgedMsg = ProtocolV2.buildMessage("victim-group2", "evil-session2", props);
        broker.sendLocal("3:victim-group2:evil-session2", forgedMsg);

        // Verify must reject because the RSA signature does not match the legitimate long-term key
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim-group2:evil-session2", s -> s),
                "Forged announcement signature must be rejected by TrustAnchor (F1)");
    }

    /**
     * TrustAnchor throws {@link TrustResolutionException.Unavailable} → must fail safe:
     * {@code verify()} throws {@link BrokerExtractionException}, does NOT accept the token.
     */
    @Test
    void unavailable_trust_anchor_fails_safe() throws Exception {
        InMemoryMetadataBroker broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();

        // Replace the trust anchor with one that always throws Unavailable
        TrustAnchor flakeyAnchor = (TrustAnchor.PublicKeyResolver) signerId -> {
            throw new TrustResolutionException.Unavailable("KMS is down");
        };
        var sv = new GenericSignerVerifier(broker, flakeyAnchor, trust.signerId,
                trust.longTermKeyPair.getPrivate());

        // Sign a legitimate token (bypasses TrustAnchor — sendLocal writes directly)
        String token = sv.sign("data",
                BasicConfigurer.builder().groupId("infra-test").validity(600).build());

        // But verify must fail because the trust anchor is unavailable
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify(token, s -> s),
                "Unavailable TrustAnchor must fail safe — cannot verify without trust");
    }

    /**
     * TrustAnchor does NOT know the signerId → {@code SignatureRejected} →
     * {@code verify()} throws.
     */
    @Test
    void unknown_signerId_is_rejected() throws Exception {
        InMemoryMetadataBroker broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        // Manually forge a message with an unknown signerId (different from trust.signerId)
        long ts = java.time.Instant.now().getEpochSecond();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair fakeKp = gen.generateKeyPair();
        String fakePubKeyB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(fakeKp.getPublic().getEncoded());

        Map<String, String> canonProps = new LinkedHashMap<>();
        canonProps.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        canonProps.put(ProtocolV2.PROP_PK, fakePubKeyB64);
        canonProps.put(ProtocolV2.PROP_TS, String.valueOf(ts));
        canonProps.put(ProtocolV2.PROP_TTL, String.valueOf(3600L));
        canonProps.put(ProtocolV2.PROP_SID, "unknown-signer");
        byte[] canonical = ProtocolV2.buildCanonicalBytes("3:victim:unknown-attack", canonProps);
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(fakeKp.getPrivate()); // sign with its own key (not the legitimate long-term)
        sig.update(canonical);
        byte[] fakeSig = sig.sign();
        String fakeSigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(fakeSig);

        Map<String, String> props = new LinkedHashMap<>();
        props.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        props.put(ProtocolV2.PROP_PK, fakePubKeyB64);
        props.put(ProtocolV2.PROP_TS, String.valueOf(ts));
        props.put(ProtocolV2.PROP_TTL, "3600");
        props.put(ProtocolV2.PROP_SID, "unknown-signer"); // not in trust store
        props.put(ProtocolV2.PROP_SIG, fakeSigB64);
        String msg = ProtocolV2.buildMessage("victim", "unknown-attack", props);
        broker.sendLocal("3:victim:unknown-attack", msg);

        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("3:victim:unknown-attack", s -> s),
                "Unknown signerId must be rejected by TrustAnchor (SignatureRejected)");
    }

    // ── Canonical announcement encoding correctness ───────────────────────────

    /**
     * Verifies that {@code buildCanonicalAnnouncement} is deterministic:
     * identical inputs always produce the same byte sequence.
     */
    @Test
    void canonical_announcement_is_deterministic() {
        byte[] dummyDer = new byte[]{0x01, 0x02, 0x03, 0x04};
        String dummyPkB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(dummyDer);
        long ts = 1706712000L;
        long ttl = 3600L;
        String signerId = "svc-A";

        Map<String, String> p = new LinkedHashMap<>();
        p.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
        p.put(ProtocolV2.PROP_PK, dummyPkB64);
        p.put(ProtocolV2.PROP_TS, String.valueOf(ts));
        p.put(ProtocolV2.PROP_TTL, String.valueOf(ttl));
        p.put(ProtocolV2.PROP_SID, signerId);

        byte[] a1 = ProtocolV2.buildCanonicalBytes("3:test:seq1", p);
        byte[] a2 = ProtocolV2.buildCanonicalBytes("3:test:seq1", p);
        assertArrayEquals(a1, a2, "buildCanonicalBytes must be deterministic");
    }

    /**
     * Verifies that changing any field changes the canonical representation
     * (no raw concatenation attack surface — length-prefixed encoding).
     */
    @Test
    void canonical_announcement_changes_when_any_field_changes() {
        byte[] der = new byte[]{0x01, 0x02, 0x03};
        String derB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(der);
        long ts = 1706712000L;
        long ttl = 3600L;
        String signerId = "svc-A";

        // Helper to build props
        java.util.function.Supplier<Map<String, String>> baseProps = () -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put(ProtocolV2.PROP_ALG, Config.DEFAULT_CRYPTO_MODE);
            m.put(ProtocolV2.PROP_PK, derB64);
            m.put(ProtocolV2.PROP_TS, String.valueOf(ts));
            m.put(ProtocolV2.PROP_TTL, String.valueOf(ttl));
            m.put(ProtocolV2.PROP_SID, signerId);
            return m;
        };

        byte[] base = ProtocolV2.buildCanonicalBytes("3:g:s", baseProps.get());

        Map<String, String> pDiffDer = baseProps.get();
        pDiffDer.put(ProtocolV2.PROP_PK, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[]{0x01, 0x02, 0x04}));
        byte[] diffDer = ProtocolV2.buildCanonicalBytes("3:g:s", pDiffDer);

        Map<String, String> pDiffTs = baseProps.get();
        pDiffTs.put(ProtocolV2.PROP_TS, String.valueOf(ts + 1));
        byte[] diffTs = ProtocolV2.buildCanonicalBytes("3:g:s", pDiffTs);

        Map<String, String> pDiffTtl = baseProps.get();
        pDiffTtl.put(ProtocolV2.PROP_TTL, String.valueOf(ttl + 1));
        byte[] diffTtl = ProtocolV2.buildCanonicalBytes("3:g:s", pDiffTtl);

        Map<String, String> pDiffSigner = baseProps.get();
        pDiffSigner.put(ProtocolV2.PROP_SID, "svc-B");
        byte[] diffSigner = ProtocolV2.buildCanonicalBytes("3:g:s", pDiffSigner);

        assertFalse(java.util.Arrays.equals(base, diffDer), "Different pubkey DER must change canonical bytes");
        assertFalse(java.util.Arrays.equals(base, diffTs), "Different timestamp must change canonical bytes");
        assertFalse(java.util.Arrays.equals(base, diffTtl), "Different TTL must change canonical bytes");
        assertFalse(java.util.Arrays.equals(base, diffSigner), "Different signerId must change canonical bytes");

        // Also verify that different messageId changes canonical bytes (anti-substitution)
        byte[] diffMsgId = ProtocolV2.buildCanonicalBytes("3:g:other", baseProps.get());
        assertFalse(java.util.Arrays.equals(base, diffMsgId), "Different messageId must change canonical bytes");
    }

    /**
     * End-to-end: sign → verify using a valid TrustAnchor.
     * This is the positive path — proves the normal flow works after F1.
     */
    @Test
    void valid_announcement_passes_trust_anchor_and_verifies_successfully() {
        InMemoryMetadataBroker broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();
        var sv = trust.newSignerVerifier(broker);

        String token = sv.sign("hello",
                BasicConfigurer.builder().groupId("g1").validity(600).build());

        // Must succeed — the trust anchor knows the signerId and can verify the signature
        assertDoesNotThrow(() -> {
            var result = sv.verify(token, s -> s);
            assertEquals("hello", result.data());
        }, "Valid token with correct TrustAnchor must verify successfully");
    }
}
