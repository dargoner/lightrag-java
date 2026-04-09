package io.github.lightrag.indexing.refinement;

import io.github.lightrag.indexing.GraphAssembler;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionRefinementPipelineTest {
    @Test
    void returnsPrimaryExtractionsUnchangedWhenRefinementIsDisabled() {
        var pipeline = new ExtractionRefinementPipeline(
            ExtractionRefinementOptions.disabled(),
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, extractions) -> {
                throw new AssertionError("should not refine");
            },
            new DefaultAttributionResolver(false),
            new DefaultExtractionMergePolicy()
        );
        var primary = List.of(primary(
            "chunk-1",
            "订单系统依赖",
            List.of(
                new ExtractedEntity("订单系统", "", "", List.of()),
                new ExtractedEntity("PostgreSQL", "", "", List.of())
            ),
            List.of()
        ));

        var result = pipeline.refine(primary);

        assertThat(result).containsExactly(
            new GraphAssembler.ChunkExtraction("chunk-1", primary.get(0).extraction())
        );
    }

    @Test
    void augmentsPrimaryExtractionsWhenAdjacentWindowProducesAttributedRelation() {
        var primary = List.of(
            primary(
                "chunk-1",
                "订单系统依赖",
                List.of(
                    new ExtractedEntity("订单系统", "", "", List.of()),
                    new ExtractedEntity("PostgreSQL", "", "", List.of())
                ),
                List.of()
            ),
            primary(
                "chunk-2",
                "PostgreSQL 进行事务存储",
                List.of(new ExtractedEntity("PostgreSQL", "", "", List.of())),
                List.of()
            )
        );
        var pipeline = new ExtractionRefinementPipeline(
            new ExtractionRefinementOptions(true, false, 3, 1_200, 1, 1, 1),
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, ignored) -> new RefinedWindowExtraction(
                List.of(),
                List.of(new RefinedRelationPatch(
                    new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                    List.of("chunk-1", "chunk-2")
                )),
                List.of(),
                true
            ),
            new DefaultAttributionResolver(false),
            new DefaultExtractionMergePolicy()
        );

        var result = pipeline.refine(primary);

        assertThat(result).allSatisfy(extraction -> assertThat(extraction.extraction().relations()).hasSize(1));
    }

    @Test
    void skipsRefinementWhenWindowTokenBudgetWouldBeExceededForChineseText() {
        var primary = List.of(primary(
            "chunk-1",
            "订单系统依赖PostgreSQL进行事务存储",
            List.of(
                new ExtractedEntity("订单系统", "", "", List.of()),
                new ExtractedEntity("PostgreSQL", "", "", List.of())
            ),
            List.of()
        ));
        var pipeline = new ExtractionRefinementPipeline(
            new ExtractionRefinementOptions(true, false, 3, 5, 1, 1, 1),
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, extractions) -> {
                throw new AssertionError("window should be skipped by token budget");
            },
            new DefaultAttributionResolver(false),
            new DefaultExtractionMergePolicy()
        );

        var result = pipeline.refine(primary);

        assertThat(result).containsExactly(
            new GraphAssembler.ChunkExtraction("chunk-1", primary.get(0).extraction())
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
