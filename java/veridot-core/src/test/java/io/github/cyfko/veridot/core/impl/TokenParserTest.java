package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TokenParser} — V5 §7.5 formal token format parsing.
 *
 * <p>Tests the three token formats:
 * <ul>
 *   <li>{@code 8:scope:key} → NATIVE (signed data)</li>
 *   <li>{@code 7:scope:key} → SECURE_PAYLOAD (encrypted)</li>
 *   <li>{@code header.payload.signature} → JWT (direct mode)</li>
 * </ul>
 */
class TokenParserTest {

    // ── NATIVE format (8:) ───────────────────────────────────────────

    @Test
    void parse_nativeGlobalScope() {
        TokenParser.TokenInfo info = TokenParser.parse("8:global:key1");
        assertEquals(TokenParser.TokenFormat.NATIVE, info.format());
        assertTrue(info.scope().isGlobal());
        assertEquals("key1", info.key());
        assertEquals("8:global:key1", info.rawToken());
    }

    @Test
    void parse_nativeGroupScope() {
        TokenParser.TokenInfo info = TokenParser.parse("7:group:orders:msg-123");
        assertEquals(TokenParser.TokenFormat.SECURE_PAYLOAD, info.format());
        assertTrue(info.scope().isGroup());
        assertEquals("orders", info.scope().groupId());
        assertEquals("msg-123", info.key());
    }

    @Test
    void parse_nativeSiteScope() {
        TokenParser.TokenInfo info = TokenParser.parse("8:site:us-east-1:entry-456");
        assertEquals(TokenParser.TokenFormat.NATIVE, info.format());
        assertTrue(info.scope().isSite());
        assertEquals("us-east-1", info.scope().siteId());
        assertEquals("entry-456", info.key());
    }

    // ── SECURE_PAYLOAD format (7:) ───────────────────────────────────

    @Test
    void parse_securePayloadGlobalScope() {
        TokenParser.TokenInfo info = TokenParser.parse("7:global:secret-key");
        assertEquals(TokenParser.TokenFormat.SECURE_PAYLOAD, info.format());
        assertTrue(info.scope().isGlobal());
        assertEquals("secret-key", info.key());
    }

    @Test
    void parse_securePayloadGroupScope() {
        TokenParser.TokenInfo info = TokenParser.parse("7:group:team1:token-abc");
        assertEquals(TokenParser.TokenFormat.SECURE_PAYLOAD, info.format());
        assertTrue(info.scope().isGroup());
        assertEquals("team1", info.scope().groupId());
        assertEquals("token-abc", info.key());
    }

    @Test
    void parse_securePayloadSiteScope() {
        TokenParser.TokenInfo info = TokenParser.parse("7:site:eu-west:data-789");
        assertEquals(TokenParser.TokenFormat.SECURE_PAYLOAD, info.format());
        assertTrue(info.scope().isSite());
        assertEquals("eu-west", info.scope().siteId());
        assertEquals("data-789", info.key());
    }

    // ── JWT format ───────────────────────────────────────────────────

    @Test
    void parse_jwtFormat() {
        String jwt = "eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJl";
        TokenParser.TokenInfo info = TokenParser.parse(jwt);
        assertEquals(TokenParser.TokenFormat.JWT, info.format());
        assertNull(info.scope(), "JWT tokens have no scope");
        assertNull(info.key(), "JWT tokens have no key");
        assertEquals(jwt, info.rawToken());
    }

    @Test
    void parse_jwtMinimalDots() {
        // Even a string with dots (but no 7: or 8: prefix) is treated as JWT
        TokenParser.TokenInfo info = TokenParser.parse("a.b.c");
        assertEquals(TokenParser.TokenFormat.JWT, info.format());
    }

    // ── Invalid formats ──────────────────────────────────────────────

    @Test
    void parse_nullThrows() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse(null));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_emptyStringThrows() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse(""));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_blankStringThrows() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("   "));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_unrecognizedFormatThrows() {
        // No prefix match and no dots → unrecognized
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("random-garbage-no-dots"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueWithEmptyRemainder() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("8:"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueWithInvalidScopePrefix() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("8:invalid:key"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueGroupMissingKeySeparator() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("8:group:team1"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueSiteMissingKeySeparator() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("7:site:us-east"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueWithEmptyKey() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("8:global:"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }

    @Test
    void parse_opaqueGroupWithEmptyKey() {
        VeridotException ex = assertThrows(VeridotException.class,
                () -> TokenParser.parse("8:group:team1:"));
        assertEquals(ErrorCode.INVALID_ENVELOPE, ex.getErrorCode());
    }
}
