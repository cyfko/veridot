package io.github.cyfko.veridot.core.impl;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Utility class encapsulating all Protocol V3 message format logic.
 * <p>
 * Responsible for building, parsing, and validating messages conforming to the
 * Veridot Protocol V3 format:
 * <pre>
 *   [3]:[groupId]:[sequenceId]|[name]:[base64url_value],[name]:[base64url_value],...
 * </pre>
 * </p>
 *
 * <p>This class is package-private and must not be exposed as public API.</p>
 */
public class Protocol {

    // ── Version ──────────────────────────────────────────────────────────────
    static final int VERSION = 3;
    static final String VERSION_STR = "3";

    // ── Separators ────────────────────────────────────────────────────────────
    static final String FIELD_SEP = ":";   // between version, groupId, sequenceId
    static final String META_SEP = "|";    // between header and metadata block
    static final String PROP_SEP = ",";    // between properties in metadata block

    // ── Reserved sequences ────────────────────────────────────────────────────
    static final String SEQ_CONFIG = "__CONFIG__";
    static final String SEQ_REVOKE = "__REVOKE__";
    static final String SEQ_ALL = "__ALL__";

    // ── Identifier validation ─────────────────────────────────────────────────
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[^:,|\\s]{1,125}$");

    // ── Standard property names (§7.4.1 Normal) ──────────────────────────────
    static final String PROP_ALG = "alg";
    static final String PROP_PK = "pk";
    static final String PROP_TS = "ts";
    static final String PROP_TTL = "ttl";
    static final String PROP_TOKEN = "token";
    static final String PROP_SITE = "site";
    static final String PROP_TARGET = "target";

    // ── Trust-anchor fields (v3.0 — F1) ──────────────────────────────────────
    /** Identity of the long-term signer that certified this key announcement. */
    static final String PROP_SID = "sid";
    /** Base64url-encoded long-term signature over the canonical announcement bytes. */
    static final String PROP_SIG = "sig";

    // ── Configuration property names (§7.4.2 Config) ─────────────────────────
    static final String PROP_MAX = "max";
    static final String PROP_POL = "pol";
    static final String PROP_DTTL = "dttl";
    static final String PROP_EXP = "exp";

    // ── Message building ──────────────────────────────────────────────────────

    /**
     * Builds a Protocol V3 messageId string.
     *
     * @param groupId    group identifier
     * @param sequenceId sequence identifier
     * @return {@code "3:<groupId>:<sequenceId>"}
     */
    public static String buildMessageId(String groupId, String sequenceId) {
        return VERSION_STR + FIELD_SEP + groupId + FIELD_SEP + sequenceId;
    }

    /**
     * Builds a complete Protocol V3 message.
     * <p>
     * Each value in {@code properties} is a raw (un-encoded) string. This method
     * encodes each value as Base64url before appending.
     * </p>
     *
     * @param groupId    group identifier
     * @param sequenceId sequence identifier
     * @param properties ordered map of property name → raw string value
     * @return full V3 message: {@code "3:<groupId>:<sequenceId>|name:b64val,..."}
     */
    public static String buildMessage(String groupId, String sequenceId, Map<String, String> properties) {
        String header = buildMessageId(groupId, sequenceId);
        String metadata = properties.entrySet().stream()
                .map(e -> e.getKey() + FIELD_SEP + base64UrlEncode(e.getValue()))
                .collect(Collectors.joining(PROP_SEP));
        return header + META_SEP + metadata;
    }

    /**
     * Overload of {@link #buildMessage(String, String, Map)} that accepts an already
     * composed messageId (useful for reserved keys such as config keys).
     */
    public static String buildMessage(String messageId, Map<String, String> properties) {
        String metadata = properties.entrySet().stream()
                .map(e -> e.getKey() + FIELD_SEP + base64UrlEncode(e.getValue()))
                .collect(Collectors.joining(PROP_SEP));
        return messageId + META_SEP + metadata;
    }

    // ── Message parsing ───────────────────────────────────────────────────────

    /**
     * Parses the header part of a message or messageId and returns its components.
     *
     * @param messageOrId either a full V3 message or just the messageId portion
     * @return {@code String[3] = {versionStr, groupId, sequenceId}}
     * @throws IllegalArgumentException if the format is invalid or version is unsupported
     */
    public static String[] parseMessageId(String messageOrId) {
        // Strip metadata block if present
        String headerPart = messageOrId.contains(META_SEP)
                ? messageOrId.substring(0, messageOrId.indexOf(META_SEP))
                : messageOrId;

        String[] parts = headerPart.split(FIELD_SEP, 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid messageId format (expected 3 parts): " + headerPart);
        }
        if (!parts[0].equals(VERSION_STR)) {
            throw new IllegalArgumentException("Unsupported protocol version: " + parts[0]);
        }
        return parts; // [versionStr, groupId, sequenceId]
    }

    /**
     * Parses the metadata section of a full V3 message into a property map.
     * <p>Each value is Base64url-decoded before being placed in the map.</p>
     *
     * @param message full V3 message (must contain {@code |})
     * @return map of property name → decoded string value; empty map if no properties
     * @throws IllegalArgumentException if the format is invalid
     */
    public static Map<String, String> parseMetadata(String message) {
        int pipeIndex = message.indexOf(META_SEP);
        if (pipeIndex < 0) {
            throw new IllegalArgumentException("Message contains no metadata separator '|': " + message);
        }
        String metaPart = message.substring(pipeIndex + 1);
        if (metaPart.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String prop : metaPart.split(PROP_SEP)) {
            int colonIndex = prop.indexOf(FIELD_SEP);
            if (colonIndex < 0) {
                throw new IllegalArgumentException("Invalid property (no ':' separator): " + prop);
            }
            String name = prop.substring(0, colonIndex);
            String encodedValue = prop.substring(colonIndex + 1);
            result.put(name, base64UrlDecode(encodedValue));
        }
        return result;
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    /**
     * Extracts the groupId from a messageId or full message.
     */
    static String extractGroupId(String messageId) {
        return parseMessageId(messageId)[1];
    }

    /**
     * Returns the broker key prefix for all sequences of a given group.
     * <p>Example: {@code groupPrefix("user123")} → {@code "3:user123:"}</p>
     */
    public static String groupPrefix(String groupId) {
        return VERSION_STR + FIELD_SEP + groupId + FIELD_SEP;
    }

    /**
     * Returns {@code true} if the given string looks like a Protocol V3 messageId.
     * <p>A messageId starts with {@code "3:"} and contains at least two {@code ':'} characters.</p>
     */
    static boolean isMessageId(String s) {
        if (s == null) return false;
        String prefix = VERSION_STR + FIELD_SEP;
        return s.startsWith(prefix) && s.chars().filter(c -> c == ':').count() >= 2;
    }

    /**
     * Returns {@code true} if the given string looks like a JWT.
     * <p>A JWT contains at least one {@code '.'} character.</p>
     */
    static boolean isJwt(String s) {
        return s != null && s.contains(".");
    }

    // ── Reserved sequence detection ───────────────────────────────────────────

    /**
     * Returns {@code true} if the messageId's sequenceId is a reserved sequence
     * (pattern: {@code __NAME__}).
     */
    public static boolean isReservedSequence(String messageId) {
        try {
            String seqId = parseMessageId(messageId)[2];
            return seqId.startsWith("__") && seqId.endsWith("__");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Revocation helpers (§5) ───────────────────────────────────────────────

    /**
     * Returns the broker key for a revocation message: {@code 3:<groupId>:__REVOKE__}.
     */
    static String buildRevocationKey(String groupId) {
        return buildMessageId(groupId, SEQ_REVOKE);
    }

    /**
     * Builds a formal V3 revocation message (§5.2).
     *
     * @param groupId the group whose sequence(s) are being revoked
     * @param target  the sequenceId to revoke, or {@code __ALL__} for group-wide revocation
     * @return a complete V3 message: {@code 3:<groupId>:__REVOKE__|target:<b64>,ts:<b64>}
     */
    static String buildRevocationMessage(String groupId, String target) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put(PROP_TARGET, target);
        props.put(PROP_TS, String.valueOf(Instant.now().getEpochSecond()));
        return buildMessage(groupId, SEQ_REVOKE, props);
    }

    // ── Configuration key helpers (§4) ────────────────────────────────────────

    /** Local config key: {@code 3:<groupId>:__CONFIG__}. */
    static String buildLocalConfigKey(String groupId) {
        return buildMessageId(groupId, SEQ_CONFIG);
    }

    /** Site config key: {@code 3:__CONFIG__:<siteId>}. */
    static String buildSiteConfigKey(String siteId) {
        return buildMessageId(SEQ_CONFIG, siteId);
    }

    /** Global config key: {@code 3:__CONFIG__:__ALL__}. */
    static String buildGlobalConfigKey() {
        return buildMessageId(SEQ_CONFIG, SEQ_ALL);
    }

    // ── Identifier validation ─────────────────────────────────────────────────

    /**
     * Validates that the given identifier contains only printable characters
     * excluding protocol delimiters ({@code :}, {@code ,}, {@code |}) and whitespace,
     * with a maximum length of 125 characters.
     *
     * @param id        identifier to validate
     * @param fieldName name of the field (for error messages)
     * @throws IllegalArgumentException if the identifier is null, blank, or malformed
     */
    public static void validateIdentifier(String id, String fieldName) {
        if (id == null || !IDENTIFIER_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be 1-125 printable characters excluding :,| and whitespace, got: " + id);
        }
        if (id.startsWith("__") && id.endsWith("__")) {
            throw new IllegalArgumentException(
                    fieldName + " must not use the reserved namespace pattern __...__: " + id);
        }
    }

    // ── Universal canonical encoding (§11.5) ─────────────────────────────────

    /**
     * Builds the canonical byte sequence for universal signature computation.
     * <p>
     * The canonical encoding includes the messageId and all metadata properties
     * (sorted alphabetically by key), excluding {@code sig} and {@code token}.
     * Each element is length-prefixed (4 bytes, big-endian unsigned) followed
     * by its UTF-8 bytes.
     * </p>
     *
     * @param messageId the full Protocol V3 messageId (e.g., {@code "3:user:sess"})
     * @param props     the metadata properties map (raw values, not base64-encoded)
     * @return the canonical byte array to be signed/verified
     */
    public static byte[] buildCanonicalBytes(String messageId, Map<String, String> props) {
        TreeMap<String, String> sorted = new TreeMap<>(props);
        sorted.remove(PROP_SIG);
        sorted.remove(PROP_TOKEN);

        try {
            var out = new java.io.ByteArrayOutputStream();
            writeLengthPrefixed(out, messageId);
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                writeLengthPrefixed(out, e.getKey());
                writeLengthPrefixed(out, e.getValue());
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build canonical bytes", e);
        }
    }

    private static void writeLengthPrefixed(java.io.ByteArrayOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }

    // ── Base64url codec ───────────────────────────────────────────────────────

    /**
     * Encodes a raw string value to Base64url without padding.
     */
    public static String base64UrlEncode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a Base64url-encoded string (with or without padding) to its raw UTF-8 value.
     */
    public static String base64UrlDecode(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // Prevent instantiation
    private Protocol() {}
}
