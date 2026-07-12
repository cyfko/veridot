package io.github.cyfko.veridot.core.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for loading and parsing JSON test vector files from the classpath.
 *
 * <p>Test vectors are stored in {@code src/test/resources/test-vectors/} and
 * follow a convention of having a top-level {@code "vectors"} array, where
 * each element contains {@code "name"}, {@code "description"}, {@code "input"},
 * and {@code "expected"} fields.
 *
 * <p>Usage:
 * <pre>{@code
 * List<JsonNode> vectors = TestVectorLoader.load("envelope_v5_vectors.json");
 * for (JsonNode vector : vectors) {
 *     String name = vector.get("name").asText();
 *     JsonNode input = vector.get("input");
 *     JsonNode expected = vector.get("expected");
 *     // ... assertions ...
 * }
 * }</pre>
 */
public final class TestVectorLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VECTORS_DIR = "test-vectors/";

    private TestVectorLoader() {} // non-instantiable

    /**
     * Loads all test vectors from the specified JSON file.
     *
     * @param filename the JSON file name (e.g. {@code "envelope_v5_vectors.json"})
     * @return a list of {@link JsonNode} elements, one per test vector
     * @throws IOException if the file cannot be read or parsed
     * @throws IllegalArgumentException if the file does not contain a {@code "vectors"} array
     */
    public static List<JsonNode> load(String filename) throws IOException {
        String path = VECTORS_DIR + filename;
        try (InputStream is = TestVectorLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Test vector file not found on classpath: " + path);
            }
            JsonNode root = MAPPER.readTree(is);
            JsonNode vectorsNode = root.get("vectors");
            if (vectorsNode == null || !vectorsNode.isArray()) {
                throw new IllegalArgumentException(
                        "Test vector file '" + filename + "' does not contain a 'vectors' array");
            }
            List<JsonNode> vectors = new ArrayList<>();
            vectorsNode.forEach(vectors::add);
            return vectors;
        }
    }

    /**
     * Loads a single test vector by name from the specified JSON file.
     *
     * @param filename   the JSON file name
     * @param vectorName the {@code "name"} field value to search for
     * @return the matching {@link JsonNode}
     * @throws IOException if the file cannot be read or parsed
     * @throws IllegalArgumentException if no vector with the given name is found
     */
    public static JsonNode loadByName(String filename, String vectorName) throws IOException {
        for (JsonNode vector : load(filename)) {
            JsonNode nameNode = vector.get("name");
            if (nameNode != null && vectorName.equals(nameNode.asText())) {
                return vector;
            }
        }
        throw new IllegalArgumentException(
                "No test vector named '" + vectorName + "' found in " + filename);
    }

    /**
     * Decodes a hex string (with or without "0x" prefix) into a byte array.
     *
     * @param hex the hex string (e.g. {@code "0x56440508"} or {@code "56440508"})
     * @return the decoded byte array
     * @throws IllegalArgumentException if the hex string has odd length or invalid characters
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex string must not be null");
        }
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string must have even length: " + hex);
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("Invalid hex character at position " + (2 * i));
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /**
     * Encodes a byte array as a lowercase hex string (without "0x" prefix).
     *
     * @param bytes the byte array
     * @return the hex string
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Returns a human-readable description for a test vector node.
     *
     * @param vector the test vector node
     * @return "{name}: {description}" or just "{name}" if no description
     */
    public static String describe(JsonNode vector) {
        String name = vector.has("name") ? vector.get("name").asText() : "(unnamed)";
        String desc = vector.has("description") ? vector.get("description").asText() : null;
        return desc != null ? name + ": " + desc : name;
    }
}
