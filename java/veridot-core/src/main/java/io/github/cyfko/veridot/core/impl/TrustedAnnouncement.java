package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Common long-term signing and verification logic for all Protocol V3 metadata announcements
 * (ephemeral key announcements, revocation tombstones, and configuration messages).
 *
 * <p>Centralizes the signature verification pattern via {@link TrustAnchor}.</p>
 *
 * @since 3.1.0 (F9)
 */
public final class TrustedAnnouncement {

    private TrustedAnnouncement() {}

    /**
     * Signs the properties map (excluding 'sig' and 'token') with the long-term private key.
     * Returns the signature encoded as URL-safe Base64 without padding.
     */
    public static String sign(String messageId, Map<String, String> props, PrivateKey longTermKey) {
        byte[] canonical = ProtocolV2.buildCanonicalBytes(messageId, props);
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(longTermKey);
            sig.update(canonical);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException(
                    "SECURITY: cannot sign announcement — refusing to publish unsigned", e);
        }
    }

    /**
     * Verifies the signer identity (sid) and signature (sig) of an announcement via TrustAnchor.
     *
     * @throws TrustResolutionException.SignatureRejected if sid/sig are missing, the signature is invalid,
     *         or the sid is unknown/revoked
     * @throws TrustResolutionException.Unavailable if the trust anchor is temporarily unreachable
     */
    public static void verify(String messageId, Map<String, String> meta, TrustAnchor anchor)
            throws TrustResolutionException {
        String sid = meta.get(ProtocolV2.PROP_SID);
        String sigB64 = meta.get(ProtocolV2.PROP_SIG);
        if (sid == null || sigB64 == null) {
            throw new TrustResolutionException.SignatureRejected("Missing sid/sig in announcement");
        }

        Map<String, String> props = new LinkedHashMap<>(meta);
        props.remove(ProtocolV2.PROP_SIG);
        props.remove(ProtocolV2.PROP_TOKEN);
        byte[] canonical = ProtocolV2.buildCanonicalBytes(messageId, props);
        byte[] sig = Base64.getUrlDecoder().decode(sigB64);

        switch (anchor) {
            case TrustAnchor.PublicKeyResolver r -> {
                PublicKey ltKey = r.resolve(sid);
                verifySignature(canonical, sig, ltKey);
            }
            case TrustAnchor.DelegatedVerifier d -> d.verify(sid, canonical, sig);
        }
    }

    private static void verifySignature(byte[] canonical, byte[] signature, PublicKey ltKey)
            throws TrustResolutionException.SignatureRejected {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(ltKey);
            sig.update(canonical);
            if (!sig.verify(signature)) {
                throw new TrustResolutionException.SignatureRejected("Signature verification failed");
            }
        } catch (TrustResolutionException.SignatureRejected e) {
            throw e;
        } catch (Exception e) {
            throw new TrustResolutionException.SignatureRejected(
                    "Signature verification error: " + e.getMessage());
        }
    }
}
