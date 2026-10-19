package io.github.lightrag.ontology.bootstrap;

import io.github.lightrag.ontology.Ontology;

/**
 * Options controlling deterministic ontology bootstrap.
 *
 * <p>All numeric limits must be positive. Invalid options are rejected at
 * construction time rather than being silently clamped.
 *
 * @param minOccurrences      minimum number of deduplicated samples for a type,
 *                            relation type, or pattern (default 2)
 * @param minDocuments        minimum number of distinct documents (default 1)
 * @param minAttributeSupport minimum number of distinct deduplication keys
 *                            containing an attribute (default 1)
 * @param includeAttributes   whether inferred properties are written to the
 *                            draft; base definitions are always preserved
 * @param maxSamples          maximum total number of input samples; exceeding
 *                            it rejects the run instead of truncating
 * @param baseOntology        optional ontology whose complete contents are the
 *                            starting point of the draft
 */
public record BootstrapOptions(
    int minOccurrences,
    int minDocuments,
    int minAttributeSupport,
    boolean includeAttributes,
    int maxSamples,
    Ontology baseOntology
) {
    /** Default frequency gate for types, relation types, and patterns. */
    public static final int DEFAULT_MIN_OCCURRENCES = 2;

    /** Default document gate. */
    public static final int DEFAULT_MIN_DOCUMENTS = 1;

    /** Default attribute support gate. */
    public static final int DEFAULT_MIN_ATTRIBUTE_SUPPORT = 1;

    /** Default maximum number of samples accepted by one run. */
    public static final int DEFAULT_MAX_SAMPLES = 200_000;

    public BootstrapOptions {
        requirePositive(minOccurrences, "minOccurrences");
        requirePositive(minDocuments, "minDocuments");
        requirePositive(minAttributeSupport, "minAttributeSupport");
        requirePositive(maxSamples, "maxSamples");
    }

    /** Creates options with the documented defaults: 2/1/1/true/200000/null. */
    public BootstrapOptions() {
        this(
            DEFAULT_MIN_OCCURRENCES,
            DEFAULT_MIN_DOCUMENTS,
            DEFAULT_MIN_ATTRIBUTE_SUPPORT,
            true,
            DEFAULT_MAX_SAMPLES,
            null
        );
    }

    /** Returns a fresh instance of the default options. */
    public static BootstrapOptions defaults() {
        return new BootstrapOptions();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
