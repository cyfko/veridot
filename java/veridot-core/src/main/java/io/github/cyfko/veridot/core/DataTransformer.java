package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;
import io.github.cyfko.veridot.core.exceptions.DataSerializationException;

/**
 * Contract for bidirectional transformation of a domain object to and from its
 * string representation.
 *
 * <p>Implementations define the serialization format (JSON, XML, binary-safe text, etc.)
 * and are responsible for both directions of the conversion. This interface is provided
 * as a convenience extension point; most users will prefer the {@code Function}-based
 * approach available via {@link BasicConfigurer#deserializer(Class)} and the default
 * Jackson serializer built into {@link BasicConfigurer}.</p>
 *
 * <h2>Custom implementation example (JSON via Jackson)</h2>
 * <pre>{@code
 * public class JsonTransformer implements DataTransformer {
 *     private final ObjectMapper mapper = new ObjectMapper();
 *
 *     @Override
 *     public String serialize(Object data) {
 *         try {
 *             return mapper.writeValueAsString(data);
 *         } catch (JsonProcessingException e) {
 *             throw new DataSerializationException("Serialization failed", e);
 *         }
 *     }
 *
 *     @Override
 *     public Object deserialize(String data) {
 *         try {
 *             return mapper.readValue(data, Map.class);
 *         } catch (JsonProcessingException e) {
 *             throw new DataDeserializationException("Deserialization failed", e);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see BasicConfigurer#deserializer(Class)
 */
public interface DataTransformer {

    /**
     * Serializes the given domain object into a UTF-8 compatible string suitable for
     * embedding in a token or transmitting over a text-based protocol.
     *
     * @param data the object to serialize; must not be {@code null}
     * @return a non-null, non-empty string representation of {@code data}
     * @throws DataSerializationException if the object cannot be converted to a string
     */
    String serialize(Object data) throws DataSerializationException;

    /**
     * Deserializes the given string back into its original domain object representation.
     *
     * @param data the serialized string to convert; must not be {@code null}
     * @return the reconstructed domain object; may be cast to the expected type by the caller
     * @throws DataDeserializationException if the string cannot be converted into an object
     */
    Object deserialize(String data) throws DataDeserializationException;
}
