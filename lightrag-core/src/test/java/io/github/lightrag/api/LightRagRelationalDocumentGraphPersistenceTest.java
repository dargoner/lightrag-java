package io.github.lightrag.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProvider;
import io.github.lightrag.storage.mysql.MySqlSchemaManager;
import io.github.lightrag.storage.mysql.MySqlStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagRelationalDocumentGraphPersistenceTest {
    private static final String WORKSPACE = "default";

    @Test
    void postgresInspectAndChunkStatusQueriesSurviveProviderRestart() {
        try (var container = newPostgresContainer()) {
            container.start();
            var config = new PostgresStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "lightrag",
                2,
                "rag_"
            );

            try (var writer = LightRag.builder()
                .chatModel(new FakeChatModel())
                .embeddingModel(new FakeEmbeddingModel())
                .storage(new PostgresStorageProvider(config, new NoopSnapshotStore()))
                .build()) {
                writer.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
            }

            try (var reader = LightRag.builder()
                .chatModel(new FakeChatModel())
                .embeddingModel(new FakeEmbeddingModel())
                .storage(new PostgresStorageProvider(config, new NoopSnapshotStore()))
                .build()) {
                var inspection = reader.inspectDocumentGraph(WORKSPACE, "doc-1");
                var chunkStatuses = reader.listDocumentChunkGraphStatuses(WORKSPACE, "doc-1");

                assertThat(inspection.graphStatus()).isEqualTo(GraphMaterializationStatus.MERGED);
                assertThat(inspection.snapshotStatus()).isEqualTo(SnapshotStatus.READY);
                assertThat(chunkStatuses).hasSize(1);
                assertThat(chunkStatuses.get(0).chunkId()).isEqualTo("doc-1:0");
                assertThat(chunkStatuses.get(0).graphStatus()).isEqualTo(ChunkGraphStatus.MATERIALIZED);
                assertThat(chunkStatuses.get(0).mergeStatus()).isEqualTo(ChunkMergeStatus.SUCCEEDED);
            }
        }
    }

    @Test
    void postgresTaskDocumentQueriesSurviveTaskCompletion() {
        try (var container = newPostgresContainer()) {
            container.start();
            var config = new PostgresStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "lightrag",
                2,
                "rag_"
            );

            try (var rag = LightRag.builder()
                .chatModel(new FakeChatModel())
                .embeddingModel(new FakeEmbeddingModel())
                .storage(new PostgresStorageProvider(config, new NoopSnapshotStore()))
                .build()) {
                var taskId = rag.submitIngest(WORKSPACE, List.of(
                    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "postgres-task"))
                ));

                var task = awaitTerminalTask(rag, taskId);
                var taskDocument = rag.getTaskDocument(WORKSPACE, taskId, "doc-1");

                assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
                assertThat(taskDocument.documentId()).isEqualTo("doc-1");
                assertThat(taskDocument.status()).isEqualTo(DocumentStatus.PROCESSED);
                assertThat(taskDocument.chunkCount()).isGreaterThan(0);
                assertThat(taskDocument.entityCount()).isGreaterThan(0);
                assertThat(taskDocument.chunkVectorCount()).isGreaterThan(0);
            }
        }
    }

    @Test
    void mySqlInspectAndChunkStatusQueriesSurviveProviderRestart() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();
            var graphAdapter = new PersistentGraphStorageAdapter();

            try (var writer = LightRag.builder()
                .chatModel(new FakeChatModel())
                .embeddingModel(new FakeEmbeddingModel())
                .storage(new MySqlMilvusNeo4jStorageProvider(
                    dataSource,
                    config,
                    new NoopSnapshotStore(),
                    new WorkspaceScope(WORKSPACE),
                    graphAdapter,
                    VectorStorageAdapter.noop()
                ))
                .build()) {
                writer.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
            }

            try (var reader = LightRag.builder()
                .chatModel(new FakeChatModel())
                .embeddingModel(new FakeEmbeddingModel())
                .storage(new MySqlMilvusNeo4jStorageProvider(
                    dataSource,
                    config,
                    new NoopSnapshotStore(),
                    new WorkspaceScope(WORKSPACE),
                    graphAdapter,
                    VectorStorageAdapter.noop()
                ))
                .build()) {
                var inspection = reader.inspectDocumentGraph(WORKSPACE, "doc-1");
                var chunkStatuses = reader.listDocumentChunkGraphStatuses(WORKSPACE, "doc-1");

                assertThat(inspection.graphStatus()).isEqualTo(GraphMaterializationStatus.MERGED);
                assertThat(inspection.snapshotStatus()).isEqualTo(SnapshotStatus.READY);
                assertThat(chunkStatuses).hasSize(1);
                assertThat(chunkStatuses.get(0).chunkId()).isEqualTo("doc-1:0");
                assertThat(chunkStatuses.get(0).graphStatus()).isEqualTo(ChunkGraphStatus.MATERIALIZED);
                assertThat(chunkStatuses.get(0).mergeStatus()).isEqualTo(ChunkMergeStatus.SUCCEEDED);
            }
        }
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        );
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
        hikariConfig.setMaximumPoolSize(6);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            var prompt = request.userPrompt() == null ? "" : request.userPrompt().toLowerCase(Locale.ROOT);
            if (prompt.contains("should_continue")) {
                return "no";
            }
            return "{\"entities\":[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Alice\",\"aliases\":[]},{\"name\":\"Bob\",\"type\":\"person\",\"description\":\"Bob\",\"aliases\":[]}],\"relations\":[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\",\"type\":\"works_with\",\"description\":\"works with\",\"weight\":1.0}]}";
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
        }
    }

    private static final class NoopSnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("not needed in test");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }

    private static TaskSnapshot awaitTerminalTask(LightRag rag, String taskId) {
        var deadline = java.time.Instant.now().plusSeconds(5);
        var snapshot = rag.getTask(WORKSPACE, taskId);
        while (!snapshot.status().isTerminal() && java.time.Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("test interrupted", exception);
            }
            snapshot = rag.getTask(WORKSPACE, taskId);
        }
        assertThat(snapshot.status().isTerminal()).isTrue();
        return snapshot;
    }

    private static final class PersistentGraphStorageAdapter implements GraphStorageAdapter {
        private final LinkedHashMap<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final LinkedHashMap<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();

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
    }
}
