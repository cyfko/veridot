package io.github.cyfko.veridot.trustroots.api.exception;

/**
 * Exception levée lorsque la signature d'une TrustEntry est invalide.
 */
public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException(String message) {
        super(message);
    }

    public InvalidSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
