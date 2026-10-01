package io.github.lightrag.query;

import java.util.List;

/** Ranks candidate chunk ids by similarity to the query vector, highest first. */
@FunctionalInterface
interface ChunkVectorRanker {
    List<String> rank(List<Double> queryVector, List<String> candidateIds, int topK);
}
