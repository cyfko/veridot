package io.github.cyfko.veridot.core.exceptions;

import io.github.cyfko.veridot.core.impl.ErrorCode;

/**
 * Exception thrown for any Veridot Protocol V4 violation or error.
 */
public class VeridotException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String entryId;
    public VeridotException(String message) {
        super(message);
        this.errorCode = null;
        this.entryId = null;
    }

    public VeridotException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
        this.entryId = null;
    }
    public VeridotException(ErrorCode errorCode, String entryId) {
        super("Veridot V4 error: " + errorCode.name() + " (" + errorCode.code + ")" + (entryId != null ? " for entry " + entryId : ""));
        this.errorCode = errorCode;
        this.entryId = entryId;
    }

    public VeridotException(ErrorCode errorCode, String entryId, String message) {
        super("Veridot V4 error: " + errorCode.name() + " (" + errorCode.code + ")" + (entryId != null ? " for entry " + entryId : "") + " - " + message);
        this.errorCode = errorCode;
        this.entryId = entryId;
    }

    public VeridotException(ErrorCode errorCode, String entryId, String message, Throwable cause) {
        super("Veridot V4 error: " + errorCode.name() + " (" + errorCode.code + ")" + (entryId != null ? " for entry " + entryId : "") + " - " + message, cause);
        this.errorCode = errorCode;
        this.entryId = entryId;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getEntryId() {
        return entryId;
    }
}
