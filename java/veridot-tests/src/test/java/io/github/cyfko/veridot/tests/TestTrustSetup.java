package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.ErrorCode;

import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration-test utility for Protocol V4.
 */
public final class TestTrustSetup {

    public final KeyPair longTermKeyPair;
    public final String signerId;
    public final TrustRoot trustRoot;

    private TestTrustSetup(KeyPair longTermKeyPair, String signerId, TrustRoot trustRoot) {
        this.longTermKeyPair = longTermKeyPair;
        this.signerId        = signerId;
        this.trustRoot       = trustRoot;
    }

    public static TestTrustSetup create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            String id  = "integration-signer";

            Map<String, PublicKey> keyStore = new HashMap<>();
            keyStore.put(id, kp.getPublic());

            TrustRoot root = new PublicKeyTrustRoot() {
                @Override
                public TrustIdentity resolve(String issuer) {
                    PublicKey pk = keyStore.get(issuer);
                    if (pk == null) {
                        throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown signerId: " + issuer);
                    }
                    return new TrustIdentity(pk, keyStore.containsKey(issuer));
                }
            };

            return new TestTrustSetup(kp, id, root);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialise integration trust setup", e);
        }
    }

    public GenericSignerVerifier newSignerVerifier(Broker broker) {
        return new GenericSignerVerifier(broker, trustRoot, signerId, longTermKeyPair.getPrivate(), Algorithm.RSA_SHA256);
    }
}
