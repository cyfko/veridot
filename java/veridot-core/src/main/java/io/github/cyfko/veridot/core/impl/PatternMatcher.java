package io.github.cyfko.veridot.core.impl;

/**
 * Unified pattern matching for subject identifiers and scope patterns (§9.2).
 *
 * <p>Supports the following pattern forms:
 * <ul>
 *   <li>{@code "*"} — matches everything</li>
 *   <li>{@code "prefix*"} — matches any value starting with {@code prefix}</li>
 *   <li>{@code "prefix:*"} — matches {@code prefix} exactly, or any value starting with {@code prefix:}</li>
 *   <li>{@code "exact"} — exact match only</li>
 * </ul>
 *
 * <p>Used by both {@code CapabilityPayload.covers()} for scope patterns (§9.2.1)
 * and {@code subjectPattern} matching (§9.2.2).
 */
public final class PatternMatcher {

    private PatternMatcher() {} // non-instantiable

    /**
     * Tests whether a value matches a pattern.
     *
     * @param pattern the pattern to match against
     * @param value the value to test
     * @return true if the value matches the pattern
     * @throws IllegalArgumentException if pattern or value is null
     */
    public static boolean matches(String pattern, String value) {
        if (pattern == null) throw new IllegalArgumentException("pattern must not be null");
        if (value == null) throw new IllegalArgumentException("value must not be null");

        // Wildcard: matches everything
        if ("*".equals(pattern)) {
            return true;
        }

        // Hierarchical wildcard: "prefix:*" matches "prefix" or "prefix:anything"
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return value.equals(prefix) || value.startsWith(prefix + ":");
        }

        // Prefix wildcard: "prefix*" matches any value starting with "prefix"
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return value.startsWith(prefix);
        }

        // Exact match
        return value.equals(pattern);
    }

    /**
     * Tests whether a value matches any pattern in the list.
     *
     * @param patterns the list of patterns
     * @param value the value to test
     * @return true if the value matches at least one pattern
     */
    public static boolean matchesAny(Iterable<String> patterns, String value) {
        if (patterns == null) return false;
        for (String pattern : patterns) {
            if (matches(pattern, value)) {
                return true;
            }
        }
        return false;
    }
}
