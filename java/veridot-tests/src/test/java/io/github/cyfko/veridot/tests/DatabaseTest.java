package io.github.cyfko.veridot.tests;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.Signer;
import io.github.cyfko.veridot.core.TokenMode;
import io.github.cyfko.veridot.core.Verifier;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.databases.DatabaseBroker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

import javax.sql.DataSource;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DatabaseTest {

    private final JdbcDatabaseContainer<?> sqlContainer;
    private Signer signer;
    private Verifier verifier;

    public DatabaseTest(JdbcDatabaseContainer<?> sqlContainer){
        this.sqlContainer = sqlContainer;
    }

    private DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(sqlContainer.getJdbcUrl());
        config.setUsername(sqlContainer.getUsername());
        config.setPassword(sqlContainer.getPassword());
        config.setDriverClassName(sqlContainer.getDriverClassName());
        return new HikariDataSource(config);
    }

    @BeforeAll
    public void setUpClass() { sqlContainer.start(); }

    @AfterAll
    public void tearDownClass() {
        sqlContainer.stop();
    }

    @BeforeEach
    public void setUp() throws IOException {
        Broker broker = new DatabaseBroker(createDataSource(), "broker_messages");
        GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(broker);
        signer = genericSignerVerifier;
        verifier = genericSignerVerifier;
    }

    @AfterEach
    public void tearDown() {

    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void sign_method_with_valid_data_should_returns_token(TokenMode mode) throws DataSerializationException {
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
        Thread.sleep(5000); // Wait 5 secs to ensure that the keys has been propagated to database

        String verifiedData = (String) verifier.verify(token);

        assertNotNull(verifiedData);
        assertEquals(data, verifiedData);
    }

    @Test
    public void verify_invalid_token_should_throws_exception() {
        String invalidToken = "invalid.token.token"; // Simulating an invalid token

        assertThrows(BrokerExtractionException.class, () -> verifier.verify(invalidToken));
    }

    @ParameterizedTest()
    @EnumSource(value = TokenMode.class)
    public void verify_expired_token_should_throws_exception(TokenMode mode) throws InterruptedException {
        String data = "john.doe@example.com";
        String token = signer.sign(data, 1, mode, mode.name().hashCode() + 6); // Token with short duration
        Thread.sleep(3000); // Wait 3 seconds for the token to expire

        assertThrows(BrokerExtractionException.class, () -> verifier.verify(token));
    }
}
