package io.github.lightrag.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface VectorStorageAdapter extends AutoCloseable {
    VectorStore vectorStore();

    VectorSnapshot captureSnapshot();

    void apply(StagedVectorWrites writes);

    void restore(VectorSnapshot snapshot);

    /**
     * Captures a scoped pre-image of the write set: for every {@code namespace -> ids} entry, point-reads the state
     * that exists <i>before</i> {@link #apply}.
     *
     * <p>Contract: the returned payload must distinguish "present" from "absent" — an absent id is deleted during
     * compensation. Returning {@link Optional#empty()} means the implementation does not support scoped pre-images;
     * the caller then falls back to {@link #captureSnapshot()} + {@link #restore(VectorSnapshot)} (existing
     * semantics, still paid per whole workspace).</p>
     *
     * <p>Implementations must allow repeated calls: {@link StorageCoordinator} calls again when the PostgreSQL
     * transaction is retried, passing only ids it has not captured yet (so ids partially applied by an earlier
     * attempt still get a real pre-image). <b>Each call's return value is an independent payload</b> — the
     * coordinator stores them in capture order and hands them back one by one during compensation; implementations
     * need not (and must not assume they may) merge later results into earlier payloads. Pre-images across retries
     * are expressed as "only new ids + replay each payload", and the id sets of distinct payloads are disjoint by
     * construction.</p>
     */
    default Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        return Optional.empty();
    }

    /**
     * Rolls back the given pre-image. The payload is opaque to {@link StorageCoordinator} and may only be handed
     * back to the <b>same adapter instance that produced it</b>.
     *
     * <p><b>Implementation contract</b>:</p>
     * <ul>
     *   <li>An implementation whose {@link #capturePreImage} returns {@code Optional.of(...)} <b>must</b> override
     *       this method as well — the two are a pair; overriding only one is an implementation error;</li>
     *   <li>Type-check first: throw {@code IllegalArgumentException("unexpected pre-image payload: " + ...)} when
     *       the payload is not of this implementation's type instead of letting a raw
     *       {@code ClassCastException} escape (that would mask orchestration errors);</li>
     *   <li><b>Idempotent</b>: the same pre-image may be rolled back repeatedly with the same result;</li>
     *   <li>Rollback is a per-id point operation: ids present in the pre-image are written back with their
     *       pre-image value, ids absent from the pre-image are deleted. A batch write that failed midway (some ids
     *       already persisted) must still converge to the pre-image state.</li>
     * </ul>
     */
    default void restorePreImage(PreImage preImage) {
        throw new UnsupportedOperationException(
            "scoped pre-image restore is not supported by " + getClass().getName()
        );
    }

    /** Scoped pre-image payload; each adapter defines its own (see {@code MilvusVectorStorageAdapter.ScopedPreImage}). */
    interface PreImage {
    }

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
