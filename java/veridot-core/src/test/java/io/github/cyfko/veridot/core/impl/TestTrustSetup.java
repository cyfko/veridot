package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * V4 Test utility: wires up a self-contained long-term key pair and a matching
 * {@link TrustRoot} for unit testing.
 */
public class TestTrustSetup {

    public final KeyPair longTermKeyPair;
    public final String signerId;
    public final TrustRoot trustRoot;

    private TestTrustSetup(KeyPair longTermKeyPair, String signerId, TrustRoot trustRoot) {
        this.longTermKeyPair = longTermKeyPair;
        this.signerId = signerId;
        this.trustRoot = trustRoot;
    }

    public static TestTrustSetup create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            String signerId = "test-signer";

            Map<String, PublicKey> keyStore = new HashMap<>();
            keyStore.put(signerId, kp.getPublic());

            TrustRoot root = new PublicKeyTrustRoot() {
                @Override
                public PublicKey resolve(String issuer) {
                    PublicKey pk = keyStore.get(issuer);
                    if (pk == null) {
                        throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown signerId: " + issuer);
                    }
                    return pk;
                }

                @Override
                public boolean isRootIdentity(String issuer) {
                    return keyStore.containsKey(issuer);
                }
            };

            return new TestTrustSetup(kp, signerId, root);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create test trust setup", e);
        }
    }

    public GenericSignerVerifier newSignerVerifier(InMemoryBroker broker) {
        return new GenericSignerVerifier(broker, trustRoot, signerId, longTermKeyPair.getPrivate(), (byte) 0x01);
    }

    public GenericSignerVerifier newSignerVerifier(InMemoryBroker broker,
                                                    int maxSessions,
                                                    io.github.cyfko.veridot.core.EvictionPolicy policy) {
        return new GenericSignerVerifier(broker, trustRoot, signerId, longTermKeyPair.getPrivate(), (byte) 0x01,
                maxSessions, policy);
    }
}
