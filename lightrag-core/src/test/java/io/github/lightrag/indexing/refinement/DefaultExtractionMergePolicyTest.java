package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExtractionMergePolicyTest {
    @Test
    void injectsMinimalEndpointEntitiesWhenChunkReceivesRelationPatch() {
        var mergePolicy = new DefaultExtractionMergePolicy();
        var primary = primary("chunk-1", "订单系统依赖", List.of(), List.of());
        var patch = new ChunkExtractionPatch(
            "chunk-1",
            List.of(),
            List.of(new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL", 1.0d))
        );

        var merged = mergePolicy.merge(primary, List.of(patch));

        assertThat(merged.extraction().entities()).contains(
            new ExtractedEntity("订单系统", "", "", List.of()),
            new ExtractedEntity("PostgreSQL", "", "", List.of())
        );
    }

    @Test
    void retainsPrimaryRelationsWhileAppendingPatchedOnes() {
        var mergePolicy = new DefaultExtractionMergePolicy();
        var primary = primary(
            "chunk-1",
            "订单系统依赖 PostgreSQL",
            List.of(new ExtractedEntity("订单系统", "", "", List.of())),
            List.of(new ExtractedRelation("订单系统", "Redis", "依赖", "已有关系", 0.6d))
        );
        var patch = new ChunkExtractionPatch(
            "chunk-1",
            List.of(),
            List.of(new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "补充关系", 1.0d))
        );

        var merged = mergePolicy.merge(primary, List.of(patch));

        assertThat(merged.extraction().relations()).containsExactlyInAnyOrder(
            new ExtractedRelation("订单系统", "Redis", "依赖", "已有关系", 0.6d),
            new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "补充关系", 1.0d)
        );
    }

    private static PrimaryChunkExtraction primary(
        String chunkId,
        String text,
        List<ExtractedEntity> entities,
        List<ExtractedRelation> relations
    ) {
        return new PrimaryChunkExtraction(
            new Chunk(chunkId, "doc-1", text, text.length(), 0, Map.of()),
            new ExtractionResult(entities, relations, List.of())
        );
    }
}
