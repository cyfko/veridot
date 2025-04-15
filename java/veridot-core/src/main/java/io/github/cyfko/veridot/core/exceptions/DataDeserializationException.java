package io.github.cyfko.veridot.core.exceptions;

public class DataDeserializationException extends RuntimeException {
    public DataDeserializationException(String message) {
        super(message);
    }

    public DataDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
