package io.github.lightrag.query;

import io.github.lightrag.api.MetadataCondition;
import io.github.lightrag.api.MetadataOperator;
import io.github.lightrag.api.QueryRequest;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class MetadataFilterNormalizer {
    private static final Pattern METADATA_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.]+");

    private MetadataFilterNormalizer() {
    }

    public static MetadataFilterPlan normalize(QueryRequest request) {
        Objects.requireNonNull(request, "request");
        return normalize(request.metadataFilters(), request.metadataConditions());
    }

    public static MetadataFilterPlan normalize(Map<String, ?> metadataFilters, List<MetadataCondition> metadataConditions) {
        var normalizedFilters = normalizeFilters(metadataFilters);
        var normalizedConditions = normalizeConditions(metadataConditions);
        var pushdownSummary = MetadataPushdownPlanner.plan(normalizedFilters, normalizedConditions);
        return new MetadataFilterPlan(normalizedFilters, normalizedConditions, pushdownSummary);
    }

    static Map<String, List<String>> normalizeFilters(Map<String, ?> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return Map.of();
        }

        var normalized = new LinkedHashMap<String, List<String>>();
        for (var entry : metadataFilters.entrySet()) {
            var key = normalizeField(entry.getKey(), "metadataFilters key");
            mergeNormalizedFilterValues(normalized, key, entry.getValue());
        }

        return normalized.isEmpty() ? Map.of() : normalized;
    }

    static List<MetadataFilterPlan.NormalizedMetadataCondition> normalizeConditions(List<MetadataCondition> metadataConditions) {
        if (metadataConditions == null || metadataConditions.isEmpty()) {
            return List.of();
        }

        return metadataConditions.stream()
            .map(MetadataFilterNormalizer::normalizeCondition)
            .toList();
    }

    static Instant parseInstant(String value) {
        var trimmed = requireNonBlankString(value, "metadata date value");

        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(trimmed).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return Instant.ofEpochMilli(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
        }

        throw new IllegalArgumentException("metadata date value must be parseable: " + trimmed);
    }

    private static MetadataFilterPlan.NormalizedMetadataCondition normalizeCondition(MetadataCondition condition) {
        if (condition == null) {
            throw new IllegalArgumentException("metadataConditions entry");
        }

        var field = normalizeField(condition.field(), "metadataConditions field");
        var operator = Objects.requireNonNull(condition.operator(), "metadataConditions operator");
        var value = Objects.requireNonNull(condition.value(), "metadataConditions value");

        return switch (operator) {
            case EQ -> new MetadataFilterPlan.NormalizedMetadataCondition(
                field,
                operator,
                List.of(normalizeScalarString(value, operator)),
                null,
                null
            );
            case IN -> new MetadataFilterPlan.NormalizedMetadataCondition(
                field,
                operator,
                normalizeInValues(value),
                null,
                null
            );
            case GT, GTE, LT, LTE -> new MetadataFilterPlan.NormalizedMetadataCondition(
                field,
                operator,
                List.of(),
                parseBigDecimal(value, operator),
                null
            );
            case BEFORE, AFTER -> new MetadataFilterPlan.NormalizedMetadataCondition(
                field,
                operator,
                List.of(),
                null,
                parseConditionInstant(value, operator)
            );
        };
    }

    private static String normalizeField(String field, String fieldName) {
        var normalized = Objects.requireNonNull(field, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        if (!METADATA_KEY_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " must match [A-Za-z0-9_.]+: " + normalized);
        }
        return normalized;
    }

    private static void mergeNormalizedFilterValues(Map<String, List<String>> normalized, String key, Object value) {
        var collector = new LinkedHashSet<String>();
        collectStringValues(value, collector);
        if (collector.isEmpty()) {
            return;
        }

        var existing = normalized.get(key);
        if (existing == null || existing.isEmpty()) {
            normalized.put(key, List.copyOf(collector));
            return;
        }

        var merged = new LinkedHashSet<String>(existing);
        merged.addAll(collector);
        normalized.put(key, List.copyOf(merged));
    }

    private static void collectStringValues(Object value, LinkedHashSet<String> collector) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (var element : iterable) {
                collectStringValues(element, collector);
            }
            return;
        }
        var type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectStringValues(Array.get(value, i), collector);
            }
            return;
        }
        var trimmed = value.toString().trim();
        if (!trimmed.isEmpty()) {
            collector.add(trimmed);
        }
    }

    private static String normalizeScalarString(Object value, MetadataOperator operator) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            throw new IllegalArgumentException("metadataConditions " + operator + " value must be scalar");
        }
        return requireNonBlankString(value.toString(), "metadataConditions " + operator + " value");
    }

    private static List<String> normalizeInValues(Object value) {
        if (!(value instanceof Iterable<?>) && !value.getClass().isArray()) {
            throw new IllegalArgumentException("metadataConditions IN value must be iterable or array");
        }
        var collector = new LinkedHashSet<String>();
        collectStringValues(value, collector);
        if (collector.isEmpty()) {
            throw new IllegalArgumentException("metadataConditions IN value must contain at least one non-empty element");
        }
        return List.copyOf(collector);
    }

    private static BigDecimal parseBigDecimal(Object value, MetadataOperator operator) {
        var normalized = normalizeScalarString(value, operator);
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("metadataConditions " + operator + " value must be numeric: " + normalized, ex);
        }
    }

    private static Instant parseConditionInstant(Object value, MetadataOperator operator) {
        var normalized = normalizeScalarString(value, operator);
        try {
            return parseInstant(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "metadataConditions " + operator + " value must be a supported date literal: " + normalized,
                ex
            );
        }
    }

    private static String requireNonBlankString(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
