package io.github.cyfko.veridot.trustroots.api.exception;

/**
 * Exception levée en cas d'erreur technique d'un provider distant.
 */
public class TrustRootProviderException extends RuntimeException {
    public TrustRootProviderException(String message) {
        super(message);
    }

    public TrustRootProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
