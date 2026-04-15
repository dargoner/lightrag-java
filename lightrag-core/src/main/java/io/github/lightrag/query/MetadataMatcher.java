package io.github.lightrag.query;

import io.github.lightrag.api.MetadataOperator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MetadataMatcher {
    private MetadataMatcher() {
    }

    public static boolean matches(MetadataFilterPlan plan, Map<String, String> metadata) {
        Objects.requireNonNull(plan, "plan");

        if (plan.isEmpty()) {
            return true;
        }
        if (metadata == null) {
            return false;
        }

        return matchesFilters(plan.normalizedFilters(), metadata) && matchesConditions(plan.normalizedConditions(), metadata);
    }

    private static boolean matchesFilters(Map<String, List<String>> normalizedFilters, Map<String, String> metadata) {
        for (var entry : normalizedFilters.entrySet()) {
            var actualValue = metadata.get(entry.getKey());
            if (actualValue == null) {
                return false;
            }
            var normalizedActual = actualValue.trim();
            if (!entry.getValue().contains(normalizedActual)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesConditions(
        List<MetadataFilterPlan.NormalizedMetadataCondition> normalizedConditions,
        Map<String, String> metadata
    ) {
        for (var condition : normalizedConditions) {
            var actualValue = metadata.get(condition.field());
            if (actualValue == null) {
                return false;
            }
            if (!matchesCondition(condition, actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesCondition(MetadataFilterPlan.NormalizedMetadataCondition condition, String actualValue) {
        return switch (condition.operator()) {
            case EQ, IN -> condition.stringValues().contains(actualValue.trim());
            case GT -> compareNumber(actualValue, condition.numericValue(), comparison -> comparison > 0);
            case GTE -> compareNumber(actualValue, condition.numericValue(), comparison -> comparison >= 0);
            case LT -> compareNumber(actualValue, condition.numericValue(), comparison -> comparison < 0);
            case LTE -> compareNumber(actualValue, condition.numericValue(), comparison -> comparison <= 0);
            case BEFORE -> compareInstant(actualValue, condition.instantValue(), comparison -> comparison < 0);
            case AFTER -> compareInstant(actualValue, condition.instantValue(), comparison -> comparison > 0);
        };
    }

    private static boolean compareNumber(String actualValue, BigDecimal expectedValue, IntPredicate predicate) {
        try {
            return predicate.test(new BigDecimal(actualValue.trim()).compareTo(expectedValue));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean compareInstant(String actualValue, java.time.Instant expectedValue, IntPredicate predicate) {
        try {
            return predicate.test(MetadataFilterNormalizer.parseInstant(actualValue.trim()).compareTo(expectedValue));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @FunctionalInterface
    private interface IntPredicate {
        boolean test(int comparison);
    }
}
