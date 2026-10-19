package io.github.lightrag.indexing;

import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import io.github.lightrag.types.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphAssemblerAttributeTest {

    @Test
    void mergesEntityAttributesPerKeyAcrossChunksWithRightSideWinning() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity(
                "Alice", "person", "a", List.of(),
                Map.of("age", 30L, "city", "X"))), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity(
                "alice", "person", "b", List.of(),
                Map.of("age", 31L, "tags", List.of("t")))), List.of())
        ));

        assertThat(graph.entities()).hasSize(1);
        var entity = graph.entities().get(0);
        assertThat(entity.name()).isEqualTo("Alice");
        assertThat(entity.attributes()).containsExactlyInAnyOrderEntriesOf(
            Map.of("age", 31L, "city", "X", "tags", List.of("t")));
    }

    @Test
    void mergesRelationAttributesPerKeyAcrossChunksWithRightSideWinning() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(new ExtractedEntity("Alice", "person", "", List.of())),
                List.of(new ExtractedRelation(
                    "Alice", "Bob", "works_with", "first", 1.0d,
                    Map.of("since", "2020-01-01", "confidence", 0.5d)))),
            extraction(
                "chunk-2",
                List.of(new ExtractedEntity("Alice", "person", "", List.of())),
                List.of(new ExtractedRelation(
                    "Alice", "Bob", "works_with", "second", 1.0d,
                    Map.of("confidence", 0.9d))))
        ));

        assertThat(graph.relations()).hasSize(1);
        var relation = graph.relations().get(0);
        assertThat(relation.attributes()).containsExactlyInAnyOrderEntriesOf(
            Map.of("since", "2020-01-01", "confidence", 0.9d));
    }

    @Test
    void mergeGuardSanitizeRebuildKeepsAttributes() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(new ExtractedEntity(
                    "Alice", "person", "", List.of("Bob"), Map.of("age", 30L))),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 1.0d)))
        ));

        var alice = graph.entities().stream()
            .filter(entity -> entity.name().equals("Alice"))
            .findFirst()
            .orElseThrow();
        assertThat(alice.aliases()).isEmpty();
        assertThat(alice.attributes()).containsExactly(Map.entry("age", 30L));
    }

    @Test
    void placeholderEndpointsCarryEmptyAttributes() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(new ExtractedEntity("Alice", "person", "", List.of())),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 1.0d)))
        ));

        var bob = graph.entities().stream()
            .filter(entity -> entity.name().equals("Bob"))
            .findFirst()
            .orElseThrow();
        assertThat(bob.attributes()).isEmpty();
    }

    @Test
    void toEntityAndToRelationKeepAttributesFromImmutableRecords() {
        var assembler = new GraphAssembler();
        var graph = assembler.assemble(List.of(extraction(
            "chunk-1",
            List.of(new ExtractedEntity(
                "Alice", "person", "", List.of(), Map.of("age", 30L))),
            List.of(new ExtractedRelation(
                "Alice", "Bob", "works_with", "collaboration", 1.0d,
                Map.of("since", "2020-01-01"))))));

        Entity alice = graph.entities().stream()
            .filter(entity -> entity.name().equals("Alice"))
            .findFirst()
            .orElseThrow();
        Relation relation = graph.relations().get(0);
        assertThat(alice.attributes()).containsExactly(Map.entry("age", 30L));
        assertThat(relation.attributes()).containsExactly(Map.entry("since", "2020-01-01"));
    }

    private static GraphAssembler.ChunkExtraction extraction(
        String chunkId,
        List<ExtractedEntity> entities,
        List<ExtractedRelation> relations
    ) {
        return new GraphAssembler.ChunkExtraction(
            chunkId,
            new ExtractionResult(entities, relations, List.of())
        );
    }
}
