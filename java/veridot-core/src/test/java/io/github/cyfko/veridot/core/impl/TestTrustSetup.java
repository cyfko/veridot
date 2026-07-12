package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.security.*;
import java.util.HashMap;
import java.util.Map;

/**
 * V5 Test utility: wires up a self-contained Ed25519 instance key pair and a matching
 * {@link TrustRoot} for unit testing.
 *
 * <p>V5 changes from V4:
 * <ul>
 *   <li>Ed25519 instead of RSA-2048 — matches V5 default algorithm</li>
 *   <li>Subject computed via {@link SubjectComputer#compute(String, PublicKey)}</li>
 *   <li>{@link TrustIdentity} now carries 3 fields: publicKey, isRoot, algorithm</li>
 *   <li>{@link GenericSignerVerifier} constructor takes CN, private key, and public key separately</li>
 * </ul>
 */
public class TestTrustSetup {

    /** V5 field name — the instance's Ed25519 key pair. */
    public final KeyPair instanceKeyPair;
    /** V4-compat alias for {@link #instanceKeyPair}. */
    public final KeyPair longTermKeyPair;
    /** The common name used to compute the V5 subject. */
    public final String cn;
    /** V5 subject identifier (CN@hash). */
    public final String signerId;
    public final TrustRoot trustRoot;

    private TestTrustSetup(KeyPair instanceKeyPair, String cn, String signerId, TrustRoot trustRoot) {
        this.instanceKeyPair = instanceKeyPair;
        this.longTermKeyPair = instanceKeyPair; // V4-compat alias
        this.cn = cn;
        this.signerId = signerId;
        this.trustRoot = trustRoot;
    }

    public static TestTrustSetup create() {
        return create("test-svc");
    }

    public static TestTrustSetup create(String cn) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            String signerId = SubjectComputer.compute(cn, kp.getPublic());

            Map<String, TrustIdentity> identityStore = new HashMap<>();
            identityStore.put(signerId, new TrustIdentity(kp.getPublic(), true, Algorithm.ED25519));

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

            return new TestTrustSetup(kp, cn, signerId, root);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create test trust setup", e);
        }
    }

    public GenericSignerVerifier newSignerVerifier(InMemoryBroker broker) {
        return new GenericSignerVerifier(broker, trustRoot, cn,
                instanceKeyPair.getPrivate(), instanceKeyPair.getPublic(), Algorithm.ED25519);
    }

    public GenericSignerVerifier newSignerVerifier(InMemoryBroker broker,
                                                    int maxSessions,
                                                    io.github.cyfko.veridot.core.EvictionPolicy policy) {
        return new GenericSignerVerifier(broker, trustRoot, cn,
                instanceKeyPair.getPrivate(), instanceKeyPair.getPublic(), Algorithm.ED25519,
                maxSessions, policy);
    }
}
