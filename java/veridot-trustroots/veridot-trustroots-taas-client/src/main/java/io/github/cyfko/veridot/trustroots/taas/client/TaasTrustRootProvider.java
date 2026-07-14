package io.github.cyfko.veridot.trustroots.taas.client;

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
 * Implémentation cliente de {@link TrustRootProvider} interrogeant un cluster d'autorités TAAS (Trust Authority as a Service).
 * <p>
 * Implémente une stratégie de tolérance aux pannes réseau en bouclant sur la liste des adresses du cluster
 * (failover) jusqu'à obtenir une réponse valide ou épuiser les nœuds disponibles.
 */
public class TaasTrustRootProvider implements TrustRootProvider {
    
    /** Liste des adresses de base HTTP/HTTPS des nœuds du cluster TAAS. */
    private final List<String> clusterUrls;
    
    /** Client HTTP Java 11 natif, réutilisé pour toutes les requêtes (thread-safe). */
    private final HttpClient httpClient;
    
    /** Mapper Jackson configuré pour la désérialisation JSON. */
    private final ObjectMapper objectMapper;
    
    /** Timeout de connexion et de lecture pour chaque requête unitaire. */
    private final Duration requestTimeout;

    /**
     * Instancie le client TAAS avec les configurations de cluster et de sécurité requises.
     *
     * @param clusterUrls Adresses de base des nœuds du cluster (ex: "http://127.0.0.1:8443").
     * @param sslContext Contexte SSL JCA pour activer la sécurité TLS/HTTPS. Optionnel.
     * @param requestTimeout Délai d'expiration de requête. Optionnel (5s par défaut).
     */
    public TaasTrustRootProvider(List<String> clusterUrls, SSLContext sslContext, Duration requestTimeout) {
        this.clusterUrls = new ArrayList<>(Objects.requireNonNull(clusterUrls, "clusterUrls"));
        if (this.clusterUrls.isEmpty()) {
            throw new IllegalArgumentException("clusterUrls cannot be empty");
        }
        
        HttpClient.Builder builder = HttpClient.newBuilder()
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
                URI uri = URI.create(baseUrl + "/v2/trust-entries/" + URLEncoder.encode(subject, StandardCharsets.UTF_8));
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
                    throw new TrustRootProviderException("TAAS Server returned status code: " + response.statusCode());
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to fetch subject '" + subject + "' from any TAAS cluster node", lastException);
    }

    @Override
    public List<TrustEntry> fetchModifiedSince(Instant since) throws TrustRootProviderException {
        Exception lastException = null;
        String formattedTime = DateTimeFormatter.ISO_INSTANT.format(since);
        
        for (String baseUrl : clusterUrls) {
            try {
                URI uri = URI.create(baseUrl + "/v2/trust-entries?modifiedSince=" + URLEncoder.encode(formattedTime, StandardCharsets.UTF_8));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    TaasSyncResponse syncResponse = objectMapper.readValue(response.body(), TaasSyncResponse.class);
                    return syncResponse.entries() != null ? syncResponse.entries() : Collections.emptyList();
                } else {
                    throw new TrustRootProviderException("TAAS Server returned status code: " + response.statusCode());
                }
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to fetch modifications since " + since + " from any TAAS cluster node", lastException);
    }

    @Override
    public String name() {
        return "TAAS-Cluster";
    }

    /**
     * Fetches the latest TAAS state digest for the given scope (V5, §18.2).
     *
     * <p>Calls {@code GET /v2/digest?scope=<scope>} directly against the TAAS cluster,
     * using the same failover strategy as other resolution methods. Instances use this
     * to verify state transparency independently of the broker.
     *
     * @param scope The scope to query the digest for.
     * @return The latest {@link io.github.cyfko.veridot.trustroots.api.SignedDigest} for the scope.
     * @throws TrustRootProviderException if no TAAS node could serve the request or the scope has no digest yet.
     */
    public io.github.cyfko.veridot.trustroots.api.SignedDigest getDigest(String scope) throws TrustRootProviderException {
        Exception lastException = null;
        String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8);

        for (String baseUrl : clusterUrls) {
            try {
                URI uri = URI.create(baseUrl + "/v2/digest?scope=" + encodedScope);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(requestTimeout)
                        .GET()
                        .build();

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return objectMapper.readValue(response.body(), io.github.cyfko.veridot.trustroots.api.SignedDigest.class);
                } else if (response.statusCode() == 404) {
                    throw new TrustRootProviderException("No digest available for scope: " + scope);
                } else {
                    throw new TrustRootProviderException("TAAS Server returned status code: " + response.statusCode());
                }
            } catch (TrustRootProviderException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
            }
        }
        throw new TrustRootProviderException("Failed to fetch digest for scope '" + scope + "' from any TAAS cluster node", lastException);
    }
}
