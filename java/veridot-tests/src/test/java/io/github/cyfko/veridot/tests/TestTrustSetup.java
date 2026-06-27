package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.MetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;

import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration-test utility: wires a self-contained long-term key pair and a
 * matching {@link TrustAnchor.PublicKeyResolver} so that
 * {@link GenericSignerVerifier} can be instantiated without an external KMS.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * TestTrustSetup trust = TestTrustSetup.create();
 * GenericSignerVerifier gsv = trust.newSignerVerifier(broker);
 * }</pre>
 */
public final class TestTrustSetup {

    public final KeyPair longTermKeyPair;
    public final String signerId;
    public final TrustAnchor trustAnchor;

    private TestTrustSetup(KeyPair longTermKeyPair, String signerId, TrustAnchor trustAnchor) {
        this.longTermKeyPair = longTermKeyPair;
        this.signerId        = signerId;
        this.trustAnchor     = trustAnchor;
    }

    /** Creates a fresh RSA-2048 long-term key pair with a simple in-memory resolver. */
    public static TestTrustSetup create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            String id  = "integration-signer";

            Map<String, PublicKey> keyStore = new HashMap<>();
            keyStore.put(id, kp.getPublic());

            TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
                PublicKey pk = keyStore.get(signerId);
                if (pk == null) {
                    throw new TrustResolutionException.SignatureRejected("Unknown signerId: " + signerId);
                }
                return pk;
            };

            return new TestTrustSetup(kp, id, anchor);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialise integration trust setup", e);
        }
    }

    /** Builds a {@link GenericSignerVerifier} wired to the given broker. */
    public GenericSignerVerifier newSignerVerifier(MetadataBroker broker) {
        return new GenericSignerVerifier(broker, trustAnchor, signerId, longTermKeyPair.getPrivate());
    }
}
