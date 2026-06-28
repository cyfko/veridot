package io.github.cyfko.veridot.core;

import java.security.PublicKey;

/**
 * Resolves long-term issuer identities to their public key.
 *
 * <p>Unlike the V3 TrustAnchor, isAuthorizedForScope is not resolved here.
 * Authorization is now established exclusively by CAPABILITY entries stored on the broker (§6).</p>
 */
public sealed interface TrustRoot permits PublicKeyTrustRoot, DelegatedTrustRoot {

    /**
     * Resolves the issuer identity to their long-term public key.
     *
     * @param issuer the long-term identifier of the issuer
     * @return the long-term public key of the issuer
     * @throws io.github.cyfko.veridot.core.exceptions.VeridotException if the issuer cannot be resolved
     */
    PublicKey resolve(String issuer);

    /**
     * Returns true if the issuer is a root identity (§6.5).
     * Root identities are unconditionally authorized to issue capabilities
     * and configs without holding a prior CAPABILITY entry.
     */
    boolean isRootIdentity(String issuer);
}
