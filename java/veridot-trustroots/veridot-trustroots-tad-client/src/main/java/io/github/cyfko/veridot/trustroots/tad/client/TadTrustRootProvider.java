package io.github.cyfko.veridot.trustroots.tad.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootProviderException;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Implémentation cliente de {@link TrustRootProvider} interrogeant un cluster d'autorités TAD (Trust Authority Directory).
 * <p>
 * Implémente une stratégie de tolérance aux pannes réseau en bouclant sur la liste des adresses du cluster
 * (failover) jusqu'à obtenir une réponse valide ou épuiser les nœuds disponibles.
 */
public class TadTrustRootProvider implements TrustRootProvider {
    
    /** Liste des adresses de base HTTP/HTTPS des nœuds du cluster TAD. */
    private final List<String> clusterUrls;
    
    /** Client HTTP Java 11 natif, réutilisé pour toutes les requêtes (thread-safe). */
    private final HttpClient httpClient;
    
    /** Mapper Jackson configuré pour la désérialisation JSON. */
    private final ObjectMapper objectMapper;
    
    /** Timeout de connexion et de lecture pour chaque requête unitaire. */
    private final Duration requestTimeout;

    /**
     * Instancie le client TAD avec les configurations de cluster et de sécurité requises.
     *
     * @param clusterUrls Adresses de base des nœuds du cluster (ex: "http://127.0.0.1:8443").
     * @param sslContext Contexte SSL JCA pour activer la sécurité TLS/HTTPS. Optionnel.
     * @param requestTimeout Délai d'expiration de requête. Optionnel (5s par défaut).
     */
    public TadTrustRootProvider(List<String> clusterUrls, SSLContext sslContext, Duration requestTimeout) {
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

    @Override
    public Optional<TrustEntry> fetch(String subject) throws TrustRootProviderException {
        Exception lastException = null;
        for (String baseUrl : clusterUrls) {
            try {
                URI uri = URI.create(baseUrl + "/v1/trust-entries/" + URLEncoder.encode(subject, StandardCharsets.UTF_8));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    TrustEntry entry = objectMapper.readValue(response.body(), TrustEntry.class);
                    return Optional.of(entry);
                } else if (response.statusCode() == 404) {
                    return Optional.empty();
                } else {
                    throw new TrustRootProviderException("TAD Server returned status code: " + response.statusCode());
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to fetch subject '" + subject + "' from any TAD cluster node", lastException);
    }

    @Override
    public List<TrustEntry> fetchModifiedSince(Instant since) throws TrustRootProviderException {
        Exception lastException = null;
        String formattedTime = DateTimeFormatter.ISO_INSTANT.format(since);
        
        for (String baseUrl : clusterUrls) {
            try {
                URI uri = URI.create(baseUrl + "/v1/trust-entries?modifiedSince=" + URLEncoder.encode(formattedTime, StandardCharsets.UTF_8));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    TadSyncResponse syncResponse = objectMapper.readValue(response.body(), TadSyncResponse.class);
                    return syncResponse.entries() != null ? syncResponse.entries() : Collections.emptyList();
                } else {
                    throw new TrustRootProviderException("TAD Server returned status code: " + response.statusCode());
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to fetch modifications since " + since + " from any TAD cluster node", lastException);
    }

    @Override
    public String name() {
        return "TAD-Cluster";
    }
}
