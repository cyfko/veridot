package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.ErrorCode;
import io.github.cyfko.veridot.core.impl.SubjectComputer;

import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration-test utility for Protocol V5.
 */
public final class TestTrustSetup {

    public final KeyPair longTermKeyPair;
    public final String cn;
    public final String signerId;
    public final TrustRoot trustRoot;

    private TestTrustSetup(KeyPair longTermKeyPair, String cn, String signerId, TrustRoot trustRoot) {
        this.longTermKeyPair = longTermKeyPair;
        this.cn              = cn;
        this.signerId        = signerId;
        this.trustRoot       = trustRoot;
    }

    public static TestTrustSetup create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            String cn  = "integration-signer";
            String id  = SubjectComputer.compute(cn, kp.getPublic());

            Map<String, TrustIdentity> identityStore = new HashMap<>();
            identityStore.put(id, new TrustIdentity(kp.getPublic(), true, Algorithm.ED25519));

            TrustRoot root = new PublicKeyTrustRoot() {
                @Override
                public TrustIdentity resolve(String issuer) {
                    TrustIdentity identity = identityStore.get(issuer);
                    if (identity == null) {
                        throw new VeridotException(ErrorCode.TRUST_RESOLUTION_FAILED, null, "Unknown subject: " + issuer);
                    }
                    return identity;
                }
            };

            return new TestTrustSetup(kp, cn, id, root);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialise integration trust setup", e);
        }
    }

    public GenericSignerVerifier newSignerVerifier(Broker broker) {
        return new GenericSignerVerifier(broker, trustRoot, cn,
                longTermKeyPair.getPrivate(), longTermKeyPair.getPublic(), Algorithm.ED25519);
    }
}

