package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.databases.DatabaseMetadataBroker;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.JdbcDatabaseContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DatabaseTest {

    private final JdbcDatabaseContainer<?> sqlContainer;
    private DataSigner dataSigner;
    private TokenVerifier tokenVerifier;
    private TokenRevoker tokenRevoker;
    private TokenTracker tokenTracker;
    private DataSource dataSource;

    public DatabaseTest(JdbcDatabaseContainer<?> sqlContainer) {
        this.sqlContainer = sqlContainer;
        sqlContainer.start();
    }

    private DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(sqlContainer.getJdbcUrl());
        config.setUsername(sqlContainer.getUsername());
        config.setPassword(sqlContainer.getPassword());
        config.setDriverClassName(sqlContainer.getDriverClassName());
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }

    @BeforeAll
    void setUpClass() {
        dataSource = createDataSource();
        MetadataBroker metadataBroker = new DatabaseMetadataBroker(dataSource, "broker_messages");
        GenericSignerVerifier gsv = new GenericSignerVerifier(metadataBroker, "test-salt");
        dataSigner    = gsv;
        tokenVerifier = gsv;
        tokenRevoker  = gsv;
        tokenTracker  = gsv;
    }

    @AfterAll
    void tearDownClass() {
        if (dataSource instanceof HikariDataSource hds) {
            hds.close();
        }
        sqlContainer.stop();
    }

    // ── Signing ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void sign_with_valid_data_returns_token(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("sign-valid-" + mode.name())
                .distribution(mode)
                .validity(60)
                .build();
        String token = dataSigner.sign(new UserData("john.doe@example.com"), cfg);
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void sign_with_null_data_throws(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("sign-null-" + mode.name())
                .distribution(mode)
                .validity(60)
                .build();
        assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, cfg));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void sign_with_negative_duration_throws(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("sign-neg-" + mode.name())
                .distribution(mode)
                .validity(-5)
                .build();
        assertThrows(IllegalArgumentException.class,
                () -> dataSigner.sign(new UserData("john.doe@example.com"), cfg));
    }

    // ── Verification ─────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void verify_valid_token_returns_string_payload(DistributionMode mode) throws InterruptedException {
        String data = "john.doe@example.com";
        var cfg = BasicConfigurer.builder()
                .groupId("verify-str-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(2000); // allow async DB propagation
        String result = tokenVerifier.verify(token, s -> s);
        assertEquals(data, result);
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void verify_valid_token_returns_pojo_payload(DistributionMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        var cfg = BasicConfigurer.builder()
                .groupId("verify-pojo-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(2000);
        UserData result = tokenVerifier.verify(token, BasicConfigurer.deserializer(UserData.class));
        assertNotNull(result);
        assertEquals(data.getEmail(), result.getEmail());
    }

    @Test
    void verify_invalid_token_throws() {
        assertThrows(BrokerExtractionException.class,
                () -> tokenVerifier.verify("invalid.token.here", s -> s));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void verify_expired_token_throws(DistributionMode mode) throws InterruptedException {
        var cfg = BasicConfigurer.builder()
                .groupId("verify-exp-" + mode.name())
                .distribution(mode)
                .validity(1)
                .build();
        String token = dataSigner.sign("john.doe@example.com", cfg);
        Thread.sleep(3000);
        assertThrows(Exception.class, () -> tokenVerifier.verify(token, s -> s));
    }

    // ── Revocation ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void verify_revoked_token_throws(DistributionMode mode) throws InterruptedException {
        String groupId = "revoke-tok-" + mode.name();
        var cfg = BasicConfigurer.builder()
                .groupId(groupId)
                .sequenceId("seq1")
                .distribution(mode)
                .validity(3600)
                .build();
        String token = dataSigner.sign("john.doe@example.com", cfg);
        Thread.sleep(2000);
        tokenRevoker.revoke(groupId, "seq1");
        Thread.sleep(2000);
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(token, s -> s));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void revokeGroup_invalidates_all_sessions(DistributionMode mode) throws InterruptedException {
        String groupId = "revoke-grp-" + mode.name();
        String t1 = dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        String t2 = dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        Thread.sleep(2000);
        tokenRevoker.revoke(groupId, null);
        Thread.sleep(2000);
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(t1, s -> s));
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(t2, s -> s));
    }

    // ── Token regeneration ────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void verify_token_regeneration_returns_payload(DistributionMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        String groupId = "regen-" + mode.name();
        // Sign twice with the same sequenceId — second sign overwrites the first
        dataSigner.sign(data, BasicConfigurer.builder().groupId(groupId).sequenceId("s1").distribution(mode).validity(600).build());
        String token = dataSigner.sign(data, BasicConfigurer.builder().groupId(groupId).sequenceId("s1").distribution(mode).validity(600).build());
        Thread.sleep(2000);
        UserData result = tokenVerifier.verify(token, BasicConfigurer.deserializer(UserData.class));
        assertNotNull(result);
        assertEquals(data.getEmail(), result.getEmail());
    }

    // ── TokenTracker ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void hasActiveToken_after_sign_returns_true(DistributionMode mode) throws InterruptedException {
        String groupId = "tracker-" + mode.name();
        dataSigner.sign("data", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(600).build());
        Thread.sleep(2000);
        assertTrue(tokenTracker.hasActiveToken(groupId));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void hasActiveToken_after_revokeGroup_returns_false(DistributionMode mode) throws InterruptedException {
        String groupId = "tracker-rg-" + mode.name();
        dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(3600).build());
        Thread.sleep(2000);
        tokenRevoker.revoke(groupId, null);
        Thread.sleep(2000);
        assertFalse(tokenTracker.hasActiveToken(groupId));
    }
    @ParameterizedTest
    @EnumSource(value = DistributionMode.class)
    void revokeGroup_physically_deletes_entries_from_database(DistributionMode mode) throws Exception {
        String groupId = "revoke-phys-" + mode.name();
        String seqA = "phys-a-" + mode.name();
        String seqB = "phys-b-" + mode.name();

        dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).sequenceId(seqA).distribution(mode).validity(3600).build());
        dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).sequenceId(seqB).distribution(mode).validity(3600).build());
        Thread.sleep(2000);

        // Pre-condition: verify entries exist in the raw SQL table
        String keyA = "2:" + groupId + ":" + seqA;
        String keyB = "2:" + groupId + ":" + seqB;
        assertTrue(existsInDb(keyA), "Sequence A must exist in DB before revokeGroup");
        assertTrue(existsInDb(keyB), "Sequence B must exist in DB before revokeGroup");

        tokenRevoker.revoke(groupId, null);
        Thread.sleep(2000);

        // Post-condition: entries are physically absent from the SQL table
        assertFalse(existsInDb(keyA), "Sequence A must be physically deleted from DB after revokeGroup");
        assertFalse(existsInDb(keyB), "Sequence B must be physically deleted from DB after revokeGroup");

        // The __REVOKE__ entry persists for interoperability
        String revokeKey = "2:" + groupId + ":__REVOKE__";
        assertTrue(existsInDb(revokeKey), "__REVOKE__ entry must persist in DB for interoperability");
    }

    /**
     * Queries the raw SQL table to check if a message_key exists.
     */
    private boolean existsInDb(String messageKey) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM broker_messages WHERE message_key = ?")) {
            stmt.setString(1, messageKey);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
