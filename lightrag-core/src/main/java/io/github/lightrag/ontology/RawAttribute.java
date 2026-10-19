package io.github.lightrag.ontology;

/**
 * One raw attribute candidate produced by {@link AttributeValues#sanitizeRaw}.
 *
 * <p>Low-level record: it keeps the trimmed raw key together with a
 * structurally cleaned value, before any declared-type coercion or identity
 * de-duplication. Candidates are kept as a list (not a map) so that keys that
 * collide after trimming, such as {@code "Age"} and {@code " Age "}, both
 * survive until the duplicate ruling.
 *
 * @param rawKey the trimmed, validated raw property spelling
 * @param value  the structurally cleaned value: {@link String}, {@link Boolean},
 *               {@link Long}, {@link Double}, {@link java.math.BigDecimal}, or an
 *               immutable {@link java.util.List} of those scalar types
 */
public record RawAttribute(String rawKey, Object value) {}
