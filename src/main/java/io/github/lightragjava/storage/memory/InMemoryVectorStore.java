package io.github.lightragjava.storage.memory;

import io.github.lightragjava.storage.VectorStore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class InMemoryVectorStore implements VectorStore {
    private static final Comparator<VectorMatch> MATCH_ORDER =
        Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id);

    private final ConcurrentHashMap<String, ConcurrentNavigableMap<String, VectorRecord>> vectorsByNamespace =
        new ConcurrentHashMap<>();

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(vectors, "vectors");
        var namespaceVectors = vectorsByNamespace.computeIfAbsent(targetNamespace, ignored -> new ConcurrentSkipListMap<>());
        for (var vector : vectors) {
            var record = Objects.requireNonNull(vector, "vector");
            namespaceVectors.put(record.id(), record);
        }
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        Objects.requireNonNull(queryVector, "queryVector");
        if (topK <= 0) {
            return List.of();
        }
        var namespaceVectors = vectorsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
        if (namespaceVectors == null || namespaceVectors.isEmpty()) {
            return List.of();
        }
        return namespaceVectors.values().stream()
            .map(vector -> new VectorMatch(vector.id(), dotProduct(queryVector, vector.vector())))
            .sorted(MATCH_ORDER)
            .limit(topK)
            .toList();
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        var namespaceVectors = vectorsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
        if (namespaceVectors == null || namespaceVectors.isEmpty()) {
            return List.of();
        }
        return List.copyOf(namespaceVectors.values());
    }

    private static double dotProduct(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) {
            throw new IllegalArgumentException("vector dimensions must match");
        }
        double score = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            score += left.get(index) * right.get(index);
        }
        return score;
    }
}
