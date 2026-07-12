package io.github.cyfko.veridot.core.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PatternMatcher} — V5 §9.2 unified pattern matching.
 */
class PatternMatcherTest {

    // ── Wildcard "*" ─────────────────────────────────────────────────

    @Test
    void wildcard_matchesAnything() {
        assertTrue(PatternMatcher.matches("*", "anything"));
        assertTrue(PatternMatcher.matches("*", ""));
        assertTrue(PatternMatcher.matches("*", "deeply:nested:value"));
    }

    // ── Prefix wildcard "prefix*" ────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "orders*, orders-svc, true",
        "orders*, orders, true",
        "orders*, orders-anything, true",
        "orders*, order, false",
        "orders*, payments-svc, false",
        "a*, abc, true",
        "a*, a, true",
        "a*, b, false",
    })
    void prefixWildcard_matchesCorrectly(String pattern, String value, boolean expected) {
        assertEquals(expected, PatternMatcher.matches(pattern, value));
    }

    // ── Hierarchical wildcard "prefix:*" ─────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "orders:*, orders, true",
        "orders:*, orders:create, true",
        "orders:*, orders:delete, true",
        "orders:*, orders:sub:deep, true",
        "orders:*, ordersX, false",
        "orders:*, order, false",
        "group:*, group, true",
        "group:*, group:alpha, true",
        "group:*, group:alpha:beta, true",
    })
    void hierarchicalWildcard_matchesCorrectly(String pattern, String value, boolean expected) {
        assertEquals(expected, PatternMatcher.matches(pattern, value));
    }

    // ── Exact match ──────────────────────────────────────────────────

    @Test
    void exactMatch_matchesOnlyExact() {
        assertTrue(PatternMatcher.matches("orders", "orders"));
        assertFalse(PatternMatcher.matches("orders", "orders-svc"));
        assertFalse(PatternMatcher.matches("orders", "order"));
        assertFalse(PatternMatcher.matches("orders", ""));
    }

    // ── Edge cases: null and empty ───────────────────────────────────

    @Test
    void nullPattern_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PatternMatcher.matches(null, "value"));
    }

    @Test
    void nullValue_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> PatternMatcher.matches("*", null));
    }

    @Test
    void emptyPattern_exactMatchOnlyEmpty() {
        assertTrue(PatternMatcher.matches("", ""));
        assertFalse(PatternMatcher.matches("", "anything"));
    }

    @Test
    void emptyValue_matchesWildcardOnly() {
        assertTrue(PatternMatcher.matches("*", ""));
        assertFalse(PatternMatcher.matches("prefix*", ""));
    }

    // ── matchesAny() ─────────────────────────────────────────────────

    @Test
    void matchesAny_trueIfAtLeastOnePatternMatches() {
        assertTrue(PatternMatcher.matchesAny(List.of("orders:*", "payments:*"), "orders:create"));
        assertTrue(PatternMatcher.matchesAny(List.of("orders:*", "payments:*"), "payments:refund"));
    }

    @Test
    void matchesAny_falseIfNoneMatch() {
        assertFalse(PatternMatcher.matchesAny(List.of("orders:*", "payments:*"), "users:list"));
    }

    @Test
    void matchesAny_nullPatternsReturnsFalse() {
        assertFalse(PatternMatcher.matchesAny(null, "anything"));
    }

    @Test
    void matchesAny_emptyListReturnsFalse() {
        assertFalse(PatternMatcher.matchesAny(List.of(), "anything"));
    }
}
