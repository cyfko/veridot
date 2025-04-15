package io.github.cyfko.veridok.core.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cyfko.veridok.core.DataTransformer;
import io.github.cyfko.veridok.core.exceptions.DataSerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JacksonDataTransformer implements DataTransformer {
    private static final Logger log = LoggerFactory.getLogger(JacksonDataTransformer.class);
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
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Serialization failed", e);
            throw new DataSerializationException("Serialization failed", e);
        }
    }

    @Override
    public Object deserialize(String data) {
        return null;
    }
}
