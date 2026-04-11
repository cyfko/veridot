package io.github.cyfko.veridot.core.exceptions;

/**
 * Root exception for all Veridot-specific errors.
 * <p>
 * Callers can catch this single type to handle any Veridot error uniformly,
 * or catch specific subclasses for fine-grained error handling.
 * </p>
 */
public class VeridotException extends RuntimeException {
    public VeridotException(String message) {
        super(message);
    }
    public VeridotException(String message, Throwable cause) {
        super(message, cause);
    }
}
