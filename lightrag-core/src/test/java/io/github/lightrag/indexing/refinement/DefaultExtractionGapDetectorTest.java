package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExtractionGapDetectorTest {
    @Test
    void requestsAdjacentRefinementWhenPrescreenAndQualitySignalsBothMatch() {
        var detector = new DefaultExtractionGapDetector();
        var extractions = List.of(
            primary(
                "chunk-1",
                "订单系统依赖",
                List.of(
                    new ExtractedEntity("订单系统", "", "", List.of()),
                    new ExtractedEntity("PostgreSQL", "", "", List.of())
                ),
                List.of()
            )
        );

        var assessment = detector.assess(extractions, 0);

        assertThat(assessment.requiresRefinement()).isTrue();
        assertThat(assessment.recommendedScope()).isEqualTo(RefinementScope.ADJACENT);
        assertThat(assessment.prescreenSignals()).isNotEmpty();
        assertThat(assessment.qualitySignals()).contains("entities_without_relations");
    }

    @Test
    void skipsRefinementWhenOnlyPrescreenMatches() {
        var detector = new DefaultExtractionGapDetector();
        var extractions = List.of(
            primary(
                "chunk-1",
                "订单系统依赖 PostgreSQL",
                List.of(
                    new ExtractedEntity("订单系统", "", "", List.of()),
                    new ExtractedEntity("PostgreSQL", "", "", List.of())
                ),
                List.of(new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "完整关系", 1.0d))
            )
        );

        assertThat(detector.assess(extractions, 0).requiresRefinement()).isFalse();
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
