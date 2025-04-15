package io.github.cyfko.dverify;

import io.github.cyfko.dverify.exceptions.DataExtractionException;
import io.github.cyfko.dverify.exceptions.JsonEncodingException;
import io.github.cyfko.dverify.impl.GenericSignerVerifier;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class KafkaUnitTest {

    private static KafkaContainer kafkaContainer;
    private Signer signer;
    private Verifier verifier;
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
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);

        tempDir = Files.createTempDirectory("rocksdb_db_test_").toFile();
        props.setProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, tempDir.getAbsolutePath());

        GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(KafkaBrokerAdapter.of(props));
        signer = genericSignerVerifier;
        verifier = genericSignerVerifier;
    }

    @AfterEach
    public void tearDown() {
        if (tempDir.exists()) {
            tempDir.delete();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_valid_data_should_returns_jwt(TokenMode mode) throws JsonEncodingException {
        UserData data = new UserData("john.doe@example.com");

        String jwt = signer.sign(data, 60, mode, 0);

        assertNotNull(jwt);
        assertFalse(jwt.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_invalid_data_should_throws_exception(TokenMode mode) {
        Object invalidData = null; // Simulating invalid data

        Assertions.assertThrows(IllegalArgumentException.class, () -> signer.sign(invalidData, 60, mode, 0));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_expired_duration_should_throws_exception(TokenMode mode) {
        UserData data = new UserData("john.doe@example.com");

        Assertions.assertThrows(IllegalArgumentException.class, () -> signer.sign(data, -5, mode, 0));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void sign_valid_data_should_returns_jwt(TokenMode mode) throws JsonEncodingException {
        UserData data = new UserData("john.doe@example.com");

        String jwt = signer.sign(data, 60, mode, 0);

        assertNotNull(jwt, "JWT should not be null");
        assertFalse(jwt.isEmpty(), "JWT should not be empty");
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void sign_invalid_data_should_throws_exception(TokenMode mode) {
        Object invalidData = null; // Simulating invalid data

        assertThrows(IllegalArgumentException.class, () -> signer.sign(invalidData, 60, mode, 0));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void verify_valid_token_should_returns_payload(TokenMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        String jwt = signer.sign(data, 60, mode, 0); // Generate a valid token
        Thread.sleep(5000); // Wait 5 seconds to ensure that the keys has been propagated to kafka

        UserData verifiedData = verifier.verify(jwt, UserData.class);

        assertNotNull(verifiedData);
        assertEquals(data.getEmail(), verifiedData.getEmail());
    }

    @Test
    public void verify_invalid_token_should_throws_exception() {
        String invalidToken = "invalid.jwt.token"; // Simulating an invalid token

        assertThrows(DataExtractionException.class, () -> verifier.verify(invalidToken, UserData.class));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    public void verify_expired_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        String token = signer.sign(data, 60, mode, 0); // Token with short duration
        Thread.sleep(10); // Wait for the token to expire

        assertThrows(DataExtractionException.class, () -> verifier.verify(token, UserData.class));
    }
}

