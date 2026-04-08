package io.github.lightrag.exception;

public final class ModelTimeoutException extends ModelException {
    public ModelTimeoutException(String message, Throwable cause, String requestUrl) {
        super(message, null, null, requestUrl, null, cause);
    }
}
