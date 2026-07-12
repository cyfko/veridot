package io.github.cyfko.veridot.trustroots.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trust Entry for Protocol V5 (§5.4).
 *
 * <p>Represents a public key identity registered with TAAS. V5 changes from V4:
 * <ul>
 *   <li>{@code schemaVersion} defaults to {@code 2}</li>
 *   <li>{@code isInstanceScoped} — single-key-per-instance (§5.1), default {@code true}</li>
 *   <li>{@code attestationPlugin} — name of the attestation plugin used during registration (§1.3.1)</li>
 *   <li>{@code attestationRef} — opaque reference to attestation evidence (optional)</li>
 *   <li>{@code kemPublicKey} — ML-KEM-768 encapsulation key for hybrid encryption (§14.5, optional, 1184 bytes)</li>
 * </ul>
 *
 * @param schemaVersion Schema version (MUST be 2 for V5).
 * @param subject Subject identifier in {@code CN@hash} format (§5.1).
 * @param publicKeyEncoded Public key in Base64 URL-safe encoding.
 * @param algorithm Cryptographic algorithm ({@link KeyAlgorithm}).
 * @param notBefore Validity start instant.
 * @param notAfter Validity end instant.
 * @param version Sequential version number (strictly positive).
 * @param fingerprint SHA-256 fingerprint of the public key (hex).
 * @param issuerSignature Cryptographic signature by the TAAS authority.
 * @param publishedAt Publication timestamp in the TAAS registry.
 * @param isRoot Whether this is a root trust anchor.
 * @param isInstanceScoped Whether this identity is instance-scoped (single-key).
 * @param attestationPlugin Name of the attestation plugin ("k8s", "gcp", "tpm", "none").
 * @param attestationRef Opaque reference to attestation evidence (nullable).
 * @param kemPublicKey ML-KEM-768 encapsulation key for HYBRID_ASYMMETRIC encryption (nullable, 1184 bytes if present).
 * @param metadata Supplementary key-value metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrustEntry(
    @JsonProperty("_schemaVersion") int schemaVersion,
    String subject,
    String publicKeyEncoded,
    KeyAlgorithm algorithm,
    Instant notBefore,
    Instant notAfter,
    long version,
    String fingerprint,
    String issuerSignature,
    Instant publishedAt,
    boolean isRoot,
    boolean isInstanceScoped,
    String attestationPlugin,
    String attestationRef,
    byte[] kemPublicKey,
    Map<String, String> metadata
) {

    /** ML-KEM-768 public key size in bytes. */
    private static final int KEM_PUBLIC_KEY_SIZE = 1184;

    /**
     * Canonical constructor with validation.
     */
    public TrustEntry {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(publicKeyEncoded, "publicKeyEncoded");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(issuerSignature, "issuerSignature");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(attestationPlugin, "attestationPlugin");

        if (subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        if (subject.length() > 512) throw new IllegalArgumentException("subject exceeds 512 chars");
        if (isInstanceScoped && !subject.contains("@")) {
            throw new IllegalArgumentException("Instance-scoped subject must contain '@': " + subject);
        }
        if (!notBefore.isBefore(notAfter)) throw new IllegalArgumentException("notBefore must be before notAfter");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        if (kemPublicKey != null && kemPublicKey.length != KEM_PUBLIC_KEY_SIZE) {
            throw new IllegalArgumentException("kemPublicKey must be exactly " + KEM_PUBLIC_KEY_SIZE 
                + " bytes (ML-KEM-768), got: " + kemPublicKey.length);
        }

        // Defensive copy of mutable fields
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
        kemPublicKey = kemPublicKey == null ? null : kemPublicKey.clone();
    }

    /**
     * Whether this entry is valid at the given instant.
     *
     * @param instant the instant to evaluate
     * @return true if the entry is valid at this instant
     */
    public boolean isValidAt(Instant instant) {
        return !instant.isBefore(notBefore) && !instant.isAfter(notAfter);
    }

    /**
     * Computes the canonical payload for cryptographic signing/verification (§5.4.2).
     *
     * <p>Fields concatenated with newline separators in the following order:
     * {@code subject\npublicKeyEncoded\nalgorithm\nnotBefore\nnotAfter\nversion\nisInstanceScoped\nattestationPlugin}
     *
     * @return the canonical payload as UTF-8 bytes
     */
    public byte[] canonicalPayload() {
        String payload = subject + "\n"
                + publicKeyEncoded + "\n"
                + algorithm.identifier() + "\n"
                + notBefore.toString() + "\n"
                + notAfter.toString() + "\n"
                + version + "\n"
                + isInstanceScoped + "\n"
                + attestationPlugin;
        return payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Returns whether this entry supports HYBRID_ASYMMETRIC encryption (encAlg 0x03).
     *
     * @return true if kemPublicKey is present
     */
    public boolean supportsHybridEncryption() {
        return kemPublicKey != null;
    }

    /**
     * Creates a new Builder for constructing a {@link TrustEntry}.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link TrustEntry}.
     */
    public static class Builder {
        private int schemaVersion = 2; // V5 default
        private String subject;
        private String publicKeyEncoded;
        private KeyAlgorithm algorithm;
        private Instant notBefore;
        private Instant notAfter;
        private long version;
        private String fingerprint;
        private String issuerSignature;
        private Instant publishedAt;
        private boolean isRoot;
        private boolean isInstanceScoped = true; // V5 default
        private String attestationPlugin = "none";
        private String attestationRef;
        private byte[] kemPublicKey;
        private Map<String, String> metadata = new HashMap<>();

        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder publicKeyEncoded(String publicKeyEncoded) {
            this.publicKeyEncoded = publicKeyEncoded;
            return this;
        }

        public Builder algorithm(KeyAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        public Builder notAfter(Instant notAfter) {
            this.notAfter = notAfter;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        public Builder issuerSignature(String issuerSignature) {
            this.issuerSignature = issuerSignature;
            return this;
        }

        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Builder isRoot(boolean isRoot) {
            this.isRoot = isRoot;
            return this;
        }

        public Builder isInstanceScoped(boolean isInstanceScoped) {
            this.isInstanceScoped = isInstanceScoped;
            return this;
        }

        public Builder attestationPlugin(String attestationPlugin) {
            this.attestationPlugin = attestationPlugin;
            return this;
        }

        public Builder attestationRef(String attestationRef) {
            this.attestationRef = attestationRef;
            return this;
        }

        public Builder kemPublicKey(byte[] kemPublicKey) {
            this.kemPublicKey = kemPublicKey;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder putMetadata(String key, String value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public TrustEntry build() {
            return new TrustEntry(
                schemaVersion,
                subject,
                publicKeyEncoded,
                algorithm,
                notBefore,
                notAfter,
                version,
                fingerprint,
                issuerSignature,
                publishedAt,
                isRoot,
                isInstanceScoped,
                attestationPlugin,
                attestationRef,
                kemPublicKey,
                metadata
            );
        }
    }
}
