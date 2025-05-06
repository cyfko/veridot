package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.DataSigner;
import io.github.cyfko.veridot.core.TokenMode;
import io.github.cyfko.veridot.core.TokenVerifier;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;
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

class KafkaUnitTest {

    private static KafkaContainer kafkaContainer;
    private DataSigner dataSigner;
    private TokenVerifier tokenVerifier;
    private File tempDir;

    @BeforeAll
    static void setUpClass() {
        kafkaContainer = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:latest"))
                .withEmbeddedZookeeper();
        kafkaContainer.start();
    }

    @AfterAll
    static void tearDownClass() {
        kafkaContainer.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        String kafkaBootstrapServers = kafkaContainer.getBootstrapServers();

        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);

        tempDir = Files.createTempDirectory("rocksdb_db_test_").toFile();
        props.setProperty(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, tempDir.getAbsolutePath());

        GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(KafkaMetadataBrokerAdapter.of(props));
        dataSigner = genericSignerVerifier;
        tokenVerifier = genericSignerVerifier;
    }

    @AfterEach
    void tearDown() {
        if (tempDir.exists()) {
            tempDir.delete();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void sign_method_with_valid_data_should_returns_jwt(TokenMode mode) throws DataSerializationException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(60)
                .build();

        String jwt = dataSigner.sign(data, configurer);

        assertNotNull(jwt);
        assertFalse(jwt.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void sign_method_with_invalid_data_should_throws_exception(TokenMode mode) {

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(60)
                .build();

        Assertions.assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, configurer));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void sign_method_with_expired_duration_should_throws_exception(TokenMode mode) {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(-5)
                .build();

        Assertions.assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(data, configurer));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void sign_valid_data_should_returns_jwt(TokenMode mode) throws DataSerializationException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(60)
                .build();

        String jwt = dataSigner.sign(data, configurer);

        assertNotNull(jwt, "JWT should not be null");
        assertFalse(jwt.isEmpty(), "JWT should not be empty");
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void sign_invalid_data_should_throws_exception(TokenMode mode) {

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(60)
                .build();

        assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, configurer));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void verify_valid_token_should_returns_payload(TokenMode mode) throws InterruptedException {
        String data = "john.doe@example.com";

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(60)
                .build();

        String jwt = dataSigner.sign(data, configurer); // Generate a valid token
        Thread.sleep(5000); // Wait 5 seconds to ensure that the keys has been propagated to kafka

        String verifiedData = tokenVerifier.verify(jwt, String::toString);

        assertNotNull(verifiedData);
        assertEquals(data, verifiedData);
    }

    @Test
    void verify_invalid_token_should_throws_exception() {
        String invalidToken = "invalid.jwt.token"; // Simulating an invalid token

        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(invalidToken,String::toString));
    }

    @ParameterizedTest
    @EnumSource(value = TokenMode.class)
    void verify_expired_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(0)
                .validity(2)
                .build();

        String token = dataSigner.sign(data, configurer); // Token with short duration
        Thread.sleep(5000); // Wait 5 seconds for the token to expire

        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token,String::toString));
    }
}

