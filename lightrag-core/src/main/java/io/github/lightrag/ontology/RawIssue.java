package io.github.lightrag.ontology;

/**
 * One structural issue detected by {@link AttributeValues#sanitizeRaw}.
 *
 * <p>Low-level record: the issue is not yet tied to a chunk or an owner. The
 * {@link OntologyAttributeBinder} maps it to a full {@link UnmatchedLabel}
 * once the owner and chunk are known.
 *
 * @param rawKey the trimmed raw key, or an empty string when the issue applies
 *               to the whole attributes object (for example
 *               {@link UnmatchedLabel.Reason#ATTRIBUTE_LIMIT_EXCEEDED} or a
 *               non-object JSON value)
 * @param reason the structural reason
 */
public record RawIssue(String rawKey, UnmatchedLabel.Reason reason) {}
