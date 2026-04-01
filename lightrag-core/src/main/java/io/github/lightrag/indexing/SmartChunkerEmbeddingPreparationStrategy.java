package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.List;
import java.util.Objects;

final class SmartChunkerEmbeddingPreparationStrategy implements DocumentChunkPreparationStrategy {
    private final SemanticChunkRefiner semanticChunkRefiner;
    private final ChunkSimilarityScorer chunkSimilarityScorer;
    private final double embeddingSemanticMergeThreshold;

    SmartChunkerEmbeddingPreparationStrategy(
        SemanticChunkRefiner semanticChunkRefiner,
        ChunkSimilarityScorer chunkSimilarityScorer,
        double embeddingSemanticMergeThreshold
    ) {
        this.semanticChunkRefiner = Objects.requireNonNull(semanticChunkRefiner, "semanticChunkRefiner");
        this.chunkSimilarityScorer = Objects.requireNonNull(chunkSimilarityScorer, "chunkSimilarityScorer");
        this.embeddingSemanticMergeThreshold = embeddingSemanticMergeThreshold;
    }

    @Override
    public List<Chunk> prepare(Document document, Chunker chunker) {
        var source = Objects.requireNonNull(document, "document");
        Objects.requireNonNull(chunker, "chunker");
        if (!(chunker instanceof SmartChunker smartChunker)) {
            throw new IllegalStateException("embedding semantic merge requires SmartChunker");
        }

        var structuralChunks = smartChunker.chunkStructural(source);
        if (structuralChunks.size() <= 1) {
            return structuralChunks;
        }
        return semanticChunkRefiner.refine(
            source.id(),
            structuralChunks,
            smartChunker.config().maxTokens(),
            embeddingSemanticMergeThreshold,
            chunkSimilarityScorer.similarityFor(structuralChunks),
            MergeMode.PAIRWISE_SINGLE_PASS
        );
    }
}
