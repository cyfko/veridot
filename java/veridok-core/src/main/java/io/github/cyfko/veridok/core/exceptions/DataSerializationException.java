package io.github.cyfko.veridok.core.exceptions;

public class DataSerializationException extends RuntimeException {
    public DataSerializationException(String message) {
        super(message);
    }

    public DataSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
