package io.github.lightrag.ontology;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Value-level primitives for materialized ontology attributes.
 *
 * <p>Processing is split into two deliberately separate stages:
 *
 * <ol>
 *   <li>{@link #sanitizeRaw} only enforces the structural DoS limits: at most
 *       {@value #MAX_RAW_CANDIDATES} raw candidates per record, keys trimmed to
 *       at most {@value #MAX_KEY_LENGTH} characters without control characters,
 *       and raw scalar strings of at most {@value #MAX_RAW_STRING_BYTES} UTF-8
 *       bytes. It keeps every pair as a candidate - never a map - so keys that
 *       collide after trimming still reach the duplicate ruling. It does
 *       <em>not</em> apply the semantic caps.</li>
 *   <li>{@link #coerce} applies the declared type, and {@link #applyCaps}
 *       enforces the semantic bag limits after coercion and duplicate ruling.</li>
 * </ol>
 *
 * <p>Numbers are canonicalized without a {@code double} detour: integer inputs
 * are truncated toward zero through {@link BigDecimal}, and magnitudes above
 * 2^53 are rejected because they cannot be represented exactly as a
 * {@code double} elsewhere in the pipeline.
 */
public final class AttributeValues {

    /** Maximum number of raw key/value pairs accepted from one attributes object. */
    public static final int MAX_RAW_CANDIDATES = 256;

    /** Maximum raw key length in characters after trimming. */
    public static final int MAX_KEY_LENGTH = 128;

    /** Maximum UTF-8 byte length of one raw scalar string. */
    public static final int MAX_RAW_STRING_BYTES = 64 * 1024;

    /** Maximum number of materialized keys per record. */
    public static final int MAX_KEYS = 64;

    /** Maximum canonical JSON size per record, measured in UTF-8 bytes. */
    public static final int MAX_CANONICAL_JSON_BYTES = 16 * 1024;

    /** Maximum number of elements in a materialized LIST value. */
    public static final int MAX_LIST_ELEMENTS = 64;

    /** Maximum character length of a materialized STRING value. */
    public static final int MAX_STRING_LENGTH = 4096;

    /** Maximum character length of one STRING element inside a LIST value. */
    public static final int MAX_LIST_ELEMENT_STRING_LENGTH = 1024;

    private static final BigDecimal MAX_INTEGER_MAGNITUDE =
        new BigDecimal(BigInteger.ONE.shiftLeft(53));

    private static final String EMPTY = "";

    private AttributeValues() {}

    /**
     * Structurally sanitizes an extraction {@code attributes} JSON node.
     *
     * <p>A missing or JSON {@code null} node yields an empty result. Any other
     * non-object node is rejected as one whole-object
     * {@link UnmatchedLabel.Reason#INVALID_ATTRIBUTE_VALUE} issue. More than
     * {@value #MAX_RAW_CANDIDATES} members discard the whole object and produce
     * a single {@link UnmatchedLabel.Reason#ATTRIBUTE_LIMIT_EXCEEDED} issue.
     *
     * @param attributesNode the JSON {@code attributes} node; may be {@code null}
     * @return the surviving candidates and structural issues
     */
    public static RawAttributes sanitizeRaw(JsonNode attributesNode) {
        if (attributesNode == null || attributesNode.isNull() || attributesNode.isMissingNode()) {
            return new RawAttributes(List.of(), List.of());
        }
        if (!attributesNode.isObject()) {
            return new RawAttributes(
                List.of(),
                List.of(new RawIssue(EMPTY, UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE))
            );
        }
        if (attributesNode.size() > MAX_RAW_CANDIDATES) {
            return limitExceeded();
        }

        List<RawAttribute> candidates = new ArrayList<>();
        List<RawIssue> issues = new ArrayList<>();
        var fields = attributesNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String rawKey = normalizeRawKey(entry.getKey());
            if (!isValidKey(rawKey)) {
                issues.add(new RawIssue(rawKey, UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY));
                continue;
            }
            Object value;
            try {
                value = cleanJsonValue(entry.getValue());
            } catch (IllegalArgumentException ex) {
                issues.add(new RawIssue(rawKey, UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE));
                continue;
            }
            if (value == null) {
                // JSON null means "not provided"; it is not materialized.
                continue;
            }
            candidates.add(new RawAttribute(rawKey, value));
        }
        return new RawAttributes(candidates, issues);
    }

    /**
     * Structurally sanitizes an attribute map, for example a bootstrap sample or
     * a host-supplied map.
     *
     * <p>All entries are kept as candidates, including entries whose keys
     * collide after trimming. The same structural limits as
     * {@link #sanitizeRaw(JsonNode)} apply.
     *
     * @param attributes the raw attributes; may be {@code null}
     * @return the surviving candidates and structural issues
     */
    public static RawAttributes sanitizeRaw(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return new RawAttributes(List.of(), List.of());
        }
        if (attributes.size() > MAX_RAW_CANDIDATES) {
            return limitExceeded();
        }

        List<RawAttribute> candidates = new ArrayList<>();
        List<RawIssue> issues = new ArrayList<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String rawKey = normalizeRawKey(entry.getKey());
            if (!isValidKey(rawKey)) {
                issues.add(new RawIssue(rawKey, UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY));
                continue;
            }
            Object value;
            try {
                value = cleanJavaValue(entry.getValue());
            } catch (IllegalArgumentException ex) {
                issues.add(new RawIssue(rawKey, UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE));
                continue;
            }
            if (value == null) {
                continue;
            }
            candidates.add(new RawAttribute(rawKey, value));
        }
        return new RawAttributes(candidates, issues);
    }

    /**
     * Coerces one structurally cleaned value to a declared property type.
     *
     * @param rawValue the raw value; must not be {@code null}
     * @param type     the declared type; {@code null} defaults to
     *                 {@link OntologyPropertyType#STRING}
     * @return the canonical Java value for the type
     * @throws IllegalArgumentException if the value cannot be represented by the
     *                                  declared type
     */
    public static Object coerce(Object rawValue, OntologyPropertyType type) {
        Objects.requireNonNull(rawValue, "rawValue");
        OntologyPropertyType effective = type == null ? OntologyPropertyType.STRING : type;
        return switch (effective) {
            case STRING -> coerceString(rawValue);
            case INTEGER -> coerceInteger(rawValue);
            case FLOAT -> coerceFloat(rawValue);
            case BOOLEAN -> coerceBoolean(rawValue);
            case DATE -> coerceDate(rawValue);
            case LIST -> coerceList(rawValue);
        };
    }

    /**
     * Per-key last-write-wins merge. Incoming values override base values.
     *
     * @param base     the base attributes; may be {@code null}
     * @param incoming the overriding attributes; may be {@code null}
     * @return a new map sorted by key and unmodifiable; {@code null} values are
     *         ignored
     */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> incoming) {
        TreeMap<String, Object> merged = new TreeMap<>();
        putAll(merged, base);
        putAll(merged, incoming);
        if (merged.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(merged);
    }

    /**
     * Applies the semantic attribute-bag caps.
     *
     * <p>Per-value caps are applied first: LIST values are limited to
     * {@value #MAX_LIST_ELEMENTS} elements with string elements of at most
     * {@value #MAX_LIST_ELEMENT_STRING_LENGTH} characters, and STRING values to
     * {@value #MAX_STRING_LENGTH} characters. Then keys are retained in
     * priority order - identities declared on the owner first, everything else
     * afterwards, each group sorted by identity - while the record stays within
     * {@value #MAX_KEYS} keys and {@value #MAX_CANONICAL_JSON_BYTES} UTF-8 bytes
     * of canonical JSON. A key that does not fit is dropped and later, smaller
     * keys are still considered, so the result depends only on the input.
     *
     * <p>This method never reports diagnostics; callers compare the input and
     * output key sets to emit {@link UnmatchedLabel.Reason#ATTRIBUTE_LIMIT_EXCEEDED}.
     *
     * @param values              the coerced attributes; may be {@code null}
     * @param declaredIdentities  the declared property identities for the owner;
     *                            may be {@code null}
     * @return the retained attributes, sorted by key and unmodifiable
     */
    public static Map<String, Object> applyCaps(
        Map<String, Object> values,
        Set<String> declaredIdentities
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Set<String> declared = declaredIdentities == null ? Set.of() : declaredIdentities;

        TreeMap<String, Object> withinValueCaps = new TreeMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (withinValueCaps(entry.getValue())) {
                withinValueCaps.put(entry.getKey(), entry.getValue());
            }
        }
        if (withinValueCaps.isEmpty()) {
            return Map.of();
        }

        List<String> priority = new ArrayList<>(withinValueCaps.keySet());
        priority.sort(Comparator
            .comparingInt((String key) -> declared.contains(key) ? 0 : 1)
            .thenComparing(Comparator.naturalOrder()));

        TreeMap<String, Object> selected = new TreeMap<>();
        for (String key : priority) {
            if (selected.size() >= MAX_KEYS) {
                break;
            }
            selected.put(key, withinValueCaps.get(key));
            if (canonicalBytes(selected) > MAX_CANONICAL_JSON_BYTES) {
                selected.remove(key);
            }
        }
        if (selected.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(selected);
    }

    private static RawAttributes limitExceeded() {
        return new RawAttributes(
            List.of(),
            List.of(new RawIssue(EMPTY, UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED))
        );
    }

    private static String normalizeRawKey(String key) {
        return key == null ? EMPTY : key.strip();
    }

    private static boolean isValidKey(String key) {
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Object cleanJsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return checkRawString(node.textValue());
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new IllegalArgumentException("Integral attribute value exceeds the Long range");
            }
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            // Keep the exact decimal form: rounding through double before
            // validation would shift truncation and hide over-range integers.
            BigDecimal value = node.decimalValue();
            if (!Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException("Non-finite attribute number");
            }
            return value;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                if (element == null || element.isNull() || element.isObject() || element.isArray()) {
                    throw new IllegalArgumentException("Attribute lists accept scalars only");
                }
                values.add(cleanJsonValue(element));
            }
            return List.copyOf(values);
        }
        throw new IllegalArgumentException("Unsupported attribute value node: " + node.getNodeType());
    }

    private static Object cleanJavaValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element == null) {
                    throw new IllegalArgumentException("Attribute lists accept scalars only");
                }
                values.add(cleanJavaScalar(element));
            }
            return List.copyOf(values);
        }
        return cleanJavaScalar(value);
    }

    private static Object cleanJavaScalar(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Attribute lists accept scalars only");
        }
        if (value instanceof String text) {
            return checkRawString(text);
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Long
            || value instanceof Float
            || value instanceof Double
            || value instanceof BigDecimal
            || value instanceof BigInteger) {
            return canonicalNumber(value);
        }
        throw new IllegalArgumentException(
            "Unsupported attribute value type: " + value.getClass().getName()
        );
    }

    /**
     * Normalizes a numeric value to the canonical materialized representation:
     * integral values become {@link Long} when exactly representable, and
     * everything else becomes a finite {@link Double}. The canonical JSON decode
     * path produces the same types, so an in-memory bag and its persisted form
     * compare equal; non-finite values are rejected.
     *
     * @param value the numeric value; must not be {@code null}
     * @return a {@link Long} or a finite {@link Double}
     */
    public static Object canonicalNumber(Object value) {
        Objects.requireNonNull(value, "value");
        if (value instanceof BigDecimal decimal) {
            BigDecimal stripped = decimal.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                try {
                    return stripped.longValueExact();
                } catch (ArithmeticException ignored) {
                    return canonicalWideNumber(stripped.doubleValue());
                }
            }
            return canonicalWideNumber(stripped.doubleValue());
        }
        if (value instanceof BigInteger integer) {
            try {
                return integer.longValueExact();
            } catch (ArithmeticException ignored) {
                return canonicalWideNumber(integer.doubleValue());
            }
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof Long) {
            return value;
        }
        if (value instanceof Float floatValue) {
            return canonicalDouble(floatValue.doubleValue());
        }
        if (value instanceof Double doubleValue) {
            return canonicalDouble(doubleValue);
        }
        throw new IllegalArgumentException(
            "Unsupported attribute value type: " + value.getClass().getName()
        );
    }

    /**
     * Integral values above the {@code Long} range persist as the {@link Double}
     * that the canonical JSON decode path would produce, keeping the value model
     * limited to {@code Long}/{@code Double}.
     */
    private static Object canonicalWideNumber(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite attribute number");
        }
        return value;
    }

    private static Object canonicalDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Non-finite attribute number");
        }
        if (value == Math.rint(value) && Math.abs(value) <= 0x1p53) {
            return (long) value;
        }
        return value;
    }

    private static String checkRawString(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RAW_STRING_BYTES) {
            throw new IllegalArgumentException("Raw attribute string exceeds 64 KiB");
        }
        return value;
    }

    private static Object coerceString(Object rawValue) {
        if (rawValue instanceof String text) {
            String value = text.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Blank string attribute value");
            }
            return value;
        }
        if (rawValue instanceof Boolean || rawValue instanceof Number) {
            return rawValue.toString();
        }
        throw new IllegalArgumentException(
            "Cannot coerce " + rawValue.getClass().getName() + " to STRING"
        );
    }

    private static Object coerceInteger(Object rawValue) {
        if (rawValue instanceof Boolean) {
            throw new IllegalArgumentException("Boolean cannot be coerced to INTEGER");
        }
        BigDecimal decimal;
        if (rawValue instanceof BigDecimal bigDecimal) {
            decimal = bigDecimal;
        } else if (rawValue instanceof BigInteger bigInteger) {
            decimal = new BigDecimal(bigInteger);
        } else if (rawValue instanceof Byte
            || rawValue instanceof Short
            || rawValue instanceof Integer
            || rawValue instanceof Long) {
            decimal = BigDecimal.valueOf(((Number) rawValue).longValue());
        } else if (rawValue instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("Non-finite number cannot be coerced to INTEGER");
            }
            decimal = new BigDecimal(floatValue.toString());
        } else if (rawValue instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("Non-finite number cannot be coerced to INTEGER");
            }
            decimal = BigDecimal.valueOf(doubleValue);
        } else if (rawValue instanceof String text) {
            String value = text.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Blank string cannot be coerced to INTEGER");
            }
            try {
                decimal = new BigDecimal(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Not an integer: " + text, ex);
            }
        } else {
            throw new IllegalArgumentException(
                "Cannot coerce " + rawValue.getClass().getName() + " to INTEGER"
            );
        }

        if (decimal.abs().compareTo(MAX_INTEGER_MAGNITUDE) > 0) {
            throw new IllegalArgumentException("INTEGER attribute exceeds the 2^53 magnitude limit");
        }
        BigInteger truncated = decimal.toBigInteger();
        try {
            return truncated.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("INTEGER attribute exceeds the Long range", ex);
        }
    }

    private static Object coerceFloat(Object rawValue) {
        if (rawValue instanceof Boolean) {
            throw new IllegalArgumentException("Boolean cannot be coerced to FLOAT");
        }
        if (rawValue instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite number cannot be coerced to FLOAT");
            }
            return value;
        }
        if (rawValue instanceof String text) {
            String value = text.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Blank string cannot be coerced to FLOAT");
            }
            try {
                double parsed = Double.parseDouble(value);
                if (!Double.isFinite(parsed)) {
                    throw new IllegalArgumentException("Non-finite number cannot be coerced to FLOAT");
                }
                return parsed;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Not a number: " + text, ex);
            }
        }
        throw new IllegalArgumentException(
            "Cannot coerce " + rawValue.getClass().getName() + " to FLOAT"
        );
    }

    private static Object coerceBoolean(Object rawValue) {
        if (rawValue instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (rawValue instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite number cannot be coerced to BOOLEAN");
            }
            return value != 0.0d;
        }
        if (rawValue instanceof String text) {
            String value = text.strip().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "true", "yes", "y", "1" -> Boolean.TRUE;
                case "false", "no", "n", "0" -> Boolean.FALSE;
                default -> throw new IllegalArgumentException("Not a boolean: " + text);
            };
        }
        throw new IllegalArgumentException(
            "Cannot coerce " + rawValue.getClass().getName() + " to BOOLEAN"
        );
    }

    private static Object coerceDate(Object rawValue) {
        if (!(rawValue instanceof String text)) {
            throw new IllegalArgumentException(
                "Cannot coerce " + rawValue.getClass().getName() + " to DATE"
            );
        }
        String value = text.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blank string cannot be coerced to DATE");
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).toString();
        } catch (DateTimeParseException ignored) {
            // fall through to date-time formats
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toLocalDate()
                .toString();
        } catch (DateTimeParseException ignored) {
            // fall through to local date-time
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toLocalDate()
                .toString();
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException("Not an ISO-8601 date: " + text);
        }
    }

    private static Object coerceList(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            List<Object> values = new ArrayList<>(list.size());
            for (Object element : list) {
                values.add(coerceListElement(element));
            }
            return List.copyOf(values);
        }
        if (rawValue instanceof Map) {
            throw new IllegalArgumentException("A map cannot be coerced to LIST");
        }
        return List.of(coerceListElement(rawValue));
    }

    private static Object coerceListElement(Object rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("LIST elements must not be null");
        }
        if (rawValue instanceof String || rawValue instanceof Boolean) {
            return rawValue;
        }
        if (rawValue instanceof Byte
            || rawValue instanceof Short
            || rawValue instanceof Integer
            || rawValue instanceof Long) {
            return ((Number) rawValue).longValue();
        }
        if (rawValue instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("Non-finite LIST element");
            }
            return floatValue.doubleValue();
        }
        if (rawValue instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException("Non-finite LIST element");
            }
            return doubleValue;
        }
        if (rawValue instanceof BigDecimal bigDecimal) {
            if (bigDecimal.stripTrailingZeros().scale() <= 0) {
                try {
                    return bigDecimal.longValueExact();
                } catch (ArithmeticException ex) {
                    throw new IllegalArgumentException("LIST integer element exceeds the Long range", ex);
                }
            }
            double value = bigDecimal.doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("LIST element exceeds the Double range");
            }
            return value;
        }
        if (rawValue instanceof BigInteger bigInteger) {
            try {
                return bigInteger.longValueExact();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("LIST integer element exceeds the Long range", ex);
            }
        }
        throw new IllegalArgumentException(
            "Unsupported LIST element type: " + rawValue.getClass().getName()
        );
    }

    private static boolean withinValueCaps(Object value) {
        if (value instanceof String text) {
            return text.length() <= MAX_STRING_LENGTH;
        }
        if (value instanceof List<?> list) {
            if (list.size() > MAX_LIST_ELEMENTS) {
                return false;
            }
            for (Object element : list) {
                if (element instanceof String text
                    && text.length() > MAX_LIST_ELEMENT_STRING_LENGTH) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int canonicalBytes(Map<String, Object> values) {
        return AttributeJsonCodec.toJson(values).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void putAll(TreeMap<String, Object> target, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            target.put(entry.getKey(), entry.getValue());
        }
    }
}
