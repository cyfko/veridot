package io.github.cyfko.veridot.trustroots.taas.server;

import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.taas.client.TaasPublisherClient;
import io.github.cyfko.veridot.trustroots.taas.client.TaasTrustRootProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TaasServerApplication.class)
class TaasServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("veridot.taas-server.storage.directory", () -> tempDir.toAbsolutePath().toString());
        registry.add("veridot.taas-server.node-id", () -> "127.0.0.1:19443");
        registry.add("veridot.taas-server.initial-peers", () -> "127.0.0.1:19443");
    }

    /**
     * Helper to create a V5 TrustEntry with all required fields.
     */
    private static TrustEntry makeEntry(String subject, String publicKeyEncoded, long version,
                                         String fingerprint, String signature, Instant now) {
        return new TrustEntry(
            2,                          // schemaVersion V5
            subject,
            publicKeyEncoded,
            KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
            version,
            fingerprint,
            signature,
            now,
            false,                      // isRoot
            false,                      // isInstanceScoped
            "none",                     // attestationPlugin
            null,                       // attestationRef
            null,                       // kemPublicKey
            Collections.emptyMap()      // metadata
        );
    }

    @Test
    void testPublishAndResolve() throws Exception {
        // Wait a short time for Raft leader election
        Thread.sleep(3000);

        String subject = "test-service";
        Instant now = Instant.now();

        TrustEntry entry = makeEntry(subject, "abc", 1, "finger", "sig", now);

        // V5 API uses /v2/ prefix
        String resolveUrl = "http://127.0.0.1:" + port + "/v2/trust-entries/" + subject;
        var getResponse = restTemplate.getForEntity(resolveUrl, Map.class);
        assertEquals(404, getResponse.getStatusCode().value());

        // V5 POST requires PublishRequest wrapper with attestationProof
        String publishUrl = "http://127.0.0.1:" + port + "/v2/trust-entries";
        Map<String, Object> publishRequest = Map.of(
            "entry", entry,
            "attestationProof", "test-proof"
        );
        var postResponse = restTemplate.postForEntity(publishUrl, publishRequest, Map.class);
        assertEquals(201, postResponse.getStatusCode().value());

        getResponse = restTemplate.getForEntity(resolveUrl, Map.class);
        assertEquals(200, getResponse.getStatusCode().value());
        assertNotNull(getResponse.getBody());
        assertEquals("test-service", getResponse.getBody().get("subject"));
        assertEquals("abc", getResponse.getBody().get("publicKeyEncoded"));

        // ----- Test using TaasPublisherClient & TaasTrustRootProvider -----
        List<String> clusterUrls = List.of("http://127.0.0.1:" + port);
        TaasPublisherClient publisherClient = new TaasPublisherClient(clusterUrls, null, Duration.ofSeconds(5));
        TaasTrustRootProvider trustRootProvider = new TaasTrustRootProvider(clusterUrls, null, Duration.ofSeconds(5));

        // 1. Fetch unknown subject, should return Optional.empty()
        Optional<TrustEntry> fetchedNone = trustRootProvider.fetch("another-service");
        assertFalse(fetchedNone.isPresent());

        // 2. Publish using TaasPublisherClient
        TrustEntry secondEntry = makeEntry("another-service", "def", 1, "finger2", "sig2", now);
        publisherClient.publish(secondEntry);

        // 3. Fetch using TaasTrustRootProvider, should succeed and match
        Optional<TrustEntry> fetchedEntry = trustRootProvider.fetch("another-service");
        assertTrue(fetchedEntry.isPresent());
        assertEquals("another-service", fetchedEntry.get().subject());
        assertEquals("def", fetchedEntry.get().publicKeyEncoded());
    }
}

