package io.github.lightrag.query;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {
    @Test
    void assemblesRelationsUsingEndpointPairAndKeywords() {
        var assembler = new ContextAssembler();
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1"));
        var relation = new Relation(
            "rel-1",
            atlas.id(),
            graphStore.id(),
            "depends_on, owned_by",
            "Atlas depends on GraphStore.",
            0.88d,
            List.of("chunk-1")
        );
        var chunk = new Chunk("chunk-1", "doc-1", "Atlas 依赖 GraphStore。", 4, 0, Map.of());

        var context = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(new ScoredRelation(relation.id(), relation, 0.88d)),
            List.of(new ScoredChunk(chunk.id(), chunk, 0.90d)),
            ""
        );

        var assembled = assembler.assemble(context);

        assertThat(assembled)
            .contains("Relations:")
            .contains("- atlas -> graphstore | depends_on, owned_by | 0.880")
            .doesNotContain("- rel-1 | depends_on, owned_by | 0.880");
    }
}
