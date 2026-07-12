package io.github.cyfko.veridot.trustroots.taas.server.attestation;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;

/**
 * SPI (Service Provider Interface) for attestation verification plugins.
 *
 * <p>Implementations verify that a service instance is who it claims to be
 * by validating platform-specific attestation proofs (§1.3.1).
 *
 * <p>Supported plugin types:
 * <ul>
 *   <li>{@code k8s} — Kubernetes Projected Service Account Token (PSAT)</li>
 *   <li>{@code gcp} — GCP Instance Identity Token (IIT)</li>
 *   <li>{@code tpm} — TPM 2.0 Quote verification</li>
 *   <li>{@code none} — No attestation (dev/test, requires explicit opt-in)</li>
 * </ul>
 */
public interface AttestationVerifier {

    /**
     * Verifies the attestation proof for the given TrustEntry.
     *
     * @param entry the TrustEntry being registered or rotated
     * @param proof the raw attestation proof bytes
     * @return the verification result
     */
    AttestationResult verify(TrustEntry entry, byte[] proof);

    /**
     * Returns the name of this attestation plugin.
     *
     * @return the plugin name (e.g., "k8s", "gcp", "tpm", "none")
     */
    String pluginName();
}
