package io.github.lightragjava.indexing;

import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.types.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticChunkRefinerTest {
    @Test
    void mergesAdjacentChunksAndNormalizesIdsAndLinks() {
        var refiner = new SemanticChunkRefiner((left, right) -> 0.95d);
        var chunks = List.of(
            chunk("doc-1:0", 0, "Alpha retrieval.", "paragraph:0"),
            chunk("doc-1:1", 1, "Alpha retrieval detail.", "paragraph:1")
        );

        var refined = refiner.refine("doc-1", chunks, 120, 0.8d);

        assertThat(refined).hasSize(1);
        assertThat(refined.get(0).id()).isEqualTo("doc-1:0");
        assertThat(refined.get(0).order()).isEqualTo(0);
        assertThat(refined.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "paragraph:0,paragraph:1")
            .containsEntry(SmartChunkMetadata.PREV_CHUNK_ID, "")
            .containsEntry(SmartChunkMetadata.NEXT_CHUNK_ID, "");
    }

    @Test
    void keepsChunksSeparateWhenSimilarityIsBelowThreshold() {
        var refiner = new SemanticChunkRefiner((left, right) -> 0.10d);
        var chunks = List.of(
            chunk("doc-1:0", 0, "Alpha retrieval.", "paragraph:0"),
            chunk("doc-1:1", 1, "Billing details.", "paragraph:1")
        );

        var refined = refiner.refine("doc-1", chunks, 120, 0.8d);

        assertThat(refined)
            .extracting(Chunk::id)
            .containsExactly("doc-1:0", "doc-1:1");
    }

    @Test
    void singleChunkIsNormalized() {
        var refiner = new SemanticChunkRefiner((left, right) -> 0.95d);
        var chunks = List.of(
            chunk("doc-1:9", 9, "Alpha retrieval.", "paragraph:9")
        );

        var refined = refiner.refine("doc-1", chunks, 120, 0.8d);

        assertThat(refined).hasSize(1);
        assertThat(refined.get(0).id()).isEqualTo("doc-1:0");
        assertThat(refined.get(0).order()).isEqualTo(0);
        assertThat(refined.get(0).metadata())
            .containsEntry(SmartChunkMetadata.PREV_CHUNK_ID, "")
            .containsEntry(SmartChunkMetadata.NEXT_CHUNK_ID, "");
    }

    @Test
    void pairwiseSinglePassMergesOnlyOneAdjacentPairPerPass() {
        var refiner = new SemanticChunkRefiner();
        var similarity = (SemanticSimilarity) (left, right) -> 0.95d;
        var chunks = List.of(
            chunk("doc-1:0", 0, "Alpha retrieval.", "paragraph:0"),
            chunk("doc-1:1", 1, "Alpha retrieval detail.", "paragraph:1"),
            chunk("doc-1:2", 2, "Alpha retrieval extension.", "paragraph:2")
        );

        var refined = refiner.refine("doc-1", chunks, 200, 0.8d, similarity, MergeMode.PAIRWISE_SINGLE_PASS);

        assertThat(refined).hasSize(2);
    }

    @Test
    void embeddingModeRejectsSectionBoundaryAndTableMerges() {
        var refiner = new SemanticChunkRefiner();
        var similarity = (SemanticSimilarity) (left, right) -> 0.95d;
        var sectionBoundaryChunks = List.of(
            chunk("doc-1:0", 0, "Alpha retrieval.", "paragraph:0", "Section A", "text"),
            chunk("doc-1:1", 1, "Alpha retrieval detail.", "paragraph:1", "Section B", "text")
        );

        var sectionRefined = refiner.refine(
            "doc-1",
            sectionBoundaryChunks,
            200,
            0.8d,
            similarity,
            MergeMode.PAIRWISE_SINGLE_PASS
        );

        assertThat(sectionRefined).extracting(Chunk::id).containsExactly("doc-1:0", "doc-1:1");

        var tableChunks = List.of(
            chunk("doc-1:0", 0, "Alpha retrieval.", "table:0", "Section A", "table"),
            chunk("doc-1:1", 1, "Alpha retrieval detail.", "table:1", "Section A", "table")
        );

        var tableRefined = refiner.refine(
            "doc-1",
            tableChunks,
            200,
            0.8d,
            similarity,
            MergeMode.PAIRWISE_SINGLE_PASS
        );

        assertThat(tableRefined).extracting(Chunk::id).containsExactly("doc-1:0", "doc-1:1");
    }

    @Test
    void embeddingModeFailsFastWhenRequiredSmartChunkMetadataIsMissing() {
        var refiner = new SemanticChunkRefiner();
        var similarity = (SemanticSimilarity) (left, right) -> 0.95d;
        var chunks = List.of(
            chunkWithoutSectionMetadata("doc-1:0", 0, "Alpha retrieval.", "paragraph:0"),
            chunkWithoutSectionMetadata("doc-1:1", 1, "Alpha retrieval detail.", "paragraph:1")
        );

        assertThatThrownBy(() -> refiner.refine(
            "doc-1",
            chunks,
            200,
            0.8d,
            similarity,
            MergeMode.PAIRWISE_SINGLE_PASS
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(SmartChunkMetadata.SECTION_PATH);
    }

    @Test
    void embeddingBatcherPreservesOrderAcrossBatches() {
        var model = new EmbeddingModel() {
            private int counter;

            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                return texts.stream()
                    .map(text -> List.of((double) counter++))
                    .toList();
            }
        };
        var batcher = new EmbeddingBatcher(model, 2);

        var embeddings = batcher.embedAll(List.of("A", "B", "C"));

        assertThat(embeddings).containsExactly(
            List.of(0.0d),
            List.of(1.0d),
            List.of(2.0d)
        );
    }

    @Test
    void embeddingBatcherFailsWhenBatchResultCountDoesNotMatchInputCount() {
        var model = new EmbeddingModel() {
            private boolean firstBatch = true;

            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                if (firstBatch) {
                    firstBatch = false;
                    return List.of(List.of(0.0d), List.of(1.0d));
                }
                return List.of();
            }
        };
        var batcher = new EmbeddingBatcher(model, 2);

        assertThatThrownBy(() -> batcher.embedAll(List.of("A", "B", "C")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Embedding size mismatch for batch");
    }

    @Test
    void embeddingBatcherFailsWhenSingleBatchResultCountDoesNotMatchInputCount() {
        var model = new EmbeddingModel() {
            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                return List.of(List.of(0.0d));
            }
        };
        var batcher = new EmbeddingBatcher(model, 8);

        assertThatThrownBy(() -> batcher.embedAll(List.of("A", "B")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Embedding size mismatch for batch");
    }

    private static Chunk chunk(String id, int order, String text, String sourceBlockId) {
        return chunk(id, order, text, sourceBlockId, "Guide", "text");
    }

    private static Chunk chunk(String id, int order, String text, String sourceBlockId, String sectionPath, String contentType) {
        return new Chunk(
            id,
            "doc-1",
            text,
            text.codePointCount(0, text.length()),
            order,
            Map.of(
                SmartChunkMetadata.SECTION_PATH, sectionPath,
                SmartChunkMetadata.CONTENT_TYPE, contentType,
                SmartChunkMetadata.SOURCE_BLOCK_IDS, sourceBlockId,
                SmartChunkMetadata.PREV_CHUNK_ID, order == 0 ? "" : "doc-1:0",
                SmartChunkMetadata.NEXT_CHUNK_ID, ""
            )
        );
    }

    private static Chunk chunkWithoutSectionMetadata(String id, int order, String text, String sourceBlockId) {
        return new Chunk(
            id,
            "doc-1",
            text,
            text.codePointCount(0, text.length()),
            order,
            Map.of(
                SmartChunkMetadata.CONTENT_TYPE, "text",
                SmartChunkMetadata.SOURCE_BLOCK_IDS, sourceBlockId,
                SmartChunkMetadata.PREV_CHUNK_ID, order == 0 ? "" : "doc-1:0",
                SmartChunkMetadata.NEXT_CHUNK_ID, ""
            )
        );
    }
}
