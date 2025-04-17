package io.github.cyfko.veridot.tests;


import io.github.cyfko.veridot.core.Revoker;
import io.github.cyfko.veridot.core.Signer;
import io.github.cyfko.veridot.core.TokenMode;
import io.github.cyfko.veridot.core.Verifier;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.kafka.KafkaBrokerAdapter;
import io.github.cyfko.veridot.kafka.VerifierConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSignerVerifierTest {

    private static KafkaContainer kafkaContainer;
    private Signer signer;
    private Verifier verifier;
    private Revoker revoker;
    private File tempDir;

    @BeforeAll
    public static void setUpClass() {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))
                .withEmbeddedZookeeper();
        kafkaContainer.start();
    }

    @AfterAll
    public static void tearDownClass() {
        kafkaContainer.stop();
    }

    @BeforeEach
    public void setUp() throws IOException {
        String kafkaBootstrapServers = kafkaContainer.getBootstrapServers();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);

        tempDir = Files.createTempDirectory("rocksdb_db_test_").toFile();
        props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, tempDir.getAbsolutePath());

        GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(KafkaBrokerAdapter.of(props));
        signer = genericSignerVerifier;
        verifier = genericSignerVerifier;
        revoker = genericSignerVerifier;
    }

    @AfterEach
    public void tearDown() {
        if (tempDir.exists()) {
            tempDir.delete();
        }
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_valid_data_should_returns_token(TokenMode mode) {
        UserData data = new UserData("john.doe@example.com");

        String token = signer.sign(data, 60, mode, mode.name().hashCode());

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_invalid_data_should_throws_exception(TokenMode mode) {
        Object invalidData = null; // Simulating invalid data

        Assertions.assertThrows(IllegalArgumentException.class, () -> signer.sign(invalidData, 60, mode, mode.name().hashCode() + 1));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_expired_duration_should_throws_exception(TokenMode mode) {
        UserData data = new UserData("john.doe@example.com");

        Assertions.assertThrows(IllegalArgumentException.class, () -> signer.sign(data, -5, mode, mode.name().hashCode() + 2));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_valid_data_should_returns_token(TokenMode mode) throws DataSerializationException {
        UserData data = new UserData("john.doe@example.com");

        String token = signer.sign(data, 60, mode, mode.name().hashCode() + 3);

        assertNotNull(token, "JWT should not be null");
        assertFalse(token.isEmpty(), "JWT should not be empty");
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_invalid_data_should_throws_exception(TokenMode mode) {
        Object invalidData = null; // Simulating invalid data

        assertThrows(IllegalArgumentException.class, () -> signer.sign(invalidData, 60, mode, mode.name().hashCode() + 4));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void verify_valid_token_should_returns_payload(TokenMode mode) throws InterruptedException {
        String data = "john.doe@example.com";
        String token = signer.sign(data, 600, mode, mode.name().hashCode() + 5); // Generate a valid token
        Thread.sleep(2000); // Wait 2 secs to ensure that the keys has been propagated to database

        String verifiedData = (String) verifier.verify(token);

        assertNotNull(verifiedData);
        assertEquals(data, verifiedData);
    }

    @Test
    public void verify_invalid_token_should_throws_exception() {
        assertThrows(BrokerExtractionException.class, () -> verifier.verify("invalid.token.token"));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void verify_expired_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        String token = signer.sign("john.doe@example.com", 1, mode, mode.name().hashCode() + 6); // Token with short duration
        Thread.sleep(3000); // Wait 3 seconds for the token to expire

        assertThrows(BrokerExtractionException.class, () -> verifier.verify(token));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void verify_revoked_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        String token = signer.sign("john.doe@example.com", 3600, mode, mode.name().hashCode() + 7); // Token with long duration (1 hour)
        Thread.sleep(2000); // Wait 2 seconds before issuing the revocation command

        revoker.revoke(token);
        Thread.sleep(2000); // Wait 2 seconds to ensure revocation took place

        assertThrows(BrokerExtractionException.class, () -> verifier.verify(token));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void verify_revoked_token_by_tracked_id_should_throws_exception(TokenMode mode) throws InterruptedException {
        final long trackingId = mode.name().hashCode() + 8;

        String token = signer.sign("john.doe@example.com", 3600, mode, trackingId); // Token with long duration (1 hour)
        Thread.sleep(2000); // Wait 2 seconds before issuing the revocation command

        revoker.revoke(trackingId);
        Thread.sleep(2000); // Wait 2 seconds to ensure revocation took place

        assertThrows(BrokerExtractionException.class, () -> verifier.verify(token));
    }
}

