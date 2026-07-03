package io.github.cyfko.veridot.trustroots.tad.client;

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
 * Client de publication et de rotation des clés de confiance auprès du cluster d'autorité TAD.
 * <p>
 * Utilisé par les microservices émetteurs pour publier de nouvelles clés ou effectuer des rotations de clés.
 * Met en œuvre une boucle de failover automatique pour s'assurer que l'opération réussisse même si certains
 * nœuds du cluster TAD sont hors-ligne.
 */
public class TadPublisherClient {
    
    /** Liste des adresses de base des nœuds du cluster TAD. */
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
     * @param clusterUrls Adresses de base des nœuds du cluster TAD.
     * @param sslContext Contexte de chiffrement TLS. Optionnel.
     * @param requestTimeout Délai d'expiration de requête. Optionnel (5s par défaut).
     */
    public TadPublisherClient(List<String> clusterUrls, SSLContext sslContext, Duration requestTimeout) {
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
     * Publie une nouvelle {@link TrustEntry} auprès du cluster TAD.
     *
     * @param entry L'entrée contenant la clé signée à enregistrer.
     * @throws TrustRootProviderException si la publication échoue sur tous les nœuds du cluster.
     */
    public void publish(TrustEntry entry) throws TrustRootProviderException {
        Exception lastException = null;
        for (String baseUrl : clusterUrls) {
            try {
                byte[] requestBody = objectMapper.writeValueAsBytes(entry);
                URI uri = URI.create(baseUrl + "/v1/trust-entries");
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
                    throw new TrustRootProviderException("TAD Server returned status code: " + response.statusCode() 
                            + " - " + new String(response.body(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to publish TrustEntry to any TAD cluster node", lastException);
    }

    /**
     * Effectue une rotation de clé en remplaçant la clé existante d'un sujet par une nouvelle version.
     *
     * @param subject Identifiant du sujet à mettre à jour.
     * @param entry La nouvelle {@link TrustEntry} contenant la clé mise à jour et signée.
     * @throws TrustRootProviderException si la rotation échoue sur tous les nœuds du cluster.
     */
    public void rotate(String subject, TrustEntry entry) throws TrustRootProviderException {
        Exception lastException = null;
        for (String baseUrl : clusterUrls) {
            try {
                byte[] requestBody = objectMapper.writeValueAsBytes(entry);
                URI uri = URI.create(baseUrl + "/v1/trust-entries/" + URLEncoder.encode(subject, StandardCharsets.UTF_8));
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
                    throw new TrustRootProviderException("TAD Server returned status code: " + response.statusCode() 
                            + " - " + new String(response.body(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to rotate TrustEntry for subject " + subject + " on any TAD cluster node", lastException);
    }
}
