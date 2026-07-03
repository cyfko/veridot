package io.github.cyfko.veridot.trustroots.api.exception;

/**
 * Exception checked levée en cas d'échec irrécupérable de l'initialisation du cache.
 */
public class TrustRootInitializationException extends Exception {
    public TrustRootInitializationException(String message) {
        super(message);
    }

    public TrustRootInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
