package io.github.lightrag.api;

import io.github.lightrag.types.ExtractionResult;

/**
 * Per-chunk validation hook invoked after parsing and gleaning, before the result is handed to the graph
 * assembly. Mirrors upstream's {@code kg_extraction_validator}: the return value replaces the extracted pair,
 * so an implementation may drop, rewrite, or augment entities and relations.
 */
@FunctionalInterface
public interface KgExtractionValidator {
    ExtractionResult validate(String chunkId, String chunkText, ExtractionResult extracted);
}
