package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;

import java.util.List;

interface ChunkSimilarityScorer {
    SemanticSimilarity similarityFor(List<Chunk> chunks);
}
