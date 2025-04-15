package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridot.core.DataTransformer;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;


class JacksonDataTransformer implements DataTransformer {
    private static final Logger logger = Logger.getLogger(JacksonDataTransformer.class.getName());
    private final ObjectMapper objectMapper;

    /**
     * Constructs a JacksonDataTransformer for a specific target type.
     *
     * @param objectMapper  the Jackson ObjectMapper instance
     */
    public JacksonDataTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String serialize(Object data) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(data);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            logger.severe("Serialization failed");
            throw new DataSerializationException("Serialization failed", e);
        }
    }

    @Override
    public Object deserialize(String data) {
        try {
            return objectMapper.readValue(data, Object.class);
        } catch (JsonProcessingException e) {
            logger.severe("Deserialization failed: " + e.getMessage());
            throw new DataDeserializationException("Deserialization failed", e);
        }
    }
}
