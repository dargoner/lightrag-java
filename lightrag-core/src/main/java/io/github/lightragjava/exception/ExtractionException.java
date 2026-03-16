package io.github.lightragjava.exception;

public class ExtractionException extends RuntimeException {
    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
