package io.github.lightrag.ontology.rdf;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Options controlling the deterministic Turtle/OWL/SHACL export of an
 * {@link io.github.lightrag.ontology.Ontology}.
 *
 * <p>The options are immutable. Use {@link #builder(String)} for a fluent
 * configuration:
 *
 * <pre>{@code
 * RdfExportOptions options = RdfExportOptions.builder("https://example.com/ontology")
 *     .includeShacl(true)
 *     .includeVersion(true)
 *     .build();
 * }</pre>
 *
 * <p>{@code ontologyIri} and {@code vocabularyBase} must be absolute IRIs.
 * {@code vocabularyBase} (and the optional {@code shapesBase}) must end with
 * {@code '/'}; all generated IRIs are strict percent-encoded path segments
 * below that base.
 *
 * @param ontologyIri           absolute IRI identifying the ontology document
 * @param vocabularyBase        absolute namespace IRI for generated vocabulary
 *                              terms; must end with {@code '/'}
 * @param shapesBase            absolute namespace IRI for SHACL shapes; must
 *                              end with {@code '/'}
 * @param includeShacl          whether {@link OntologyRdfExporter#export}
 *                              appends SHACL shapes
 * @param includeVersion        whether the ontology header carries version and
 *                              definition-hash metadata
 * @param includeSourceBindings whether {@code lg:SourceBinding} nodes are
 *                              emitted
 * @param prefix                prefix used for LightRAG extension terms;
 *                              defaults to {@code lg}
 */
public record RdfExportOptions(
    String ontologyIri,
    String vocabularyBase,
    String shapesBase,
    boolean includeShacl,
    boolean includeVersion,
    boolean includeSourceBindings,
    String prefix
) {

    /** Default prefix for LightRAG extension terms. */
    public static final String DEFAULT_PREFIX = "lg";

    private static final Pattern PREFIX_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Set<String> BUILT_IN_PREFIXES = Set.of("rdf", "rdfs", "owl", "xsd", "sh");

    public RdfExportOptions {
        ontologyIri = RdfIriEncoder.requireAbsoluteIri(ontologyIri, "ontologyIri");
        vocabularyBase = vocabularyBase == null
            ? deriveVocabularyBase(ontologyIri)
            : RdfIriEncoder.requireAbsoluteIri(vocabularyBase, "vocabularyBase");
        requireTrailingSlash(vocabularyBase, "vocabularyBase");

        shapesBase = shapesBase == null
            ? vocabularyBase + "shapes/"
            : RdfIriEncoder.requireAbsoluteIri(shapesBase, "shapesBase");
        requireTrailingSlash(shapesBase, "shapesBase");

        prefix = prefix == null ? DEFAULT_PREFIX : prefix;
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty");
        }
        if (!PREFIX_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                "prefix must match [A-Za-z_][A-Za-z0-9_.-]*: " + prefix
            );
        }
        if (BUILT_IN_PREFIXES.contains(prefix) || "a".equals(prefix)) {
            throw new IllegalArgumentException("prefix collides with a built-in Turtle prefix: " + prefix);
        }
    }

    /** Returns the default vocabulary namespace derived from {@code ontologyIri}. */
    public static String deriveVocabularyBase(String ontologyIri) {
        String iri = RdfIriEncoder.requireAbsoluteIri(ontologyIri, "ontologyIri");
        int end = iri.length();
        while (end > 0) {
            char c = iri.charAt(end - 1);
            if (c == '/' || c == '#') {
                end--;
            } else {
                break;
            }
        }
        if (end == 0) {
            throw new IllegalArgumentException("ontologyIri must not consist only of a fragment separator");
        }
        return iri.substring(0, end) + "/";
    }

    /** Returns options with all documented defaults. */
    public static RdfExportOptions defaults(String ontologyIri) {
        return new RdfExportOptions(ontologyIri, null, null, true, true, true, DEFAULT_PREFIX);
    }

    /** Creates a builder seeded with {@code ontologyIri}. */
    public static Builder builder(String ontologyIri) {
        return new Builder(ontologyIri);
    }

    /** Returns a builder initialised from these options. */
    public Builder toBuilder() {
        return new Builder(ontologyIri)
            .vocabularyBase(vocabularyBase)
            .shapesBase(shapesBase)
            .includeShacl(includeShacl)
            .includeVersion(includeVersion)
            .includeSourceBindings(includeSourceBindings)
            .prefix(prefix);
    }

    /** Alias for {@link #vocabularyBase()} matching the plan wording. */
    public String vocabularyBaseIri() {
        return vocabularyBase;
    }

    /** Alias for {@link #shapesBase()} matching the plan wording. */
    public String shapesBaseIri() {
        return shapesBase;
    }

    /**
     * Convenience constructor using the derived vocabulary and shapes bases.
     */
    public RdfExportOptions(String ontologyIri) {
        this(ontologyIri, null, null, true, true, true, DEFAULT_PREFIX);
    }

    /**
     * Convenience constructor for the commonly used option subset.
     */
    public RdfExportOptions(
        String ontologyIri,
        String vocabularyBase,
        boolean includeShacl,
        boolean includeVersion,
        String prefix
    ) {
        this(ontologyIri, vocabularyBase, null, includeShacl, includeVersion, true, prefix);
    }

    /**
     * Convenience constructor matching the plan's option subset (SHACL is
     * included by default).
     */
    public RdfExportOptions(
        String ontologyIri,
        String vocabularyBase,
        String shapesBase,
        boolean includeSourceBindings,
        boolean includeVersion
    ) {
        this(
            ontologyIri,
            vocabularyBase,
            shapesBase,
            true,
            includeVersion,
            includeSourceBindings,
            DEFAULT_PREFIX
        );
    }

    private static void requireTrailingSlash(String value, String field) {
        if (!value.endsWith("/")) {
            throw new IllegalArgumentException(field + " must end with '/': " + value);
        }
    }

    /** Immutable builder for {@link RdfExportOptions}. */
    public static final class Builder {

        private final String ontologyIri;
        private String vocabularyBase;
        private String shapesBase;
        private boolean includeShacl = true;
        private boolean includeVersion = true;
        private boolean includeSourceBindings = true;
        private String prefix = DEFAULT_PREFIX;

        private Builder(String ontologyIri) {
            this.ontologyIri = ontologyIri;
        }

        public Builder vocabularyBase(String vocabularyBase) {
            this.vocabularyBase = vocabularyBase;
            return this;
        }

        /** Alias for {@link #vocabularyBase(String)} matching the plan wording. */
        public Builder vocabularyBaseIri(String vocabularyBase) {
            return vocabularyBase(vocabularyBase);
        }

        public Builder shapesBase(String shapesBase) {
            this.shapesBase = shapesBase;
            return this;
        }

        /** Alias for {@link #shapesBase(String)} matching the plan wording. */
        public Builder shapesBaseIri(String shapesBase) {
            return shapesBase(shapesBase);
        }

        public Builder includeShacl(boolean includeShacl) {
            this.includeShacl = includeShacl;
            return this;
        }

        public Builder includeVersion(boolean includeVersion) {
            this.includeVersion = includeVersion;
            return this;
        }

        public Builder includeSourceBindings(boolean includeSourceBindings) {
            this.includeSourceBindings = includeSourceBindings;
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public RdfExportOptions build() {
            return new RdfExportOptions(
                ontologyIri,
                vocabularyBase,
                shapesBase,
                includeShacl,
                includeVersion,
                includeSourceBindings,
                prefix
            );
        }
    }

    @Override
    public String toString() {
        return "RdfExportOptions[ontologyIri=" + ontologyIri
            + ", vocabularyBase=" + vocabularyBase
            + ", shapesBase=" + shapesBase
            + ", includeShacl=" + includeShacl
            + ", includeVersion=" + includeVersion
            + ", includeSourceBindings=" + includeSourceBindings
            + ", prefix=" + prefix
            + ']';
    }

}
