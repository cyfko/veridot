package io.github.cyfko.veridot.trustroots.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Entrée canonique du registre de confiance.
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
    Map<String, String> metadata
) {
    public TrustEntry {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(publicKeyEncoded, "publicKeyEncoded");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(notBefore, "notBefore");
        Objects.requireNonNull(notAfter, "notAfter");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(issuerSignature, "issuerSignature");
        Objects.requireNonNull(publishedAt, "publishedAt");
        
        if (subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        if (subject.length() > 512) throw new IllegalArgumentException("subject exceeds 512 chars");
        if (!notBefore.isBefore(notAfter)) throw new IllegalArgumentException("notBefore must be before notAfter");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    /**
     * Indique si cette entrée est valide à l'instant donné.
     */
    public boolean isValidAt(Instant instant) {
        return !instant.isBefore(notBefore) && !instant.isAfter(notAfter);
    }

    /**
     * Calcule la charge canonique à signer, encodée en UTF-8.
     */
    public byte[] canonicalPayload() {
        String payload = subject + "\n"
                + publicKeyEncoded + "\n"
                + algorithm.identifier() + "\n"
                + notBefore.toString() + "\n"
                + notAfter.toString() + "\n"
                + version;
        return payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int schemaVersion = 1;
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
                metadata
            );
        }
    }
}
