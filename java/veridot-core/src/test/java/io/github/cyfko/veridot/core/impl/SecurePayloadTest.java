package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.DistributionMode;
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
        String token = node.sign("Hello World Public",
            BasicConfigurer.builder()
                .groupId("test-group")
                .sequenceId("doc-1")
                .distribution(DistributionMode.PRIVATE)
                .validity(3600)
                .mimeType("text/plain")
                .build()
        );
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
                String key = SubjectComputer.isInstanceScoped(issuer) ? SubjectComputer.extractCn(issuer) : issuer;
                PublicKey pk = keyStore.get(key);
                if (pk == null) {
                    throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown: " + issuer);
                }
                return new TrustIdentity(pk, keyStore.containsKey(key));
            }
        };

        GenericSignerVerifier signerNode = new GenericSignerVerifier(broker, trustRoot, signerId, kpSigner.getPrivate(), kpSigner.getPublic(), Algorithm.RSA_SHA256);
        GenericSignerVerifier aliceNode = new GenericSignerVerifier(broker, trustRoot, aliceId, kpAlice.getPrivate(), kpAlice.getPublic(), Algorithm.RSA_SHA256);
        GenericSignerVerifier bobNode = new GenericSignerVerifier(broker, trustRoot, bobId, kpBob.getPrivate(), kpBob.getPublic(), Algorithm.RSA_SHA256);
        GenericSignerVerifier charlieNode = new GenericSignerVerifier(broker, trustRoot, charlieId, kpCharlie.getPrivate(), kpCharlie.getPublic(), Algorithm.RSA_SHA256);

        Scope scope = Scope.group("confidential-docs");
        byte[] payload = "Highly Sensitive Data".getBytes(StandardCharsets.UTF_8);

        String aliceSubject = SubjectComputer.compute(aliceId, kpAlice.getPublic());
        String bobSubject = SubjectComputer.compute(bobId, kpBob.getPublic());

        // Publish to Alice and Bob only
        String token = signerNode.sign("Highly Sensitive Data",
            BasicConfigurer.builder()
                .groupId("confidential-docs")
                .sequenceId("secret-1")
                .distribution(DistributionMode.PRIVATE)
                .validity(3600)
                .recipients(Arrays.asList(aliceSubject, bobSubject))
                .mimeType("text/plain")
                .build()
        );

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
