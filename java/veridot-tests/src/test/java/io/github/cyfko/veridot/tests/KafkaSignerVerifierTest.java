package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.kafka.KafkaBroker;
import io.github.cyfko.veridot.kafka.VerifierConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class KafkaSignerVerifierTest {

    private static ConfluentKafkaContainer kafkaContainer;
    private KafkaBroker broker;
    private DataSigner dataSigner;
    private TokenVerifier tokenVerifier;
    private TokenRevoker tokenRevoker;
    private TokenTracker tokenTracker;
    private File tempDir;

    @BeforeAll
    static void setUpClass() {
        kafkaContainer = new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        kafkaContainer.start();
    }

    @AfterAll
    static void tearDownClass() {
        kafkaContainer.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        tempDir = Files.createTempDirectory("veridot_kafka_test_").toFile();
        props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, tempDir.getAbsolutePath());

        broker = new KafkaBroker(props);
        GenericSignerVerifier gsv = TestTrustSetup.create().newSignerVerifier(broker);
        dataSigner    = gsv;
        tokenVerifier = gsv;
        tokenRevoker  = gsv;
        tokenTracker  = gsv;
    }

    @AfterEach
    void tearDown() {
        if (broker != null) {
            broker.close();
        }
        if (tempDir != null && tempDir.exists()) {
            tempDir.delete();
        }
    }

    // ── Signing ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void sign_with_valid_data_returns_token(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-sign-" + mode.name())
                .distribution(mode)
                .validity(60)
                .build();
        String token = dataSigner.sign(new UserData("john.doe@example.com"), cfg);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void sign_with_null_data_throws(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-null-" + mode.name())
                .distribution(mode)
                .validity(60)
                .build();
        assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, cfg));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void sign_with_negative_duration_throws(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-neg-" + mode.name())
                .distribution(mode)
                .validity(-5)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> dataSigner.sign(new UserData("john.doe@example.com"), cfg));
    }

    // ── Verification ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void verify_valid_token_returns_string_payload(DistributionMode mode) throws InterruptedException {
        String data = "john.doe@example.com";
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-verify-str-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(5000); // allow Kafka→RocksDB propagation
        var result = tokenVerifier.verify(token, s -> s);
        assertEquals(data, result.data());
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void verify_valid_token_returns_pojo_payload(DistributionMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-verify-pojo-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(5000);
        var result = tokenVerifier.verify(token, BasicConfigurer.deserializer(UserData.class));
        assertNotNull(result.data());
        assertEquals(data.getEmail(), result.data().getEmail());
    }

    @Test
    void verify_invalid_token_throws() {
        assertThrows(BrokerExtractionException.class,
                () -> tokenVerifier.verify("invalid.token.here", s -> s));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void verify_expired_token_throws(DistributionMode mode) throws InterruptedException {
        var cfg = BasicConfigurer.builder()
                .groupId("ksv-exp-" + mode.name())
                .distribution(mode)
                .validity(2)
                .build();
        String token = dataSigner.sign("john.doe@example.com", cfg);
        Thread.sleep(5000);
        assertThrows(Exception.class, () -> tokenVerifier.verify(token, s -> s));
    }

    // ── Revocation ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void verify_revoked_token_throws(DistributionMode mode) throws InterruptedException {
        String groupId = "ksv-revoke-" + mode.name();
        var cfg = BasicConfigurer.builder()
                .groupId(groupId)
                .sequenceId("seq1")
                .distribution(mode)
                .validity(3600)
                .build();
        String token = dataSigner.sign("john.doe@example.com", cfg);
        Thread.sleep(5000);
        tokenRevoker.revoke(groupId, "seq1");
        Thread.sleep(5000);
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token, s -> s));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void revokeGroup_invalidates_all_sessions(DistributionMode mode) throws InterruptedException {
        String groupId = "ksv-rg-" + mode.name();
        String t1 = dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        String t2 = dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        Thread.sleep(5000);
        tokenRevoker.revoke(groupId, null);
        Thread.sleep(5000);
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(t1, s -> s));
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(t2, s -> s));
    }

    // ── TokenTracker ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void hasActiveToken_after_sign_returns_true(DistributionMode mode) throws InterruptedException {
        String groupId = "ksv-tracker-" + mode.name();
        dataSigner.sign("data", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(600).build());
        Thread.sleep(5000);
        assertTrue(tokenTracker.hasActiveToken(groupId));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "INDIRECT"})
    void hasActiveToken_after_revokeGroup_returns_false(DistributionMode mode) throws InterruptedException {
        String groupId = "ksv-tracker-rg-" + mode.name();
        dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        Thread.sleep(5000);
        tokenRevoker.revoke(groupId, null);
        Thread.sleep(5000);
        assertFalse(tokenTracker.hasActiveToken(groupId));
    }
}
