package io.github.cyfko.veridot.trustroots.taas.server;

import io.github.cyfko.veridot.core.impl.SubjectComputer;
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.taas.server.store.TaasRocksDbStore;

import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Bootstrap service for creating the initial root trust anchor (§5.7).
 *
 * <p>Used during first-time deployment to insert a root {@link TrustEntry} directly
 * into the TAAS store, bypassing Raft consensus. This entry serves as the root
 * of the trust chain for the entire deployment.
 *
 * <p>Security constraints:
 * <ul>
 *   <li>Only works if the store is empty ({@code store.count() == 0})</li>
 *   <li>The HTTP endpoint is protected by {@code VDOT_BOOTSTRAP_ENABLED=true}</li>
 *   <li>Creates an entry with {@code isRoot=true}, {@code isInstanceScoped=false},
 *       {@code attestationPlugin="none"}</li>
 * </ul>
 */
public class TaasBootstrapService {

    /** Utility class. */
    private TaasBootstrapService() {} // utility class

    /**
     * Bootstraps a root trust entry into the store.
     *
     * @param rootKeyPair The root key pair (trust anchor).
     * @param cn          The common name for the root identity (e.g., "veridot-root").
     * @param store       The TAAS RocksDB store.
     * @throws IllegalStateException if the store already contains entries
     */
    public static void bootstrap(KeyPair rootKeyPair, String cn, TaasRocksDbStore store) {
        if (store.count() > 0) {
            throw new IllegalStateException("Cannot bootstrap: store already contains " + store.count() + " entries");
        }

        String subject = SubjectComputer.compute(cn, rootKeyPair.getPublic());
        String publicKeyEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(rootKeyPair.getPublic().getEncoded());
        String fingerprint = computeFingerprint(rootKeyPair.getPublic().getEncoded());

        Instant now = Instant.now();
        TrustEntry rootEntry = TrustEntry.builder()
                .subject(subject)
                .publicKeyEncoded(publicKeyEncoded)
                .algorithm(KeyAlgorithm.ED25519)
                .notBefore(now)
                .notAfter(now.plus(3650, ChronoUnit.DAYS)) // 10-year validity for root
                .version(1)
                .fingerprint(fingerprint)
                .issuerSignature("self-signed-bootstrap")
                .publishedAt(now)
                .isRoot(true)
                .isInstanceScoped(false)
                .attestationPlugin("none")
                .build();

        store.putDirect(rootEntry);
    }

    /**
     * Computes the SHA-256 fingerprint of a public key as a hex string.
     *
     * @param publicKeyBytes The raw bytes of the public key.
     * @return The SHA-256 fingerprint as a hex string.
     */
    private static String computeFingerprint(byte[] publicKeyBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
