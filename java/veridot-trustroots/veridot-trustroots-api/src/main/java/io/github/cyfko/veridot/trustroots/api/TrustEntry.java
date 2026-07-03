package io.github.cyfko.veridot.trustroots.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Entrée canonique du registre de confiance représentant une clé publique signée.
 * Ce record Java garantit l'immuabilité et applique des règles strictes de validation à l'instanciation.
 *
 * @param schemaVersion Version du schéma JSON du document (par défaut 1).
 * @param subject Identifiant de l'émetteur de tokens (ex: "service-name").
 * @param publicKeyEncoded Clé publique encodée en Base64 URL-safe.
 * @param algorithm Algorithme cryptographique de la clé publique ({@link KeyAlgorithm}).
 * @param notBefore Instant de début de validité de la clé.
 * @param notAfter Instant d'expiration de la clé.
 * @param version Numéro de version séquentiel strictement positif de l'entrée.
 * @param fingerprint Empreinte de hachage unique de la clé (ex: SHA-256 hex).
 * @param issuerSignature Signature cryptographique de l'autorité de confiance, garantissant l'intégrité de l'entrée.
 * @param publishedAt Date de publication de cette entrée dans le registre TAD.
 * @param isRoot Indique s'il s'agit d'une clé racine (Root Key).
 * @param metadata Métadonnées complémentaires clés/valeurs.
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
    /**
     * Constructeur canonique avec logique de validation robuste.
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
        
        if (subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        if (subject.length() > 512) throw new IllegalArgumentException("subject exceeds 512 chars");
        if (!notBefore.isBefore(notAfter)) throw new IllegalArgumentException("notBefore must be before notAfter");
        if (version <= 0) throw new IllegalArgumentException("version must be positive");
        
        metadata = metadata == null ? Collections.emptyMap() : Map.copyOf(metadata);
    }

    /**
     * Indique si cette entrée est valide à l'instant donné.
     * Une entrée est valide si l'instant spécifié se trouve dans l'intervalle [notBefore, notAfter].
     *
     * @param instant L'instant à évaluer.
     * @return {@code true} si l'entrée est valide à cet instant, sinon {@code false}.
     */
    public boolean isValidAt(Instant instant) {
        return !instant.isBefore(notBefore) && !instant.isAfter(notAfter);
    }

    /**
     * Calcule la charge utile canonique brute destinée à être signée ou vérifiée cryptographiquement.
     * La charge utile est générée selon un format strict à base de sauts de lignes (\n).
     *
     * @return La charge utile brute au format UTF-8.
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

    /**
     * Crée une nouvelle instance de Builder pour construire une {@link TrustEntry}.
     *
     * @return Un nouveau {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder fluide pour faciliter la création d'instances {@link TrustEntry}.
     */
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

        /**
         * Spécifie la version de schéma.
         *
         * @param schemaVersion Version de schéma.
         * @return {@code this} Builder.
         */
        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        /**
         * Spécifie le sujet.
         *
         * @param subject Le sujet.
         * @return {@code this} Builder.
         */
        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        /**
         * Spécifie la clé publique encodée.
         *
         * @param publicKeyEncoded La clé publique encodée en Base64 URL-safe.
         * @return {@code this} Builder.
         */
        public Builder publicKeyEncoded(String publicKeyEncoded) {
            this.publicKeyEncoded = publicKeyEncoded;
            return this;
        }

        /**
         * Spécifie l'algorithme cryptographique de la clé publique.
         *
         * @param algorithm L'algorithme.
         * @return {@code this} Builder.
         */
        public Builder algorithm(KeyAlgorithm algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        /**
         * Spécifie le début de validité de la clé.
         *
         * @param notBefore Date de début.
         * @return {@code this} Builder.
         */
        public Builder notBefore(Instant notBefore) {
            this.notBefore = notBefore;
            return this;
        }

        /**
         * Spécifie la fin de validité (expiration) de la clé.
         *
         * @param notAfter Date d'expiration.
         * @return {@code this} Builder.
         */
        public Builder notAfter(Instant notAfter) {
            this.notAfter = notAfter;
            return this;
        }

        /**
         * Spécifie la version séquentielle de l'entrée.
         *
         * @param version Numéro de version.
         * @return {@code this} Builder.
         */
        public Builder version(long version) {
            this.version = version;
            return this;
        }

        /**
         * Spécifie l'empreinte unique calculée.
         *
         * @param fingerprint L'empreinte.
         * @return {@code this} Builder.
         */
        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        /**
         * Spécifie la signature d'intégrité de l'entrée.
         *
         * @param issuerSignature La signature de l'émetteur/autorité.
         * @return {@code this} Builder.
         */
        public Builder issuerSignature(String issuerSignature) {
            this.issuerSignature = issuerSignature;
            return this;
        }

        /**
         * Spécifie la date de publication dans le TAD.
         *
         * @param publishedAt La date de publication.
         * @return {@code this} Builder.
         */
        public Builder publishedAt(Instant publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        /**
         * Indique s'il s'agit d'une clé racine.
         *
         * @param isRoot {@code true} si clé racine.
         * @return {@code this} Builder.
         */
        public Builder isRoot(boolean isRoot) {
            this.isRoot = isRoot;
            return this;
        }

        /**
         * Spécifie la map complète des métadonnées.
         *
         * @param metadata Map clé/valeur.
         * @return {@code this} Builder.
         */
        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Ajoute une métadonnée unitaire.
         *
         * @param key Clé.
         * @param value Valeur.
         * @return {@code this} Builder.
         */
        public Builder putMetadata(String key, String value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        /**
         * Construit l'instance finale de {@link TrustEntry}.
         *
         * @return L'entrée {@link TrustEntry} immuable construite.
         */
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
