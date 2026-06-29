package io.github.cyfko.veridot.core;

/**
 * Resolves long-term issuer identities to their public key.
 *
 * <p>Unlike the V3 TrustAnchor, isAuthorizedForScope is not resolved here.
 * Authorization is now established exclusively by CAPABILITY entries stored on the broker (§6).</p>
 */
public sealed interface TrustRoot permits PublicKeyTrustRoot, DelegatedTrustRoot {

    /**
     * Resolves the long-term identity of the issuer.
     *
     * @param issuer the long-term identifier of the issuer
     * @return the long-term identity of the issuer
     * @throws io.github.cyfko.veridot.core.exceptions.VeridotException if the issuer cannot be resolved
     */
    TrustIdentity resolve(String issuer);
}
