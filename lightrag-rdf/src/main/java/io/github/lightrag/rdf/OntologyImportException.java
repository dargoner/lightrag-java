package io.github.lightrag.rdf;

/**
 * Raised when an RDF document cannot be imported safely or unambiguously.
 *
 * <p>The importer is fail-closed: resource limits, security settings,
 * unresolvable owners, conflicting declarations, and malformed input all abort
 * the import without returning a partial ontology.
 */
public class OntologyImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OntologyImportException(String message) {
        super(message);
    }

    public OntologyImportException(String message, Throwable cause) {
        super(message, cause);
    }

    public OntologyImportException(Throwable cause) {
        super(cause);
    }
}
