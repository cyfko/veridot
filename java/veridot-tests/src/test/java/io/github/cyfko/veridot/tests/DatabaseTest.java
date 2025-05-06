package io.github.cyfko.veridot.tests;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.databases.DatabaseMetadataBroker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DatabaseTest {
    private static ObjectMapper objectMapper = new ObjectMapper();

    private final JdbcDatabaseContainer<?> sqlContainer;
    private DataSigner dataSigner;
    private TokenVerifier tokenVerifier;
    private TokenRevoker tokenRevoker;
    private DataSource dataSource;

    public DatabaseTest(JdbcDatabaseContainer<?> sqlContainer){
        this.sqlContainer = sqlContainer;
        sqlContainer.start();
    }

    private DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(sqlContainer.getJdbcUrl());
        config.setUsername(sqlContainer.getUsername());
        config.setPassword(sqlContainer.getPassword());
        config.setDriverClassName(sqlContainer.getDriverClassName());
        config.setMaximumPoolSize(5);  // tune per your load
        return new HikariDataSource(config);
    }

    @BeforeAll
    void setUpClass() {
        dataSource = createDataSource();

        MetadataBroker metadataBroker = new DatabaseMetadataBroker(dataSource, "broker_messages");
        GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(metadataBroker);
        dataSigner = genericSignerVerifier;
        tokenVerifier = genericSignerVerifier;
        tokenRevoker = genericSignerVerifier;
    }

    @AfterAll
    void tearDownClass() {
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
        sqlContainer.stop();
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void sign_method_with_valid_data_should_returns_token(TokenMode mode) throws JsonProcessingException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode())
                .validity(60)
                .build();

        String token = dataSigner.sign(data, configurer);

        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void sign_method_with_invalid_data_should_throws_exception(TokenMode mode) {

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 1)
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
                .trackedBy(mode.name().hashCode() + 2)
                .validity(-5)
                .build();

        Assertions.assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(data, configurer));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void sign_valid_data_should_returns_token(TokenMode mode) throws DataSerializationException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 3)
                .validity(60)
                .build();

        String token = dataSigner.sign(data, configurer);

        assertNotNull(token, "JWT should not be null");
        assertFalse(token.isEmpty(), "JWT should not be empty");
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void sign_invalid_data_should_throws_exception(TokenMode mode) {

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 4)
                .validity(60)
                .build();

        assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, configurer));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void verify_valid_token_should_returns_payload(TokenMode mode) throws InterruptedException {
        String data = "john.doe@example.com";

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 5)
                .validity(600)
                .build();

        String token = dataSigner.sign(data, configurer); // Generate a valid token

        Thread.sleep(2000); // Wait 2 secs to ensure that the keys has been propagated to database

        String verifiedData = tokenVerifier.verify(token, String::toString);

        assertNotNull(verifiedData);
        assertEquals(data, verifiedData);
    }

    @Test
    void verify_invalid_token_should_throws_exception() {
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify("invalid.token.token", String::toString));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void verify_expired_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 6)
                .validity(1)
                .build();

        String token = dataSigner.sign("john.doe@example.com", configurer); // Token with short duration
        Thread.sleep(3000); // Wait 3 seconds for the token to expire

        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token, String::toString));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void verify_revoked_token_should_throws_exception(TokenMode mode) throws InterruptedException {

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 7)
                .validity(3600)
                .build();

        String token = dataSigner.sign("john.doe@example.com", configurer); // Token with long duration (1 hour)
        Thread.sleep(2000); // Wait 2 seconds before issuing the revocation command

        tokenRevoker.revoke(token);
        Thread.sleep(2000); // Wait 2 seconds to ensure revocation took place

        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token, String::toString));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void verify_revoked_token_by_tracked_id_should_throws_exception(TokenMode mode) throws InterruptedException {
        final long trackingId = mode.name().hashCode() + 8;

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(trackingId)
                .validity(3600)
                .build();

        String token = dataSigner.sign("john.doe@example.com", configurer); // Token with long duration (1 hour)
        Thread.sleep(2000); // Wait 2 seconds before issuing the revocation command

        tokenRevoker.revoke(trackingId);
        Thread.sleep(2000); // Wait 2 seconds to ensure revocation took place

        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token, String::toString));
    }


    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    void verify_valid_token_should_returns_payload_pojo(TokenMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");

        BasicConfigurer configurer = BasicConfigurer.builder()
                .useMode(mode)
                .trackedBy(mode.name().hashCode() + 9)
                .validity(600)
                .build();

        String token = dataSigner.sign(data, configurer); // Generate a valid token

        Thread.sleep(2000); // Wait 2 secs to ensure that the keys has been propagated to database

        UserData verifiedData = tokenVerifier.verify(token, BasicConfigurer.deserializer(UserData.class));

        assertNotNull(verifiedData);
        assertEquals(data.getEmail(), verifiedData.getEmail());
    }
}
