package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.nio.charset.StandardCharsets;

/**
 * Value type representing a Config/Entry Scope (§3.5).
 */
public record Scope(String value) {

    public Scope {
        validate(value);
    }

    public static Scope group(String groupId) {
        if (groupId == null || groupId.isEmpty()) {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, "Group ID cannot be null or empty");
        }
        return new Scope("group:" + groupId);
    }

    public static Scope site(String siteId) {
        if (siteId == null || siteId.isEmpty()) {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, "Site ID cannot be null or empty");
        }
        return new Scope("site:" + siteId);
    }

    public static Scope global() {
        return new Scope("global");
    }

    public static Scope parse(String raw) {
        return new Scope(raw);
    }

    public boolean isGroup() {
        return value.startsWith("group:");
    }

    public boolean isSite() {
        return value.startsWith("site:");
    }

    public boolean isGlobal() {
        return "global".equals(value);
    }

    public String groupId() {
        if (!isGroup()) {
            throw new IllegalStateException("Scope is not of type group");
        }
        return value.substring(6);
    }

    public String siteId() {
        if (!isSite()) {
            throw new IllegalStateException("Scope is not of type site");
        }
        return value.substring(5);
    }

    private static void validate(String raw) {
        if (raw == null) {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, "Scope raw value cannot be null");
        }

        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > 4096) {
            throw new VeridotException(ErrorCode.INVALID_IDENTIFIER_LENGTH, null, 
                "Scope length must be 1 to 4096 bytes. Got: " + bytes.length);
        }

        if ("global".equals(raw)) {
            return;
        }

        String id;
        if (raw.startsWith("group:")) {
            id = raw.substring(6);
        } else if (raw.startsWith("site:")) {
            id = raw.substring(5);
        } else {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, 
                "Scope must be 'global', 'group:<id>', or 'site:<id>'. Got: " + raw);
        }

        // Validate identifier-char
        // 1*125(identifier-char)
        if (id.isEmpty() || id.length() > 125) {
            throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, 
                "Scope identifier part length must be 1 to 125 characters. Got: " + id.length());
        }

        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == 0x00 || (c >= 0x01 && c <= 0x1F) || c == ':') {
                throw new VeridotException(ErrorCode.INVALID_SCOPE_GRAMMAR, null, 
                    "Scope identifier contains invalid character: " + String.format("0x%02X", (int) c));
            }
        }
    }
}
