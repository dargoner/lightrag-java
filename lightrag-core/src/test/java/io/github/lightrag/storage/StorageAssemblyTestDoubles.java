package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryChunkStore;
import io.github.lightrag.storage.memory.InMemoryDocumentGraphJournalStore;
import io.github.lightrag.storage.memory.InMemoryDocumentGraphSnapshotStore;
import io.github.lightrag.storage.memory.InMemoryDocumentStatusStore;
import io.github.lightrag.storage.memory.InMemoryDocumentStore;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.storage.memory.InMemoryTaskStageStore;
import io.github.lightrag.storage.memory.InMemoryTaskStore;
import io.github.lightrag.storage.memory.InMemoryVectorStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class StorageAssemblyTestDoubles {
    private StorageAssemblyTestDoubles() {
    }

    /**
     * Models a commit the database explicitly rejected — the kind {@code PostgresRetrySupport} retries by re-running
     * the same supplier. Every other exception models a failure the relational adapter must propagate without
     * retrying.
     */
    public static final class TransientTransactionFailure extends RuntimeException {
        TransientTransactionFailure(String message) {
            super(message);
        }
    }

    /**
     * Relational adapter double that honors {@link RelationalStorageAdapter#writeInTransaction}: writes made inside
     * the operation are discarded when it throws (as {@code connection.rollback()} would), and a transient commit
     * failure re-runs the same supplier up to three attempts, mirroring {@code PostgresRetrySupport}.
     */
    public static final class FakeRelationalStorageAdapter implements RelationalStorageAdapter {
        private static final int MAX_ATTEMPTS = 3;

        private final InMemoryDocumentStore documentStore = new InMemoryDocumentStore();
        private final InMemoryChunkStore chunkStore = new InMemoryChunkStore();
        private final InMemoryDocumentStatusStore documentStatusStore = new InMemoryDocumentStatusStore();
        private final InMemoryTaskStore taskStore = new InMemoryTaskStore();
        private final InMemoryTaskStageStore taskStageStore = new InMemoryTaskStageStore();
        private final InMemoryDocumentGraphSnapshotStore documentGraphSnapshotStore = new InMemoryDocumentGraphSnapshotStore();
        private final InMemoryDocumentGraphJournalStore documentGraphJournalStore = new InMemoryDocumentGraphJournalStore();
        private final SnapshotStore snapshotStore = new NoopSnapshotStore();
        private int restoreCount;
        private int attempts;
        private RuntimeException fatalCommitFailure;
        private int transientCommitFailuresRemaining;

        @Override
        public DocumentStore documentStore() {
            return documentStore;
        }

        @Override
        public ChunkStore chunkStore() {
            return chunkStore;
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return documentStatusStore;
        }

        @Override
        public TaskStore taskStore() {
            return taskStore;
        }

        @Override
        public TaskStageStore taskStageStore() {
            return taskStageStore;
        }

        @Override
        public SnapshotStore snapshotStore() {
            return snapshotStore;
        }

        @Override
        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return documentGraphSnapshotStore;
        }

        @Override
        public DocumentGraphJournalStore documentGraphJournalStore() {
            return documentGraphJournalStore;
        }

        @Override
        public SnapshotStore.Snapshot captureSnapshot() {
            return captureState();
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            discardTransactionWrites(snapshot);
            restoreCount++;
        }

        @Override
        public <T> T writeInTransaction(RelationalWriteOperation<T> operation) {
            var requiredOperation = Objects.requireNonNull(operation, "operation");
            var attempt = 1;
            while (true) {
                attempts = attempt;
                var stateBefore = captureState();
                try {
                    T result = requiredOperation.execute(transactionalView());
                    throwPendingCommitFailure();
                    return result;
                } catch (RuntimeException failure) {
                    discardTransactionWrites(stateBefore);
                    if (!(failure instanceof TransientTransactionFailure) || attempt >= MAX_ATTEMPTS) {
                        throw failure;
                    }
                    attempt++;
                }
            }
        }

        /** Simulates a commit failure that must not be retried: thrown after the operation returns successfully. */
        public void failNextCommit(RuntimeException failure) {
            fatalCommitFailure = Objects.requireNonNull(failure, "failure");
        }

        /** Simulates a commit the database explicitly rejected: the same supplier is re-run (up to three attempts). */
        public void failNextCommitTransiently() {
            transientCommitFailuresRemaining++;
        }

        /** Number of supplier executions observed by the last {@code writeInTransaction} call. */
        public int attempts() {
            return attempts;
        }

        /** Number of calls to the public {@link #restore(SnapshotStore.Snapshot)}; internal rollback does not count. */
        int restoreCount() {
            return restoreCount;
        }

        private void throwPendingCommitFailure() {
            if (transientCommitFailuresRemaining > 0) {
                transientCommitFailuresRemaining--;
                throw new TransientTransactionFailure("transaction commit was rejected by the database");
            }
            if (fatalCommitFailure != null) {
                var failure = fatalCommitFailure;
                fatalCommitFailure = null;
                throw failure;
            }
        }

        private SnapshotStore.Snapshot captureState() {
            var documents = documentStore.snapshot();
            var chunks = chunkStore.snapshot();
            var statuses = documentStatusStore.snapshot();
            var documentGraphState = DocumentGraphStateSupport.capture(
                documentGraphSnapshotStore, documentGraphJournalStore, List.of(), documents, statuses);
            return new SnapshotStore.Snapshot(
                documents,
                chunks,
                List.of(),
                List.of(),
                Map.of(),
                statuses,
                documentGraphState.documentSnapshots(),
                documentGraphState.chunkSnapshots(),
                documentGraphState.documentJournals(),
                documentGraphState.chunkJournals()
            );
        }

        private void discardTransactionWrites(SnapshotStore.Snapshot state) {
            documentStore.restore(state.documents());
            chunkStore.restore(state.chunks());
            documentStatusStore.restore(state.documentStatuses());
            DocumentGraphStateSupport.restore(
                documentGraphSnapshotStore,
                documentGraphJournalStore,
                List.of(),
                state
            );
        }

        private RelationalStorageView transactionalView() {
            return new RelationalStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return documentStore;
                }

                @Override
                public ChunkStore chunkStore() {
                    return chunkStore;
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return documentStatusStore;
                }

                @Override
                public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
                    return documentGraphSnapshotStore;
                }

                @Override
                public DocumentGraphJournalStore documentGraphJournalStore() {
                    return documentGraphJournalStore;
                }

                @Override
                public TaskStore taskStore() {
                    return taskStore;
                }

                @Override
                public TaskStageStore taskStageStore() {
                    return taskStageStore;
                }
            };
        }
    }

    public static final class FakeGraphStorageAdapter implements GraphStorageAdapter {
        private final InMemoryGraphStore graphStore = new InMemoryGraphStore();
        private int captureSnapshotCount;
        private int applyCount;
        private int restoreCount;
        private RuntimeException applyFailure;

        @Override
        public GraphStore graphStore() {
            return graphStore;
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            captureSnapshotCount++;
            return new GraphSnapshot(graphStore.snapshotEntities(), graphStore.snapshotRelations());
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            applyCount++;
            if (applyFailure != null) {
                throw applyFailure;
            }
            for (var entity : writes.entities()) {
                graphStore.saveEntity(entity);
            }
            for (var relation : writes.relations()) {
                graphStore.saveRelation(relation);
            }
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            graphStore.restore(snapshot.entities(), snapshot.relations());
            restoreCount++;
        }

        void failOnApply(RuntimeException exception) {
            applyFailure = exception;
        }

        int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        int applyCount() {
            return applyCount;
        }

        int restoreCount() {
            return restoreCount;
        }
    }

    public static final class FakeVectorStorageAdapter implements VectorStorageAdapter {
        private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        private int captureSnapshotCount;
        private int applyCount;
        private int restoreCount;
        private RuntimeException applyFailure;

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            captureSnapshotCount++;
            return new VectorSnapshot(vectorStore.snapshot());
        }

        @Override
        public void apply(StagedVectorWrites writes) {
            applyCount++;
            if (applyFailure != null) {
                throw applyFailure;
            }
            for (var entry : writes.upserts().entrySet()) {
                if (entry.getValue().stream().anyMatch(VectorStorageAdapter.VectorWrite::hasMetadata)) {
                    vectorStore.saveAllEnriched(
                        entry.getKey(),
                        entry.getValue().stream()
                            .map(VectorStorageAdapter.VectorWrite::toEnrichedVectorRecord)
                            .toList()
                    );
                } else {
                    vectorStore.saveAll(
                        entry.getKey(),
                        entry.getValue().stream()
                            .map(VectorStorageAdapter.VectorWrite::toVectorRecord)
                            .toList()
                    );
                }
            }
        }

        @Override
        public void restore(VectorSnapshot snapshot) {
            vectorStore.restore(snapshot.namespaces());
            restoreCount++;
        }

        void failOnApply(RuntimeException exception) {
            applyFailure = exception;
        }

        int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        int applyCount() {
            return applyCount;
        }

        int restoreCount() {
            return restoreCount;
        }
    }

    /**
     * Graph adapter with scoped pre-image support backed by real per-id state: captures point-read values, and
     * restores by writing present ids back and deleting absent ones — so "partial apply, then scoped rollback,
     * ends equal to the pre-image" is asserted against actual storage state rather than call counts.
     */
    public static final class ScopedGraphStorageAdapter implements GraphStorageAdapter {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private final List<CaptureRequest> preImageRequests = new ArrayList<>();
        private final List<PreImage> preImagePayloads = new ArrayList<>();
        private final List<PreImage> restoreCalls = new ArrayList<>();
        private int captureSnapshotCount;
        private int applyCount;
        private int restoreCount;
        private int armedWriteIndex = -1;
        private RuntimeException armedApplyFailure;
        private RuntimeException restoreFailure;

        public record CaptureRequest(List<String> entityIds, List<String> relationIds) {
            public CaptureRequest {
                entityIds = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
                relationIds = List.copyOf(Objects.requireNonNull(relationIds, "relationIds"));
            }
        }

        public record ScopedPreImage(
            Map<String, GraphStore.EntityRecord> entities,
            Map<String, GraphStore.RelationRecord> relations,
            List<String> requestedEntityIds,
            List<String> requestedRelationIds
        ) implements GraphStorageAdapter.PreImage {
            public ScopedPreImage {
                entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
                relations = Map.copyOf(Objects.requireNonNull(relations, "relations"));
                requestedEntityIds = List.copyOf(Objects.requireNonNull(requestedEntityIds, "requestedEntityIds"));
                requestedRelationIds = List.copyOf(Objects.requireNonNull(requestedRelationIds, "requestedRelationIds"));
            }
        }

        public void seedEntity(GraphStore.EntityRecord entity) {
            var record = Objects.requireNonNull(entity, "entity");
            entities.put(record.id(), record);
        }

        public void seedRelation(GraphStore.RelationRecord relation) {
            var record = Objects.requireNonNull(relation, "relation");
            relations.put(record.id(), record);
        }

        public Optional<GraphStore.EntityRecord> storedEntity(String entityId) {
            return Optional.ofNullable(entities.get(Objects.requireNonNull(entityId, "entityId")));
        }

        public Optional<GraphStore.RelationRecord> storedRelation(String relationId) {
            return Optional.ofNullable(relations.get(Objects.requireNonNull(relationId, "relationId")));
        }

        public List<GraphStore.EntityRecord> storedEntities() {
            return List.copyOf(entities.values());
        }

        public List<GraphStore.RelationRecord> storedRelations() {
            return List.copyOf(relations.values());
        }

        public List<CaptureRequest> preImageRequests() {
            return List.copyOf(preImageRequests);
        }

        public List<PreImage> preImagePayloads() {
            return List.copyOf(preImagePayloads);
        }

        public List<PreImage> restoreCalls() {
            return List.copyOf(restoreCalls);
        }

        public int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        public int applyCount() {
            return applyCount;
        }

        public int restoreCount() {
            return restoreCount;
        }

        /** The next {@code apply} call fails one-shot when it reaches its {@code n}-th write. */
        public void failOnNthWrite(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("n must be >= 1");
            }
            armedWriteIndex = n;
            armedApplyFailure = new IllegalStateException("scoped graph apply failed at write #" + n);
        }

        /** The next {@code restorePreImage} call throws the given failure before touching any state. */
        public void failOnRestore(RuntimeException failure) {
            restoreFailure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public GraphStore graphStore() {
            return new GraphStore() {
                @Override
                public void saveEntity(GraphStore.EntityRecord entity) {
                    entities.put(entity.id(), entity);
                }

                @Override
                public void saveRelation(GraphStore.RelationRecord relation) {
                    relations.put(relation.id(), relation);
                }

                @Override
                public Optional<GraphStore.EntityRecord> loadEntity(String entityId) {
                    return Optional.ofNullable(entities.get(entityId));
                }

                @Override
                public Optional<GraphStore.RelationRecord> loadRelation(String relationId) {
                    return Optional.ofNullable(relations.get(relationId));
                }

                @Override
                public List<GraphStore.EntityRecord> allEntities() {
                    return List.copyOf(entities.values());
                }

                @Override
                public List<GraphStore.RelationRecord> allRelations() {
                    return List.copyOf(relations.values());
                }

                @Override
                public List<GraphStore.RelationRecord> findRelations(String entityId) {
                    return relations.values().stream()
                        .filter(relation ->
                            relation.srcId().equals(entityId) || relation.tgtId().equals(entityId))
                        .toList();
                }
            };
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            captureSnapshotCount++;
            return new GraphSnapshot(List.copyOf(entities.values()), List.copyOf(relations.values()));
        }

        @Override
        public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
            var requestedEntityIds = List.copyOf(entityIds);
            var requestedRelationIds = List.copyOf(relationIds);
            preImageRequests.add(new CaptureRequest(requestedEntityIds, requestedRelationIds));
            var presentEntities = new LinkedHashMap<String, GraphStore.EntityRecord>();
            for (var id : requestedEntityIds) {
                var value = entities.get(id);
                if (value != null) {
                    presentEntities.put(id, value);
                }
            }
            var presentRelations = new LinkedHashMap<String, GraphStore.RelationRecord>();
            for (var id : requestedRelationIds) {
                var value = relations.get(id);
                if (value != null) {
                    presentRelations.put(id, value);
                }
            }
            var payload = new ScopedPreImage(
                presentEntities,
                presentRelations,
                requestedEntityIds,
                requestedRelationIds
            );
            preImagePayloads.add(payload);
            return Optional.of(payload);
        }

        @Override
        public void restorePreImage(PreImage preImage) {
            if (!(preImage instanceof ScopedPreImage scoped)) {
                throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
            }
            restoreCalls.add(scoped);
            if (restoreFailure != null) {
                var failure = restoreFailure;
                restoreFailure = null;
                throw failure;
            }
            for (var id : scoped.requestedEntityIds()) {
                var value = scoped.entities().get(id);
                if (value == null) {
                    entities.remove(id);
                } else {
                    entities.put(id, value);
                }
            }
            for (var id : scoped.requestedRelationIds()) {
                var value = scoped.relations().get(id);
                if (value == null) {
                    relations.remove(id);
                } else {
                    relations.put(id, value);
                }
            }
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            applyCount++;
            var writeIndex = 0;
            for (var entity : writes.entities()) {
                writeIndex++;
                failIfArmed(writeIndex);
                entities.put(entity.id(), entity);
            }
            for (var relation : writes.relations()) {
                writeIndex++;
                failIfArmed(writeIndex);
                relations.put(relation.id(), relation);
            }
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            restoreCount++;
            entities.clear();
            relations.clear();
            for (var entity : snapshot.entities()) {
                entities.put(entity.id(), entity);
            }
            for (var relation : snapshot.relations()) {
                relations.put(relation.id(), relation);
            }
        }

        private void failIfArmed(int writeIndex) {
            if (armedApplyFailure != null && writeIndex == armedWriteIndex) {
                var failure = armedApplyFailure;
                armedApplyFailure = null;
                armedWriteIndex = -1;
                throw failure;
            }
        }
    }

    /**
     * Vector adapter with scoped pre-image support backed by real per-id state, mirroring
     * {@link ScopedGraphStorageAdapter} for the namespace-grouped write sets of
     * {@link VectorStorageAdapter#capturePreImage(Map)}.
     */
    public static final class ScopedVectorStorageAdapter implements VectorStorageAdapter {
        private final Map<String, Map<String, VectorWrite>> writesByNamespace = new LinkedHashMap<>();
        private final List<Map<String, List<String>>> preImageRequests = new ArrayList<>();
        private final List<PreImage> preImagePayloads = new ArrayList<>();
        private final List<PreImage> restoreCalls = new ArrayList<>();
        private int captureSnapshotCount;
        private int applyCount;
        private int restoreCount;
        private int armedWriteIndex = -1;
        private RuntimeException armedApplyFailure;
        private RuntimeException restoreFailure;

        public record ScopedPreImage(
            Map<String, Map<String, VectorWrite>> writesByNamespace,
            Map<String, List<String>> requestedIdsByNamespace
        ) implements VectorStorageAdapter.PreImage {
            public ScopedPreImage {
                var writesCopy = new LinkedHashMap<String, Map<String, VectorWrite>>();
                writesByNamespace.forEach((namespace, writes) -> writesCopy.put(namespace, Map.copyOf(writes)));
                writesByNamespace = Map.copyOf(writesCopy);
                var requestedCopy = new LinkedHashMap<String, List<String>>();
                requestedIdsByNamespace.forEach((namespace, ids) -> requestedCopy.put(namespace, List.copyOf(ids)));
                requestedIdsByNamespace = Map.copyOf(requestedCopy);
            }
        }

        public void seed(String namespace, VectorWrite write) {
            var ns = Objects.requireNonNull(namespace, "namespace");
            var record = Objects.requireNonNull(write, "write");
            writesByNamespace.computeIfAbsent(ns, ignored -> new LinkedHashMap<>()).put(record.id(), record);
        }

        public Optional<VectorWrite> storedWrite(String namespace, String id) {
            var ns = Objects.requireNonNull(namespace, "namespace");
            var writes = writesByNamespace.get(ns);
            if (writes == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(writes.get(Objects.requireNonNull(id, "id")));
        }

        public List<VectorWrite> storedWrites(String namespace) {
            var writes = writesByNamespace.get(Objects.requireNonNull(namespace, "namespace"));
            if (writes == null) {
                return List.of();
            }
            return List.copyOf(writes.values());
        }

        public List<Map<String, List<String>>> preImageRequests() {
            return List.copyOf(preImageRequests);
        }

        public List<PreImage> preImagePayloads() {
            return List.copyOf(preImagePayloads);
        }

        public List<PreImage> restoreCalls() {
            return List.copyOf(restoreCalls);
        }

        public int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        public int applyCount() {
            return applyCount;
        }

        public int restoreCount() {
            return restoreCount;
        }

        /** The next {@code apply} call fails one-shot when it reaches its {@code n}-th write. */
        public void failOnNthWrite(int n) {
            if (n < 1) {
                throw new IllegalArgumentException("n must be >= 1");
            }
            armedWriteIndex = n;
            armedApplyFailure = new IllegalStateException("scoped vector apply failed at write #" + n);
        }

        /** The next {@code restorePreImage} call throws the given failure before touching any state. */
        public void failOnRestore(RuntimeException failure) {
            restoreFailure = Objects.requireNonNull(failure, "failure");
        }

        @Override
        public VectorStore vectorStore() {
            return new VectorStore() {
                @Override
                public void saveAll(String namespace, List<VectorRecord> vectors) {
                    var writes = writesByNamespace.computeIfAbsent(
                        Objects.requireNonNull(namespace, "namespace"),
                        ignored -> new LinkedHashMap<>()
                    );
                    for (var vector : vectors) {
                        writes.put(vector.id(), VectorWrite.of(vector));
                    }
                }

                @Override
                public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
                    return List.of();
                }

                @Override
                public List<VectorRecord> list(String namespace) {
                    return storedWrites(namespace).stream().map(VectorWrite::toVectorRecord).toList();
                }
            };
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            captureSnapshotCount++;
            var namespaces = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
            writesByNamespace.forEach((namespace, writes) -> namespaces.put(
                namespace,
                writes.values().stream().map(VectorWrite::toVectorRecord).toList()
            ));
            return new VectorSnapshot(namespaces);
        }

        @Override
        public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
            var requestedCopy = new LinkedHashMap<String, List<String>>();
            idsByNamespace.forEach((namespace, ids) -> requestedCopy.put(namespace, List.copyOf(ids)));
            preImageRequests.add(requestedCopy);
            var presentWrites = new LinkedHashMap<String, Map<String, VectorWrite>>();
            requestedCopy.forEach((namespace, ids) -> {
                var writes = writesByNamespace.get(namespace);
                if (writes == null) {
                    return;
                }
                var present = new LinkedHashMap<String, VectorWrite>();
                for (var id : ids) {
                    var value = writes.get(id);
                    if (value != null) {
                        present.put(id, value);
                    }
                }
                if (!present.isEmpty()) {
                    presentWrites.put(namespace, present);
                }
            });
            var payload = new ScopedPreImage(presentWrites, requestedCopy);
            preImagePayloads.add(payload);
            return Optional.of(payload);
        }

        @Override
        public void restorePreImage(PreImage preImage) {
            if (!(preImage instanceof ScopedPreImage scoped)) {
                throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
            }
            restoreCalls.add(scoped);
            if (restoreFailure != null) {
                var failure = restoreFailure;
                restoreFailure = null;
                throw failure;
            }
            scoped.requestedIdsByNamespace().forEach((namespace, ids) -> {
                var writes = writesByNamespace.get(namespace);
                if (writes == null) {
                    return;
                }
                var present = scoped.writesByNamespace().getOrDefault(namespace, Map.of());
                for (var id : ids) {
                    var value = present.get(id);
                    if (value == null) {
                        writes.remove(id);
                    } else {
                        writes.put(id, value);
                    }
                }
            });
        }

        @Override
        public void apply(StagedVectorWrites writes) {
            applyCount++;
            var writeIndex = 0;
            for (var entry : writes.upserts().entrySet()) {
                var namespaceWrites = writesByNamespace.computeIfAbsent(
                    entry.getKey(),
                    ignored -> new LinkedHashMap<>()
                );
                for (var write : entry.getValue()) {
                    writeIndex++;
                    failIfArmed(writeIndex);
                    namespaceWrites.put(write.id(), write);
                }
            }
        }

        @Override
        public void restore(VectorSnapshot snapshot) {
            restoreCount++;
            writesByNamespace.clear();
            snapshot.namespaces().forEach((namespace, records) -> {
                var writes = new LinkedHashMap<String, VectorWrite>();
                for (var record : records) {
                    writes.put(record.id(), VectorWrite.of(record));
                }
                writesByNamespace.put(namespace, writes);
            });
        }

        private void failIfArmed(int writeIndex) {
            if (armedApplyFailure != null && writeIndex == armedWriteIndex) {
                var failure = armedApplyFailure;
                armedApplyFailure = null;
                armedWriteIndex = -1;
                throw failure;
            }
        }
    }

    private static final class NoopSnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new NoSuchElementException("No snapshot stored for path: " + path);
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
