package io.github.lightrag.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void legacyEntityConstructorsDefaultToEmptyAttributes() {
        var entity = new Entity("id", "Alice", "Person", "desc", List.of("Al"), List.of("chunk"));
        assertThat(entity.attributes()).isEmpty();
        assertThat(entity).isEqualTo(new Entity(
            "id", "Alice", "Person", "desc", List.of("Al"), List.of("chunk"), "", Map.of()
        ));
    }

    @Test
    void legacyRelationConstructorsDefaultToEmptyAttributes() {
        var relation = new Relation("rel", "a", "b", "knows", "desc", 1.0d, "chunk", "");
        assertThat(relation.attributes()).isEmpty();
    }

    @Test
    void equalityIncludesAttributes() {
        var plain = new Entity("id", "Alice", "Person", "desc", List.of(), List.of());
        var enriched = new Entity("id", "Alice", "Person", "desc", List.of(), List.of(), "", Map.of("age", 1L));
        assertThat(plain).isNotEqualTo(enriched);
    }

    @Test
    void legacyExtractedRecordsDefaultToEmptyAttributes() {
        var entity = new ExtractedEntity("Alice", "Person", "desc", List.of());
        var relation = new ExtractedRelation("Alice", "Bob", "knows", "desc", 1.0d);
        assertThat(entity.attributes()).isEmpty();
        assertThat(relation.attributes()).isEmpty();
    }

    @Test
    void emptyAttributesAreOmittedFromGraphApiJson() throws Exception {
        var entity = new GraphEntity("id", "Alice", "Person", "desc", List.of(), List.of());
        var relation = new GraphRelation("rel", "a", "b", "knows", "desc", 1.0d, "chunk", "");

        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(entity)).has("attributes")).isFalse();
        assertThat(MAPPER.readTree(MAPPER.writeValueAsString(relation)).has("attributes")).isFalse();
    }

    @Test
    void nonEmptyAttributesAreSerializedOnGraphApiJson() throws Exception {
        var entity = new GraphEntity(
            "id", "Alice", "Person", "desc", List.of(), List.of(), "", Map.of("age", 1L)
        );

        var json = MAPPER.readTree(MAPPER.writeValueAsString(entity));
        assertThat(json.get("attributes").get("age").asLong()).isEqualTo(1L);
    }
}
