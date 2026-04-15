package io.github.lightrag.query;

import io.github.lightrag.api.MetadataOperator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MetadataFilterPlan(
    Map<String, List<String>> normalizedFilters,
    List<NormalizedMetadataCondition> normalizedConditions,
    PushdownSummary pushdownSummary
) {
    public MetadataFilterPlan {
        normalizedFilters = Map.copyOf(Objects.requireNonNull(normalizedFilters, "normalizedFilters"));
        normalizedConditions = List.copyOf(Objects.requireNonNull(normalizedConditions, "normalizedConditions"));
        pushdownSummary = Objects.requireNonNull(pushdownSummary, "pushdownSummary");
    }

    public boolean isEmpty() {
        return normalizedFilters.isEmpty() && normalizedConditions.isEmpty();
    }

    public record NormalizedMetadataCondition(
        String field,
        MetadataOperator operator,
        List<String> stringValues,
        BigDecimal numericValue,
        Instant instantValue
    ) {
        public NormalizedMetadataCondition {
            field = Objects.requireNonNull(field, "field");
            operator = Objects.requireNonNull(operator, "operator");
            stringValues = List.copyOf(Objects.requireNonNull(stringValues, "stringValues"));
        }

        public boolean isEarlyApplicable() {
            return switch (operator) {
                case EQ, IN -> true;
                case GT, GTE, LT, LTE, BEFORE, AFTER -> false;
            };
        }
    }

    public record PushdownSummary(
        boolean hasEarlyApplicablePredicates,
        boolean hasFallbackOnlyPredicates
    ) {
    }
}
