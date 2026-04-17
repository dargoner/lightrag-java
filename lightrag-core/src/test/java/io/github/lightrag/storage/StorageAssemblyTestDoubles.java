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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class StorageAssemblyTestDoubles {
    private StorageAssemblyTestDoubles() {
    }

    public static final class FakeRelationalStorageAdapter implements RelationalStorageAdapter {
        private final InMemoryDocumentStore documentStore = new InMemoryDocumentStore();
        private final InMemoryChunkStore chunkStore = new InMemoryChunkStore();
        private final InMemoryDocumentStatusStore documentStatusStore = new InMemoryDocumentStatusStore();
        private final InMemoryTaskStore taskStore = new InMemoryTaskStore();
        private final InMemoryTaskStageStore taskStageStore = new InMemoryTaskStageStore();
        private final InMemoryDocumentGraphSnapshotStore documentGraphSnapshotStore = new InMemoryDocumentGraphSnapshotStore();
        private final InMemoryDocumentGraphJournalStore documentGraphJournalStore = new InMemoryDocumentGraphJournalStore();
        private final SnapshotStore snapshotStore = new NoopSnapshotStore();
        private int restoreCount;

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
            var documentGraphState = DocumentGraphStateSupport.capture(
                documentGraphSnapshotStore,
                documentGraphJournalStore,
                List.of(),
                documentStore.snapshot(),
                documentStatusStore.snapshot()
            );
            return new SnapshotStore.Snapshot(
                documentStore.snapshot(),
                chunkStore.snapshot(),
                List.of(),
                List.of(),
                Map.of(),
                documentStatusStore.snapshot(),
                documentGraphState.documentSnapshots(),
                documentGraphState.chunkSnapshots(),
                documentGraphState.documentJournals(),
                documentGraphState.chunkJournals()
            );
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            documentStore.restore(snapshot.documents());
            chunkStore.restore(snapshot.chunks());
            documentStatusStore.restore(snapshot.documentStatuses());
            DocumentGraphStateSupport.restore(
                documentGraphSnapshotStore,
                documentGraphJournalStore,
                List.of(),
                snapshot
            );
            restoreCount++;
        }

        @Override
        public <T> T writeInTransaction(RelationalWriteOperation<T> operation) {
            return operation.execute(new RelationalStorageView() {
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
            });
        }

        int restoreCount() {
            return restoreCount;
        }
    }

    public static final class FakeGraphStorageAdapter implements GraphStorageAdapter {
        private final InMemoryGraphStore graphStore = new InMemoryGraphStore();
        private int applyCount;
        private int restoreCount;
        private RuntimeException applyFailure;

        @Override
        public GraphStore graphStore() {
            return graphStore;
        }

        @Override
        public GraphSnapshot captureSnapshot() {
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

        int applyCount() {
            return applyCount;
        }

        int restoreCount() {
            return restoreCount;
        }
    }

    public static final class FakeVectorStorageAdapter implements VectorStorageAdapter {
        private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        private int applyCount;
        private int restoreCount;
        private RuntimeException applyFailure;

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public VectorSnapshot captureSnapshot() {
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

        int applyCount() {
            return applyCount;
        }

        int restoreCount() {
            return restoreCount;
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
