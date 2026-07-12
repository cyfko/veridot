package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryBroker;
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.exceptions.SessionCapacityExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MultiInstanceSessionTest {

    private InMemoryBroker broker;
    private GenericSignerVerifier limitedSigner;
    private GenericSignerVerifier unlimitedSigner;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        TestTrustSetup trust = TestTrustSetup.create();

        limitedSigner = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT);
        unlimitedSigner = trust.newSignerVerifier(broker);
    }

    @Test
    void unlimited_signer_allows_refresh_token_even_when_access_token_limit_reached() {
        String username = "user@email.com";
        String refreshGroup = "refresh." + username;
        String sessionId = "session-1";

        String accessToken = limitedSigner.sign(username,
                BasicConfigurer.builder()
                        .groupId(username)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(120)  // 2 minutes
                        .build());
        assertNotNull(accessToken);

        String refreshToken = unlimitedSigner.sign(username,
                BasicConfigurer.builder()
                        .groupId(refreshGroup)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(604800) // 7 days
                        .build());
        assertNotNull(refreshToken);

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

        limitedSigner.sign("data",
                BasicConfigurer.builder()
                        .groupId(username)
                        .sequenceId("device-1")
                        .distribution(DistributionMode.DIRECT)
                        .validity(120)
                        .build());

        assertThrows(SessionCapacityExceededException.class,
                () -> limitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(username)
                                .sequenceId("device-2")
                                .distribution(DistributionMode.DIRECT)
                                .validity(120)
                                .build()),
                "Limited signer must REJECT when maxSessions=1 exceeded");

        assertDoesNotThrow(
                () -> unlimitedSigner.sign("data",
                        BasicConfigurer.builder()
                                .groupId(refreshGroup)
                                .sequenceId("device-1")
                                .distribution(DistributionMode.DIRECT)
                                .validity(604800)
                                .build()),
                "Unlimited signer must allow 1st refresh token");

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

        TestTrustSetup trust = TestTrustSetup.create();
        String accessToken;
        try (GenericSignerVerifier tempLimited = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT)) {
            accessToken = tempLimited.sign(username,
                    BasicConfigurer.builder()
                            .groupId(username)
                            .sequenceId(sessionId)
                            .distribution(DistributionMode.DIRECT)
                            .validity(1) // 1 second
                            .build());
        }
        assertNotNull(accessToken);

        String refreshToken = unlimitedSigner.sign(username,
                BasicConfigurer.builder()
                        .groupId(refreshGroup)
                        .sequenceId(sessionId)
                        .distribution(DistributionMode.DIRECT)
                        .validity(604800)
                        .build());
        assertNotNull(refreshToken);

        Thread.sleep(2500);

        try (GenericSignerVerifier newLimited = trust.newSignerVerifier(broker, 1, EvictionPolicy.REJECT)) {
            assertThrows(Exception.class,
                    () -> newLimited.verify(accessToken, s -> s),
                    "Access token must be expired");

            assertDoesNotThrow(
                    () -> unlimitedSigner.verify(refreshToken, s -> s),
                    "Refresh token must still be verifiable (7-day TTL)");

            assertDoesNotThrow(
                    () -> newLimited.sign(username,
                            BasicConfigurer.builder()
                                    .groupId(username)
                                    .sequenceId("sess-B")
                                    .distribution(DistributionMode.DIRECT)
                                    .validity(120)
                                    .build()),
                    "Limited signer must allow new access token after previous one expired");
        }
    }
}
