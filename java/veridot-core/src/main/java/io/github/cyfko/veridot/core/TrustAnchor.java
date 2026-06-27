package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.security.PublicKey;

/**
 * Root of trust for Veridot's distributed key-announcement model.
 *
 * <p>In Veridot v3.0, the broker (Kafka, database, etc.) is a <em>transport only</em>.
 * A party that can write to the broker must not automatically become trusted. The
 * {@code TrustAnchor} breaks this coupling: every key announcement received from the
 * broker must be independently validated through this interface before any cryptographic
 * verification takes place.</p>
 *
 * <h2>Two trust models</h2>
 * <p>Implementors choose one of the two sealed sub-interfaces to match their deployment:</p>
 * <ul>
 *   <li>{@link PublicKeyResolver} — the long-term identity is published as a static public
 *       key (e.g., stored in a Vault KV secret, a configuration file, a service mesh's
 *       SPIFFE/X.509 certificate). The cryptographic verification happens locally inside
 *       {@code GenericSignerVerifier} after the key is resolved.</li>
 *   <li>{@link DelegatedVerifier} — the long-term identity and the verification logic both
 *       live inside an external security boundary (KMS, Vault Transit, HSM). The key never
 *       leaves that boundary. This is the highest-security option.</li>
 * </ul>
 *
 * <h2>Canonical announcement encoding</h2>
 * <p>The {@code canonicalAnnouncement} bytes passed to
 * {@link DelegatedVerifier#verify} follow the length-prefixed layout defined
 * in Protocol V2 §F1:</p>
 * <pre>
 *   len(pubkeyDER) [4 bytes, big-endian] ‖ pubkeyDER
 *   ‖ timestamp [8 bytes, big-endian, epoch seconds]
 *   ‖ ttl       [8 bytes, big-endian, seconds]
 *   ‖ len(signerId) [4 bytes, big-endian] ‖ signerId [UTF-8]
 * </pre>
 *
 * <h2>Authorization scope</h2>
 * <p>Implementations are also responsible for enforcing that the resolved {@code signerId}
 * is authorized to certify keys for the {@code groupId} prefix it claims. A compromised
 * signing identity must remain confined to its authorized {@code groupId} prefixes.</p>
 *
 * @author Frank KOSSI
 * @since 3.0.0
 * @see io.github.cyfko.veridot.core.exceptions.TrustResolutionException
 */
public sealed interface TrustAnchor
        permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier {

    /**
     * Resolves the long-term identity of {@code signerId} into the public key that the
     * caller must use to verify the key-announcement signature.
     *
     * <p>The implementation is responsible for fetching and returning the key associated
     * with the given {@code signerId} from a trust store that is entirely independent of
     * the Veridot broker (e.g., a Vault KV store, a local trust store, a service mesh's
     * certificate authority, etc.).</p>
     *
     * <p>The cryptographic verification of the announcement signature is performed by
     * {@code GenericSignerVerifier} after this method returns. The implementation should
     * only resolve the key — not verify the announcement.</p>
     */
    non-sealed interface PublicKeyResolver extends TrustAnchor {

        /**
         * Resolves the long-term {@link PublicKey} associated with {@code signerId}.
         *
         * @param signerId the identifier of the signer whose long-term key is requested;
         *                 never {@code null} or blank
         * @return the long-term public key to use for verifying the announcement signature;
         *         never {@code null}
         * @throws TrustResolutionException.Unavailable       if the trust store is
         *         temporarily unreachable (network failure, timeout, etc.)
         * @throws TrustResolutionException.SignatureRejected  if {@code signerId} is
         *         unknown or has been revoked in the trust store
         */
        PublicKey resolve(String signerId) throws TrustResolutionException;
    }

    /**
     * Delegates the entire verification of a key announcement to an external security
     * boundary (KMS, Vault Transit, HSM).
     *
     * <p>The long-term signing key never leaves the external boundary. Neither does the
     * verification logic. This is the recommended option for production deployments
     * where key material must be hardware-protected.</p>
     *
     * <p>Implementations must throw {@link TrustResolutionException.SignatureRejected}
     * if the signature does not match — never swallow the failure or return normally.</p>
     */
    non-sealed interface DelegatedVerifier extends TrustAnchor {

        /**
         * Asks the external trust boundary to verify that {@code signature} was produced
         * by the long-term key of {@code signerId} over {@code canonicalAnnouncement}.
         *
         * <p>On success, this method returns normally. On failure, it throws the
         * appropriate subtype of {@link TrustResolutionException}.</p>
         *
         * @param signerId              the claimed identity of the announcer; never {@code null}
         * @param canonicalAnnouncement the byte representation of the key announcement
         *                              (length-prefixed format defined in Protocol V2 §F1)
         * @param signature             the signature bytes to verify
         * @throws TrustResolutionException.Unavailable      if the external boundary is
         *         temporarily unreachable
         * @throws TrustResolutionException.SignatureRejected if the signature is invalid
         *         or {@code signerId} is unknown / revoked
         */
        void verify(String signerId, byte[] canonicalAnnouncement, byte[] signature)
                throws TrustResolutionException;
    }
}
