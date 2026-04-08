package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PostgresNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final List<String> VECTOR_NAMESPACES = List.of("chunks", "entities", "relations");
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");

    private final ReentrantReadWriteLock lock;
    private final PostgresStorageProvider postgresProvider;
    private final WorkspaceScopedNeo4jGraphStore neo4jGraphStore;
    private final ProjectionApplier projectionApplier;
    private final GraphStore graphStore;

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
            new WorkspaceScopedNeo4jGraphStore(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                workspaceScope
            ),
            new ReentrantReadWriteLock(true),
            null
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
            new WorkspaceScopedNeo4jGraphStore(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                workspaceScope
            ),
            new ReentrantReadWriteLock(true),
            null
        );
    }

    public PostgresNeo4jStorageProvider(
        PostgresStorageProvider postgresProvider,
        WorkspaceScopedNeo4jGraphStore neo4jGraphStore
    ) {
        this(
            Objects.requireNonNull(postgresProvider, "postgresProvider"),
            Objects.requireNonNull(neo4jGraphStore, "neo4jGraphStore"),
            new ReentrantReadWriteLock(true),
            null
        );
    }

    PostgresNeo4jStorageProvider(
        PostgresStorageProvider postgresProvider,
        WorkspaceScopedNeo4jGraphStore neo4jGraphStore,
        ReentrantReadWriteLock lock,
        ProjectionApplier projectionApplier
    ) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.postgresProvider = Objects.requireNonNull(postgresProvider, "postgresProvider");
        this.neo4jGraphStore = Objects.requireNonNull(neo4jGraphStore, "neo4jGraphStore");
        this.projectionApplier = projectionApplier == null ? this::applyProjection : projectionApplier;
        this.graphStore = new MirroringGraphStore();
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
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return postgresProvider.vectorStore();
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return postgresProvider.documentStatusStore();
    }

    @Override
    public SnapshotStore snapshotStore() {
        return postgresProvider.snapshotStore();
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        lock.writeLock().lock();
        try {
            var beforePostgres = capturePostgresSnapshot();
            var beforeNeo4j = neo4jGraphStore.captureSnapshot();
            var stagedEntities = new LinkedHashMap<String, GraphStore.EntityRecord>();
            var stagedRelations = new LinkedHashMap<String, GraphStore.RelationRecord>();

            try {
                T result = postgresProvider.writeAtomically(storage -> operation.execute(new AtomicView(
                    storage.documentStore(),
                    storage.chunkStore(),
                    new ProjectionStagingGraphStore(storage.graphStore(), stagedEntities, stagedRelations),
                    storage.vectorStore(),
                    storage.documentStatusStore()
                )));
                projectionApplier.apply(stagedEntities.values(), stagedRelations.values());
                return result;
            } catch (RuntimeException failure) {
                restoreSnapshots(beforePostgres, beforeNeo4j, failure);
                throw failure;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var replacement = Objects.requireNonNull(snapshot, "snapshot");
        lock.writeLock().lock();
        try {
            var beforePostgres = capturePostgresSnapshot();
            var beforeNeo4j = neo4jGraphStore.captureSnapshot();
            try {
                postgresProvider.restore(replacement);
                neo4jGraphStore.restore(new Neo4jGraphSnapshot(replacement.entities(), replacement.relations()));
            } catch (RuntimeException failure) {
                restoreSnapshots(beforePostgres, beforeNeo4j, failure);
                throw failure;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            neo4jGraphStore.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }

        try {
            postgresProvider.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    private SnapshotStore.Snapshot capturePostgresSnapshot() {
        return new SnapshotStore.Snapshot(
            postgresProvider.documentStore().list(),
            postgresProvider.chunkStore().list(),
            postgresProvider.graphStore().allEntities(),
            postgresProvider.graphStore().allRelations(),
            Map.of(
                "chunks", postgresProvider.vectorStore().list("chunks"),
                "entities", postgresProvider.vectorStore().list("entities"),
                "relations", postgresProvider.vectorStore().list("relations")
            ),
            postgresProvider.documentStatusStore().list()
        );
    }

    private void applyProjection(
        java.util.Collection<GraphStore.EntityRecord> entities,
        java.util.Collection<GraphStore.RelationRecord> relations
    ) {
        for (var entity : entities) {
            neo4jGraphStore.saveEntity(entity);
        }
        for (var relation : relations) {
            neo4jGraphStore.saveRelation(relation);
        }
    }

    private void restoreSnapshots(
        SnapshotStore.Snapshot postgresSnapshot,
        Neo4jGraphSnapshot neo4jSnapshot,
        RuntimeException failure
    ) {
        try {
            postgresProvider.restore(postgresSnapshot);
        } catch (RuntimeException exception) {
            failure.addSuppressed(exception);
        }

        try {
            neo4jGraphStore.restore(neo4jSnapshot);
        } catch (RuntimeException exception) {
            failure.addSuppressed(exception);
        }
    }

    private final class MirroringGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            mirrorWrite(entity, null);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            mirrorWrite(null, relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return neo4jGraphStore.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return neo4jGraphStore.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return neo4jGraphStore.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return neo4jGraphStore.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return neo4jGraphStore.findRelations(entityId);
        }

        private void mirrorWrite(EntityRecord entity, RelationRecord relation) {
            lock.writeLock().lock();
            try {
                var beforePostgres = capturePostgresSnapshot();
                var beforeNeo4j = neo4jGraphStore.captureSnapshot();
                try {
                    if (entity != null) {
                        postgresProvider.graphStore().saveEntity(entity);
                        projectionApplier.apply(List.of(entity), List.of());
                    }
                    if (relation != null) {
                        postgresProvider.graphStore().saveRelation(relation);
                        projectionApplier.apply(List.of(), List.of(relation));
                    }
                } catch (RuntimeException failure) {
                    restoreSnapshots(beforePostgres, beforeNeo4j, failure);
                    throw failure;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }

    private static final class ProjectionStagingGraphStore implements GraphStore {
        private final GraphStore postgresGraphStore;
        private final Map<String, EntityRecord> stagedEntities;
        private final Map<String, RelationRecord> stagedRelations;

        private ProjectionStagingGraphStore(
            GraphStore postgresGraphStore,
            Map<String, EntityRecord> stagedEntities,
            Map<String, RelationRecord> stagedRelations
        ) {
            this.postgresGraphStore = Objects.requireNonNull(postgresGraphStore, "postgresGraphStore");
            this.stagedEntities = Objects.requireNonNull(stagedEntities, "stagedEntities");
            this.stagedRelations = Objects.requireNonNull(stagedRelations, "stagedRelations");
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            postgresGraphStore.saveEntity(entity);
            stagedEntities.put(entity.id(), entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            postgresGraphStore.saveRelation(relation);
            stagedRelations.put(relation.id(), relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return postgresGraphStore.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return postgresGraphStore.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return postgresGraphStore.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return postgresGraphStore.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return postgresGraphStore.findRelations(entityId);
        }
    }

    @FunctionalInterface
    interface ProjectionApplier {
        void apply(
            java.util.Collection<GraphStore.EntityRecord> entities,
            java.util.Collection<GraphStore.RelationRecord> relations
        );
    }
}
