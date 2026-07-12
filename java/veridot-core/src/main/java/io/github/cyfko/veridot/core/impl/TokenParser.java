package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;

/**
 * Formal token format parser for Protocol V5 (§7.5).
 *
 * <p>Resolves the token type from its prefix and extracts the scope and key components:
 * <ul>
 *   <li>{@code "8:<scope>:<key>"} → SIGNED_DATA (native mode)</li>
 *   <li>{@code "7:<scope>:<key>"} → SECURE_PAYLOAD (private mode)</li>
 *   <li>{@code "<header>.<payload>.<signature>"} → JWT (direct mode)</li>
 * </ul>
 *
 * <p>The colon-ambiguity in scope formats ({@code "global"}, {@code "group:<id>"},
 * {@code "site:<id>"}) is resolved by matching the scope grammar before the final key separator.
 */
public final class TokenParser {

    private TokenParser() {} // non-instantiable

    /**
     * Parsed token information.
     *
     * @param format the token format
     * @param scope the resolved scope (null for JWT)
     * @param key the entry key (null for JWT)
     * @param rawToken the original token string
     */
    public record TokenInfo(
        TokenFormat format,
        Scope scope,
        String key,
        String rawToken
    ) {}

    /**
     * Token format discriminator.
     */
    public enum TokenFormat {
        /** JWT token (direct mode). */
        JWT,
        /** Native V5 envelope reference (8:scope:key). */
        NATIVE,
        /** Encrypted secure payload reference (7:scope:key). */
        SECURE_PAYLOAD
    }

    /**
     * Parses a token string and resolves its format, scope, and key.
     *
     * @param token the raw token string
     * @return the parsed token information
     * @throws VeridotException with V5001 if the token format is unrecognized
     */
    public static TokenInfo parse(String token) {
        if (token == null || token.isBlank()) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Token must not be null or blank");
        }

        if (token.startsWith("8:")) {
            return parseOpaque(token, TokenFormat.NATIVE);
        }
        if (token.startsWith("7:")) {
            return parseOpaque(token, TokenFormat.SECURE_PAYLOAD);
        }
        if (token.contains(".")) {
            return new TokenInfo(TokenFormat.JWT, null, null, token);
        }

        throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Unrecognized token format: " + token);
    }

    /**
     * Parses an opaque token (prefix:scope:key) resolving scope grammar colon ambiguity.
     *
     * <p>Scope grammar: {@code "global"} | {@code "group:<id>"} | {@code "site:<id>"}.
     * The key is whatever follows the scope.
     */
    private static TokenInfo parseOpaque(String token, TokenFormat format) {
        String remainder = token.substring(2); // skip "7:" or "8:"

        if (remainder.isEmpty()) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null, "Opaque token missing scope/key: " + token);
        }

        // Try to match scope grammar:
        // "global:<key>" → scope=global, key=<key>
        // "group:<groupId>:<key>" → scope=group:<groupId>, key=<key>
        // "site:<siteId>:<key>" → scope=site:<siteId>, key=<key>

        Scope scope;
        String key;

        if (remainder.startsWith("global:")) {
            scope = Scope.global();
            key = remainder.substring("global:".length());
        } else if (remainder.startsWith("group:")) {
            // "group:<groupId>:<key>" — split at the SECOND colon after "group:"
            String afterGroup = remainder.substring("group:".length());
            int colonIdx = afterGroup.indexOf(':');
            if (colonIdx < 0) {
                throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null,
                    "Opaque token with group scope missing key separator: " + token);
            }
            String groupId = afterGroup.substring(0, colonIdx);
            key = afterGroup.substring(colonIdx + 1);
            scope = Scope.group(groupId);
        } else if (remainder.startsWith("site:")) {
            // "site:<siteId>:<key>"
            String afterSite = remainder.substring("site:".length());
            int colonIdx = afterSite.indexOf(':');
            if (colonIdx < 0) {
                throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null,
                    "Opaque token with site scope missing key separator: " + token);
            }
            String siteId = afterSite.substring(0, colonIdx);
            key = afterSite.substring(colonIdx + 1);
            scope = Scope.site(siteId);
        } else {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null,
                "Opaque token has invalid scope prefix: " + token);
        }

        if (key.isEmpty()) {
            throw new VeridotException(ErrorCode.INVALID_ENVELOPE, null,
                "Opaque token has empty key: " + token);
        }

        return new TokenInfo(format, scope, key, token);
    }
}
