package io.github.lightrag.api;

import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.types.ExtractionResult;

import java.util.List;

/**
 * Per-chunk validation hook invoked after parsing and gleaning, before the result is handed to the graph
 * assembly. Mirrors upstream's {@code kg_extraction_validator}: the return value replaces the extracted pair,
 * so an implementation may drop, rewrite, or augment entities and relations.
 */
@FunctionalInterface
public interface KgExtractionValidator {
    ExtractionResult validate(String chunkId, String chunkText, ExtractionResult extracted);

    /**
     * Receives the attribute diagnostics produced by the parse-time attribute binder.
     *
     * <p>The extractor invokes this method once per extraction, after
     * {@link #validate}; implementations that do not track attributes keep the
     * no-op default.
     *
     * @param diagnostics the sorted, de-duplicated diagnostics; never {@code null}
     */
    default void onAttributeDiagnostics(List<UnmatchedLabel> diagnostics) {}
}
