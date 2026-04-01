package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.VectorStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryVectorStore implements VectorStore {
    private static final Comparator<VectorMatch> MATCH_ORDER =
        Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id);

    private final Map<String, Map<String, VectorRecord>> vectorsByNamespace = new TreeMap<>();
    private final ReadWriteLock lock;

    public InMemoryVectorStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryVectorStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(vectors, "vectors");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            var namespaceVectors = vectorsByNamespace.computeIfAbsent(targetNamespace, ignored -> new TreeMap<>());
            for (var vector : vectors) {
                var record = Objects.requireNonNull(vector, "vector");
                namespaceVectors.put(record.id(), record);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        Objects.requireNonNull(queryVector, "queryVector");
        if (topK <= 0) {
            return List.of();
        }
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var namespaceVectors = vectorsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (namespaceVectors == null || namespaceVectors.isEmpty()) {
                return List.of();
            }
            return namespaceVectors.values().stream()
                .map(vector -> new VectorMatch(vector.id(), dotProduct(queryVector, vector.vector())))
                .sorted(MATCH_ORDER)
                .limit(topK)
                .toList();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var namespaceVectors = vectorsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (namespaceVectors == null || namespaceVectors.isEmpty()) {
                return List.of();
            }
            return List.copyOf(namespaceVectors.values());
        } finally {
            readLock.unlock();
        }
    }

    public Map<String, List<VectorRecord>> snapshot() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var snapshot = new LinkedHashMap<String, List<VectorRecord>>();
            for (var entry : vectorsByNamespace.entrySet()) {
                snapshot.put(entry.getKey(), List.copyOf(entry.getValue().values()));
            }
            return Map.copyOf(snapshot);
        } finally {
            readLock.unlock();
        }
    }

    public void restore(Map<String, List<VectorRecord>> snapshot) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            vectorsByNamespace.clear();
            for (var entry : Objects.requireNonNull(snapshot, "snapshot").entrySet()) {
                var namespaceVectors = new TreeMap<String, VectorRecord>();
                for (var vector : entry.getValue()) {
                    namespaceVectors.put(vector.id(), vector);
                }
                vectorsByNamespace.put(entry.getKey(), namespaceVectors);
            }
        } finally {
            writeLock.unlock();
        }
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
