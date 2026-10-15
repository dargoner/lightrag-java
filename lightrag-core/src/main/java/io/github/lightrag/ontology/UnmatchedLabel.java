package io.github.lightrag.ontology;

/**
 * Callback record for an entity or relation label that did not conform to the
 * active ontology.
 */
public record UnmatchedLabel(
    Kind kind,
    String rawLabel,
    String chunkId,
    Reason reason
) {
    public enum Kind {
        ENTITY,
        RELATION,
        RELATION_KEYWORD
    }

    public enum Reason {
        UNKNOWN_ENTITY_TYPE,
        UNKNOWN_RELATION_TYPE,
        PATTERN_MISMATCH,
        UNKNOWN_RELATION_KEYWORD,
        DANGLING_ENDPOINT
    }
}
