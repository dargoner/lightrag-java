package io.github.lightrag.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MetadataPushdownPlanner {
    private MetadataPushdownPlanner() {
    }

    public static MetadataFilterPlan.PushdownSummary plan(
        Map<String, List<String>> normalizedFilters,
        List<MetadataFilterPlan.NormalizedMetadataCondition> normalizedConditions
    ) {
        Objects.requireNonNull(normalizedFilters, "normalizedFilters");
        Objects.requireNonNull(normalizedConditions, "normalizedConditions");

        boolean hasEarlyApplicable = !normalizedFilters.isEmpty()
            || normalizedConditions.stream().anyMatch(MetadataFilterPlan.NormalizedMetadataCondition::isEarlyApplicable);
        boolean hasFallbackOnly = normalizedConditions.stream().anyMatch(condition -> !condition.isEarlyApplicable());
        return new MetadataFilterPlan.PushdownSummary(hasEarlyApplicable, hasFallbackOnly);
    }
}
