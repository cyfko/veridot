package io.github.cyfko.veridot.trustroots.tad.server;

import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.tad.client.TadPublisherClient;
import io.github.cyfko.veridot.trustroots.tad.client.TadTrustRootProvider;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = TadServerApplication.class)
class TadServerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("veridot.tad-server.storage.directory", () -> tempDir.toAbsolutePath().toString());
        registry.add("veridot.tad-server.node-id", () -> "127.0.0.1:19443");
        registry.add("veridot.tad-server.initial-peers", () -> "127.0.0.1:19443");
    }

    @Test
    void testPublishAndResolve() throws Exception {
        // Wait a short time for Raft leader election
        Thread.sleep(3000);

        String subject = "test-service";
        long version = 1;
        Instant now = Instant.now();

        TrustEntry entry = new TrustEntry(
            1,
            subject,
            "abc",
            KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
            version,
            "finger",
            "sig",
            now,
            false,
            Collections.emptyMap()
        );

        String resolveUrl = "http://127.0.0.1:" + port + "/v1/trust-entries/" + subject;
        var getResponse = restTemplate.getForEntity(resolveUrl, Map.class);
        assertEquals(404, getResponse.getStatusCode().value());

        String publishUrl = "http://127.0.0.1:" + port + "/v1/trust-entries";
        var postResponse = restTemplate.postForEntity(publishUrl, entry, Map.class);
        assertEquals(201, postResponse.getStatusCode().value());

        getResponse = restTemplate.getForEntity(resolveUrl, Map.class);
        assertEquals(200, getResponse.getStatusCode().value());
        assertNotNull(getResponse.getBody());
        assertEquals("test-service", getResponse.getBody().get("subject"));
        assertEquals("abc", getResponse.getBody().get("publicKeyEncoded"));

        // ----- Test using TadPublisherClient & TadTrustRootProvider -----
        List<String> clusterUrls = List.of("http://127.0.0.1:" + port);
        TadPublisherClient publisherClient = new TadPublisherClient(clusterUrls, null, Duration.ofSeconds(5));
        TadTrustRootProvider trustRootProvider = new TadTrustRootProvider(clusterUrls, null, Duration.ofSeconds(5));

        // 1. Fetch unknown subject, should return Optional.empty()
        Optional<TrustEntry> fetchedNone = trustRootProvider.fetch("another-service");
        assertFalse(fetchedNone.isPresent());

        // 2. Publish using TadPublisherClient
        TrustEntry secondEntry = new TrustEntry(
            1,
            "another-service",
            "def",
            KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
            1,
            "finger2",
            "sig2",
            now,
            false,
            Collections.emptyMap()
        );
        publisherClient.publish(secondEntry);

        // 3. Fetch using TadTrustRootProvider, should succeed and match
        Optional<TrustEntry> fetchedEntry = trustRootProvider.fetch("another-service");
        assertTrue(fetchedEntry.isPresent());
        assertEquals("another-service", fetchedEntry.get().subject());
        assertEquals("def", fetchedEntry.get().publicKeyEncoded());
    }
}
