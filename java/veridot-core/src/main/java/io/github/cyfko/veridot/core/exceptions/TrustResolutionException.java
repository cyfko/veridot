package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when a {@link io.github.cyfko.veridot.core.TrustAnchor} fails to resolve or
 * verify an identity.
 *
 * <p>Two sealed subtypes model the two distinct failure modes:
 * <ul>
 *   <li>{@link Unavailable} — transient infrastructure failure (KMS unreachable, network
 *       timeout). This must <em>never</em> be silently treated as "not verified therefore
 *       accepted". Retry / alert infra.</li>
 *   <li>{@link SignatureRejected} — definitive cryptographic rejection. The announcement
 *       signature does not match the claimed identity. This is a potential security event
 *       and must be logged/alerted separately from {@code Unavailable}.</li>
 * </ul>
 *
 * <p>This exception is checked so that callers are forced by the compiler to handle both
 * failure paths explicitly — the whole point of the sealed hierarchy is to make it
 * impossible to accidentally swallow a {@code SignatureRejected} as if it were an
 * {@code Unavailable}.</p>
 *
 * @author Frank KOSSI
 * @since 3.0.0
 */
public sealed class TrustResolutionException extends Exception
        permits TrustResolutionException.Unavailable, TrustResolutionException.SignatureRejected {

    /**
     * Constructs a new {@code TrustResolutionException} with the given message.
     *
     * @param message a human-readable description of the failure
     */
    protected TrustResolutionException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code TrustResolutionException} with the given message and cause.
     *
     * @param message a human-readable description of the failure
     * @param cause   the underlying exception that triggered this failure; may be {@code null}
     */
    protected TrustResolutionException(String message, Throwable cause) {
        super(message, cause);
    }

    // ── Subtypes ──────────────────────────────────────────────────────────────

    /**
     * Transient failure: the trust anchor infrastructure (KMS, HSM, remote service) was
     * unreachable or timed out.
     *
     * <p>This must <strong>never</strong> be interpreted as "could not verify → accept anyway".
     * The only correct responses are: fail the operation, retry with back-off, or raise an
     * infrastructure alert.</p>
     */
    public static final class Unavailable extends TrustResolutionException {

        /**
         * Constructs an {@code Unavailable} failure with the given detail message.
         *
         * @param message description of why the trust anchor was unreachable
         */
        public Unavailable(String message) {
            super(message);
        }

        /**
         * Constructs an {@code Unavailable} failure with the given message and cause.
         *
         * @param message description of why the trust anchor was unreachable
         * @param cause   underlying infrastructure exception
         */
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Definitive failure: the announcement signature does not match the identity claimed
     * by {@code signerId}.
     *
     * <p>This is a <strong>security event</strong> — not a routine infrastructure error.
     * It indicates a potential forgery attempt and must be logged with higher severity than
     * {@link Unavailable}, and ideally trigger a security alert in the monitoring stack.</p>
     */
    public static final class SignatureRejected extends TrustResolutionException {

        /**
         * Constructs a {@code SignatureRejected} failure with the given detail message.
         *
         * @param message description of why the signature was rejected
         */
        public SignatureRejected(String message) {
            super(message);
        }

        /**
         * Constructs a {@code SignatureRejected} failure with the given message and cause.
         *
         * @param message description of why the signature was rejected
         * @param cause   underlying cryptographic exception (e.g., {@link java.security.SignatureException})
         */
        public SignatureRejected(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
