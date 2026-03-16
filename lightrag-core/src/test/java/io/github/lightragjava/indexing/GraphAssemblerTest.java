package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.ExtractedEntity;
import io.github.lightragjava.types.ExtractedRelation;
import io.github.lightragjava.types.ExtractionResult;
import io.github.lightragjava.types.Relation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphAssemblerTest {
    @Test
    void mergesEntitiesByNormalizedName() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity(" Alice ", "person", "Researcher", List.of())), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("alice", "person", "Scientist", List.of())), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("entity:alice", "Alice", "person", "Researcher", List.of(), List.of("chunk-1", "chunk-2"))
        );
        assertThat(graph.relations()).isEmpty();
    }

    @Test
    void mergesEntitiesByExplicitAliases() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction("chunk-1", List.of(new ExtractedEntity("Robert", "person", "Lead", List.of("Bob"))), List.of()),
            extraction("chunk-2", List.of(new ExtractedEntity("Bob", "person", "Engineer", List.of("Bobby"))), List.of())
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("entity:robert", "Robert", "person", "Lead", List.of("Bob", "Bobby"), List.of("chunk-1", "chunk-2"))
        );
    }

    @Test
    void mergesRelationsByNormalizedEndpointsAndType() {
        var assembler = new GraphAssembler();

        var graph = assembler.assemble(List.of(
            extraction(
                "chunk-1",
                List.of(
                    new ExtractedEntity("Alice", "person", "Researcher", List.of()),
                    new ExtractedEntity("Bob", "person", "Engineer", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 0.8d))
            ),
            extraction(
                "chunk-2",
                List.of(
                    new ExtractedEntity(" alice ", "person", "Researcher", List.of()),
                    new ExtractedEntity("Robert", "person", "Engineer", List.of("Bob"))
                ),
                List.of(new ExtractedRelation("ALICE", "ROBERT", "works_with", "duplicate", 1.0d))
            )
        ));

        assertThat(graph.entities()).containsExactly(
            new Entity("entity:alice", "Alice", "person", "Researcher", List.of(), List.of("chunk-1", "chunk-2")),
            new Entity("entity:bob", "Bob", "person", "Engineer", List.of("Robert"), List.of("chunk-1", "chunk-2"))
        );
        assertThat(graph.relations()).containsExactly(
            new Relation(
                "relation:entity:alice|works_with|entity:bob",
                "entity:alice",
                "entity:bob",
                "works_with",
                "collaboration",
                1.0d,
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    private static GraphAssembler.ChunkExtraction extraction(
        String chunkId,
        List<ExtractedEntity> entities,
        List<ExtractedRelation> relations
    ) {
        return new GraphAssembler.ChunkExtraction(chunkId, new ExtractionResult(entities, relations, List.of()));
    }
}
