package io.github.cyfko.veridot.core.exceptions;

public class BrokerTransportException extends VeridotException {
    public BrokerTransportException(String message) {
        super(message);
    }

    public BrokerTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
