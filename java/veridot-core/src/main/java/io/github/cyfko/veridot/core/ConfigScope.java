package io.github.cyfko.veridot.core;

/**
 * Scope of a distributed configuration in the Veridot protocol.
 */
public enum ConfigScope {
    /** Local scope: applies to a specific groupId. */
    LOCAL,
    /** Site scope: applies to all groups declaring a specific siteId. */
    SITE,
    /** Global scope: applies to all groups on the broker. */
    GLOBAL
}
