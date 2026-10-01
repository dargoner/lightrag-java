package io.github.lightrag.query;

import io.github.lightrag.storage.VectorStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Ranks candidate chunk ids by vector similarity against the chunk namespace
 * (upstream {@code pick_by_vector_similarity}, {@code utils.py:6780-6870}).
 *
 * <p>Divergence: upstream aborts the whole vector selection when any candidate lacks a
 * stored vector — {@code len(chunk_vectors) != len(all_chunk_ids)} → {@code []} → WEIGHT
 * ({@code utils.py:6803-6825}). Java's
 * {@link VectorStore#search(String, List, int)} returns the store's top-N and cannot
 * distinguish "candidate has no vector" from "store returned fewer matches", so partial
 * coverage keeps the ranked subset instead of aborting. Only a total miss falls back,
 * through the {@code ranked.isEmpty()} check in {@link KgChunkSelector#select}.
 */
final class VectorStoreChunkVectorRanker implements ChunkVectorRanker {
    private static final String CHUNK_NAMESPACE = "chunks";

    private final VectorStore vectorStore;

    VectorStoreChunkVectorRanker(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    @Override
    public List<String> rank(List<Double> queryVector, List<String> candidateIds, int topK) {
        if (candidateIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        var candidates = new LinkedHashSet<>(candidateIds);
        var selected = new ArrayList<String>(Math.min(topK, candidateIds.size()));
        var matches = vectorStore.search(CHUNK_NAMESPACE, queryVector, Math.max(topK, candidateIds.size()));
        for (var match : matches) {
            if (selected.size() >= topK) {
                break;
            }
            if (candidates.contains(match.id())) {
                selected.add(match.id());
            }
        }
        return List.copyOf(selected);
    }
}
