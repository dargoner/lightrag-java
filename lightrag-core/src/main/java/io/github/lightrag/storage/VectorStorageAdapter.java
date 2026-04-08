package io.github.lightrag.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface VectorStorageAdapter extends AutoCloseable {
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
        Map<String, List<VectorWrite>> upserts
    ) {
        public StagedVectorWrites {
            upserts = copyWrites(Objects.requireNonNull(upserts, "upserts"));
        }

        public static StagedVectorWrites empty() {
            return new StagedVectorWrites(Map.of());
        }

        public boolean isEmpty() {
            return upserts.isEmpty();
        }
    }

    record VectorWrite(
        String id,
        List<Double> vector,
        String searchableText,
        List<String> keywords
    ) {
        public VectorWrite {
            id = Objects.requireNonNull(id, "id");
            vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
            searchableText = searchableText == null ? "" : searchableText;
            keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
        }

        public static VectorWrite of(VectorStore.VectorRecord record) {
            var source = Objects.requireNonNull(record, "record");
            return new VectorWrite(source.id(), source.vector(), "", List.of());
        }

        public static VectorWrite of(HybridVectorStore.EnrichedVectorRecord record) {
            var source = Objects.requireNonNull(record, "record");
            return new VectorWrite(source.id(), source.vector(), source.searchableText(), source.keywords());
        }

        public VectorStore.VectorRecord toVectorRecord() {
            return new VectorStore.VectorRecord(id, vector);
        }

        public HybridVectorStore.EnrichedVectorRecord toEnrichedVectorRecord() {
            return new HybridVectorStore.EnrichedVectorRecord(id, vector, searchableText, keywords);
        }

        public boolean hasMetadata() {
            return !searchableText.isBlank() || !keywords.isEmpty();
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

    private static Map<String, List<VectorWrite>> copyWrites(Map<String, List<VectorWrite>> writes) {
        var copy = new LinkedHashMap<String, List<VectorWrite>>();
        for (var entry : writes.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    @Override
    default void close() {
    }
}
