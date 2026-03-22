package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.List;

public interface ChunkSimilarityScorer {
    SemanticSimilarity similarityFor(List<Chunk> chunks);
}
