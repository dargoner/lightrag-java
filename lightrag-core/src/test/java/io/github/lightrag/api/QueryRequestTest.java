package io.github.lightrag.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryRequestTest {
    @Test
    void builderDefaultsMetadataCollectionsEmpty() {
        var request = QueryRequest.builder()
            .query("query")
            .build();

        assertThat(request.metadataFilters()).isEmpty();
        assertThat(request.metadataConditions()).isEmpty();
    }

    @Test
    void metadataFiltersNormalizeScalarAndCollections() {
        var filters = new LinkedHashMap<String, Object>();
        filters.put("category", "  shiny  ");
        filters.put("tags", Arrays.asList("blue", "Blue", "blue ", null, " "));
        filters.put("empty", List.of("", "   "));

        var request = QueryRequest.builder()
            .query("query")
            .metadataFilters(filters)
            .build();

        assertThat(request.metadataFilters()).containsEntry("category", List.of("shiny"));
        assertThat(request.metadataFilters()).containsEntry("tags", List.of("blue", "Blue"));
        assertThat(request.metadataFilters()).doesNotContainKey("empty");
    }

    @Test
    void metadataFiltersReturnsUnmodifiableMap() {
        var request = QueryRequest.builder()
            .query("query")
            .metadataFilters(Map.of("key", "value"))
            .build();

        assertThatThrownBy(() -> request.metadataFilters().put("another", List.of("value")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metadataFiltersRejectsInvalidKey() {
        var invalid = new LinkedHashMap<String, Object>();
        invalid.put("bad key", "value");

        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataFilters(invalid)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataFiltersAcceptDotKey() {
        var request = QueryRequest.builder()
            .query("query")
            .metadataFilters(Map.of("smart_chunker.section_path", "chapter/intro"))
            .build();

        assertThat(request.metadataFilters()).containsEntry("smart_chunker.section_path", List.of("chapter/intro"));
    }

    @Test
    void metadataFiltersMergeValuesWhenKeysCollideAfterTrim() {
        var filters = new LinkedHashMap<String, Object>();
        filters.put("region", List.of("east", "west"));
        filters.put(" region ", List.of("west", "north"));

        var request = QueryRequest.builder()
            .query("query")
            .metadataFilters(filters)
            .build();

        assertThat(request.metadataFilters()).containsEntry("region", List.of("east", "west", "north"));
    }

    @Test
    void metadataConditionsReturnsUnmodifiableList() {
        var request = QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(new MetadataCondition("field", MetadataOperator.EQ, "value")))
            .build();

        assertThatThrownBy(() -> request.metadataConditions().add(
            new MetadataCondition("field", MetadataOperator.IN, List.of("x"))))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metadataConditionsRejectsNullEntry() {
        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(Arrays.asList((MetadataCondition) null))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataConditionsRejectInvalidPayload() {
        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(
                new MetadataCondition("createdAt", MetadataOperator.BEFORE, Map.of("bad", "payload"))))
            .build())
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(new MetadataCondition("status", MetadataOperator.IN, "draft")))
            .build())
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(new MetadataCondition("score", MetadataOperator.GT, List.of("80"))))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataConditionsRejectUnparseableNumericLiteralAtConstruction() {
        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(new MetadataCondition("score", MetadataOperator.GT, "not-a-number")))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataConditionsRejectUnparseableDateLiteralAtConstruction() {
        assertThatThrownBy(() -> QueryRequest.builder()
            .query("query")
            .metadataConditions(List.of(new MetadataCondition("publishDate", MetadataOperator.BEFORE, "not-a-date")))
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstLegacyConstructorKeepsMetadataEmpty() {
        var request = new QueryRequest(
            "query",
            QueryMode.MIX,
            1,
            1,
            "response",
            true
        );

        assertThat(request.metadataFilters()).isEmpty();
        assertThat(request.metadataConditions()).isEmpty();
    }

    @Test
    void secondLegacyConstructorKeepsMetadataEmpty() {
        var request = new QueryRequest(
            "query",
            QueryMode.MIX,
            1,
            1,
            "response",
            true,
            "user",
            List.of()
        );

        assertThat(request.metadataFilters()).isEmpty();
        assertThat(request.metadataConditions()).isEmpty();
    }
}
