package io.github.cyfko.veridot.core.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolV2Test {

    @Test
    void buildMessageId_valid() {
        assertEquals("2:user123:session001", ProtocolV2.buildMessageId("user123", "session001"));
    }

    @Test
    void buildMessage_containsHeaderAndMetadata() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("mode", "rsa");
        props.put("timestamp", "1706712000");
        String msg = ProtocolV2.buildMessage("grp", "seq", props);
        assertTrue(msg.startsWith("2:grp:seq|"), "Message must start with header");
        assertTrue(msg.contains("mode:"), "Must contain mode property");
        assertTrue(msg.contains("timestamp:"), "Must contain timestamp property");
        assertFalse(msg.contains("rsa"), "Raw values must not appear un-encoded");
    }

    @Test
    void parseMessageId_fromBareId() {
        String[] parts = ProtocolV2.parseMessageId("2:user123:session001");
        assertEquals("2", parts[0]);
        assertEquals("user123", parts[1]);
        assertEquals("session001", parts[2]);
    }

    @Test
    void parseMessageId_fromFullMessage() {
        String[] parts = ProtocolV2.parseMessageId("2:grp:seq|mode:cnNh");
        assertEquals("2", parts[0]);
        assertEquals("grp", parts[1]);
        assertEquals("seq", parts[2]);
    }

    @Test
    void parseMessageId_invalidVersion_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.parseMessageId("1:grp:seq"));
    }

    @Test
    void parseMessageId_tooFewParts_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.parseMessageId("2:onlyone"));
    }

    @Test
    void parseMetadata_decodesValues() {
        // Build a message with known property, then parse
        Map<String, String> props = new LinkedHashMap<>();
        props.put("mode", "rsa");
        String msg = ProtocolV2.buildMessage("g", "s", props);
        Map<String, String> meta = ProtocolV2.parseMetadata(msg);
        assertEquals("rsa", meta.get("mode"));
    }

    @Test
    void parseMetadata_multipleProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("mode", "rsa");
        props.put("ttl", "3600");
        props.put("timestamp", "1706712000");
        String msg = ProtocolV2.buildMessage("g", "s", props);
        Map<String, String> meta = ProtocolV2.parseMetadata(msg);
        assertEquals("rsa", meta.get("mode"));
        assertEquals("3600", meta.get("ttl"));
        assertEquals("1706712000", meta.get("timestamp"));
    }

    @Test
    void validateIdentifier_valid() {
        assertDoesNotThrow(() -> ProtocolV2.validateIdentifier("abc-123_XYZ", "test"));
        assertDoesNotThrow(() -> ProtocolV2.validateIdentifier("a", "test"));
        assertDoesNotThrow(() -> ProtocolV2.validateIdentifier("a".repeat(64), "test"));
    }

    @Test
    void validateIdentifier_rejects_colon() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.validateIdentifier("abc:def", "field"));
    }

    @Test
    void validateIdentifier_rejects_pipe() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.validateIdentifier("abc|def", "field"));
    }

    @Test
    void validateIdentifier_rejects_empty() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.validateIdentifier("", "field"));
    }

    @Test
    void validateIdentifier_rejects_tooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.validateIdentifier("a".repeat(65), "field"));
    }

    @Test
    void validateIdentifier_rejects_null() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolV2.validateIdentifier(null, "field"));
    }

    @Test
    void base64Url_roundtrip() {
        String original = "hello world 123 !@#$%";
        assertEquals(original, ProtocolV2.base64UrlDecode(ProtocolV2.base64UrlEncode(original)));
    }

    @Test
    void isMessageId_true() {
        assertTrue(ProtocolV2.isMessageId("2:grp:seq"));
    }

    @Test
    void isMessageId_false_for_jwt() {
        assertFalse(ProtocolV2.isMessageId("eyJhbG.eyJzdW.signature"));
    }

    @Test
    void isMessageId_false_for_null() {
        assertFalse(ProtocolV2.isMessageId(null));
    }

    @Test
    void isJwt_true() {
        assertTrue(ProtocolV2.isJwt("part1.part2.part3"));
    }

    @Test
    void isJwt_false_for_messageId() {
        assertFalse(ProtocolV2.isJwt("2:grp:seq"));
    }

    @Test
    void isJwt_false_for_null() {
        assertFalse(ProtocolV2.isJwt(null));
    }

    @Test
    void groupPrefix_format() {
        assertEquals("2:user123:", ProtocolV2.groupPrefix("user123"));
    }

    @Test
    void buildRevocationMessage_format() {
        String msg = ProtocolV2.buildRevocationMessage("user123", "session001");
        assertTrue(msg.startsWith("2:user123:__REVOKE__|"), "Must have __REVOKE__ header");
        assertTrue(msg.contains("target:"), "Must contain target property");
        assertTrue(msg.contains("timestamp:"), "Must contain timestamp property");

        // Decode target to verify it matches
        var meta = ProtocolV2.parseMetadata(msg);
        assertEquals("session001", meta.get("target"));
    }

    @Test
    void buildLocalConfigKey_format() {
        assertEquals("2:myGroup:__CONFIG__", ProtocolV2.buildLocalConfigKey("myGroup"));
        assertEquals("2:__CONFIG__:mySite", ProtocolV2.buildSiteConfigKey("mySite"));
        assertEquals("2:__CONFIG__:__ALL__", ProtocolV2.buildGlobalConfigKey());
    }

    @Test
    void isReservedSequence_detection() {
        assertTrue(ProtocolV2.isReservedSequence("2:grp:__REVOKE__"), "__REVOKE__ is reserved");
        assertTrue(ProtocolV2.isReservedSequence("2:grp:__CONFIG__"), "__CONFIG__ is reserved");
        assertTrue(ProtocolV2.isReservedSequence("2:grp:__ALL__"), "__ALL__ is reserved");
        assertFalse(ProtocolV2.isReservedSequence("2:grp:session001"), "Normal sequence is not reserved");
        assertFalse(ProtocolV2.isReservedSequence("invalid"), "Non-messageId is not reserved");
    }
}
