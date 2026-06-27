package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Test utility: wires up a self-contained long-term key pair and a matching
 * {@link TrustAnchor.PublicKeyResolver} for unit testing.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * TestTrustSetup setup = TestTrustSetup.create();
 * GenericSignerVerifier sv = setup.newSignerVerifier(broker);
 * }</pre>
 */
public class TestTrustSetup {

    /** The long-term key pair used to sign key announcements. */
    public final KeyPair longTermKeyPair;

    /** The signerId associated with the long-term key pair. */
    public final String signerId;

    /** A TrustAnchor that resolves signerId → the long-term public key. */
    public final TrustAnchor trustAnchor;

    private TestTrustSetup(KeyPair longTermKeyPair, String signerId, TrustAnchor trustAnchor) {
        this.longTermKeyPair = longTermKeyPair;
        this.signerId = signerId;
        this.trustAnchor = trustAnchor;
    }

    /**
     * Creates a new {@code TestTrustSetup} with a fresh RSA-2048 long-term key pair
     * (2048 is sufficient for tests) and a simple in-memory resolver.
     */
    public static TestTrustSetup create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            KeyPair kp = gen.generateKeyPair();
            String signerId = "test-signer";

            // Simple in-memory resolver: signerId → public key
            Map<String, PublicKey> keyStore = new HashMap<>();
            keyStore.put(signerId, kp.getPublic());

            TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) id -> {
                PublicKey pk = keyStore.get(id);
                if (pk == null) {
                    throw new TrustResolutionException.SignatureRejected("Unknown signerId: " + id);
                }
                return pk;
            };

            return new TestTrustSetup(kp, signerId, anchor);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create test trust setup", e);
        }
    }

    /**
     * Creates a {@link GenericSignerVerifier} connected to the given broker,
     * using this setup's trust anchor and long-term key pair.
     */
    public GenericSignerVerifier newSignerVerifier(InMemoryMetadataBroker broker) {
        return new GenericSignerVerifier(broker, trustAnchor, signerId, longTermKeyPair.getPrivate());
    }

    /**
     * Creates a {@link GenericSignerVerifier} with session limits.
     */
    public GenericSignerVerifier newSignerVerifier(InMemoryMetadataBroker broker,
                                                    int maxSessions,
                                                    EvictionPolicy policy) {
        return new GenericSignerVerifier(broker, trustAnchor, signerId, longTermKeyPair.getPrivate(),
                maxSessions, policy);
    }
}
