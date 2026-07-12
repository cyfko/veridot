package io.github.cyfko.veridot.trustroots.taas.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootProviderException;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Client de publication et de rotation des clés de confiance auprès du cluster d'autorité TAAS.
 * <p>
 * Utilisé par les microservices émetteurs pour publier de nouvelles clés ou effectuer des rotations de clés.
 * Met en œuvre une boucle de failover automatique pour s'assurer que l'opération réussisse même si certains
 * nœuds du cluster TAAS sont hors-ligne.
 */
public class TaasPublisherClient {
    
    /** Liste des adresses de base des nœuds du cluster TAAS. */
    private final List<String> clusterUrls;
    
    /** Client HTTP réutilisé. */
    private final HttpClient httpClient;
    
    /** Sérialiseur Jackson. */
    private final ObjectMapper objectMapper;
    
    /** Timeout par défaut de la requête. */
    private final Duration requestTimeout;

    /**
     * Instancie le client de publication.
     *
     * @param clusterUrls Adresses de base des nœuds du cluster TAAS.
     * @param sslContext Contexte de chiffrement TLS. Optionnel.
     * @param requestTimeout Délai d'expiration de requête. Optionnel (5s par défaut).
     */
    public TaasPublisherClient(List<String> clusterUrls, SSLContext sslContext, Duration requestTimeout) {
        this.clusterUrls = new ArrayList<>(Objects.requireNonNull(clusterUrls, "clusterUrls"));
        if (this.clusterUrls.isEmpty()) {
            throw new IllegalArgumentException("clusterUrls cannot be empty");
        }

        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        this.httpClient = builder.build();
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(5);
    }

    /**
     * Publie une nouvelle {@link TrustEntry} auprès du cluster TAAS avec une preuve d'attestation par défaut.
     *
     * @param entry L'entrée contenant la clé signée à enregistrer.
     * @throws TrustRootProviderException si la publication échoue sur tous les nœuds du cluster.
     */
    public void publish(TrustEntry entry) throws TrustRootProviderException {
        String proof = System.getenv("VDOT_ATTESTATION_PROOF");
        if (proof == null) {
            proof = System.getProperty("VDOT_ATTESTATION_PROOF", "none");
        }
        publish(entry, proof);
    }

    /**
     * Publie une nouvelle {@link TrustEntry} auprès du cluster TAAS avec une preuve d'attestation explicite.
     *
     * @param entry L'entrée contenant la clé signée à enregistrer.
     * @param attestationProof La preuve d'attestation (e.g. jeton GCP, jeton K8s, TPM quote).
     * @throws TrustRootProviderException si la publication échoue sur tous les nœuds du cluster.
     */
    public void publish(TrustEntry entry, String attestationProof) throws TrustRootProviderException {
        if (attestationProof == null || attestationProof.isBlank()) {
            throw new IllegalArgumentException("attestationProof cannot be null or blank");
        }
        Exception lastException = null;
        for (String baseUrl : clusterUrls) {
            try {
                java.util.Map<String, Object> publishRequest = java.util.Map.of(
                    "entry", entry,
                    "attestationProof", attestationProof
                );
                byte[] requestBody = objectMapper.writeValueAsBytes(publishRequest);
                URI uri = URI.create(baseUrl + "/v2/trust-entries");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 201 || response.statusCode() == 200) {
                    return; // Succès
                } else {
                    throw new TrustRootProviderException("TAAS Server returned status code: " + response.statusCode() 
                            + " - " + new String(response.body(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to publish TrustEntry to any TAAS cluster node", lastException);
    }

    /**
     * Effectue une rotation de clé en remplaçant la clé existante d'un sujet par une nouvelle version, avec preuve d'attestation par défaut.
     *
     * @param subject Identifiant du sujet à mettre à jour.
     * @param entry La nouvelle {@link TrustEntry} contenant la clé mise à jour et signée.
     * @throws TrustRootProviderException si la rotation échoue sur tous les nœuds du cluster.
     */
    public void rotate(String subject, TrustEntry entry) throws TrustRootProviderException {
        String proof = System.getenv("VDOT_ATTESTATION_PROOF");
        if (proof == null) {
            proof = System.getProperty("VDOT_ATTESTATION_PROOF", "none");
        }
        rotate(subject, entry, proof);
    }

    /**
     * Effectue une rotation de clé en remplaçant la clé existante d'un sujet par une nouvelle version, avec preuve d'attestation explicite.
     *
     * @param subject Identifiant du sujet à mettre à jour.
     * @param entry La nouvelle {@link TrustEntry} contenant la clé mise à jour et signée.
     * @param attestationProof La preuve d'attestation.
     * @throws TrustRootProviderException si la rotation échoue sur tous les nœuds du cluster.
     */
    public void rotate(String subject, TrustEntry entry, String attestationProof) throws TrustRootProviderException {
        if (attestationProof == null || attestationProof.isBlank()) {
            throw new IllegalArgumentException("attestationProof cannot be null or blank");
        }
        Exception lastException = null;
        for (String baseUrl : clusterUrls) {
            try {
                java.util.Map<String, Object> publishRequest = java.util.Map.of(
                    "entry", entry,
                    "attestationProof", attestationProof
                );
                byte[] requestBody = objectMapper.writeValueAsBytes(publishRequest);
                URI uri = URI.create(baseUrl + "/v2/trust-entries/" + URLEncoder.encode(subject, StandardCharsets.UTF_8));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200 || response.statusCode() == 204) {
                    return; // Succès
                } else {
                    throw new TrustRootProviderException("TAAS Server returned status code: " + response.statusCode() 
                            + " - " + new String(response.body(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to rotate TrustEntry for subject " + subject + " on any TAAS cluster node", lastException);
    }
}
