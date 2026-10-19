package io.github.lightrag.rdf;

/**
 * Immutable options for {@link OntologyRdfImporter}.
 *
 * <p>All limits must be positive and are enforced fail-closed: exceeding a
 * limit aborts the import with {@link OntologyImportException}, no partial
 * result is returned. The importer never resolves remote documents; JSON-LD
 * remote contexts are rejected before parsing and again by the injected
 * {@code SecureDocumentLoader}.
 *
 * <p>Use {@link #defaults()} or {@link #builder()} for construction.
 */
public record RdfImportOptions(
    RdfImportFormat format,
    String baseIri,
    boolean includeSourceBindings,
    boolean allowUnknownAxioms,
    long maxBytes,
    int maxTriples,
    int maxDepth,
    int maxNodes,
    int maxLiteralBytes,
    int maxIriBytes,
    String extensionNamespace
) {

    /** Default input limit: 10 MiB of UTF-8 content. */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    /** Default statement limit. */
    public static final int DEFAULT_MAX_TRIPLES = 200_000;
    /** Default depth limit for RDF list / union / owner traversal. */
    public static final int DEFAULT_MAX_DEPTH = 32;
    /** Default node limit for RDF list / union / owner traversal. */
    public static final int DEFAULT_MAX_NODES = 10_000;
    /** Default literal limit: 64 KiB of UTF-8 bytes. */
    public static final int DEFAULT_MAX_LITERAL_BYTES = 64 * 1024;
    /** Default IRI limit: 8 KiB of UTF-8 bytes. */
    public static final int DEFAULT_MAX_IRI_BYTES = 8 * 1024;

    public RdfImportOptions {
        format = format == null ? RdfImportFormat.AUTO : format;
        baseIri = baseIri == null ? null : RdfIriSupport.requireAbsoluteIri(baseIri, "baseIri");
        extensionNamespace = extensionNamespace == null
            ? null
            : RdfIriSupport.requireExtensionNamespace(extensionNamespace);
        requirePositive(maxBytes, "maxBytes");
        requirePositive(maxTriples, "maxTriples");
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxNodes, "maxNodes");
        requirePositive(maxLiteralBytes, "maxLiteralBytes");
        requirePositive(maxIriBytes, "maxIriBytes");
    }

    /** Returns the documented defaults. */
    public static RdfImportOptions defaults() {
        return builder().build();
    }

    /** Creates a builder seeded with the documented defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder initialised from these options. */
    public Builder toBuilder() {
        return new Builder()
            .format(format)
            .baseIri(baseIri)
            .includeSourceBindings(includeSourceBindings)
            .allowUnknownAxioms(allowUnknownAxioms)
            .maxBytes(maxBytes)
            .maxTriples(maxTriples)
            .maxDepth(maxDepth)
            .maxNodes(maxNodes)
            .maxLiteralBytes(maxLiteralBytes)
            .maxIriBytes(maxIriBytes)
            .extensionNamespace(extensionNamespace);
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive: " + value);
        }
    }

    /** Immutable builder for {@link RdfImportOptions}. */
    public static final class Builder {

        private RdfImportFormat format = RdfImportFormat.AUTO;
        private String baseIri;
        private boolean includeSourceBindings = true;
        private boolean allowUnknownAxioms = true;
        private long maxBytes = DEFAULT_MAX_BYTES;
        private int maxTriples = DEFAULT_MAX_TRIPLES;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxNodes = DEFAULT_MAX_NODES;
        private int maxLiteralBytes = DEFAULT_MAX_LITERAL_BYTES;
        private int maxIriBytes = DEFAULT_MAX_IRI_BYTES;
        private String extensionNamespace;

        public Builder format(RdfImportFormat format) {
            this.format = format;
            return this;
        }

        public Builder baseIri(String baseIri) {
            this.baseIri = baseIri;
            return this;
        }

        public Builder includeSourceBindings(boolean includeSourceBindings) {
            this.includeSourceBindings = includeSourceBindings;
            return this;
        }

        public Builder allowUnknownAxioms(boolean allowUnknownAxioms) {
            this.allowUnknownAxioms = allowUnknownAxioms;
            return this;
        }

        public Builder maxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        public Builder maxTriples(int maxTriples) {
            this.maxTriples = maxTriples;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public Builder maxNodes(int maxNodes) {
            this.maxNodes = maxNodes;
            return this;
        }

        public Builder maxLiteralBytes(int maxLiteralBytes) {
            this.maxLiteralBytes = maxLiteralBytes;
            return this;
        }

        public Builder maxIriBytes(int maxIriBytes) {
            this.maxIriBytes = maxIriBytes;
            return this;
        }

        public Builder extensionNamespace(String extensionNamespace) {
            this.extensionNamespace = extensionNamespace;
            return this;
        }

        public RdfImportOptions build() {
            return new RdfImportOptions(
                format,
                baseIri,
                includeSourceBindings,
                allowUnknownAxioms,
                maxBytes,
                maxTriples,
                maxDepth,
                maxNodes,
                maxLiteralBytes,
                maxIriBytes,
                extensionNamespace
            );
        }
    }
}
