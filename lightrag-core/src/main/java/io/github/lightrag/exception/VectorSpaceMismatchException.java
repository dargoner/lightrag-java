package io.github.lightrag.exception;

/**
 * Raised when a vector write is attempted with an embedding space that differs from the space the stored
 * vectors were recorded with. Mirrors the upstream {@code VectorSpaceMismatchError}: the workspace must be
 * rebuilt with the offline rebuild tool (or the recorded space marker cleared) before writes can continue.
 */
public class VectorSpaceMismatchException extends RuntimeException {
    public VectorSpaceMismatchException(String message) {
        super(message);
    }
}
