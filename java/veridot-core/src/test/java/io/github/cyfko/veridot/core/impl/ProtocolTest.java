package io.github.cyfko.veridot.core.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {

    @Test
    void buildMessageId_valid() {
        assertEquals("3:user123:session001", Protocol.buildMessageId("user123", "session001"));
    }

    @Test
    void buildMessage_containsHeaderAndMetadata() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("alg", "rsa");
        props.put("ts", "1706712000");
        String msg = Protocol.buildMessage("grp", "seq", props);
        assertTrue(msg.startsWith("3:grp:seq|"), "Message must start with header");
        assertTrue(msg.contains("alg:"), "Must contain alg property");
        assertTrue(msg.contains("ts:"), "Must contain ts property");
        assertFalse(msg.contains("rsa"), "Raw values must not appear un-encoded");
    }

    @Test
    void parseMessageId_fromBareId() {
        String[] parts = Protocol.parseMessageId("3:user123:session001");
        assertEquals("3", parts[0]);
        assertEquals("user123", parts[1]);
        assertEquals("session001", parts[2]);
    }

    @Test
    void parseMessageId_fromFullMessage() {
        String[] parts = Protocol.parseMessageId("3:grp:seq|alg:cnNh");
        assertEquals("3", parts[0]);
        assertEquals("grp", parts[1]);
        assertEquals("seq", parts[2]);
    }

    @Test
    void parseMessageId_invalidVersion_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.parseMessageId("2:grp:seq"));
    }

    @Test
    void parseMessageId_tooFewParts_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.parseMessageId("3:onlyone"));
    }

    @Test
    void parseMetadata_decodesValues() {
        // Build a message with known property, then parse
        Map<String, String> props = new LinkedHashMap<>();
        props.put("alg", "rsa");
        String msg = Protocol.buildMessage("g", "s", props);
        Map<String, String> meta = Protocol.parseMetadata(msg);
        assertEquals("rsa", meta.get("alg"));
    }

    @Test
    void parseMetadata_multipleProperties() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("alg", "rsa");
        props.put("ttl", "3600");
        props.put("ts", "1706712000");
        String msg = Protocol.buildMessage("g", "s", props);
        Map<String, String> meta = Protocol.parseMetadata(msg);
        assertEquals("rsa", meta.get("alg"));
        assertEquals("3600", meta.get("ttl"));
        assertEquals("1706712000", meta.get("ts"));
    }

    @Test
    void validateIdentifier_valid() {
        assertDoesNotThrow(() -> Protocol.validateIdentifier("abc-123_XYZ", "test"));
        assertDoesNotThrow(() -> Protocol.validateIdentifier("a", "test"));
        assertDoesNotThrow(() -> Protocol.validateIdentifier("a".repeat(125), "test"));
        assertDoesNotThrow(() -> Protocol.validateIdentifier("user@mail.com", "test"));
        assertDoesNotThrow(() -> Protocol.validateIdentifier("tenant.service.region", "test"));
        assertDoesNotThrow(() -> Protocol.validateIdentifier("192.168.1.1", "test"));
    }

    @Test
    void validateIdentifier_rejects_colon() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("abc:def", "field"));
    }

    @Test
    void validateIdentifier_rejects_pipe() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("abc|def", "field"));
    }

    @Test
    void validateIdentifier_rejects_comma() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("abc,def", "field"));
    }

    @Test
    void validateIdentifier_rejects_whitespace() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("abc def", "field"));
    }

    @Test
    void validateIdentifier_rejects_empty() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("", "field"));
    }

    @Test
    void validateIdentifier_rejects_tooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier("a".repeat(126), "field"));
    }

    @Test
    void validateIdentifier_rejects_null() {
        assertThrows(IllegalArgumentException.class,
                () -> Protocol.validateIdentifier(null, "field"));
    }

    @Test
    void base64Url_roundtrip() {
        String original = "hello world 123 !@#$%";
        assertEquals(original, Protocol.base64UrlDecode(Protocol.base64UrlEncode(original)));
    }

    @Test
    void isMessageId_true() {
        assertTrue(Protocol.isMessageId("3:grp:seq"));
    }

    @Test
    void isMessageId_false_for_jwt() {
        assertFalse(Protocol.isMessageId("eyJhbG.eyJzdW.signature"));
    }

    @Test
    void isMessageId_false_for_null() {
        assertFalse(Protocol.isMessageId(null));
    }

    @Test
    void isJwt_true() {
        assertTrue(Protocol.isJwt("part1.part2.part3"));
    }

    @Test
    void isJwt_false_for_messageId() {
        assertFalse(Protocol.isJwt("3:grp:seq"));
    }

    @Test
    void isJwt_false_for_null() {
        assertFalse(Protocol.isJwt(null));
    }

    @Test
    void groupPrefix_format() {
        assertEquals("3:user123:", Protocol.groupPrefix("user123"));
    }

    @Test
    void buildRevocationMessage_format() {
        String msg = Protocol.buildRevocationMessage("user123", "session001");
        assertTrue(msg.startsWith("3:user123:__REVOKE__|"), "Must have __REVOKE__ header");
        assertTrue(msg.contains("target:"), "Must contain target property");
        assertTrue(msg.contains("ts:"), "Must contain ts property");

        // Decode target to verify it matches
        var meta = Protocol.parseMetadata(msg);
        assertEquals("session001", meta.get("target"));
    }

    @Test
    void buildLocalConfigKey_format() {
        assertEquals("3:myGroup:__CONFIG__", Protocol.buildLocalConfigKey("myGroup"));
        assertEquals("3:__CONFIG__:mySite", Protocol.buildSiteConfigKey("mySite"));
        assertEquals("3:__CONFIG__:__ALL__", Protocol.buildGlobalConfigKey());
    }

    @Test
    void isReservedSequence_detection() {
        assertTrue(Protocol.isReservedSequence("3:grp:__REVOKE__"), "__REVOKE__ is reserved");
        assertTrue(Protocol.isReservedSequence("3:grp:__CONFIG__"), "__CONFIG__ is reserved");
        assertTrue(Protocol.isReservedSequence("3:grp:__ALL__"), "__ALL__ is reserved");
        assertFalse(Protocol.isReservedSequence("3:grp:session001"), "Normal sequence is not reserved");
        assertFalse(Protocol.isReservedSequence("invalid"), "Non-messageId is not reserved");
    }
}
