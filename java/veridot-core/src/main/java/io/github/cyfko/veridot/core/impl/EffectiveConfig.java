package io.github.cyfko.veridot.core.impl;

/**
 * Resolved configuration for a group, obtained from the broker's config hierarchy
 * (local → site → global → constructor default) as defined in Protocol V3 §4.
 *
 * @param maxSessions maximum concurrent active sequences per group; {@code -1} means unlimited
 * @param policy      eviction policy applied when {@code maxSessions} is reached
 * @param defaultTTL  default TTL in seconds for new sequences; {@code -1} means no default
 */
record EffectiveConfig(int maxSessions, GenericSignerVerifier.EvictionPolicy policy, long defaultTTL) {}
