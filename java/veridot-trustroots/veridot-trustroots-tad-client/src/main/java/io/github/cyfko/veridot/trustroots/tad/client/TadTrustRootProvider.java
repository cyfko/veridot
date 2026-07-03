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
 * Implémentation du provider TrustRootProvider pour interroger le TAD (Trust Authority Directory).
 */
public class TadTrustRootProvider implements TrustRootProvider {
    private final List<String> clusterUrls;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

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
