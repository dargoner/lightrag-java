package io.github.lightrag.storage.falkordb;

import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribute-bag persistence shape for the FalkorDB store, exercised without a live server: the
 * property maps written by {@code SET n += $properties}/{@code SET r += $properties} and the
 * read-back mapping are pure functions.
 */
class FalkorDbGraphStoreAttributeTest {

    @Test
    void entityPropertiesCarryCanonicalAttributeJson() {
        var entity = new EntityRecord(
            "entity-1",
            "Alice",
            "person",
            "researcher",
            List.of("A"),
            List.of("chunk-1"),
            "/docs/a.md",
            Map.of("tags", List.of("x", "y"), "age", 42L, "active", true, "nickname", "Al")
        );

        assertThat(FalkorDbGraphStore.entityProperties(entity))
            .containsEntry("attributes", "{\"active\":true,\"age\":42,\"nickname\":\"Al\",\"tags\":[\"x\",\"y\"]}");
    }

    @Test
    void emptyAttributesAreWrittenAsAnEmptyJsonObject() {
        var entity = new EntityRecord("entity-1", "Alice", "person", "", List.of(), List.of());
        var relation = new RelationRecord("relation-1", "entity-1", "entity-2", "knows", "", 0.5d, List.of());

        assertThat(FalkorDbGraphStore.entityProperties(entity)).containsEntry("attributes", "{}");
        assertThat(FalkorDbGraphStore.relationProperties(relation)).containsEntry("attributes", "{}");
    }

    @Test
    void relationPropertiesCarryCanonicalAttributeJson() {
        var relation = new RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "linked",
            0.5d,
            "chunk-1",
            "/docs/a.md",
            Map.of("since", 2020L, "confidence", 0.75d)
        );

        assertThat(FalkorDbGraphStore.relationProperties(relation))
            .containsEntry("attributes", "{\"confidence\":0.75,\"since\":2020}");
    }

    @Test
    void entityRecordsReadTheAttributeBagBack() {
        var entity = new EntityRecord(
            "entity-1",
            "Alice",
            "person",
            "researcher",
            List.of("A"),
            List.of("chunk-1"),
            "/docs/a.md",
            Map.of("age", 42L, "tags", List.of("x", "y"))
        );
        var properties = new LinkedHashMap<String, Object>(FalkorDbGraphStore.entityProperties(entity));
        properties.put("entity_id", entity.id());

        assertThat(FalkorDbGraphStore.toEntityRecord(properties)).isEqualTo(entity);
    }

    @Test
    void relationRecordsReadTheAttributeBagBack() {
        var relation = new RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "linked",
            0.5d,
            "chunk-1",
            "/docs/a.md",
            Map.of("since", 2020L)
        );
        var properties = new LinkedHashMap<String, Object>(FalkorDbGraphStore.relationProperties(relation));
        properties.put("src_id", relation.srcId());
        properties.put("tgt_id", relation.tgtId());

        assertThat(FalkorDbGraphStore.toRelationRecord(properties)).isEqualTo(relation);
    }

    @Test
    void legacyRowsWithoutTheAttributePropertyReadAsEmpty() {
        // Graph shape written before the attribute property existed.
        var entityProperties = Map.<String, Object>of(
            "entity_id", "legacy-1",
            "name", "Legacy",
            "entity_type", "person",
            "description", "",
            "aliases", List.of(),
            "source_id", "chunk-1"
        );
        var relationProperties = Map.<String, Object>of(
            "relation_id", "legacy-relation",
            "src_id", "legacy-1",
            "tgt_id", "legacy-2",
            "keywords", "knows",
            "description", "",
            "weight", 0.5d,
            "source_id", "chunk-1"
        );

        assertThat(FalkorDbGraphStore.toEntityRecord(entityProperties).attributes()).isEmpty();
        assertThat(FalkorDbGraphStore.toRelationRecord(relationProperties).attributes()).isEmpty();
    }

    @Test
    void malformedAttributeJsonReadsAsEmptyInsteadOfFailingTheLoad() {
        var entityProperties = Map.<String, Object>of(
            "entity_id", "entity-1",
            "name", "Alice",
            "attributes", "{not json"
        );

        assertThat(FalkorDbGraphStore.toEntityRecord(entityProperties).attributes()).isEmpty();
    }

    @Test
    void emptyAttributeJsonObjectReadsAsEmpty() {
        var entityProperties = Map.<String, Object>of(
            "entity_id", "entity-1",
            "name", "Alice",
            "attributes", "{}"
        );

        assertThat(FalkorDbGraphStore.toEntityRecord(entityProperties).attributes()).isEmpty();
    }
}
