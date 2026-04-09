package io.github.lightrag.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlMilvusNeo4jStorageProviderTest {
    @Test
    void delegatesMySqlMilvusNeo4jWritesThroughStorageCoordinator() throws Exception {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var constructor = MySqlMilvusNeo4jStorageProvider.class.getConstructor(
                javax.sql.DataSource.class,
                MySqlStorageConfig.class,
                SnapshotStore.class,
                WorkspaceScope.class,
                GraphStorageAdapter.class,
                VectorStorageAdapter.class
            );
            var graphAdapter = new RecordingGraphStorageAdapter();
            var vectorAdapter = new RecordingVectorStorageAdapter();

            try (var provider = (MySqlMilvusNeo4jStorageProvider) constructor.newInstance(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphAdapter,
                vectorAdapter
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test")));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test")));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Alice",
                        "person",
                        "Researcher",
                        List.of("A"),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                    return null;
                });

                assertThat(graphAdapter.applyCount()).isEqualTo(1);
                assertThat(vectorAdapter.applyCount()).isEqualTo(1);
                assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
                assertThat(countRows(dataSource, config, "documents", "doc-1")).isEqualTo(1);
            }
        }
    }

    @Test
    void rollsBackMySqlRowsWhenProjectionFailsAfterCommit() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            graphProjection.saveRelation(new GraphStore.RelationRecord(
                "relation-0",
                "entity-0",
                "entity-0",
                "self",
                "Seed relation",
                1.0d,
                List.of("doc-0:0")
            ));

            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));
            graphProjection.failOnSaveEntity(new IllegalStateException("projection failed"));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "incoming",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
                    return null;
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("projection failed");

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(graphProjection.allEntities())
                    .containsExactly(new GraphStore.EntityRecord(
                        "entity-0",
                        "Seed",
                        "seed",
                        "Seed entity",
                        List.of("S"),
                        List.of("doc-0:0")
                    ));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    @Test
    void restoreRestoresMySqlRowsWhenGraphRestoreFails() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of(),
                List.of("doc-0:0")
            ));
            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                graphProjection.failOnRestore(new IllegalStateException("graph restore failed"));

                assertThatThrownBy(() -> provider.restore(new SnapshotStore.Snapshot(
                    List.of(new DocumentStore.DocumentRecord("doc-1", "Replacement", "body", Map.of())),
                    List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of())),
                    List.of(new GraphStore.EntityRecord("entity-1", "Replacement", "person", "entity", List.of(), List.of("doc-1:0"))),
                    List.of(),
                    Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d)))),
                    List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "replacement", null))
                )))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("graph restore failed");

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    @Test
    void rollsBackStateWhenMilvusSaveFailsAfterCommit() {
        assertRollbackWhenMilvusProjectionFails(
            milvusProjection -> milvusProjection.failOnSaveAllEnriched(new IllegalStateException("milvus save failed")),
            "milvus save failed"
        );
    }

    @Test
    void rollsBackStateWhenMilvusFlushFailsAfterCommit() {
        assertRollbackWhenMilvusProjectionFails(
            milvusProjection -> milvusProjection.failOnFlushNamespaces(new IllegalStateException("milvus flush failed")),
            "milvus flush failed"
        );
    }

    @Test
    void appliesGraphAndMilvusIncrementallyWhenStateOnlyGrows() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            graphProjection.saveRelation(new GraphStore.RelationRecord(
                "relation-0",
                "entity-0",
                "entity-0",
                "self",
                "Seed relation",
                1.0d,
                List.of("doc-0:0")
            ));

            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));
            graphProjection.resetMetrics();
            milvusProjection.resetMetrics();

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "incoming",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                    storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                        "relation-1",
                        "entity-0",
                        "entity-1",
                        "handoff_to",
                        "Seed hands off to incoming",
                        0.9d,
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
                    return null;
                });

                assertThat(graphProjection.restoreCount).isZero();
                assertThat(graphProjection.savedEntitiesSinceReset)
                    .containsExactly(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                assertThat(graphProjection.savedRelationsSinceReset)
                    .containsExactly(new GraphStore.RelationRecord(
                        "relation-1",
                        "entity-0",
                        "entity-1",
                        "handoff_to",
                        "Seed hands off to incoming",
                        0.9d,
                        List.of("doc-1:0")
                    ));
                assertThat(milvusProjection.deletedNamespaces).isEmpty();
                assertThat(milvusProjection.savedNamespacesSinceReset).containsExactly("chunks");
                assertThat(milvusProjection.flushedNamespacesSinceReset).containsExactly(List.of("chunks"));
            }
        }
    }

    @Test
    void exposesTaskStoresInsideMySqlTransactionalView() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            try (var adapter = new MySqlRelationalStorageAdapter(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default")
            )) {
                adapter.writeInTransaction(storage -> {
                    storage.taskStore().save(new io.github.lightrag.storage.TaskStore.TaskRecord(
                        "task-1",
                        "default",
                        TaskType.INGEST_DOCUMENTS,
                        TaskStatus.RUNNING,
                        java.time.Instant.parse("2026-04-09T00:00:00Z"),
                        java.time.Instant.parse("2026-04-09T00:00:01Z"),
                        null,
                        "running",
                        null,
                        false,
                        Map.of("documentCount", "1")
                    ));
                    storage.taskStageStore().save(new io.github.lightrag.storage.TaskStageStore.TaskStageRecord(
                        "task-1",
                        TaskStage.PREPARING,
                        TaskStageStatus.RUNNING,
                        1,
                        java.time.Instant.parse("2026-04-09T00:00:01Z"),
                        null,
                        "starting",
                        null
                    ));
                    return null;
                });

                assertThat(adapter.taskStore().load("task-1")).isPresent();
                assertThat(adapter.taskStageStore().listByTask("task-1"))
                    .extracting(io.github.lightrag.storage.TaskStageStore.TaskStageRecord::stage)
                    .containsExactly(TaskStage.PREPARING);
            }
        }
    }

    private static void assertRollbackWhenMilvusProjectionFails(
        java.util.function.Consumer<RecordingMilvusProjection> failer,
        String expectedMessage
    ) {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                failer.accept(milvusProjection);

                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "incoming",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
                    return null;
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(expectedMessage);

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(graphProjection.allEntities())
                    .containsExactly(new GraphStore.EntityRecord(
                        "entity-0",
                        "Seed",
                        "seed",
                        "Seed entity",
                        List.of("S"),
                        List.of("doc-0:0")
                    ));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    private static MySQLContainer<?> newMySqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    private static MySqlStorageConfig startedConfig(MySQLContainer<?> container) {
        container.start();
        return new MySqlStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "rag_"
        );
    }

    private static HikariDataSource newDataSource(MySqlStorageConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static int countRows(DataSource dataSource, MySqlStorageConfig config, String tableName, String id) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM " + config.qualifiedTableName(tableName) + " WHERE workspace_id = ? AND id = ?"
             )) {
            statement.setString(1, "default");
            statement.setString(2, id);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to count MySQL rows", exception);
        }
    }

    private static final class RecordingGraphProjection implements MySqlMilvusNeo4jStorageProvider.GraphProjection {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private final List<GraphStore.EntityRecord> savedEntitiesSinceReset = new java.util.ArrayList<>();
        private final List<GraphStore.RelationRecord> savedRelationsSinceReset = new java.util.ArrayList<>();
        private int restoreCount;
        private RuntimeException failureOnSaveEntity;
        private RuntimeException failureOnSaveRelation;
        private RuntimeException failureOnRestore;

        @Override
        public void saveEntity(GraphStore.EntityRecord entity) {
            if (failureOnSaveEntity != null) {
                var failure = failureOnSaveEntity;
                failureOnSaveEntity = null;
                throw failure;
            }
            entities.put(entity.id(), entity);
            savedEntitiesSinceReset.add(entity);
        }

        @Override
        public void saveRelation(GraphStore.RelationRecord relation) {
            if (failureOnSaveRelation != null) {
                var failure = failureOnSaveRelation;
                failureOnSaveRelation = null;
                throw failure;
            }
            relations.put(relation.id(), relation);
            savedRelationsSinceReset.add(relation);
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
            return entities.values().stream().toList();
        }

        @Override
        public List<GraphStore.RelationRecord> allRelations() {
            return relations.values().stream().toList();
        }

        @Override
        public List<GraphStore.RelationRecord> findRelations(String entityId) {
            return relations.values().stream()
                .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                .toList();
        }

        @Override
        public Neo4jGraphSnapshot captureSnapshot() {
            return new Neo4jGraphSnapshot(allEntities(), allRelations());
        }

        @Override
        public void restore(Neo4jGraphSnapshot snapshot) {
            if (failureOnRestore != null) {
                throw failureOnRestore;
            }
            restoreCount++;
            entities.clear();
            relations.clear();
            snapshot.entities().forEach(entity -> entities.put(entity.id(), entity));
            snapshot.relations().forEach(relation -> relations.put(relation.id(), relation));
        }

        @Override
        public void close() {
        }

        void failOnRestore(RuntimeException failure) {
            this.failureOnRestore = Objects.requireNonNull(failure, "failure");
        }

        void failOnSaveEntity(RuntimeException failure) {
            this.failureOnSaveEntity = Objects.requireNonNull(failure, "failure");
        }

        void failOnSaveRelation(RuntimeException failure) {
            this.failureOnSaveRelation = Objects.requireNonNull(failure, "failure");
        }

        void resetMetrics() {
            savedEntitiesSinceReset.clear();
            savedRelationsSinceReset.clear();
            restoreCount = 0;
        }
    }

    private static final class RecordingGraphStorageAdapter implements GraphStorageAdapter {
        private final LinkedHashMap<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final LinkedHashMap<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private int applyCount;

        @Override
        public GraphStore graphStore() {
            return new GraphStore() {
                @Override
                public void saveEntity(EntityRecord entity) {
                    entities.put(entity.id(), entity);
                }

                @Override
                public void saveRelation(RelationRecord relation) {
                    relations.put(relation.id(), relation);
                }

                @Override
                public Optional<EntityRecord> loadEntity(String entityId) {
                    return Optional.ofNullable(entities.get(entityId));
                }

                @Override
                public Optional<RelationRecord> loadRelation(String relationId) {
                    return Optional.ofNullable(relations.get(relationId));
                }

                @Override
                public List<EntityRecord> allEntities() {
                    return List.copyOf(entities.values());
                }

                @Override
                public List<RelationRecord> allRelations() {
                    return List.copyOf(relations.values());
                }

                @Override
                public List<RelationRecord> findRelations(String entityId) {
                    return relations.values().stream()
                        .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                        .toList();
                }
            };
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            return new GraphSnapshot(List.copyOf(entities.values()), List.copyOf(relations.values()));
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            applyCount++;
            writes.entities().forEach(entity -> entities.put(entity.id(), entity));
            writes.relations().forEach(relation -> relations.put(relation.id(), relation));
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            entities.clear();
            relations.clear();
            snapshot.entities().forEach(entity -> entities.put(entity.id(), entity));
            snapshot.relations().forEach(relation -> relations.put(relation.id(), relation));
        }

        int applyCount() {
            return applyCount;
        }
    }

    private static final class RecordingMilvusProjection implements MySqlMilvusNeo4jStorageProvider.VectorProjection {
        private final Map<String, LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord>> namespaces = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final List<String> deletedNamespaces = new java.util.ArrayList<>();
        private final List<String> savedNamespacesSinceReset = new java.util.ArrayList<>();
        private final List<List<String>> flushedNamespacesSinceReset = new java.util.ArrayList<>();
        private RuntimeException failureOnDeleteNamespace;
        private RuntimeException failureOnSaveAllEnriched;
        private RuntimeException failureOnFlushNamespaces;

        @Override
        public void saveAll(String namespace, List<VectorStore.VectorRecord> vectors) {
            saveAllEnriched(namespace, vectors.stream()
                .map(vector -> new HybridVectorStore.EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
                .toList());
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public List<VectorStore.VectorRecord> list(String namespace) {
            return namespace(namespace).values().stream()
                .map(HybridVectorStore.EnrichedVectorRecord::toVectorRecord)
                .sorted(java.util.Comparator.comparing(VectorStore.VectorRecord::id))
                .toList();
        }

        @Override
        public void saveAllEnriched(String namespace, List<HybridVectorStore.EnrichedVectorRecord> records) {
            if (failureOnSaveAllEnriched != null) {
                var failure = failureOnSaveAllEnriched;
                failureOnSaveAllEnriched = null;
                throw failure;
            }
            var target = namespace(namespace);
            savedNamespacesSinceReset.add(namespace);
            for (var record : records) {
                target.put(record.id(), record);
            }
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, HybridVectorStore.SearchRequest request) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public void deleteNamespace(String namespace) {
            if (failureOnDeleteNamespace != null) {
                var failure = failureOnDeleteNamespace;
                failureOnDeleteNamespace = null;
                throw failure;
            }
            deletedNamespaces.add(namespace);
            namespace(namespace).clear();
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            if (failureOnFlushNamespaces != null) {
                var failure = failureOnFlushNamespaces;
                failureOnFlushNamespaces = null;
                throw failure;
            }
            flushedNamespacesSinceReset.add(List.copyOf(namespaces));
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord> namespace(String namespace) {
            return namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
        }

        void failOnDeleteNamespace(RuntimeException failure) {
            this.failureOnDeleteNamespace = Objects.requireNonNull(failure, "failure");
        }

        void failOnSaveAllEnriched(RuntimeException failure) {
            this.failureOnSaveAllEnriched = Objects.requireNonNull(failure, "failure");
        }

        void failOnFlushNamespaces(RuntimeException failure) {
            this.failureOnFlushNamespaces = Objects.requireNonNull(failure, "failure");
        }

        void resetMetrics() {
            deletedNamespaces.clear();
            savedNamespacesSinceReset.clear();
            flushedNamespacesSinceReset.clear();
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }

    private static final class RecordingVectorStorageAdapter implements VectorStorageAdapter {
        private final LinkedHashMap<String, LinkedHashMap<String, VectorStore.VectorRecord>> namespaces = new LinkedHashMap<>();
        private int applyCount;

        @Override
        public VectorStore vectorStore() {
            return new VectorStore() {
                @Override
                public void saveAll(String namespace, List<VectorRecord> vectors) {
                    var target = namespace(namespace);
                    vectors.forEach(vector -> target.put(vector.id(), vector));
                }

                @Override
                public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
                    throw new UnsupportedOperationException("Not needed in this test");
                }

                @Override
                public List<VectorRecord> list(String namespace) {
                    return List.copyOf(namespace(namespace).values());
                }
            };
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            var snapshot = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
            namespaces.forEach((namespace, vectors) -> snapshot.put(namespace, List.copyOf(vectors.values())));
            return new VectorSnapshot(snapshot);
        }

        @Override
        public void apply(StagedVectorWrites writes) {
            applyCount++;
            writes.upserts().forEach((namespace, vectors) -> {
                var target = namespace(namespace);
                vectors.forEach(vector -> target.put(vector.id(), vector.toVectorRecord()));
            });
        }

        @Override
        public void restore(VectorSnapshot snapshot) {
            namespaces.clear();
            snapshot.namespaces().forEach((namespace, vectors) -> {
                var target = namespace(namespace);
                vectors.forEach(vector -> target.put(vector.id(), vector));
            });
        }

        int applyCount() {
            return applyCount;
        }

        private LinkedHashMap<String, VectorStore.VectorRecord> namespace(String namespace) {
            return namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
        }
    }
}
