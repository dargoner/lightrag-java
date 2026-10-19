package io.github.lightrag.ontology.rdf;

/**
 * Raised when an ontology cannot be exported safely.
 *
 * <p>Typical causes are conflicting declarations for the same
 * {@code (kind, ownerIdentity, propertyIdentity)} tuple, oversized IRIs or
 * literals, and a generated IRI that collides with the ontology IRI. The
 * exporter fails closed instead of silently choosing one declaration.
 */
public class OntologyRdfExportException extends RuntimeException {

    public OntologyRdfExportException(String message) {
        super(message);
    }

    public OntologyRdfExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
