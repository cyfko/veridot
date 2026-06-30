package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.VerifiedData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SecurePayloadTest {

    @Test
    public void testBroadcastPublicMode() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();
        TestTrustSetup setup = TestTrustSetup.create();
        GenericSignerVerifier node = setup.newSignerVerifier(broker);

        Scope scope = Scope.group("test-group");
        byte[] payload = "Hello World Public".getBytes(StandardCharsets.UTF_8);

        // 1. Publish Broadcast Public (no recipients)
        String token = node.publishSecurePayload(scope, "doc-1", payload, null, "text/plain");
        assertTrue(token.startsWith("7:"));

        // 2. Verify and decrypt
        VerifiedData<String> verified = node.verify(token, s -> s);
        assertEquals("Hello World Public", verified.data());
        assertEquals("test-group", verified.groupId());
        assertEquals("doc-1", verified.sequenceId());
    }

    @Test
    public void testUnicastAndMulticastModes() throws Exception {
        InMemoryBroker broker = new InMemoryBroker();

        // Signer Node
        KeyPairGenerator kpgRSA = KeyPairGenerator.getInstance("RSA");
        kpgRSA.initialize(2048);
        KeyPair kpSigner = kpgRSA.generateKeyPair();
        String signerId = "signer";

        // Recipient Alice (RSA Key)
        KeyPair kpAlice = kpgRSA.generateKeyPair();
        String aliceId = "alice";

        // Recipient Bob (EC Key)
        KeyPairGenerator kpgEC = KeyPairGenerator.getInstance("EC");
        kpgEC.initialize(256);
        KeyPair kpBob = kpgEC.generateKeyPair();
        String bobId = "bob";

        // Recipient Charlie (RSA Key) - Not authorized for this session
        KeyPair kpCharlie = kpgRSA.generateKeyPair();
        String charlieId = "charlie";

        Map<String, PublicKey> keyStore = new HashMap<>();
        keyStore.put(signerId, kpSigner.getPublic());
        keyStore.put(aliceId, kpAlice.getPublic());
        keyStore.put(bobId, kpBob.getPublic());
        keyStore.put(charlieId, kpCharlie.getPublic());

        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                PublicKey pk = keyStore.get(issuer);
                if (pk == null) {
                    throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown: " + issuer);
                }
                return new TrustIdentity(pk, keyStore.containsKey(issuer));
            }
        };

        GenericSignerVerifier signerNode = new GenericSignerVerifier(broker, trustRoot, signerId, kpSigner.getPrivate(), (byte) 0x01);
        GenericSignerVerifier aliceNode = new GenericSignerVerifier(broker, trustRoot, aliceId, kpAlice.getPrivate(), (byte) 0x01);
        GenericSignerVerifier bobNode = new GenericSignerVerifier(broker, trustRoot, bobId, kpBob.getPrivate(), (byte) 0x01);
        GenericSignerVerifier charlieNode = new GenericSignerVerifier(broker, trustRoot, charlieId, kpCharlie.getPrivate(), (byte) 0x01);

        Scope scope = Scope.group("confidential-docs");
        byte[] payload = "Highly Sensitive Data".getBytes(StandardCharsets.UTF_8);

        // Publish to Alice and Bob only
        String token = signerNode.publishSecurePayload(scope, "secret-1", payload, Arrays.asList(aliceId, bobId), "text/plain");

        // 1. Alice can decrypt
        VerifiedData<String> verifiedAlice = aliceNode.verify(token, s -> s);
        assertEquals("Highly Sensitive Data", verifiedAlice.data());

        // 2. Bob can decrypt (tests EC hybrid decryption)
        VerifiedData<String> verifiedBob = bobNode.verify(token, s -> s);
        assertEquals("Highly Sensitive Data", verifiedBob.data());

        // 3. Charlie cannot decrypt (Access Denied / Capability Not Found)
        assertThrows(Exception.class, () -> charlieNode.verify(token, s -> s));
    }
}
