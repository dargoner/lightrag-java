package io.github.lightrag.query;

import io.github.lightrag.api.MetadataCondition;
import io.github.lightrag.api.MetadataOperator;
import io.github.lightrag.api.QueryRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataFilterNormalizerTest {
    @Test
    void normalizesRawFiltersAndConditionsIntoSharedPlan() {
        var rawFilters = new LinkedHashMap<String, Object>();
        rawFilters.put("region", Arrays.asList("  east  ", "west", "east", null, " "));
        rawFilters.put("docType", " policy ");

        var plan = MetadataFilterNormalizer.normalize(
            rawFilters,
            List.of(
                new MetadataCondition("status", MetadataOperator.EQ, " active "),
                new MetadataCondition("category", MetadataOperator.IN, List.of("alpha", " beta ", "alpha"))
            )
        );

        assertThat(plan.normalizedFilters())
            .containsEntry("region", List.of("east", "west"))
            .containsEntry("docType", List.of("policy"));
        assertThat(plan.normalizedConditions()).hasSize(2);
        assertThat(plan.pushdownSummary().hasEarlyApplicablePredicates()).isTrue();
    }

    @Test
    void preservesFilterEncounterOrderWhileDeduplicatingValues() {
        var rawFilters = new LinkedHashMap<String, Object>();
        rawFilters.put("status", List.of(" draft ", "published", "draft", "archived", "published"));

        var plan = MetadataFilterNormalizer.normalize(rawFilters, List.of());

        assertThat(plan.normalizedFilters()).containsEntry("status", List.of("draft", "published", "archived"));
    }

    @Test
    void supportsDotKeyInFilters() {
        var rawFilters = new LinkedHashMap<String, Object>();
        rawFilters.put("smart_chunker.section_path", " chapter/intro ");

        var plan = MetadataFilterNormalizer.normalize(rawFilters, List.of());

        assertThat(plan.normalizedFilters()).containsEntry("smart_chunker.section_path", List.of("chapter/intro"));
    }

    @Test
    void mergesTrimEquivalentFilterKeysWithoutOverwrite() {
        var rawFilters = new LinkedHashMap<String, Object>();
        rawFilters.put("region", List.of("east", "west"));
        rawFilters.put(" region ", List.of("west", "north"));

        var plan = MetadataFilterNormalizer.normalize(rawFilters, List.of());

        assertThat(plan.normalizedFilters()).containsEntry("region", List.of("east", "west", "north"));
    }

    @Test
    void normalizesNumericConditionValuesToBigDecimal() {
        var plan = MetadataFilterNormalizer.normalize(
            java.util.Map.of(),
            List.of(new MetadataCondition("score", MetadataOperator.GTE, " 80.50 "))
        );

        assertThat(plan.normalizedConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.operator()).isEqualTo(MetadataOperator.GTE);
            assertThat(condition.numericValue()).isEqualByComparingTo(new BigDecimal("80.50"));
            assertThat(condition.instantValue()).isNull();
            assertThat(condition.stringValues()).isEmpty();
        });
    }

    @Test
    void normalizesDateConditionValuesToInstant() {
        var plan = MetadataFilterNormalizer.normalize(
            java.util.Map.of(),
            List.of(new MetadataCondition("publishDate", MetadataOperator.BEFORE, "2024-05-01T10:15:30+08:00"))
        );

        assertThat(plan.normalizedConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.operator()).isEqualTo(MetadataOperator.BEFORE);
            assertThat(condition.instantValue()).isEqualTo(Instant.parse("2024-05-01T02:15:30Z"));
            assertThat(condition.numericValue()).isNull();
            assertThat(condition.stringValues()).isEmpty();
        });
    }

    @Test
    void normalizesDateConditionValuesFromDateDateTimeAndEpochMillis() {
        var plan = MetadataFilterNormalizer.normalize(
            java.util.Map.of(),
            List.of(
                new MetadataCondition("publishDate", MetadataOperator.BEFORE, "2024-05-01"),
                new MetadataCondition("updatedAt", MetadataOperator.AFTER, "2024-05-01T10:15:30"),
                new MetadataCondition("createdAt", MetadataOperator.BEFORE, "1714558530000")
            )
        );

        assertThat(plan.normalizedConditions()).hasSize(3);
        assertThat(plan.normalizedConditions().get(0).instantValue()).isEqualTo(Instant.parse("2024-05-01T00:00:00Z"));
        assertThat(plan.normalizedConditions().get(1).instantValue()).isEqualTo(Instant.parse("2024-05-01T10:15:30Z"));
        assertThat(plan.normalizedConditions().get(2).instantValue()).isEqualTo(Instant.ofEpochMilli(1714558530000L));
    }

    @Test
    void marksPlanAsEarlyApplicableWhenFiltersOrEqualityConditionsExist() {
        var equalityPlan = MetadataFilterNormalizer.normalize(
            java.util.Map.of(),
            List.of(new MetadataCondition("status", MetadataOperator.EQ, "active"))
        );
        var filtersPlan = MetadataFilterNormalizer.normalize(
            java.util.Map.of("region", "east"),
            List.of()
        );

        assertThat(equalityPlan.pushdownSummary().hasEarlyApplicablePredicates()).isTrue();
        assertThat(equalityPlan.pushdownSummary().hasFallbackOnlyPredicates()).isFalse();
        assertThat(filtersPlan.pushdownSummary().hasEarlyApplicablePredicates()).isTrue();
    }

    @Test
    void marksPlanAsFallbackOnlyWhenOnlyNumericOrDateConditionsExist() {
        var plan = MetadataFilterNormalizer.normalize(
            java.util.Map.of(),
            List.of(
                new MetadataCondition("score", MetadataOperator.GT, 10),
                new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01")
            )
        );

        assertThat(plan.pushdownSummary().hasEarlyApplicablePredicates()).isFalse();
        assertThat(plan.pushdownSummary().hasFallbackOnlyPredicates()).isTrue();
    }

    @Test
    void supportsBuildingPlanFromQueryRequest() {
        var request = QueryRequest.builder()
            .query("metadata query")
            .metadataFilters(java.util.Map.of("region", List.of("east", "east", "west")))
            .metadataConditions(List.of(new MetadataCondition("score", MetadataOperator.GTE, "42")))
            .build();

        var plan = MetadataFilterNormalizer.normalize(request);

        assertThat(plan.normalizedFilters()).containsEntry("region", List.of("east", "west"));
        assertThat(plan.normalizedConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.field()).isEqualTo("score");
            assertThat(condition.numericValue()).isEqualByComparingTo(new BigDecimal("42"));
        });
        assertThat(plan.isEmpty()).isFalse();
    }
}
