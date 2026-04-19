package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.RelationalStorageAdapter;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageCoordinator;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import io.github.lightrag.storage.postgres.PostgresVectorStorageAdapter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PostgresNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");

    private final ReentrantReadWriteLock lock;
    private final PostgresStorageProvider postgresProvider;
    private final SnapshotStore snapshotStore;
    private final StorageCoordinator coordinator;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final TaskStore lockedTaskStore;
    private final TaskStageStore lockedTaskStageStore;
    private final TaskDocumentStore lockedTaskDocumentStore;
    private final VectorStore lockedVectorStore;
    private final GraphStore graphStore;
    private final DocumentGraphSnapshotStore documentGraphSnapshotStore;
    private final DocumentGraphJournalStore documentGraphJournalStore;

    public PostgresNeo4jStorageProvider(
        PostgresStorageConfig postgresConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(postgresConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public PostgresNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(dataSource, postgresConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public PostgresNeo4jStorageProvider(
        PostgresStorageConfig postgresConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            new PostgresStorageProvider(
                Objects.requireNonNull(postgresConfig, "postgresConfig"),
                Objects.requireNonNull(snapshotStore, "snapshotStore"),
                Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
            ),
            new Neo4jGraphStorageAdapter(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                workspaceScope
            ),
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            new PostgresStorageProvider(
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(postgresConfig, "postgresConfig"),
                Objects.requireNonNull(snapshotStore, "snapshotStore"),
                Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
            ),
            new Neo4jGraphStorageAdapter(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                workspaceScope
            ),
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresNeo4jStorageProvider(
        PostgresStorageProvider postgresProvider,
        WorkspaceScopedNeo4jGraphStore neo4jGraphStore
    ) {
        this(
            Objects.requireNonNull(postgresProvider, "postgresProvider"),
            new Neo4jGraphStorageAdapter(Objects.requireNonNull(neo4jGraphStore, "neo4jGraphStore")),
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresNeo4jStorageProvider(
        PostgresStorageProvider postgresProvider,
        GraphStorageAdapter graphAdapter
    ) {
        this(
            Objects.requireNonNull(postgresProvider, "postgresProvider"),
            Objects.requireNonNull(graphAdapter, "graphAdapter"),
            new ReentrantReadWriteLock(true)
        );
    }

    PostgresNeo4jStorageProvider(
        PostgresStorageProvider postgresProvider,
        GraphStorageAdapter graphAdapter,
        ReentrantReadWriteLock lock
    ) {
        this.lock = Objects.requireNonNull(lock, "lock");
        var provider = Objects.requireNonNull(postgresProvider, "postgresProvider");
        this.postgresProvider = provider;
        var relationalAdapter = new PostgresProviderRelationalAdapter(provider);
        this.snapshotStore = relationalAdapter.snapshotStore();
        this.coordinator = new StorageCoordinator(
            relationalAdapter,
            Objects.requireNonNull(graphAdapter, "graphAdapter"),
            new PostgresVectorStorageAdapter(provider)
        );
        this.lockedDocumentStore = new LockedDocumentStore(coordinator.documentStore());
        this.lockedChunkStore = new LockedChunkStore(coordinator.chunkStore());
        this.lockedDocumentStatusStore = new LockedDocumentStatusStore(coordinator.documentStatusStore());
        this.lockedTaskStore = new LockedTaskStore(coordinator.taskStore());
        this.lockedTaskStageStore = new LockedTaskStageStore(coordinator.taskStageStore());
        this.lockedTaskDocumentStore = new LockedTaskDocumentStore(coordinator.taskDocumentStore());
        this.lockedVectorStore = new LockedVectorStore(provider.vectorStore());
        this.graphStore = new MirroringGraphStore();
        this.documentGraphSnapshotStore = coordinator.documentGraphSnapshotStore();
        this.documentGraphJournalStore = coordinator.documentGraphJournalStore();
    }

    @Override
    public DocumentStore documentStore() {
        return lockedDocumentStore;
    }

    @Override
    public ChunkStore chunkStore() {
        return lockedChunkStore;
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return lockedVectorStore;
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return lockedDocumentStatusStore;
    }

    @Override
    public TaskStore taskStore() {
        return lockedTaskStore;
    }

    @Override
    public TaskStageStore taskStageStore() {
        return lockedTaskStageStore;
    }

    @Override
    public TaskDocumentStore taskDocumentStore() {
        return lockedTaskDocumentStore;
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
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return withWriteLock(() -> postgresProvider.withWorkspaceExclusiveLock(() -> coordinator.writeAtomically(operation)));
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        withWriteLock(() -> postgresProvider.withWorkspaceExclusiveLock(() -> {
            coordinator.restore(source);
            return null;
        }));
    }

    @Override
    public void close() {
        coordinator.close();
    }

    private <T> T withReadLock(RuntimeSupplier<T> supplier) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return postgresProvider.withWorkspaceSharedLock(supplier::get);
        } finally {
            readLock.unlock();
        }
    }

    private <T> T withWriteLock(RuntimeSupplier<T> supplier) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            return supplier.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void withWriteLock(Runnable runnable) {
        withWriteLock(() -> {
            runnable.run();
            return null;
        });
    }

    private final class MirroringGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            writeAtomically(storage -> {
                storage.graphStore().saveEntity(entity);
                return null;
            });
        }

        @Override
        public void saveEntities(List<EntityRecord> entities) {
            var records = List.copyOf(Objects.requireNonNull(entities, "entities"));
            if (records.isEmpty()) {
                return;
            }
            writeAtomically(storage -> {
                storage.graphStore().saveEntities(records);
                return null;
            });
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            writeAtomically(storage -> {
                storage.graphStore().saveRelation(relation);
                return null;
            });
        }

        @Override
        public void saveRelations(List<RelationRecord> relations) {
            var records = List.copyOf(Objects.requireNonNull(relations, "relations"));
            if (records.isEmpty()) {
                return;
            }
            writeAtomically(storage -> {
                storage.graphStore().saveRelations(records);
                return null;
            });
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return withReadLock(() -> coordinator.graphStore().loadEntity(entityId));
        }

        @Override
        public List<EntityRecord> loadEntities(List<String> entityIds) {
            return withReadLock(() -> coordinator.graphStore().loadEntities(entityIds));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return withReadLock(() -> coordinator.graphStore().loadRelation(relationId));
        }

        @Override
        public List<RelationRecord> loadRelations(List<String> relationIds) {
            return withReadLock(() -> coordinator.graphStore().loadRelations(relationIds));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return withReadLock(() -> coordinator.graphStore().allEntities());
        }

        @Override
        public List<RelationRecord> allRelations() {
            return withReadLock(() -> coordinator.graphStore().allRelations());
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return withReadLock(() -> coordinator.graphStore().findRelations(entityId));
        }
    }

    private final class LockedDocumentStore implements DocumentStore {
        private final DocumentStore delegate;

        private LockedDocumentStore(DocumentStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(DocumentRecord document) {
            withWriteLock(() -> delegate.save(document));
        }

        @Override
        public Optional<DocumentRecord> load(String documentId) {
            return withReadLock(() -> delegate.load(documentId));
        }

        @Override
        public List<DocumentRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public boolean contains(String documentId) {
            return withReadLock(() -> delegate.contains(documentId));
        }
    }

    private final class LockedChunkStore implements ChunkStore {
        private final ChunkStore delegate;

        private LockedChunkStore(ChunkStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(ChunkRecord chunk) {
            withWriteLock(() -> delegate.save(chunk));
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            return withReadLock(() -> delegate.load(chunkId));
        }

        @Override
        public List<ChunkRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return withReadLock(() -> delegate.listByDocument(documentId));
        }
    }

    private final class LockedDocumentStatusStore implements DocumentStatusStore {
        private final DocumentStatusStore delegate;

        private LockedDocumentStatusStore(DocumentStatusStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(StatusRecord statusRecord) {
            withWriteLock(() -> delegate.save(statusRecord));
        }

        @Override
        public Optional<StatusRecord> load(String documentId) {
            return withReadLock(() -> delegate.load(documentId));
        }

        @Override
        public List<StatusRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public void delete(String documentId) {
            withWriteLock(() -> delegate.delete(documentId));
        }
    }

    private final class LockedTaskStore implements TaskStore {
        private final TaskStore delegate;

        private LockedTaskStore(TaskStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(TaskRecord taskRecord) {
            withWriteLock(() -> delegate.save(taskRecord));
        }

        @Override
        public Optional<TaskRecord> load(String taskId) {
            return withReadLock(() -> delegate.load(taskId));
        }

        @Override
        public List<TaskRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public void delete(String taskId) {
            withWriteLock(() -> delegate.delete(taskId));
        }
    }

    private final class LockedTaskStageStore implements TaskStageStore {
        private final TaskStageStore delegate;

        private LockedTaskStageStore(TaskStageStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(TaskStageRecord taskStageRecord) {
            withWriteLock(() -> delegate.save(taskStageRecord));
        }

        @Override
        public List<TaskStageRecord> listByTask(String taskId) {
            return withReadLock(() -> delegate.listByTask(taskId));
        }

        @Override
        public void deleteByTask(String taskId) {
            withWriteLock(() -> delegate.deleteByTask(taskId));
        }
    }

    private final class LockedTaskDocumentStore implements TaskDocumentStore {
        private final TaskDocumentStore delegate;

        private LockedTaskDocumentStore(TaskDocumentStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(TaskDocumentRecord record) {
            withWriteLock(() -> delegate.save(record));
        }

        @Override
        public Optional<TaskDocumentRecord> load(String taskId, String documentId) {
            return withReadLock(() -> delegate.load(taskId, documentId));
        }

        @Override
        public List<TaskDocumentRecord> listByTask(String taskId) {
            return withReadLock(() -> delegate.listByTask(taskId));
        }

        @Override
        public void deleteByTask(String taskId) {
            withWriteLock(() -> delegate.deleteByTask(taskId));
        }
    }

    private final class LockedVectorStore implements VectorStore {
        private final VectorStore delegate;

        private LockedVectorStore(VectorStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            withWriteLock(() -> delegate.saveAll(namespace, vectors));
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return withReadLock(() -> delegate.search(namespace, queryVector, topK));
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return withReadLock(() -> delegate.list(namespace));
        }
    }

    private static final class PostgresProviderRelationalAdapter implements RelationalStorageAdapter {
        private final PostgresStorageProvider postgresProvider;

        private PostgresProviderRelationalAdapter(PostgresStorageProvider postgresProvider) {
            this.postgresProvider = Objects.requireNonNull(postgresProvider, "postgresProvider");
        }

        @Override
        public DocumentStore documentStore() {
            return postgresProvider.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return postgresProvider.chunkStore();
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return postgresProvider.documentStatusStore();
        }

        @Override
        public TaskStore taskStore() {
            return postgresProvider.taskStore();
        }

        @Override
        public TaskStageStore taskStageStore() {
            return postgresProvider.taskStageStore();
        }

        @Override
        public TaskDocumentStore taskDocumentStore() {
            return postgresProvider.taskDocumentStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return postgresProvider.snapshotStore();
        }

        @Override
        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return postgresProvider.documentGraphSnapshotStore();
        }

        @Override
        public DocumentGraphJournalStore documentGraphJournalStore() {
            return postgresProvider.documentGraphJournalStore();
        }

        @Override
        public SnapshotStore.Snapshot captureSnapshot() {
            var documentGraphState = DocumentGraphStateSupport.capture(
                postgresProvider.documentGraphSnapshotStore(),
                postgresProvider.documentGraphJournalStore(),
                java.util.List.of(),
                postgresProvider.documentStore().list(),
                postgresProvider.documentStatusStore().list()
            );
            return new SnapshotStore.Snapshot(
                postgresProvider.documentStore().list(),
                postgresProvider.chunkStore().list(),
                postgresProvider.graphStore().allEntities(),
                postgresProvider.graphStore().allRelations(),
                Map.of(),
                postgresProvider.documentStatusStore().list(),
                documentGraphState.documentSnapshots(),
                documentGraphState.chunkSnapshots(),
                documentGraphState.documentJournals(),
                documentGraphState.chunkJournals()
            );
        }

        @Override
        public SnapshotStore.Snapshot toRelationalRestoreSnapshot(SnapshotStore.Snapshot snapshot) {
            var source = Objects.requireNonNull(snapshot, "snapshot");
            return new SnapshotStore.Snapshot(
                source.documents(),
                source.chunks(),
                source.entities(),
                source.relations(),
                Map.of(),
                source.documentStatuses(),
                source.documentGraphSnapshots(),
                source.chunkGraphSnapshots(),
                source.documentGraphJournals(),
                source.chunkGraphJournals()
            );
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            postgresProvider.restore(toRelationalRestoreSnapshot(snapshot));
        }

        @Override
        public <T> T writeInTransaction(RelationalWriteOperation<T> operation) {
            Objects.requireNonNull(operation, "operation");
            return postgresProvider.writeAtomically(storage -> operation.execute(new RelationalStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return storage.documentStore();
                }

                @Override
                public ChunkStore chunkStore() {
                    return storage.chunkStore();
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return storage.documentStatusStore();
                }

                @Override
                public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
                    return storage.documentGraphSnapshotStore();
                }

                @Override
                public DocumentGraphJournalStore documentGraphJournalStore() {
                    return storage.documentGraphJournalStore();
                }

                @Override
                public TaskStore taskStore() {
                    return postgresProvider.taskStore();
                }

                @Override
                public TaskStageStore taskStageStore() {
                    return postgresProvider.taskStageStore();
                }

                @Override
                public TaskDocumentStore taskDocumentStore() {
                    return postgresProvider.taskDocumentStore();
                }

                @Override
                public Optional<GraphStore> transactionalGraphStore() {
                    return Optional.of(storage.graphStore());
                }

                @Override
                public Optional<VectorStore> transactionalVectorStore() {
                    return Optional.of(storage.vectorStore());
                }
            }));
        }

        @Override
        public void close() {
            postgresProvider.close();
        }
    }

    @FunctionalInterface
    private interface RuntimeSupplier<T> {
        T get();
    }
}
