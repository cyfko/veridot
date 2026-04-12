package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.DataSigner;
import io.github.cyfko.veridot.core.DistributionMode;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

import java.util.function.Function;

/**
 * A simple implementation of {@link DataSigner.Configurer} that uses a builder pattern to configure
 * token signing parameters such as distribution mode, groupId, sequenceId, validity duration,
 * and payload serializer.
 *
 * @since 2.0.0
 */
public class BasicConfigurer implements DataSigner.Configurer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ── Builder ──────────────────────────────────────────────────────────────

    /**
     * Builder for {@link BasicConfigurer}.
     *
     * <p>Required fields: {@code groupId}, {@code validity}.<br>
     * Optional fields: {@code sequenceId} (auto-generated UUID if null),
     * {@code distribution} (defaults to {@link DistributionMode#DIRECT}),
     * {@code serializer} (defaults to Jackson {@link ObjectMapper}).</p>
     */
    public static class Builder {
        private String groupId;
        private String sequenceId;
        private DistributionMode distribution = DistributionMode.DIRECT;
        private Function<Object, String> serializer;
        private Long seconds;

        /**
         * Sets the group identifier.
         *
         * @param groupId non-null, non-blank string of 1-125 printable characters (excluding {@code :,|} and whitespace)
         * @return this builder
         * @throws IllegalArgumentException if {@code groupId} is null or blank
         */
        public Builder groupId(String groupId) {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("groupId must not be null or blank");
            }
            this.groupId = groupId;
            return this;
        }

        /**
         * Sets the optional sequence identifier within the group.
         * If not set (or set to {@code null}), a UUID will be auto-generated at signing time.
         *
         * @param sequenceId a string of 1-125 printable characters (excluding {@code :,|} and whitespace), or {@code null}
         * @return this builder
         */
        public Builder sequenceId(String sequenceId) {
            this.sequenceId = sequenceId; // null is explicitly allowed
            return this;
        }

        /**
         * Sets the distribution mode.
         *
         * @param distribution how the signed token is delivered to the caller; must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if {@code distribution} is {@code null}
         */
        public Builder distribution(DistributionMode distribution) {
            if (distribution == null) {
                throw new IllegalArgumentException("distribution mode must not be null");
            }
            this.distribution = distribution;
            return this;
        }

        /**
         * Sets the token validity duration in seconds.
         *
         * @param seconds number of seconds the token remains valid; must be positive
         * @return this builder
         */
        public Builder validity(long seconds) {
            this.seconds = seconds;
            return this;
        }

        /**
         * Sets a custom object serializer to convert the data into a string (typically JSON).
         *
         * @param serializer a function that serializes an object to string; must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if {@code serializer} is {@code null}
         */
        public Builder serializedBy(Function<Object, String> serializer) {
            if (serializer == null) {
                throw new IllegalArgumentException("serializer must not be null");
            }
            this.serializer = serializer;
            return this;
        }

        /**
         * Builds a {@link BasicConfigurer} instance with the current configuration.
         *
         * @return a configured {@link BasicConfigurer} instance
         * @throws IllegalStateException if required fields ({@code groupId} or {@code validity}) are not set
         */
        public BasicConfigurer build() {
            if (serializer == null) {
                serializer = data -> {
                    try {
                        return (data instanceof String str) ? str : objectMapper.writeValueAsString(data);
                    } catch (JsonProcessingException e) {
                        throw new DataSerializationException("Serialization failed.", e);
                    }
                };
            }
            if (groupId == null) {
                throw new IllegalStateException("groupId is required");
            }
            if (seconds == null) {
                throw new IllegalStateException("validity duration (seconds) is required");
            }
            return new BasicConfigurer(this);
        }
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Returns a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a deserialization function that converts a JSON string into an object of the specified class type,
     * using Jackson's {@link ObjectMapper}.
     *
     * @param clazz the target class to deserialize into
     * @param <T>   the type of the target object
     * @return a function that takes a JSON string and returns a deserialized object of type T
     */
    public static <T> Function<String, T> deserializer(Class<T> clazz) {
        return data -> {
            if (clazz.equals(String.class)) {
                return clazz.cast(data);
            }
            try {
                return objectMapper.readValue(data, clazz);
            } catch (JsonProcessingException e) {
                throw new DataDeserializationException("Failed to deserialize", e);
            }
        };
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private final Builder builderConfig;

    private BasicConfigurer(Builder builder) {
        this.builderConfig = builder;
    }

    @Override
    public String getGroupId() {
        return builderConfig.groupId;
    }

    @Override
    public String getSequenceId() {
        return builderConfig.sequenceId; // may be null → auto-generated by signer
    }

    @Override
    public DistributionMode getDistribution() {
        return builderConfig.distribution;
    }

    @Override
    public long getDuration() {
        return builderConfig.seconds;
    }

    @Override
    public Function<Object, String> getSerializer() {
        return builderConfig.serializer;
    }
}
