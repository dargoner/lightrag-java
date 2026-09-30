package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class MilvusVectorStorageAdapter implements VectorStorageAdapter {
    private static final List<String> DEFAULT_NAMESPACES = List.of("chunks", "entities", "relations");

    private final Projection projection;
    private final EnrichedPayloadResolver payloadResolver;

    public MilvusVectorStorageAdapter(MilvusVectorStore vectorStore) {
        this(new MilvusStoreProjection(Objects.requireNonNull(vectorStore, "vectorStore")));
    }

    public MilvusVectorStorageAdapter(MilvusVectorStore vectorStore, EnrichedPayloadResolver payloadResolver) {
        this(new MilvusStoreProjection(Objects.requireNonNull(vectorStore, "vectorStore")), payloadResolver);
    }

    public MilvusVectorStorageAdapter(Projection projection) {
        this(projection, MilvusVectorStorageAdapter::defaultPayloads);
    }

    public MilvusVectorStorageAdapter(Projection projection, EnrichedPayloadResolver payloadResolver) {
        this.projection = Objects.requireNonNull(projection, "projection");
        this.payloadResolver = Objects.requireNonNull(payloadResolver, "payloadResolver");
    }

    @Override
    public VectorStore vectorStore() {
        return projection;
    }

    @Override
    public VectorSnapshot captureSnapshot() {
        var namespaces = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        for (var namespace : DEFAULT_NAMESPACES) {
            namespaces.put(namespace, projection.list(namespace));
        }
        return new VectorSnapshot(namespaces);
    }

    @Override
    public void apply(StagedVectorWrites writes) {
        var source = Objects.requireNonNull(writes, "writes");
        if (source.isEmpty()) {
            return;
        }
        for (var entry : source.upserts().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (entry.getValue().stream().anyMatch(VectorWrite::hasMetadata)) {
                projection.saveAllEnriched(
                    entry.getKey(),
                    entry.getValue().stream().map(VectorWrite::toEnrichedVectorRecord).toList()
                );
            } else {
                projection.saveAll(
                    entry.getKey(),
                    entry.getValue().stream().map(VectorWrite::toVectorRecord).toList()
                );
            }
        }
        projection.flushNamespaces(List.copyOf(source.upserts().keySet()));
    }

    @Override
    public void restore(VectorSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        var enrichedPayloads = payloadResolver.resolve(source);
        var namespaces = new LinkedHashSet<>(DEFAULT_NAMESPACES);
        namespaces.addAll(source.namespaces().keySet());
        namespaces.addAll(enrichedPayloads.keySet());

        for (var namespace : namespaces) {
            projection.deleteNamespace(namespace);
            var records = enrichedPayloads.getOrDefault(namespace, List.of());
            if (!records.isEmpty()) {
                projection.saveAllEnriched(namespace, records);
            }
        }
        projection.flushNamespaces(List.copyOf(namespaces));
    }

    @Override
    public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        var requestedByNamespace = new LinkedHashMap<String, List<String>>();
        var rowsByNamespace = new LinkedHashMap<String, List<MilvusClientAdapter.StoredVectorRow>>();
        try {
            for (var entry : idsByNamespace.entrySet()) {
                var ids = List.copyOf(entry.getValue());
                if (ids.isEmpty()) {
                    continue;
                }
                requestedByNamespace.put(entry.getKey(), ids);
                rowsByNamespace.put(entry.getKey(), projection.readRows(entry.getKey(), ids));
            }
        } catch (UnsupportedOperationException exception) {
            // Projections (or client adapters) that predate row point-reads keep their existing semantics: the
            // default readRows throws, and the caller falls back to the whole-workspace snapshot. Point reads have
            // no side effects, so the partially filled capture is discarded safely.
            return Optional.empty();
        }
        return Optional.of(new ScopedPreImage(requestedByNamespace, rowsByNamespace));
    }

    @Override
    public void restorePreImage(PreImage preImage) {
        if (!(preImage instanceof ScopedPreImage scoped)) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        var namespaces = new LinkedHashSet<String>(scoped.rowsByNamespace().keySet());
        namespaces.addAll(scoped.idsByNamespace().keySet());
        for (var namespace : namespaces) {
            var requestedIds = scoped.idsByNamespace().getOrDefault(namespace, List.of());
            var rows = scoped.rowsByNamespace().getOrDefault(namespace, List.of());
            var presentIds = rows.stream()
                .map(MilvusClientAdapter.StoredVectorRow::vectorId)
                .collect(Collectors.toSet());
            var absentIds = requestedIds.stream()
                .filter(id -> !presentIds.contains(id))
                .toList();
            if (!absentIds.isEmpty()) {
                projection.deleteIds(namespace, absentIds);
            }
            if (!rows.isEmpty()) {
                projection.writeRows(namespace, rows);
            }
        }
        projection.flushNamespaces(List.copyOf(namespaces));
    }

    @Override
    public void close() {
        projection.close();
    }

    public interface Projection extends HybridVectorStore, AutoCloseable {
        void deleteNamespace(String namespace);

        void deleteIds(String namespace, List<String> ids);

        void flushNamespaces(List<String> namespaces);

        default List<MilvusClientAdapter.StoredVectorRow> readRows(String namespace, List<String> ids) {
            throw new UnsupportedOperationException("row point-read is not implemented");
        }

        default void writeRows(String namespace, List<MilvusClientAdapter.StoredVectorRow> rows) {
            throw new UnsupportedOperationException("row write-back is not implemented");
        }

        @Override
        void close();
    }

    private record ScopedPreImage(
        Map<String, List<String>> idsByNamespace,
        Map<String, List<MilvusClientAdapter.StoredVectorRow>> rowsByNamespace
    ) implements PreImage {
    }

    @FunctionalInterface
    public interface EnrichedPayloadResolver {
        Map<String, List<HybridVectorStore.EnrichedVectorRecord>> resolve(VectorSnapshot snapshot);
    }

    private static Map<String, List<HybridVectorStore.EnrichedVectorRecord>> defaultPayloads(VectorSnapshot snapshot) {
        var payloads = new LinkedHashMap<String, List<HybridVectorStore.EnrichedVectorRecord>>();
        for (var entry : snapshot.namespaces().entrySet()) {
            payloads.put(
                entry.getKey(),
                entry.getValue().stream()
                    .map(record -> new HybridVectorStore.EnrichedVectorRecord(record.id(), record.vector(), "", List.of()))
                    .toList()
            );
        }
        return Map.copyOf(payloads);
    }

    private static final class MilvusStoreProjection implements Projection {
        private final MilvusVectorStore delegate;

        private MilvusStoreProjection(MilvusVectorStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            delegate.saveAll(namespace, vectors);
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return delegate.search(namespace, queryVector, topK);
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return delegate.list(namespace);
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
            delegate.saveAllEnriched(namespace, records);
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return delegate.search(namespace, request);
        }

        @Override
        public void deleteNamespace(String namespace) {
            delegate.deleteNamespace(namespace);
        }

        @Override
        public void deleteIds(String namespace, List<String> ids) {
            delegate.deleteIds(namespace, ids);
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            delegate.flushNamespaces(namespaces);
        }

        @Override
        public List<MilvusClientAdapter.StoredVectorRow> readRows(String namespace, List<String> ids) {
            return delegate.readRows(namespace, ids);
        }

        @Override
        public void writeRows(String namespace, List<MilvusClientAdapter.StoredVectorRow> rows) {
            delegate.writeRows(namespace, rows);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
