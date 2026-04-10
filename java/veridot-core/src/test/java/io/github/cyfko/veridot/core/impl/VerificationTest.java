package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.InMemoryMetadataBroker;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VerificationTest {

    private InMemoryMetadataBroker broker;
    private GenericSignerVerifier sv;

    @BeforeEach
    void setUp() {
        broker = new InMemoryMetadataBroker();
        sv = new GenericSignerVerifier(broker, "test-salt");
    }

    @Test
    void verify_valid_direct_token_returns_payload() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("hello world", cfg);
        String result = sv.verify(jwt, s -> s);
        assertEquals("hello world", result);
    }

    @Test
    void verify_valid_indirect_token_returns_payload() {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("hello world", cfg);
        String result = sv.verify(messageId, s -> s);
        assertEquals("hello world", result);
    }

    @Test
    void verify_valid_pojo_direct() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("email", "test@example.com", "role", "admin");

        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign(data, cfg);

        @SuppressWarnings("unchecked")
        Map<String, String> result = sv.verify(jwt, s -> {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("test@example.com", result.get("email"));
        assertEquals("admin", result.get("role"));
    }

    @Test
    void verify_valid_pojo_indirect() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("userId", "42");

        var cfg = BasicConfigurer.builder().groupId("u1").validity(600)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign(data, cfg);

        @SuppressWarnings("unchecked")
        Map<String, String> result = sv.verify(messageId, s -> {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals("42", result.get("userId"));
    }

    @Test
    void verify_invalid_token_throws_BrokerExtractionException() {
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("invalid.token.here", s -> s));
    }

    @Test
    void verify_unknown_messageId_throws() {
        assertThrows(BrokerExtractionException.class,
                () -> sv.verify("2:unknown:session", s -> s));
    }

    @Test
    void verify_expired_direct_token_throws() throws InterruptedException {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(1)
                .distribution(DistributionMode.DIRECT).build();
        String jwt = sv.sign("hello", cfg);
        Thread.sleep(2000); // wait for TTL to pass
        assertThrows(Exception.class, () -> sv.verify(jwt, s -> s),
                "Expired token must throw an exception");
    }

    @Test
    void verify_expired_indirect_token_throws() throws InterruptedException {
        var cfg = BasicConfigurer.builder().groupId("u1").validity(1)
                .distribution(DistributionMode.INDIRECT).build();
        String messageId = sv.sign("hello", cfg);
        Thread.sleep(2000);
        assertThrows(BrokerExtractionException.class, () -> sv.verify(messageId, s -> s),
                "Expired INDIRECT token must throw BrokerExtractionException");
    }
}
