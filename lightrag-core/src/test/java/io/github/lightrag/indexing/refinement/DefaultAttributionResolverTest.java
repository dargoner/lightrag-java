package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAttributionResolverTest {
    @Test
    void distributesRelationPatchToAllSupportingChunks() {
        var resolver = new DefaultAttributionResolver(false);
        var window = window(
            "doc-1",
            chunk("chunk-1", "订单系统依赖"),
            chunk("chunk-2", "PostgreSQL 事务")
        );
        var refined = new RefinedWindowExtraction(
            List.of(),
            List.of(new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of("chunk-1", "chunk-2")
            )),
            List.of(),
            true
        );

        var patches = resolver.distribute(refined, window);

        assertThat(patches).hasSize(2);
        assertThat(patches).extracting(ChunkExtractionPatch::chunkId).containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void keepsPatchedRelationOnEachTargetChunk() {
        var resolver = new DefaultAttributionResolver(false);
        var window = window(
            "doc-1",
            chunk("chunk-1", "订单系统依赖"),
            chunk("chunk-2", "PostgreSQL 事务")
        );
        var refined = new RefinedWindowExtraction(
            List.of(),
            List.of(new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of("chunk-1", "chunk-2")
            )),
            List.of(),
            true
        );

        var patches = resolver.distribute(refined, window);

        assertThat(patches).allSatisfy(patch -> assertThat(patch.relations()).containsExactly(
            new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d)
        ));
    }

    @Test
    void doesNotFallbackWhenSupportingChunkIdsDoNotMatchWindow() {
        var resolver = new DefaultAttributionResolver(false);
        var window = window("doc-1", chunk("chunk-1", "订单系统依赖"));
        var refined = new RefinedWindowExtraction(
            List.of(),
            List.of(new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL", 1.0d),
                List.of("chunk-9")
            )),
            List.of(),
            true
        );

        assertThat(resolver.distribute(refined, window)).isEmpty();
    }

    @Test
    void fallsBackToDeterministicChunkMatchWhenEnabled() {
        var resolver = new DefaultAttributionResolver(true);
        var window = window("doc-1", chunk("chunk-1", "订单系统依赖 PostgreSQL 进行事务存储"));
        var refined = new RefinedWindowExtraction(
            List.of(),
            List.of(new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of()
            )),
            List.of(),
            true
        );

        assertThat(resolver.distribute(refined, window))
            .extracting(ChunkExtractionPatch::chunkId)
            .containsExactly("chunk-1");
    }

    private static RefinementWindow window(String documentId, Chunk... chunks) {
        return new RefinementWindow(documentId, List.of(chunks), 0, RefinementScope.ADJACENT, 16);
    }

    private static Chunk chunk(String chunkId, String text) {
        return new Chunk(chunkId, "doc-1", text, text.length(), 0, Map.of());
    }
}
