package io.github.lightrag.ontology;

/**
 * Callback record for an entity or relation label that did not conform to the
 * active ontology.
 *
 * <p>{@code ownerLabel} carries the canonical owner identity for attribute
 * diagnostics - the entity type or relation type identity that owns the
 * declaration. It is empty for label diagnostics and whenever no owner can be
 * resolved. The field is intentionally kept verbatim (never replaced by a
 * sentinel); consumers that sort or de-duplicate diagnostics must use a
 * whitespace-safe normalization such as
 * {@link OntologyAttributeBinder#diagnosticSortKey(String)}.
 */
public record UnmatchedLabel(
    Kind kind,
    String rawLabel,
    String chunkId,
    Reason reason,
    String ownerLabel
) {
    public UnmatchedLabel {
        ownerLabel = ownerLabel == null ? "" : ownerLabel;
    }

    /**
     * Compatibility constructor for label diagnostics that have no owner.
     *
     * @param kind     the diagnostic kind
     * @param rawLabel the raw label reported by the extractor
     * @param chunkId  the chunk the label came from
     * @param reason   the diagnostic reason
     */
    public UnmatchedLabel(Kind kind, String rawLabel, String chunkId, Reason reason) {
        this(kind, rawLabel, chunkId, reason, "");
    }

    public enum Kind {
        ENTITY,
        RELATION,
        RELATION_KEYWORD,
        ATTRIBUTE
    }

    public enum Reason {
        UNKNOWN_ENTITY_TYPE,
        UNKNOWN_RELATION_TYPE,
        PATTERN_MISMATCH,
        UNKNOWN_RELATION_KEYWORD,
        DANGLING_ENDPOINT,
        UNDECLARED_ATTRIBUTE,
        INVALID_ATTRIBUTE_KEY,
        INVALID_ATTRIBUTE_VALUE,
        ATTRIBUTE_TYPE_CONFLICT,
        ATTRIBUTE_LIMIT_EXCEEDED,
        ATTRIBUTE_DUPLICATE_KEY
    }
}
