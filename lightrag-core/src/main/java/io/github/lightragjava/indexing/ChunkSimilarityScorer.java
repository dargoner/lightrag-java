package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.List;

interface ChunkSimilarityScorer {
    SemanticSimilarity similarityFor(List<Chunk> chunks);
}
