package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.DataSigner;
import io.github.cyfko.veridot.core.TokenMode;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

import java.util.function.Function;

/**
 * A simple implementation of {@link DataSigner.Configurer} that uses a builder pattern to configure
 * token signing parameters such as token mode, validity duration, serializer, and tracker ID.
 */
public class BasicConfigurer implements DataSigner.Configurer {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Builder for {@link BasicConfigurer}.
     * <p>
     * Provides configuration options for:
     * <ul>
     *     <li>{@link TokenMode} — the mode used to generate the token (default: {@code jwt})</li>
     *     <li>Validity duration in seconds — required</li>
     *     <li>Tracking ID — required (used for revocation and grouping)</li>
     *     <li>Object serializer — optional; uses Jackson {@link ObjectMapper} by default</li>
     * </ul>
     */
    public static class Builder {
        private TokenMode mode = TokenMode.jwt;
        private Function<Object, String> serializer;
        private Long tracker;
        private Long seconds;

        /**
         * Sets the token mode.
         *
         * @param mode the {@link TokenMode} to use; must not be {@code null}
         * @return this builder instance
         * @throws IllegalArgumentException if {@code mode} is {@code null}
         */
        public Builder useMode(TokenMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("token mode must not be null");
            }
            this.mode = mode;
            return this;
        }

        /**
         * Sets the token validity duration in seconds.
         *
         * @param seconds number of seconds the token remains valid
         * @return this builder instance
         */
        public Builder validity(long seconds) {
            this.seconds = seconds;
            return this;
        }

        /**
         * Sets the tracking ID for the token.
         * <p>
         * This ID can be used later for revocation or grouping purposes.
         *
         * @param id tracking ID
         * @return this builder instance
         */
        public Builder trackedBy(long id) {
            this.tracker = id;
            return this;
        }

        /**
         * Sets a custom object serializer to convert the data into a string (typically JSON).
         *
         * @param serializer a function that serializes an object to string; must not be {@code null}
         * @return this builder instance
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
         * <p>
         * If not explicitly set, the following defaults are used:
         * <ul>
         *     <li>{@link TokenMode} defaults to {@code jwt}</li>
         *     <li>{@link ObjectMapper} is used as the default serializer</li>
         * </ul>
         *
         * @return a configured {@link BasicConfigurer} instance
         * @throws IllegalStateException if required fields (tracker or validity duration) are not set
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

            if (tracker == null) {
                throw new IllegalStateException("tracker is required");
            }

            if (seconds == null) {
                throw new IllegalStateException("validity duration (seconds) is required");
            }

            return new BasicConfigurer(this);
        }
    }

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

    private final Builder builderConfig;

    private BasicConfigurer(Builder builder) {
        this.builderConfig = builder;
    }

    @Override
    public TokenMode getMode() {
        return builderConfig.mode;
    }

    @Override
    public long getTracker() {
        return builderConfig.tracker;
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
