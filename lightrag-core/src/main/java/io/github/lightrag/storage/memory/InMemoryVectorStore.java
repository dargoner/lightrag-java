package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

public final class InMemoryVectorStore implements HybridVectorStore {
    private static final Comparator<VectorMatch> MATCH_ORDER =
        Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id);

    private final Map<String, Map<String, EnrichedVectorRecord>> recordsByNamespace = new TreeMap<>();
    private final ReadWriteLock lock;

    public InMemoryVectorStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryVectorStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        saveAllEnriched(
            namespace,
            Objects.requireNonNull(vectors, "vectors").stream()
                .map(vector -> new EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
                .toList()
        );
    }

    @Override
    public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(records, "records");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            var namespaceRecords = recordsByNamespace.computeIfAbsent(targetNamespace, ignored -> new TreeMap<>());
            for (var record : records) {
                var normalizedRecord = Objects.requireNonNull(record, "record");
                namespaceRecords.put(normalizedRecord.id(), normalizedRecord);
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
            var namespaceRecords = recordsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (namespaceRecords == null || namespaceRecords.isEmpty()) {
                return List.of();
            }
            return namespaceRecords.values().stream()
                .map(record -> new VectorMatch(record.id(), dotProduct(queryVector, record.vector())))
                .sorted(MATCH_ORDER)
                .limit(topK)
                .toList();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<VectorMatch> search(String namespace, SearchRequest request) {
        var searchRequest = Objects.requireNonNull(request, "request");
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var namespaceRecords = recordsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (namespaceRecords == null || namespaceRecords.isEmpty()) {
                return List.of();
            }
            return namespaceRecords.values().stream()
                .map(record -> new VectorMatch(record.id(), score(record, searchRequest)))
                .sorted(MATCH_ORDER)
                .limit(searchRequest.topK())
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
            var namespaceRecords = recordsByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (namespaceRecords == null || namespaceRecords.isEmpty()) {
                return List.of();
            }
            return namespaceRecords.values().stream()
                .map(EnrichedVectorRecord::toVectorRecord)
                .toList();
        } finally {
            readLock.unlock();
        }
    }

    public Map<String, List<VectorRecord>> snapshot() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var snapshot = new LinkedHashMap<String, List<VectorRecord>>();
            for (var entry : recordsByNamespace.entrySet()) {
                snapshot.put(
                    entry.getKey(),
                    entry.getValue().values().stream().map(EnrichedVectorRecord::toVectorRecord).toList()
                );
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
            recordsByNamespace.clear();
            for (var entry : Objects.requireNonNull(snapshot, "snapshot").entrySet()) {
                var namespaceVectors = new TreeMap<String, EnrichedVectorRecord>();
                for (var vector : entry.getValue()) {
                    namespaceVectors.put(
                        vector.id(),
                        new EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of())
                    );
                }
                recordsByNamespace.put(entry.getKey(), namespaceVectors);
            }
        } finally {
            writeLock.unlock();
        }
    }

    private static double score(EnrichedVectorRecord record, SearchRequest request) {
        return switch (request.mode()) {
            case SEMANTIC -> semanticScore(record, request.queryVector());
            case KEYWORD -> keywordScore(record, request);
            case HYBRID -> semanticScore(record, request.queryVector()) + keywordScore(record, request);
        };
    }

    private static double semanticScore(EnrichedVectorRecord record, List<Double> queryVector) {
        if (queryVector.isEmpty()) {
            return 0.0d;
        }
        return dotProduct(queryVector, record.vector());
    }

    private static double keywordScore(EnrichedVectorRecord record, SearchRequest request) {
        var loweredSearchableText = record.searchableText().toLowerCase(java.util.Locale.ROOT);
        var loweredKeywords = record.keywords().stream()
            .map(keyword -> keyword.toLowerCase(java.util.Locale.ROOT))
            .toList();

        return Stream.concat(
                tokenize(request.queryText()).stream(),
                request.keywords().stream().map(keyword -> keyword.toLowerCase(java.util.Locale.ROOT))
            )
            .filter(token -> !token.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .mapToDouble(token -> {
                double score = 0.0d;
                if (loweredSearchableText.contains(token)) {
                    score += 1.0d;
                }
                if (loweredKeywords.contains(token)) {
                    score += 1.0d;
                }
                return score;
            })
            .sum();
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

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            .filter(token -> !token.isBlank())
            .toList();
    }
}
