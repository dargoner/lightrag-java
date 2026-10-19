package io.github.lightrag.rdf;

import no.hasmac.jsonld.JsonLdError;
import no.hasmac.jsonld.JsonLdErrorCode;
import no.hasmac.jsonld.document.Document;
import no.hasmac.jsonld.loader.DocumentLoader;
import no.hasmac.jsonld.loader.DocumentLoaderOptions;

import java.net.URI;
import java.util.Objects;

/**
 * JSON-LD document loader that unconditionally rejects every document fetch.
 *
 * <p>The importer is offline-only. String contexts are rejected by the preflight
 * scan before RDF4J runs, and this loader is the second line of defence: it
 * never touches the network or the local file system, so a context reference
 * that slips past the scan cannot read arbitrary files or hosts.
 */
final class SecureDocumentLoader implements DocumentLoader {

    @Override
    public Document loadDocument(URI uri, DocumentLoaderOptions options) throws JsonLdError {
        Objects.requireNonNull(uri, "uri");
        throw new JsonLdError(
            JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
            "JSON-LD document loading is disabled (offline import): " + uri
        );
    }
}
