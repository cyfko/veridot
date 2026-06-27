package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the real-world scenario where two {@link GenericSignerVerifier} instances
 * coexist on the same broker — one with session limits (access tokens) and one without
 * (refresh tokens) — simulating a typical OAuth2-style Spring configuration.
 *
 * <p>This test proves that the session-limited signer does NOT interfere with the
 * unlimited signer when both share the same {@link io.github.cyfko.veridot.core.MetadataBroker},
 * provided they are correctly instantiated as separate Java objects.</p>
 *
 * <p><strong>v3.0 note</strong>: both signers share the same {@link TestTrustSetup} (same
 * long-term key pair), which is realistic for a deployment where all service instances
 * share the same identity. A multi-signer-id scenario would require a {@code TrustAnchor}
 * that resolves multiple signerIds.</p>
 */
class MultiInstanceSessionTest {

    private InMemoryMetadataBroker broker;

    /** Simulates: {@code @Bean("limited-sessions-management")} */
    private GenericSignerVerifier limitedSigner;

    /** Simulates: {@code @Primary @Bean} */
    private GenericSignerVerifier unlimitedSigner;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        TestTrustSetup trust = TestTrustSetup.create();

        // Limited signer: maxSessions=1, REJECT (for access tokens)
        limitedSigner = trust.newSignerVerifier(broker, 1, GenericSignerVerifier.EvictionPolicy.REJECT);

        // Unlimited signer: no session limit (for refresh tokens)
        unlimitedSigner = trust.newSignerVerifier(broker);
    }

    // ── Core scenario: access + refresh token coexistence ────────────────────

    @Test
    void unlimited_signer_allows_refresh_token_even_when_access_token_limit_reached() {
        String username = "user@email.com";
        String refreshGroup = "refresh." + username;
        String sessionId = "session-1";

        // 1. Sign access token with LIMITED signer (maxSessions=1)
        String accessToken = limitedSigner.sign(username,
                BasicConfigurer.builder()
                        .groupId(username)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(120)  // 2 minutes
                        .build());
        assertNotNull(accessToken);

        // 2. Sign refresh token with UNLIMITED signer (same broker, different group)
        String refreshToken = unlimitedSigner.sign(username,
                BasicConfigurer.builder()
                        .groupId(refreshGroup)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(604800) // 7 days
                        .build());
        assertNotNull(refreshToken);

        // 3. Both tokens must be verifiable (cross-instance verification works)
        assertDoesNotThrow(() -> {
            VerifiedData<String> accessResult = unlimitedSigner.verify(accessToken, s -> s);
            assertEquals(username, accessResult.data());
            assertEquals(username, accessResult.groupId());
        }, "Unlimited signer must verify access token signed by limited signer (same broker)");

        assertDoesNotThrow(() -> {
            VerifiedData<String> refreshResult = unlimitedSigner.verify(refreshToken, s -> s);
            assertEquals(username, refreshResult.data());
            assertEquals(refreshGroup, refreshResult.groupId());
        }, "Unlimited signer must verify its own refresh token");
    }

    @Test
    void limited_signer_rejects_second_access_token_but_unlimited_allows_second_refresh_token() {
        String username = "user@example.com";
        String refreshGroup = "refresh." + username;

        // 1. Sign 1st access token (OK)
        limitedSigner.sign("data",
                BasicConfigurer.builder()
                        .groupId(username)
                        .sequenceId("device-1")
                        .distribution(DistributionMode.DIRECT)
                        .validity(120)
                        .build());

        // 2. Sign 2nd access token → REJECT (maxSessions=1 enforced)
        assertThrows(SessionCapacityExceededException.class,
                () -> limitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(username)
                                .sequenceId("device-2")
                                .distribution(DistributionMode.DIRECT)
                                .validity(120)
                                .build()),
                "Limited signer must REJECT when maxSessions=1 exceeded");

        // 3. Sign 1st refresh token (OK — unlimited signer, different group)
        assertDoesNotThrow(
                () -> unlimitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(refreshGroup)
                                .sequenceId("device-1")
                                .distribution(DistributionMode.DIRECT)
                                .validity(604800)
                                .build()),
                "Unlimited signer must allow 1st refresh token");

        // 4. Sign 2nd refresh token (OK — no limit on unlimited signer)
        assertDoesNotThrow(
                () -> unlimitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(refreshGroup)
                                .sequenceId("device-2")
                                .distribution(DistributionMode.DIRECT)
                                .validity(604800)
                                .build()),
                "Unlimited signer must allow 2nd refresh token (no session limit)");
    }

    @Test
    void unlimited_signer_allows_new_refresh_after_access_expired() throws InterruptedException {
        String username = "user@example.com";
        String refreshGroup = "refresh." + username;
        String sessionId = "sess-A";

        // 1. Sign access token with 1-second TTL
        limitedSigner.sign("data",
                BasicConfigurer.builder()
                        .groupId(username)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(1)
                        .build());

        // 2. Sign refresh token with unlimited signer
        String refreshToken1 = unlimitedSigner.sign("data",
                BasicConfigurer.builder()
                        .groupId(refreshGroup)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(604800)
                        .build());
        assertNotNull(refreshToken1);

        // 3. Wait for access token to expire
        Thread.sleep(2500);

        // 4. Generate a NEW access token (slot freed due to expired token GC)
        assertDoesNotThrow(
                () -> limitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(username)
                                .sequenceId("sess-B")
                                .distribution(DistributionMode.DIRECT)
                                .validity(120)
                                .build()),
                "Limited signer must allow new access token after previous one expired");

        // 5. Generate a SECOND refresh token — must NOT be blocked
        assertDoesNotThrow(
                () -> unlimitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(refreshGroup)
                                .sequenceId("sess-B")
                                .distribution(DistributionMode.DIRECT)
                                .validity(604800)
                                .build()),
                "Unlimited signer must allow new refresh token regardless of access token state");

        // 6. Verify both refresh tokens are still active
        assertDoesNotThrow(() -> unlimitedSigner.verify(refreshToken1, s -> s),
                "1st refresh token must still be verifiable (7-day TTL)");
    }

    @Test
    void same_broker_different_signers_do_not_share_session_limits() {
        String group = "shared-group";

        // Sign 5 tokens with unlimited signer on the same group
        for (int i = 0; i < 5; i++) {
            assertDoesNotThrow(
                    () -> unlimitedSigner.sign("data",
                            BasicConfigurer.builder()
                                    .groupId(group)
                                    .distribution(DistributionMode.DIRECT)
                                    .validity(600)
                                    .build()),
                    "Unlimited signer must never reject, regardless of active session count");
        }

        // Now sign with SAME group on limited signer — it should see the 5 existing sessions
        // and REJECT (maxSessions=1) because session limits are LOCAL to the signer instance
        // BUT the broker already has 5 active keys for this group
        assertThrows(SessionCapacityExceededException.class,
                () -> limitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(group)
                                .sequenceId("new")
                                .distribution(DistributionMode.DIRECT)
                                .validity(600)
                                .build()),
                "Limited signer must REJECT because broker already has 5 active sessions for this group");
    }
}
