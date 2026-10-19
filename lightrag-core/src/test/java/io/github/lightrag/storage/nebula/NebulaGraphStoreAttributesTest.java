package io.github.lightrag.storage.nebula;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement shapes and read mapping of the NebulaGraph attribute bag, without a live server.
 */
class NebulaGraphStoreAttributesTest {

    @Test
    void entityInsertCarriesTheAttributesColumnLast() {
        var record = new EntityRecord(
            "entity-1",
            "Alice",
            "person",
            "researcher",
            List.of("A"),
            List.of("chunk-1"),
            "/docs/a.md",
            Map.of("nickname", "Al", "age", 42L)
        );

        var statement = NebulaGraphStore.saveEntityStatement("ws", record);

        assertThat(statement)
            .contains("INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, "
                + "file_path, workspace_id, materialized, attributes) VALUES")
            .contains(", true, " + NebulaSupport.quoted("{\"age\":42,\"nickname\":\"Al\"}") + ");");
    }

    @Test
    void entityInsertWritesAnEmptyJsonObjectWhenThereAreNoAttributes() {
        var record = new EntityRecord("entity-1", "Alice", "person", "", List.of(), List.of());

        assertThat(NebulaGraphStore.saveEntityStatement("ws", record))
            .contains(", true, " + NebulaSupport.quoted("{}") + ");");
    }

    @Test
    void edgeInsertCarriesTheAttributesColumnLast() {
        var record = new RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "linked",
            0.8d,
            "chunk-1",
            "/docs/a.md",
            Map.of("since", 2020L)
        );

        var statement = NebulaGraphStore.saveRelationStatement("ws", record, "src-vid", "tgt-vid");

        assertThat(statement)
            .contains("INSERT EDGE directed(relation_id, src_id, tgt_id, keywords, description, weight, "
                + "source_id, file_path, workspace_id, attributes) VALUES")
            .contains("\"src-vid\"->\"tgt-vid\":")
            .contains(NebulaSupport.quoted("{\"since\":2020}") + ");");
    }

    @Test
    void entityRecordsParseTheAttributeBagAndDefaultLegacyRowsToEmpty() {
        var withAttributes = NebulaGraphStore.toEntityRecord(Map.of(
            "entity_id", "entity-1",
            "name", "Alice",
            "entity_type", "person",
            "attributes", "{\"age\":42,\"tags\":[\"x\",\"y\"]}"
        ));
        var legacy = NebulaGraphStore.toEntityRecord(Map.of(
            "entity_id", "entity-1",
            "name", "Alice"
        ));
        var malformed = NebulaGraphStore.toEntityRecord(Map.of(
            "entity_id", "entity-1",
            "name", "Alice",
            "attributes", "{not json"
        ));

        assertThat(withAttributes.attributes()).isEqualTo(Map.of("age", 42L, "tags", List.of("x", "y")));
        assertThat(legacy.attributes()).isEmpty();
        assertThat(malformed.attributes()).isEmpty();
    }

    @Test
    void relationRecordsParseTheAttributeBagAndDefaultLegacyRowsToEmpty() {
        var withAttributes = NebulaGraphStore.toRelationRecord(Map.of(
            "relation_id", "relation-1",
            "src_id", "entity-1",
            "tgt_id", "entity-2",
            "keywords", "knows",
            "description", "linked",
            "weight", 0.8d,
            "attributes", "{\"since\":2020}"
        ));
        var legacy = NebulaGraphStore.toRelationRecord(Map.of(
            "relation_id", "relation-1",
            "src_id", "entity-1",
            "tgt_id", "entity-2",
            "keywords", "knows",
            "description", "linked",
            "weight", 0.8d
        ));

        assertThat(withAttributes.attributes()).isEqualTo(Map.of("since", 2020L));
        assertThat(legacy.attributes()).isEmpty();
    }

    @Test
    void missingAttributeColumnFailuresAreExplainedWithTheUpgradeStatements() {
        var original = new StorageException(
            "NebulaGraph save entity 'entity-1' failed: [1004] Unsupported column `attributes'."
        );

        var enriched = NebulaGraphStore.attributeColumnHint("save entity 'entity-1'", original);

        assertThat(enriched).isNotSameAs(original);
        assertThat(enriched.getMessage())
            .contains("no 'attributes' column")
            .contains(NebulaSupport.ALTER_TAG_ATTRIBUTES_STATEMENT)
            .contains(NebulaSupport.ALTER_EDGE_ATTRIBUTES_STATEMENT);
        assertThat(enriched.getCause()).isSameAs(original);
    }

    @Test
    void unrelatedWriteFailuresArePassedThroughUnchanged() {
        var original = new StorageException("NebulaGraph save entity 'entity-1' failed: [1004] Syntax error");

        assertThat(NebulaGraphStore.attributeColumnHint("save entity 'entity-1'", original))
            .isSameAs(original);
    }
}
