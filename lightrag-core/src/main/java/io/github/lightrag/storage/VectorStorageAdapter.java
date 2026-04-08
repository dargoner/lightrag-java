package io.github.lightrag.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface VectorStorageAdapter {
    VectorStore vectorStore();

    VectorSnapshot captureSnapshot();

    void apply(StagedVectorWrites writes);

    void restore(VectorSnapshot snapshot);

    record VectorSnapshot(Map<String, List<VectorStore.VectorRecord>> namespaces) {
        public VectorSnapshot {
            namespaces = copyVectorNamespaces(Objects.requireNonNull(namespaces, "namespaces"));
        }

        public static VectorSnapshot empty() {
            return new VectorSnapshot(Map.of());
        }
    }

    record StagedVectorWrites(
        Map<String, List<HybridVectorStore.EnrichedVectorRecord>> upserts,
        List<String> namespacesToReset
    ) {
        public StagedVectorWrites {
            upserts = copyEnrichedNamespaces(Objects.requireNonNull(upserts, "upserts"));
            namespacesToReset = List.copyOf(Objects.requireNonNull(namespacesToReset, "namespacesToReset"));
        }

        public static StagedVectorWrites empty() {
            return new StagedVectorWrites(Map.of(), List.of());
        }
    }

    static VectorStorageAdapter noop() {
        return new VectorStorageAdapter() {
            private final VectorStore vectorStore = new VectorStore() {
                @Override
                public void saveAll(String namespace, List<VectorRecord> vectors) {
                }

                @Override
                public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
                    return List.of();
                }

                @Override
                public List<VectorRecord> list(String namespace) {
                    return List.of();
                }
            };

            @Override
            public VectorStore vectorStore() {
                return vectorStore;
            }

            @Override
            public VectorSnapshot captureSnapshot() {
                return VectorSnapshot.empty();
            }

            @Override
            public void apply(StagedVectorWrites writes) {
            }

            @Override
            public void restore(VectorSnapshot snapshot) {
            }
        };
    }

    private static Map<String, List<VectorStore.VectorRecord>> copyVectorNamespaces(
        Map<String, List<VectorStore.VectorRecord>> namespaces
    ) {
        var copy = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        for (var entry : namespaces.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static Map<String, List<HybridVectorStore.EnrichedVectorRecord>> copyEnrichedNamespaces(
        Map<String, List<HybridVectorStore.EnrichedVectorRecord>> namespaces
    ) {
        var copy = new LinkedHashMap<String, List<HybridVectorStore.EnrichedVectorRecord>>();
        for (var entry : namespaces.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
