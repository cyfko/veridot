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
 * <h2>Universal canonical encoding</h2>
 * <p>The {@code canonicalBytes} passed to
 * {@link DelegatedVerifier#verify} follow the length-prefixed layout defined
 * in Protocol V3 §11.5:</p>
 * <pre>
 *   len(messageId) [4 bytes, big-endian] ‖ messageId [UTF-8]
 *   ‖ for each (key, value) sorted alphabetically (excluding 'sig' and 'token'):
 *       len(key)   [4 bytes, big-endian] ‖ key   [UTF-8]
 *       ‖ len(val) [4 bytes, big-endian] ‖ value [UTF-8]
 * </pre>
 * <p>Including {@code messageId} binds the signature to the specific broker key,
 * preventing a substitution attack where a valid announcement is relocated to a
 * different groupId/sequenceId.</p>
 *
 * <h2>Authorization scope</h2>
 * <p>Implementations are also responsible for enforcing that the resolved {@code sid}
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
     * Resolves the long-term identity of {@code sid} into the public key that the
     * caller must use to verify the key-announcement signature.
     *
     * <p>The implementation is responsible for fetching and returning the key associated
     * with the given {@code sid} from a trust store that is entirely independent of
     * the Veridot broker (e.g., a Vault KV store, a local trust store, a service mesh's
     * certificate authority, etc.).</p>
     *
     * <p>The cryptographic verification of the announcement signature is performed by
     * {@code GenericSignerVerifier} after this method returns. The implementation should
     * only resolve the key — not verify the announcement.</p>
     */
    non-sealed interface PublicKeyResolver extends TrustAnchor {

        /**
         * Resolves the long-term {@link PublicKey} associated with {@code sid}.
         *
         * @param sid the identifier of the signer whose long-term key is requested;
         *            never {@code null} or blank
         * @return the long-term public key to use for verifying the announcement signature;
         *         never {@code null}
         * @throws TrustResolutionException.Unavailable       if the trust store is
         *         temporarily unreachable (network failure, timeout, etc.)
         * @throws TrustResolutionException.SignatureRejected  if {@code sid} is
         *         unknown or has been revoked in the trust store
         */
        PublicKey resolve(String sid) throws TrustResolutionException;
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
         * by the long-term key of {@code sid} over {@code canonicalBytes}.
         *
         * <p>On success, this method returns normally. On failure, it throws the
         * appropriate subtype of {@link TrustResolutionException}.</p>
         *
         * @param sid            the claimed identity of the announcer; never {@code null}
         * @param canonicalBytes the canonical bytes to verify (format defined in Protocol V3 §11.5)
         * @param signature      the signature bytes to verify
         * @throws TrustResolutionException.Unavailable      if the external boundary is
         *         temporarily unreachable
         * @throws TrustResolutionException.SignatureRejected if the signature is invalid
         *         or {@code sid} is unknown / revoked
         */
        void verify(String sid, byte[] canonicalBytes, byte[] signature)
                throws TrustResolutionException;
    }

    /**
     * Verifies that the identity {@code sid} is authorized to publish a configuration
     * for the scope designated by {@code scopeKey} (e.g., {@code "3:user-123:__CONFIG__"},
     * {@code "3:__CONFIG__:eu-west"}, {@code "3:__CONFIG__:__ALL__"}).
     *
     * <p><strong>Default behavior: permissive</strong> (returns {@code true}),
     * to ensure backward compatibility with TrustAnchor implementations written
     * before version 3.1.0.</p>
     *
     * <p><strong>WARNING:</strong> unless this method is overridden in production,
     * any signing identity whose long-term key is resolved or verified by this anchor
     * can modify the configuration (session limits, eviction policies, default TTLs)
     * of any group, site, or the global configuration. This default behavior
     * authenticates the <em>origin</em> of the configuration but not its
     * <em>perimeter of authority</em>.</p>
     *
     * @param sid      the identity of the signer of the configuration, already authenticated
     * @param scopeKey the Protocol V3 key of the target configuration scope
     * @return {@code true} if {@code sid} is authorized to publish for this scope
     * @since 3.1.0
     */
    default boolean isAuthorizedForScope(String sid, String scopeKey) {
        return true; // Permissive by default — see warning above
    }
}
