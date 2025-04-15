package io.github.cyfko.veridot.core.exceptions;

public class BrokerExtractionException extends RuntimeException {
    public BrokerExtractionException(String message) {
        super(message);
    }
    public BrokerExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
