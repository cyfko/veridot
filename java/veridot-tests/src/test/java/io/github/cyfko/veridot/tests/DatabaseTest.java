package io.github.cyfko.veridot.tests;

import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.EntryType;
import io.github.cyfko.veridot.core.impl.Scope;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.databases.DatabaseBroker;
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

    private DatabaseBroker broker;

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
        broker = new DatabaseBroker(dataSource, "broker_messages");
        GenericSignerVerifier gsv = TestTrustSetup.create().newSignerVerifier(broker);
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void sign_with_null_data_throws(DistributionMode mode) {
        var cfg = BasicConfigurer.builder()
                .groupId("sign-null-" + mode.name())
                .distribution(mode)
                .validity(60)
                .build();
        assertThrows(IllegalArgumentException.class, () -> dataSigner.sign(null, cfg));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void verify_valid_token_returns_string_payload(DistributionMode mode) throws InterruptedException {
        String data = "john.doe@example.com";
        var cfg = BasicConfigurer.builder()
                .groupId("verify-str-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(2000); // allow async DB propagation
        var result = tokenVerifier.verify(token, s -> s);
        assertEquals(data, result.data());
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void verify_valid_token_returns_pojo_payload(DistributionMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        var cfg = BasicConfigurer.builder()
                .groupId("verify-pojo-" + mode.name())
                .distribution(mode)
                .validity(600)
                .build();
        String token = dataSigner.sign(data, cfg);
        Thread.sleep(2000);
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void verify_token_regeneration_returns_payload(DistributionMode mode) throws InterruptedException {
        UserData data = new UserData("john.doe@example.com");
        String groupId = "regen-" + mode.name();
        dataSigner.sign(data, BasicConfigurer.builder().groupId(groupId).sequenceId("s1").distribution(mode).validity(600).build());
        String token = dataSigner.sign(data, BasicConfigurer.builder().groupId(groupId).sequenceId("s1").distribution(mode).validity(600).build());
        Thread.sleep(2000);
        var result = tokenVerifier.verify(token, BasicConfigurer.deserializer(UserData.class));
        assertNotNull(result.data());
        assertEquals(data.getEmail(), result.data().getEmail());
    }

    // ── TokenTracker ──────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void hasActiveToken_after_sign_returns_true(DistributionMode mode) throws InterruptedException {
        String groupId = "tracker-" + mode.name();
        dataSigner.sign("data", BasicConfigurer.builder().groupId(groupId).distribution(mode).validity(600).build());
        Thread.sleep(2000);
        assertTrue(tokenTracker.hasActiveToken(groupId));
    }

    @ParameterizedTest
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
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
    @EnumSource(value = DistributionMode.class, names = {"DIRECT", "NATIVE"})
    void revokeGroup_marks_liveness_revoked_but_leaves_capability_intact(DistributionMode mode) throws Exception {
        String groupId = "revoke-phys-" + mode.name();
        String seqA = "phys-a-" + mode.name();
        String seqB = "phys-b-" + mode.name();

        String tokenA = dataSigner.sign("d1", BasicConfigurer.builder().groupId(groupId).sequenceId(seqA).distribution(mode).validity(3600).build());
        String tokenB = dataSigner.sign("d2", BasicConfigurer.builder().groupId(groupId).sequenceId(seqB).distribution(mode).validity(3600).build());
        Thread.sleep(2000);

        byte[] capA = computeStorageKey(groupId, EntryType.CAPABILITY, seqA);
        byte[] capB = computeStorageKey(groupId, EntryType.CAPABILITY, seqB);
        byte[] liveA = computeStorageKey(groupId, EntryType.LIVENESS, seqA);
        byte[] liveB = computeStorageKey(groupId, EntryType.LIVENESS, seqB);

        // V5: Root identities are implicitly authorized and no CAPABILITY is published on sign() by default.
        // Therefore, we manually put dummy capability envelopes to verify that the database broker's revokeGroup
        // operation does not delete them physically.
        byte[] dummyCapA = encodeDummyEnvelope(Scope.group(groupId), seqA, EntryType.CAPABILITY);
        byte[] dummyCapB = encodeDummyEnvelope(Scope.group(groupId), seqB, EntryType.CAPABILITY);
        broker.put(capA, dummyCapA).join();
        broker.put(capB, dummyCapB).join();

        assertTrue(existsInDb(capA), "Sequence A CAPABILITY must exist in DB before revokeGroup");
        assertTrue(existsInDb(capB), "Sequence B CAPABILITY must exist in DB before revokeGroup");

        tokenRevoker.revoke(groupId, null);
        Thread.sleep(2000);

        // V5: Capability entries must NOT be physically deleted from DB after revokeGroup
        assertTrue(existsInDb(capA), "Sequence A CAPABILITY must NOT be deleted from DB after revokeGroup");
        assertTrue(existsInDb(capB), "Sequence B CAPABILITY must NOT be deleted from DB after revokeGroup");

        // Verify that liveness entries exist
        assertTrue(existsInDb(liveA), "Liveness A entry must exist");
        assertTrue(existsInDb(liveB), "Liveness B entry must exist");

        // Verify that token verification fails for both (due to liveness = revoked)
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(tokenA, s -> s));
        assertThrows(BrokerExtractionException.class, () -> tokenVerifier.verify(tokenB, s -> s));
    }

    private byte[] encodeDummyEnvelope(Scope scope, String key, EntryType entryType) {
        byte[] scopeBytes = scope.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] issuerBytes = "dummy-issuer".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payloadBytes = new byte[0];
        byte[] sigBytes = new byte[0];

        int totalLen = 2 + 1 + 1 + 2 + 2 + scopeBytes.length + 2 + keyBytes.length 
                     + 8 + 8 + 2 + issuerBytes.length + 4 + payloadBytes.length + 1 + 2 + sigBytes.length;
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(totalLen);

        buffer.put(new byte[]{0x56, 0x44});                         // Magic
        buffer.put((byte) 0x05);                                   // ProtoVersion
        buffer.put(entryType.code);                                // EntryType
        buffer.putShort((short) 1);                                // Flags (COMPACT_SIG)
        buffer.putShort((short) scopeBytes.length);                // ScopeLen
        buffer.put(scopeBytes);
        buffer.putShort((short) keyBytes.length);                  // KeyLen
        buffer.put(keyBytes);
        buffer.putLong(1L);                                        // Version
        buffer.putLong(System.currentTimeMillis());                // Timestamp
        buffer.putShort((short) issuerBytes.length);               // IssuerLen
        buffer.put(issuerBytes);
        buffer.putInt(payloadBytes.length);                        // PayloadLen
        buffer.put(payloadBytes);
        buffer.put((byte) 0x01);                                   // SigAlg (ED25519)
        buffer.putShort((short) sigBytes.length);                  // SigLen
        buffer.put(sigBytes);

        return buffer.array();
    }

    private byte[] computeStorageKey(String groupId, EntryType type, String key) {
        byte[] scopeBytes = ("group:" + groupId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] result = new byte[scopeBytes.length + 1 + 1 + 1 + keyBytes.length];
        System.arraycopy(scopeBytes, 0, result, 0, scopeBytes.length);
        result[scopeBytes.length] = 0x00;
        result[scopeBytes.length + 1] = type.code;
        result[scopeBytes.length + 2] = 0x00;
        System.arraycopy(keyBytes, 0, result, scopeBytes.length + 3, keyBytes.length);

        return result;
    }

    private boolean existsInDb(byte[] storageKey) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM broker_messages WHERE storage_key = ?")) {
            stmt.setBytes(1, storageKey);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        }
    }
}
