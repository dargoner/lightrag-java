package io.github.lightrag.storage.nebula;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL/statement shape for the NebulaGraph attribute bag, without a live server: the tag and edge
 * schema declare the column, the bootstrap can upgrade legacy spaces, and the post-upgrade probe
 * writes and reads a non-empty bag on both.
 */
class NebulaSupportAttributesTest {

    @Test
    void createStatementsDeclareTheAttributeColumnOnTagAndEdge() {
        assertThat(NebulaSupport.CREATE_TAG_STATEMENT)
            .contains("materialized bool, attributes string")
            .endsWith(";");
        assertThat(NebulaSupport.CREATE_EDGE_STATEMENT)
            .contains("workspace_id string, attributes string")
            .endsWith(";");
    }

    @Test
    void existingSpacesAreUpgradedWithIdempotentAlterStatements() {
        assertThat(NebulaSupport.DESCRIBE_TAG_STATEMENT).isEqualTo("DESCRIBE TAG entity;");
        assertThat(NebulaSupport.DESCRIBE_EDGE_STATEMENT).isEqualTo("DESCRIBE EDGE directed;");
        assertThat(NebulaSupport.ALTER_TAG_ATTRIBUTES_STATEMENT)
            .isEqualTo("ALTER TAG entity ADD (attributes string);");
        assertThat(NebulaSupport.ALTER_EDGE_ATTRIBUTES_STATEMENT)
            .isEqualTo("ALTER EDGE directed ADD (attributes string);");
        assertThat(NebulaSupport.attributesUpgradeInstruction())
            .isEqualTo("ALTER TAG entity ADD (attributes string); ALTER EDGE directed ADD (attributes string);");
    }

    @Test
    void describeFieldListsAreCheckedForTheAttributeColumn() {
        assertThat(NebulaSupport.containsColumn(List.of("entity_id", "name", "attributes"), "attributes")).isTrue();
        assertThat(NebulaSupport.containsColumn(List.of("entity_id", "attributes "), "attributes")).isTrue();
        assertThat(NebulaSupport.containsColumn(List.of("entity_id", "name"), "attributes")).isFalse();
        assertThat(NebulaSupport.containsColumn(List.of(), "attributes")).isFalse();
    }

    @Test
    void javaStringLiteralsAreQuotedForNgl() {
        assertThat(NebulaSupport.quoted("{\"probe\":\"vertex\"}"))
            .isEqualTo("\"{\\\"probe\\\":\\\"vertex\\\"}\"");
        assertThat(NebulaSupport.quoted("a\nb\tc\\d"))
            .isEqualTo("\"a\\nb\\tc\\\\d\"");
    }

    @Test
    void attributeWriteProbeInsertsAndReadsTagAndEdgeAttributes() {
        var vertexJson = NebulaSupport.quoted(NebulaSupport.ATTRIBUTES_PROBE_VERTEX_JSON);
        var edgeJson = NebulaSupport.quoted(NebulaSupport.ATTRIBUTES_PROBE_EDGE_JSON);
        String sourceVid = "a".repeat(NebulaSupport.VID_LENGTH);
        String targetVid = "b".repeat(NebulaSupport.VID_LENGTH);

        assertThat(NebulaSupport.attributesProbeVertexInsertStatement(sourceVid, targetVid))
            .contains("INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, "
                + "file_path, workspace_id, materialized, attributes) VALUES")
            .contains(vertexJson)
            .contains("\"" + sourceVid + "\"")
            .contains("\"" + targetVid + "\"")
            .contains(", ");
        assertThat(NebulaSupport.attributesProbeEdgeInsertStatement(sourceVid, targetVid))
            .contains("INSERT EDGE directed(relation_id, src_id, tgt_id, keywords, description, weight, "
                + "source_id, file_path, workspace_id, attributes) VALUES")
            .contains("\"" + sourceVid + "\"->\"" + targetVid + "\"")
            .contains(edgeJson);
        assertThat(NebulaSupport.attributesProbeVertexReadStatement(sourceVid))
            .contains("RETURN v.entity.attributes AS attrs")
            .contains("\"" + sourceVid + "\"");
        assertThat(NebulaSupport.attributesProbeEdgeReadStatement(sourceVid, targetVid))
            .contains("RETURN e.attributes AS attrs")
            .contains("\"" + targetVid + "\"");
    }

    @Test
    void probeVidsAreFreshAndFullLength() {
        String first = NebulaSupport.newProbeVid();
        String second = NebulaSupport.newProbeVid();

        assertThat(first).hasSize(NebulaSupport.VID_LENGTH).matches("[0-9a-f]+");
        assertThat(second).hasSize(NebulaSupport.VID_LENGTH).matches("[0-9a-f]+");
        assertThat(first).isNotEqualTo(second);
    }
}
