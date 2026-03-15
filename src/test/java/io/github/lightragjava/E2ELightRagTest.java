package io.github.lightragjava;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.persistence.FileSnapshotStore;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.storage.neo4j.Neo4jGraphConfig;
import io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import io.github.lightragjava.types.Document;
import org.testcontainers.containers.Neo4jContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2ELightRagTest {
    @Test
    void ingestBuildsChunkEntityRelationAndVectorIndexes() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "test"))));

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.chunkStore().listByDocument("doc-1")).hasSize(1);
        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.graphStore().allRelations())
            .extracting(relation -> relation.id())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
        assertThat(storage.vectorStore().list("chunks"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:bob");
    }

    @Test
    void ingestPersistsSnapshotWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("doc-1.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documents()).hasSize(1);
        assertThat(snapshot.chunks()).hasSize(1);
        assertThat(snapshot.entities()).hasSize(2);
        assertThat(snapshot.relations()).hasSize(1);
        assertThat(snapshot.vectors()).containsKeys("chunks", "entities", "relations");
        assertThat(Files.exists(snapshotPath)).isTrue();
    }

    @Test
    void builderLoadFromSnapshotRestoresStorageBeforeBuild() {
        var snapshotStore = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("seed.snapshot.json");
        snapshotStore.save(snapshotPath, new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-seed", "Seed", "Body", Map.of())),
            List.of(new ChunkStore.ChunkRecord("doc-seed:0", "doc-seed", "Body", 4, 0, Map.of())),
            List.of(new GraphStore.EntityRecord("entity:seed", "Seed", "person", "Seed entity", List.of(), List.of("doc-seed:0"))),
            List.of(),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-seed:0", List.of(1.0d, 0.0d))))
        ));
        var storage = InMemoryStorageProvider.create(snapshotStore);

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        assertThat(rag).isNotNull();
        assertThat(storage.documentStore().load("doc-seed")).isPresent();
        assertThat(storage.chunkStore().load("doc-seed:0")).isPresent();
        assertThat(storage.graphStore().loadEntity("entity:seed")).isPresent();
        assertThat(storage.vectorStore().list("chunks"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-seed:0");
    }

    @Test
    void successfulIngestAutoSavesOnlyWhenSnapshotPersistenceIsConfigured() {
        var snapshotPath = tempDir.resolve("not-configured.snapshot.json");
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        assertThat(Files.exists(snapshotPath)).isFalse();
    }

    @TempDir
    Path tempDir;

    @Test
    void queryUsesMixModeByDefaultAndCallsChatModelWithContext() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        QueryResult result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .build());

        assertThat(result.answer()).isEqualTo("Alice works with Bob.");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-1:0");
        assertThat(chatModel.lastQueryRequest()).isNotNull();
        assertThat(chatModel.lastQueryRequest().userPrompt()).contains("Who works with Bob?");
        assertThat(chatModel.lastQueryRequest().userPrompt()).contains("Alice");
    }

    @Test
    void queryModesExposeNonEmptyContextsForSuccessfulQueries() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        ));

        var local = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.LOCAL)
            .build());
        var global = rag.query(QueryRequest.builder()
            .query("Who reports to Carol?")
            .mode(io.github.lightragjava.api.QueryMode.GLOBAL)
            .build());
        var hybrid = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.HYBRID)
            .build());
        var mix = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.MIX)
            .build());

        assertThat(local.contexts()).isNotEmpty();
        assertThat(local.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");

        assertThat(global.contexts()).isNotEmpty();
        assertThat(global.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-2:0");

        assertThat(hybrid.contexts()).isNotEmpty();
        assertThat(hybrid.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");

        assertThat(mix.contexts()).isNotEmpty();
        assertThat(mix.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");
    }

    @Test
    void deletesEntityAndConnectedRelations() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        rag.deleteByEntity("Alice");

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.chunkStore().listByDocument("doc-1")).hasSize(1);
        assertThat(storage.graphStore().loadEntity("entity:alice")).isEmpty();
        assertThat(storage.graphStore().loadEntity("entity:bob")).isPresent();
        assertThat(storage.graphStore().allRelations()).isEmpty();
        assertThat(storage.vectorStore().list("chunks"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:bob");
        assertThat(storage.vectorStore().list("relations")).isEmpty();
    }

    @Test
    void deletesRelationButPreservesEntities() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        rag.deleteByRelation("Alice", "Bob");

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.chunkStore().listByDocument("doc-1")).hasSize(1);
        assertThat(storage.graphStore().allRelations()).isEmpty();
        assertThat(storage.graphStore().loadEntity("entity:alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("entity:bob")).isPresent();
        assertThat(storage.vectorStore().list("chunks"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.vectorStore().list("relations")).isEmpty();
    }

    @Test
    void deletesDocumentAndRebuildsRemainingKnowledge() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        ));

        rag.deleteByDocumentId("doc-1");

        assertThat(storage.documentStore().load("doc-1")).isEmpty();
        assertThat(storage.documentStore().load("doc-2")).isPresent();
        assertThat(storage.chunkStore().list())
            .extracting(ChunkStore.ChunkRecord::documentId)
            .containsExactly("doc-2");
        assertThat(storage.graphStore().allEntities())
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("entity:bob", "entity:carol");
        assertThat(storage.graphStore().allRelations())
            .extracting(GraphStore.RelationRecord::id)
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
    }

    @Test
    void restoresOriginalStateWhenDocumentDeleteRebuildFails() {
        var storage = InMemoryStorageProvider.create();
        var seedRag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        seedRag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        ));

        var rag = LightRag.builder()
            .chatModel(new FailingExtractionChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        assertThatThrownBy(() -> rag.deleteByDocumentId("doc-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("extract failed");

        assertThat(storage.documentStore().list())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactly("doc-1", "doc-2");
        assertThat(storage.graphStore().allEntities())
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("entity:alice", "entity:bob", "entity:carol");
        assertThat(storage.graphStore().allRelations())
            .extracting(GraphStore.RelationRecord::id)
            .containsExactly(
                "relation:entity:alice|works_with|entity:bob",
                "relation:entity:bob|reports_to|entity:carol"
            );
    }

    @Test
    void deletingMissingEntityOrRelationIsNoOp() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        rag.deleteByEntity("Missing");
        rag.deleteByRelation("Missing", "Bob");

        assertThat(storage.documentStore().list())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactly("doc-1");
        assertThat(storage.graphStore().allEntities())
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.graphStore().allRelations())
            .extracting(GraphStore.RelationRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:bob");
    }

    @Test
    void deleteOperationsPersistSnapshotWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("delete.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        rag.deleteByRelation("Alice", "Bob");

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documents())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactly("doc-1");
        assertThat(snapshot.relations()).isEmpty();
        assertThat(snapshot.vectors().get("relations")).isEmpty();
    }

    @Test
    void postgresProviderSupportsIngestAndQueryModes() {
        try (
            var container = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            )
        ) {
            container.start();
            var storage = new PostgresStorageProvider(
                new PostgresStorageConfig(
                    container.getJdbcUrl(),
                    container.getUsername(),
                    container.getPassword(),
                    "lightrag",
                    2,
                    "rag_"
                ),
                new FileSnapshotStore()
            );

            try (storage) {
                var rag = LightRag.builder()
                    .chatModel(new FakeChatModel())
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .build();

                rag.ingest(List.of(
                    new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
                    new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
                ));

                assertThat(storage.documentStore().load("doc-1")).isPresent();
                assertThat(storage.chunkStore().listByDocument("doc-1")).isNotEmpty();
                assertThat(storage.graphStore().allEntities()).isNotEmpty();
                assertThat(storage.graphStore().allRelations()).isNotEmpty();
                assertThat(storage.vectorStore().list("chunks")).isNotEmpty();

                var local = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.LOCAL)
                    .build());
                var global = rag.query(QueryRequest.builder()
                    .query("Who reports to Carol?")
                    .mode(io.github.lightragjava.api.QueryMode.GLOBAL)
                    .build());
                var hybrid = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.HYBRID)
                    .build());
                var mix = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.MIX)
                    .build());

                assertThat(local.contexts()).isNotEmpty();
                assertThat(global.contexts()).isNotEmpty();
                assertThat(hybrid.contexts()).isNotEmpty();
                assertThat(mix.contexts()).isNotEmpty();
            }
        }
    }

    @Test
    void postgresProviderRestoresFromSnapshotBeforeBuild() {
        try (
            var container = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            )
        ) {
            container.start();

            var snapshotStore = new FileSnapshotStore();
            var snapshotPath = tempDir.resolve("postgres-seed.snapshot.json");
            snapshotStore.save(snapshotPath, new SnapshotStore.Snapshot(
                List.of(new DocumentStore.DocumentRecord("doc-seed", "Seed", "Body", Map.of())),
                List.of(new ChunkStore.ChunkRecord("doc-seed:0", "doc-seed", "Body", 4, 0, Map.of())),
                List.of(new GraphStore.EntityRecord("entity:seed", "Seed", "person", "Seed entity", List.of(), List.of("doc-seed:0"))),
                List.of(),
                Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-seed:0", List.of(1.0d, 0.0d))))
            ));

            var storage = new PostgresStorageProvider(
                new PostgresStorageConfig(
                    container.getJdbcUrl(),
                    container.getUsername(),
                    container.getPassword(),
                    "lightrag",
                    2,
                    "rag_"
                ),
                snapshotStore
            );

            try (storage) {
                var rag = LightRag.builder()
                    .chatModel(new FakeChatModel())
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .loadFromSnapshot(snapshotPath)
                    .build();

                assertThat(rag).isNotNull();
                assertThat(storage.documentStore().load("doc-seed")).isPresent();
                assertThat(storage.chunkStore().load("doc-seed:0")).isPresent();
                assertThat(storage.graphStore().loadEntity("entity:seed")).isPresent();
                assertThat(storage.vectorStore().list("chunks"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("doc-seed:0");
            }
        }
    }

    @Test
    void postgresIngestRollsBackWhenExtractionFailsAfterChunkPersistence() {
        try (
            var container = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            )
        ) {
            container.start();
            var storage = new PostgresStorageProvider(
                new PostgresStorageConfig(
                    container.getJdbcUrl(),
                    container.getUsername(),
                    container.getPassword(),
                    "lightrag",
                    2,
                    "rag_"
                ),
                new FileSnapshotStore()
            );

            try (storage) {
                var rag = LightRag.builder()
                    .chatModel(new FailingExtractionChatModel())
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .build();

                assertThatThrownBy(() -> rag.ingest(List.of(
                    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "test"))
                )))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("extract failed");

                assertThat(storage.documentStore().list()).isEmpty();
                assertThat(storage.chunkStore().list()).isEmpty();
                assertThat(storage.graphStore().allEntities()).isEmpty();
                assertThat(storage.graphStore().allRelations()).isEmpty();
                assertThat(storage.vectorStore().list("chunks")).isEmpty();
                assertThat(storage.vectorStore().list("entities")).isEmpty();
                assertThat(storage.vectorStore().list("relations")).isEmpty();
            }
        }
    }

    @Test
    void postgresNeo4jProviderSupportsIngestAndQueryModes() {
        try (
            var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            );
            var neo4j = new Neo4jContainer<>("neo4j:5-community").withAdminPassword("password")
        ) {
            postgres.start();
            neo4j.start();
            var storage = new PostgresNeo4jStorageProvider(
                new PostgresStorageConfig(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    "lightrag",
                    2,
                    "rag_"
                ),
                new Neo4jGraphConfig(
                    neo4j.getBoltUrl(),
                    "neo4j",
                    neo4j.getAdminPassword(),
                    "neo4j"
                ),
                new FileSnapshotStore()
            );

            try (storage) {
                var rag = LightRag.builder()
                    .chatModel(new FakeChatModel())
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .build();

                rag.ingest(List.of(
                    new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
                    new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
                ));

                assertThat(storage.documentStore().load("doc-1")).isPresent();
                assertThat(storage.chunkStore().listByDocument("doc-1")).isNotEmpty();
                assertThat(storage.graphStore().allEntities()).isNotEmpty();
                assertThat(storage.graphStore().allRelations()).isNotEmpty();
                assertThat(storage.vectorStore().list("chunks")).isNotEmpty();

                var local = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.LOCAL)
                    .build());
                var global = rag.query(QueryRequest.builder()
                    .query("Who reports to Carol?")
                    .mode(io.github.lightragjava.api.QueryMode.GLOBAL)
                    .build());
                var hybrid = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.HYBRID)
                    .build());
                var mix = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.MIX)
                    .build());

                assertThat(local.contexts()).isNotEmpty();
                assertThat(global.contexts()).isNotEmpty();
                assertThat(hybrid.contexts()).isNotEmpty();
                assertThat(mix.contexts()).isNotEmpty();
            }
        }
    }

    @Test
    void postgresNeo4jProviderRestoresFromSnapshotBeforeBuild() {
        try (
            var postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
            );
            var neo4j = new Neo4jContainer<>("neo4j:5-community").withAdminPassword("password")
        ) {
            postgres.start();
            neo4j.start();

            var snapshotStore = new FileSnapshotStore();
            var snapshotPath = tempDir.resolve("postgres-neo4j-seed.snapshot.json");
            snapshotStore.save(snapshotPath, new SnapshotStore.Snapshot(
                List.of(new DocumentStore.DocumentRecord("doc-seed", "Seed", "Body", Map.of())),
                List.of(new ChunkStore.ChunkRecord("doc-seed:0", "doc-seed", "Body", 4, 0, Map.of())),
                List.of(new GraphStore.EntityRecord("entity:seed", "Seed", "person", "Seed entity", List.of(), List.of("doc-seed:0"))),
                List.of(),
                Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-seed:0", List.of(1.0d, 0.0d))))
            ));

            var storage = new PostgresNeo4jStorageProvider(
                new PostgresStorageConfig(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    "lightrag",
                    2,
                    "rag_"
                ),
                new Neo4jGraphConfig(
                    neo4j.getBoltUrl(),
                    "neo4j",
                    neo4j.getAdminPassword(),
                    "neo4j"
                ),
                snapshotStore
            );

            try (storage) {
                var rag = LightRag.builder()
                    .chatModel(new FakeChatModel())
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .loadFromSnapshot(snapshotPath)
                    .build();

                assertThat(rag).isNotNull();
                assertThat(storage.documentStore().load("doc-seed")).isPresent();
                assertThat(storage.chunkStore().load("doc-seed:0")).isPresent();
                assertThat(storage.graphStore().loadEntity("entity:seed")).isPresent();
                assertThat(storage.vectorStore().list("chunks"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("doc-seed:0");
            }
        }
    }

    private static final class FakeChatModel implements ChatModel {
        private ChatRequest lastQueryRequest;

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains("Question:")) {
                lastQueryRequest = request;
                return "Alice works with Bob.";
            }
            if (request.userPrompt().contains("Bob reports to Carol")) {
                return """
                    {
                      "entities": [
                        {
                          "name": "Bob",
                          "type": "person",
                          "description": "Engineer",
                          "aliases": ["Robert"]
                        },
                        {
                          "name": "Carol",
                          "type": "person",
                          "description": "Manager",
                          "aliases": []
                        }
                      ],
                      "relations": [
                        {
                          "sourceEntityName": "Bob",
                          "targetEntityName": "Carol",
                          "type": "reports_to",
                          "description": "reporting line",
                          "weight": 0.9
                        }
                      ]
                    }
                    """;
            }
            return """
                {
                  "entities": [
                    {
                      "name": "Alice",
                      "type": "person",
                      "description": "Researcher",
                      "aliases": []
                    },
                    {
                      "name": "Bob",
                      "type": "person",
                      "description": "Engineer",
                      "aliases": ["Robert"]
                    }
                  ],
                  "relations": [
                    {
                      "sourceEntityName": "Alice",
                      "targetEntityName": "Bob",
                      "type": "works_with",
                      "description": "collaboration",
                      "weight": 0.8
                    }
                  ]
                }
                """;
        }

        ChatRequest lastQueryRequest() {
            return lastQueryRequest;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(FakeEmbeddingModel::vectorFor)
                .toList();
        }

        private static List<Double> vectorFor(String text) {
            if (text.contains("Who works with Bob?") || text.contains("Alice works with Bob") || text.contains("works_with")) {
                return List.of(1.0d, 0.0d);
            }
            if (text.contains("Who reports to Carol?") || text.contains("Bob reports to Carol") || text.contains("reports_to")) {
                return List.of(0.0d, 1.0d);
            }
            if (text.contains("Alice")) {
                return List.of(1.0d, 0.0d);
            }
            if (text.contains("Carol")) {
                return List.of(0.0d, 1.0d);
            }
            if (text.contains("Bob")) {
                return List.of(0.6d, 0.4d);
            }
            return List.of(0.1d, 0.1d);
        }
    }

    private static final class FailingExtractionChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains("Question:")) {
                return "unreachable";
            }
            throw new IllegalStateException("extract failed");
        }
    }
}
