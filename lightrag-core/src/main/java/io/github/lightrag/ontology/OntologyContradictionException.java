package io.github.lightrag.ontology;

/**
 * Exception thrown when an ontology candidate contradicts a base ontology.
 */
public class OntologyContradictionException extends RuntimeException {

    public OntologyContradictionException(String message) {
        super(message);
    }

    public OntologyContradictionException(String message, Throwable cause) {
        super(message, cause);
    }
}
