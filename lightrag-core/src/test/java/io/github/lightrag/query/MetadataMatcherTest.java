package io.github.lightrag.query;

import io.github.lightrag.api.MetadataCondition;
import io.github.lightrag.api.MetadataOperator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMatcherTest {
    @Test
    void matchesEqualityAndInStringConditions() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of("region", List.of("east", "west")),
            List.of(
                new MetadataCondition("status", MetadataOperator.EQ, "active"),
                new MetadataCondition("category", MetadataOperator.IN, List.of("a", "b"))
            )
        );

        assertThat(MetadataMatcher.matches(plan, Map.of(
            "region", "west",
            "status", "active",
            "category", "b"
        ))).isTrue();
    }

    @Test
    void matchesNumericComparisons() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of(),
            List.of(
                new MetadataCondition("score", MetadataOperator.GTE, "80"),
                new MetadataCondition("rank", MetadataOperator.LT, "10")
            )
        );

        assertThat(MetadataMatcher.matches(plan, Map.of(
            "score", "88.5",
            "rank", "9"
        ))).isTrue();
    }

    @Test
    void matchesDateComparisons() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of(),
            List.of(
                new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01"),
                new MetadataCondition("updatedAt", MetadataOperator.BEFORE, "2024-06-01T00:00:00Z")
            )
        );

        assertThat(MetadataMatcher.matches(plan, Map.of(
            "publishDate", "2024-01-02",
            "updatedAt", "2024-05-20T12:00:00"
        ))).isTrue();
    }

    @Test
    void matchesDateComparisonsForLocalDateTimeAndEpochMillis() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of(),
            List.of(
                new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01"),
                new MetadataCondition("updatedAt", MetadataOperator.BEFORE, "2024-06-01T00:00:00"),
                new MetadataCondition("createdAt", MetadataOperator.AFTER, "1714558530000")
            )
        );

        assertThat(MetadataMatcher.matches(plan, Map.of(
            "publishDate", "2024-01-02",
            "updatedAt", "2024-05-20T12:00:00",
            "createdAt", "1714558530001"
        ))).isTrue();
    }

    @Test
    void returnsFalseWhenStoredMetadataFieldIsMissing() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of("region", "east"),
            List.of(new MetadataCondition("status", MetadataOperator.EQ, "active"))
        );

        assertThat(MetadataMatcher.matches(plan, Map.of("region", "east"))).isFalse();
    }

    @Test
    void returnsFalseForBadStoredNumericOrDateValuesWithoutThrowing() {
        var numericPlan = MetadataFilterNormalizer.normalize(
            Map.of(),
            List.of(new MetadataCondition("score", MetadataOperator.GT, "10"))
        );
        var datePlan = MetadataFilterNormalizer.normalize(
            Map.of(),
            List.of(new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01"))
        );

        assertThat(MetadataMatcher.matches(numericPlan, Map.of("score", "not-a-number"))).isFalse();
        assertThat(MetadataMatcher.matches(datePlan, Map.of("publishDate", "not-a-date"))).isFalse();
    }

    @Test
    void returnsTrueWhenPlanIsEmpty() {
        var plan = MetadataFilterNormalizer.normalize(Map.of(), List.of());

        assertThat(MetadataMatcher.matches(plan, Map.of())).isTrue();
        assertThat(MetadataMatcher.matches(plan, Map.of("anything", "goes"))).isTrue();
        assertThat(MetadataMatcher.matches(plan, null)).isTrue();
    }

    @Test
    void returnsFalseWhenPlanIsNonEmptyAndMetadataIsNull() {
        var plan = MetadataFilterNormalizer.normalize(
            Map.of("region", "east"),
            List.of()
        );

        assertThat(MetadataMatcher.matches(plan, null)).isFalse();
    }
}
