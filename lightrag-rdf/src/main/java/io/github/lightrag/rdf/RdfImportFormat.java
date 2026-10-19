package io.github.lightrag.rdf;

import org.eclipse.rdf4j.rio.RDFFormat;

/**
 * RDF serialization formats accepted by {@link OntologyRdfImporter}.
 *
 * <p>{@link #AUTO} enables deterministic content sniffing: JSON documents are
 * parsed as JSON-LD, XML documents as RDF/XML, everything else as Turtle. The
 * importer never falls back to a different format when parsing fails, so a
 * wrong choice is reported instead of silently accepting a partially parsed
 * document.
 */
public enum RdfImportFormat {

    /** Detect the format from the document content. */
    AUTO(null),
    /** Turtle / N-Triples style syntax. */
    TURTLE(RDFFormat.TURTLE),
    /** RDF/XML syntax. */
    RDFXML(RDFFormat.RDFXML),
    /** JSON-LD syntax. */
    JSONLD(RDFFormat.JSONLD);

    private final RDFFormat rdf4jFormat;

    RdfImportFormat(RDFFormat rdf4jFormat) {
        this.rdf4jFormat = rdf4jFormat;
    }

    RDFFormat rdf4jFormat() {
        return rdf4jFormat;
    }
}
