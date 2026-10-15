package io.github.lightrag.ontology;

/**
 * Exception thrown when a requested ontology modification is not allowed.
 */
public class OntologyModificationNotAllowedException extends RuntimeException {

    public OntologyModificationNotAllowedException(String message) {
        super(message);
    }

    public OntologyModificationNotAllowedException(String message, Throwable cause) {
        super(message, cause);
    }
}
