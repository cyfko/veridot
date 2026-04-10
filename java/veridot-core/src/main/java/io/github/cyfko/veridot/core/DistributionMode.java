package io.github.cyfko.veridot.core;

/**
 * Defines how a signed token is delivered to the caller.
 *
 * <ul>
 *   <li>{@link #DIRECT} — The signed JWT is returned directly to the caller.</li>
 *   <li>{@link #INDIRECT} — A {@code messageId} is returned; the signed JWT is stored
 *       in the broker and retrieved during verification.</li>
 * </ul>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 */
public enum DistributionMode {
    DIRECT,
    INDIRECT
}
