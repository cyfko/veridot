package io.github.cyfko.veridot.core.exceptions;

public class DataDeserializationException extends VeridotException {
    public DataDeserializationException(String message) {
        super(message);
    }

    public DataDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
