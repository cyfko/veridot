package io.github.cyfko.veridok.core;

import io.github.cyfko.veridok.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridok.core.exceptions.DataSerializationException;

/**
 * A contract for transforming data objects to and from their serialized {@link String} form.
 *
 * <p>This interface enables customizable serialization strategies (e.g., JSON, XML, custom formats)
 * while remaining language-agnostic and transport-friendly.</p>
 *
 * <p>Typical usage includes converting a domain object into a serialized string for transmission
 * or storage, and converting the string back into the domain object for further processing.</p>
 *
 * @author Frank KOSSI
 * @since 0.0.1
 */
public interface DataTransformer {

    /**
     * Serializes the given data object to a UTF-8 compatible {@link String}.
     *
     * @param data the object to serialize (must not be {@code null})
     * @return a non-null string representation of the object
     * @throws DataSerializationException if serialization fails
     */
    String serialize(Object data) throws DataSerializationException;

    /**
     * Deserializes the given UTF-8 {@link String} back into the original data object.
     *
     * @param data the serialized string (must not be {@code null})
     * @return the deserialized object
     * @throws DataDeserializationException if deserialization fails
     */
    Object deserialize(String data) throws DataDeserializationException;
}
