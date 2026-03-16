package io.github.lightragjava;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.CreateEntityRequest;
import io.github.lightragjava.api.CreateRelationRequest;
import io.github.lightragjava.api.EditEntityRequest;
import io.github.lightragjava.api.EditRelationRequest;
import io.github.lightragjava.api.DocumentProcessingStatus;
import io.github.lightragjava.api.DocumentStatus;
import io.github.lightragjava.api.GraphEntity;
import io.github.lightragjava.api.GraphRelation;
import io.github.lightragjava.api.MergeEntitiesRequest;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
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
    void builderLoadFromSnapshotRestoresLegacyPayloadWithoutDocumentStatuses() throws Exception {
        var snapshotPath = tempDir.resolve("legacy-seed.snapshot.json");
        Files.writeString(snapshotPath, """
            {
              "schemaVersion": 1,
              "createdAt": "2026-03-15T00:00:00Z",
              "payloadFile": "legacy-seed.payload.json"
            }
            """);
        Files.writeString(snapshotPath.resolveSibling("legacy-seed.payload.json"), """
            {
              "documents": [
                {
                  "id": "doc-seed",
                  "title": "Seed",
                  "content": "Body",
                  "metadata": {}
                }
              ],
              "chunks": [
                {
                  "id": "doc-seed:0",
                  "documentId": "doc-seed",
                  "text": "Body",
                  "tokenCount": 4,
                  "order": 0,
                  "metadata": {}
                }
              ],
              "entities": [
                {
                  "id": "entity:seed",
                  "name": "Seed",
                  "type": "person",
                  "description": "Seed entity",
                  "aliases": [],
                  "sourceChunkIds": ["doc-seed:0"]
                }
              ],
              "relations": [],
              "vectors": {
                "chunks": [
                  {
                    "id": "doc-seed:0",
                    "vector": [1.0, 0.0]
                  }
                ]
              }
            }
            """);
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());

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
        assertThat(rag.listDocumentStatuses()).isEmpty();
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

    @Test
    void ingestPersistsProcessedDocumentStatus() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        assertThat(rag.getDocumentStatus("doc-1"))
            .isEqualTo(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
        assertThat(rag.listDocumentStatuses())
            .containsExactly(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
    }

    @Test
    void failedIngestPersistsFailedDocumentStatus() {
        var storage = InMemoryStorageProvider.create();
        var seedRag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
        var failingRag = LightRag.builder()
            .chatModel(new SelectiveFailingExtractionChatModel("doc-2"))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        seedRag.ingest(List.of(new Document("doc-0", "Title", "Alice works with Bob", Map.of())));

        assertThatThrownBy(() -> failingRag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("extract failed for doc-2");

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.documentStore().load("doc-2")).isEmpty();
        assertThat(failingRag.getDocumentStatus("doc-1"))
            .isEqualTo(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
        assertThat(failingRag.getDocumentStatus("doc-2"))
            .isEqualTo(new DocumentProcessingStatus("doc-2", DocumentStatus.FAILED, "", "extract failed for doc-2"));
    }

    @Test
    void deleteByDocumentRemovesDocumentStatus() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        rag.deleteByDocumentId("doc-1");

        assertThatThrownBy(() -> rag.getDocumentStatus("doc-1"))
            .isInstanceOf(java.util.NoSuchElementException.class)
            .hasMessageContaining("document status does not exist");
        assertThat(rag.listDocumentStatuses()).isEmpty();
    }

    @Test
    void deleteByDocumentPreservesOtherFailedStatusesAndPersistsSnapshot() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("delete-doc-status.snapshot.json");
        var seedRag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();
        var failingRag = LightRag.builder()
            .chatModel(new SelectiveFailingExtractionChatModel("doc-3"))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        seedRag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        assertThatThrownBy(() -> failingRag.ingest(List.of(
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of()),
            new Document("doc-3", "Title", "Carol mentors Dave", Map.of())
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("extract failed for doc-3");

        seedRag.deleteByDocumentId("doc-1");

        assertThat(seedRag.listDocumentStatuses())
            .containsExactly(
                new DocumentProcessingStatus("doc-2", DocumentStatus.PROCESSED, "processed 1 chunks", null),
                new DocumentProcessingStatus("doc-3", DocumentStatus.FAILED, "", "extract failed for doc-3")
            );

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documents())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactly("doc-2");
        assertThat(snapshot.documentStatuses())
            .containsExactly(
                new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                    "doc-2",
                    DocumentStatus.PROCESSED,
                    "processed 1 chunks",
                    null
                ),
                new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                    "doc-3",
                    DocumentStatus.FAILED,
                    "",
                    "extract failed for doc-3"
                )
            );
    }

    @Test
    void ingestPersistsDocumentStatusesWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("doc-status.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documentStatuses())
            .containsExactly(new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                "doc-1",
                DocumentStatus.PROCESSED,
                "processed 1 chunks",
                null
            ));
    }

    @Test
    void failedIngestPersistsSnapshotWithDocumentStatusesWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("failed-doc-status.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new SelectiveFailingExtractionChatModel("doc-2"))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        assertThatThrownBy(() -> rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("extract failed for doc-2");

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documents())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactly("doc-1");
        assertThat(snapshot.documentStatuses())
            .containsExactly(
                new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                    "doc-1",
                    DocumentStatus.PROCESSED,
                    "processed 1 chunks",
                    null
                ),
                new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                    "doc-2",
                    DocumentStatus.FAILED,
                    "",
                    "extract failed for doc-2"
                )
            );
    }

    @Test
    void postgresProviderPersistsDocumentStatus() {
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

                rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

                assertThat(rag.getDocumentStatus("doc-1"))
                    .isEqualTo(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
                assertThat(storage.documentStatusStore().load("doc-1"))
                    .contains(new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "processed 1 chunks",
                        null
                    ));
            }
        }
    }

    @Test
    void postgresProviderPersistsFailedDocumentStatus() {
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
                    .chatModel(new SelectiveFailingExtractionChatModel("doc-2"))
                    .embeddingModel(new FakeEmbeddingModel())
                    .storage(storage)
                    .build();

                assertThatThrownBy(() -> rag.ingest(List.of(
                    new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
                    new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
                )))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("extract failed for doc-2");

                assertThat(rag.getDocumentStatus("doc-1"))
                    .isEqualTo(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
                assertThat(rag.getDocumentStatus("doc-2"))
                    .isEqualTo(new DocumentProcessingStatus("doc-2", DocumentStatus.FAILED, "", "extract failed for doc-2"));
            }
        }
    }

    @Test
    void postgresNeo4jProviderPersistsDocumentStatus() {
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

                rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

                assertThat(rag.getDocumentStatus("doc-1"))
                    .isEqualTo(new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunks", null));
                assertThat(storage.documentStatusStore().load("doc-1"))
                    .contains(new io.github.lightragjava.storage.DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "processed 1 chunks",
                        null
                    ));
            }
        }
    }

    @Test
    void createsEntityWithoutDocumentsOrChunks() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var entity = rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .aliases(List.of("Ally"))
            .build());

        assertThat(entity).isEqualTo(new GraphEntity(
            "entity:alice",
            "Alice",
            "person",
            "Researcher",
            List.of("Ally"),
            List.of()
        ));

        assertThat(storage.graphStore().loadEntity("entity:alice"))
            .contains(new GraphStore.EntityRecord(
                "entity:alice",
                "Alice",
                "person",
                "Researcher",
                List.of("Ally"),
                List.of()
            ));
        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
        assertThat(storage.graphStore().allRelations()).isEmpty();
        assertThat(storage.vectorStore().list("chunks")).isEmpty();
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice");
        assertThat(storage.vectorStore().list("relations")).isEmpty();
    }

    @Test
    void createsRelationBetweenExistingEntities() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .aliases(List.of("Robert"))
            .build());

        var relation = rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Robert")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        assertThat(relation).isEqualTo(new GraphRelation(
            "relation:entity:alice|works_with|entity:bob",
            "entity:alice",
            "entity:bob",
            "works_with",
            "collaboration",
            0.8d,
            List.of()
        ));

        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:bob"))
            .contains(new GraphStore.RelationRecord(
                "relation:entity:alice|works_with|entity:bob",
                "entity:alice",
                "entity:bob",
                "works_with",
                "collaboration",
                0.8d,
                List.of()
            ));
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:bob");
    }

    @Test
    void createEntityRejectsNameThatCollidesWithExistingAlias() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .aliases(List.of("Robert"))
            .build());

        assertThatThrownBy(() -> rag.createEntity(CreateEntityRequest.builder()
            .name("Robert")
            .type("person")
            .description("Architect")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void createOperationsUseAtomicWritesWithoutSnapshotRestore() {
        var storage = new AtomicOnlyStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());

        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());

        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        assertThat(storage.restoreCalls()).isZero();
        assertThat(storage.graphStore().loadEntity("entity:alice")).isPresent();
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:bob")).isPresent();
    }

    @Test
    void editsEntityAndMigratesRelationsOnRename() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        var entity = rag.editEntity(EditEntityRequest.builder()
            .entityName("Bob")
            .newName("Robert")
            .description("Principal investigator")
            .build());

        assertThat(entity).isEqualTo(new GraphEntity(
            "entity:robert",
            "Robert",
            "person",
            "Principal investigator",
            List.of("Bob"),
            List.of()
        ));
        assertThat(storage.graphStore().loadEntity("entity:bob")).isEmpty();
        assertThat(storage.graphStore().loadEntity("entity:robert"))
            .contains(new GraphStore.EntityRecord(
                "entity:robert",
                "Robert",
                "person",
                "Principal investigator",
                List.of("Bob"),
                List.of()
            ));
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:bob")).isEmpty();
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:robert"))
            .contains(new GraphStore.RelationRecord(
                "relation:entity:alice|works_with|entity:robert",
                "entity:alice",
                "entity:robert",
                "works_with",
                "collaboration",
                0.8d,
                List.of()
            ));
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:robert");
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:robert");
    }

    @Test
    void editsRelationAndRefreshesRelationVector() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        var relation = rag.editRelation(EditRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .currentRelationType("works_with")
            .newRelationType("reports_to")
            .description("reporting line")
            .weight(0.9d)
            .build());

        assertThat(relation).isEqualTo(new GraphRelation(
            "relation:entity:alice|reports_to|entity:bob",
            "entity:alice",
            "entity:bob",
            "reports_to",
            "reporting line",
            0.9d,
            List.of()
        ));
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:bob")).isEmpty();
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|reports_to|entity:bob"))
            .contains(new GraphStore.RelationRecord(
                "relation:entity:alice|reports_to|entity:bob",
                "entity:alice",
                "entity:bob",
                "reports_to",
                "reporting line",
                0.9d,
                List.of()
            ));
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|reports_to|entity:bob");
    }

    @Test
    void editsEntityMetadataAndRefreshesEntityVector() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());

        var before = storage.vectorStore().list("entities");

        var entity = rag.editEntity(EditEntityRequest.builder()
            .entityName("Alice")
            .description("Principal investigator")
            .aliases(List.of("Lead Alice"))
            .build());

        assertThat(entity).isEqualTo(new GraphEntity(
            "entity:alice",
            "Alice",
            "person",
            "Principal investigator",
            List.of("Lead Alice"),
            List.of()
        ));
        assertThat(storage.graphStore().loadEntity("entity:alice"))
            .contains(new GraphStore.EntityRecord(
                "entity:alice",
                "Alice",
                "person",
                "Principal investigator",
                List.of("Lead Alice"),
                List.of()
            ));
        assertThat(storage.vectorStore().list("entities")).isNotEqualTo(before);
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice");
    }

    @Test
    void rejectsDuplicateGraphManagementCreatesAndMissingEdits() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        assertThatThrownBy(() -> rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Duplicate")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
        assertThatThrownBy(() -> rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("duplicate")
            .weight(1.0d)
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
        assertThatThrownBy(() -> rag.editEntity(EditEntityRequest.builder()
            .entityName("Missing")
            .description("Nope")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");
        assertThatThrownBy(() -> rag.editRelation(EditRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .currentRelationType("reports_to")
            .description("Nope")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsInvalidGraphManagementRequestsAndAmbiguousAliases() {
        assertThatThrownBy(() -> CreateEntityRequest.builder()
            .name("   ")
            .type("person")
            .description("Invalid")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name must not be blank");
        assertThatThrownBy(() -> CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("   ")
            .description("Invalid")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("relationType must not be blank");
        assertThatThrownBy(() -> CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .weight(Double.NaN)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("weight must be finite");
        assertThatThrownBy(() -> EditEntityRequest.builder()
            .entityName("Alice")
            .newName("   ")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("newName must not be blank");
        assertThatThrownBy(() -> EditRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .currentRelationType("works_with")
            .newRelationType("   ")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("newRelationType must not be blank");

        var storage = InMemoryStorageProvider.create();
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alice",
            "Alice",
            "person",
            "Researcher",
            List.of("Shared"),
            List.of()
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alicia",
            "Alicia",
            "person",
            "Engineer",
            List.of("Shared"),
            List.of()
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:bob",
            "Bob",
            "person",
            "Manager",
            List.of(),
            List.of()
        ));
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        assertThatThrownBy(() -> rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Shared")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("ambiguous")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolves ambiguously via alias");
    }

    @Test
    void renameToExistingAliasRemovesSelfAlias() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var created = rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .aliases(List.of("Robert"))
            .build());
        assertThat(created.aliases()).containsExactly("Robert");

        var renamed = rag.editEntity(EditEntityRequest.builder()
            .entityName("Bob")
            .newName("Robert")
            .build());

        assertThat(renamed.aliases()).containsExactly("Bob");
        assertThat(storage.graphStore().loadEntity("entity:robert"))
            .contains(new GraphStore.EntityRecord(
                "entity:robert",
                "Robert",
                "person",
                "Engineer",
                List.of("Bob"),
                List.of()
            ));
    }

    @Test
    void editsEntityDisplayNameWhenNormalizedIdIsUnchanged() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());

        var renamed = rag.editEntity(EditEntityRequest.builder()
            .entityName("Bob")
            .newName("BOB")
            .build());

        assertThat(renamed).isEqualTo(new GraphEntity(
            "entity:bob",
            "BOB",
            "person",
            "Engineer",
            List.of(),
            List.of()
        ));
        assertThat(storage.graphStore().loadEntity("entity:bob"))
            .contains(new GraphStore.EntityRecord(
                "entity:bob",
                "BOB",
                "person",
                "Engineer",
                List.of(),
                List.of()
            ));
    }

    @Test
    void renameRejectsRelationIdCollisionDuringMigration() {
        var storage = InMemoryStorageProvider.create();
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alice",
            "Alice",
            "person",
            "Researcher",
            List.of(),
            List.of()
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:bob",
            "Bob",
            "person",
            "Engineer",
            List.of(),
            List.of()
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            "relation:entity:alice|works_with|entity:bob",
            "entity:alice",
            "entity:bob",
            "works_with",
            "Current",
            0.8d,
            List.of()
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            "relation:entity:alice|works_with|entity:robert",
            "entity:alice",
            "entity:robert",
            "works_with",
            "Dangling",
            0.7d,
            List.of()
        ));
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        assertThatThrownBy(() -> rag.editEntity(EditEntityRequest.builder()
            .entityName("Bob")
            .newName("Robert")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("relation already exists");
    }

    @Test
    void mergesEntitiesAndRedirectsRelations() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .aliases(List.of("Robert Jr"))
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Robert")
            .type("person")
            .description("Principal investigator")
            .aliases(List.of("Rob"))
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Bob")
            .targetEntityName("Alice")
            .relationType("reports_to")
            .description("line management")
            .weight(0.4d)
            .build());

        var merged = rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Robert")
            .build());

        assertThat(merged).isEqualTo(new GraphEntity(
            "entity:robert",
            "Robert",
            "person",
            "Principal investigator\n\nEngineer",
            List.of("Rob", "Robert Jr", "Bob"),
            List.of()
        ));
        assertThat(storage.graphStore().loadEntity("entity:bob")).isEmpty();
        assertThat(storage.graphStore().loadEntity("entity:robert"))
            .contains(new GraphStore.EntityRecord(
                "entity:robert",
                "Robert",
                "person",
                "Principal investigator\n\nEngineer",
                List.of("Rob", "Robert Jr", "Bob"),
                List.of()
            ));
        assertThat(storage.graphStore().loadRelation("relation:entity:alice|works_with|entity:robert")).isPresent();
        assertThat(storage.graphStore().loadRelation("relation:entity:robert|reports_to|entity:alice")).isPresent();
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:robert");
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactlyInAnyOrder(
                "relation:entity:alice|works_with|entity:robert",
                "relation:entity:robert|reports_to|entity:alice"
            );
    }

    @Test
    void mergeEntitiesFoldsDuplicateRelationsAndDropsSelfLoops() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Robert")
            .type("person")
            .description("Principal investigator")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("with Bob")
            .weight(0.5d)
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Robert")
            .relationType("works_with")
            .description("with Robert")
            .weight(0.9d)
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Bob")
            .targetEntityName("Robert")
            .relationType("reports_to")
            .description("Bob reports to Robert")
            .weight(0.7d)
            .build());

        var merged = rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Robert")
            .build());

        assertThat(merged.id()).isEqualTo("entity:robert");
        assertThat(storage.graphStore().allRelations())
            .containsExactly(
                new GraphStore.RelationRecord(
                    "relation:entity:alice|works_with|entity:robert",
                    "entity:alice",
                    "entity:robert",
                    "works_with",
                    "with Bob\n\nwith Robert",
                    0.9d,
                    List.of()
                )
            );
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:robert");
    }

    @Test
    void rejectsInvalidEntityMergeRequests() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alice",
            "Alice",
            "person",
            "Researcher",
            List.of("Shared"),
            List.of()
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alicia",
            "Alicia",
            "person",
            "Engineer",
            List.of("Shared"),
            List.of()
        ));
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Analyst")
            .aliases(List.of("Robert Jr"))
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Robert")
            .type("person")
            .description("Principal investigator")
            .aliases(List.of("Rob"))
            .build());

        assertThatThrownBy(() -> MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of())
            .targetEntityName("Robert")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceEntityNames must not be empty");
        assertThatThrownBy(() -> rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Shared"))
            .targetEntityName("Robert")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resolves ambiguously via alias");
        assertThatThrownBy(() -> rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob", "Robert Jr"))
            .targetEntityName("Robert")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate entities");
        assertThatThrownBy(() -> rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Robert"))
            .targetEntityName("Robert")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target entity must not be included");
        assertThatThrownBy(() -> rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Missing"))
            .targetEntityName("Robert")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match an existing entity");
        assertThatThrownBy(() -> rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Missing")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match an existing entity");

        var merged = rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Robert")
            .targetType("leader")
            .targetDescription("Merged profile")
            .targetAliases(List.of("Merged Bob", "Rob"))
            .build());

        assertThat(merged).isEqualTo(new GraphEntity(
            "entity:robert",
            "Robert",
            "leader",
            "Merged profile",
            List.of("Merged Bob", "Rob"),
            List.of()
        ));
    }

    @Test
    void mergeEntitiesPersistsSnapshotWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("entity-merge.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Robert")
            .type("person")
            .description("Principal investigator")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        rag.mergeEntities(MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Robert")
            .build());

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.entities())
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("entity:alice", "entity:robert");
        assertThat(snapshot.relations())
            .extracting(GraphStore.RelationRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:robert");
        assertThat(snapshot.vectors().get("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:robert");
    }

    @Test
    void postgresProviderSupportsEntityMerge() {
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

                rag.createEntity(CreateEntityRequest.builder()
                    .name("Alice")
                    .type("person")
                    .description("Researcher")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Bob")
                    .type("person")
                    .description("Engineer")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Robert")
                    .type("person")
                    .description("Principal investigator")
                    .build());
                rag.createRelation(CreateRelationRequest.builder()
                    .sourceEntityName("Alice")
                    .targetEntityName("Bob")
                    .relationType("works_with")
                    .description("collaboration")
                    .weight(0.8d)
                    .build());

                rag.mergeEntities(MergeEntitiesRequest.builder()
                    .sourceEntityNames(List.of("Bob"))
                    .targetEntityName("Robert")
                    .build());

                assertThat(storage.graphStore().allEntities())
                    .extracting(GraphStore.EntityRecord::id)
                    .containsExactly("entity:alice", "entity:robert");
                assertThat(storage.graphStore().allRelations())
                    .extracting(GraphStore.RelationRecord::id)
                    .containsExactly("relation:entity:alice|works_with|entity:robert");
                assertThat(storage.vectorStore().list("entities"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("entity:alice", "entity:robert");
            }
        }
    }

    @Test
    void postgresNeo4jProviderSupportsEntityMerge() {
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

                rag.createEntity(CreateEntityRequest.builder()
                    .name("Alice")
                    .type("person")
                    .description("Researcher")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Bob")
                    .type("person")
                    .description("Engineer")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Robert")
                    .type("person")
                    .description("Principal investigator")
                    .build());
                rag.createRelation(CreateRelationRequest.builder()
                    .sourceEntityName("Alice")
                    .targetEntityName("Bob")
                    .relationType("works_with")
                    .description("collaboration")
                    .weight(0.8d)
                    .build());

                rag.mergeEntities(MergeEntitiesRequest.builder()
                    .sourceEntityNames(List.of("Bob"))
                    .targetEntityName("Robert")
                    .build());

                assertThat(storage.graphStore().allEntities())
                    .extracting(GraphStore.EntityRecord::id)
                    .containsExactly("entity:alice", "entity:robert");
                assertThat(storage.graphStore().allRelations())
                    .extracting(GraphStore.RelationRecord::id)
                    .containsExactly("relation:entity:alice|works_with|entity:robert");
                assertThat(storage.vectorStore().list("relations"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("relation:entity:alice|works_with|entity:robert");
            }
        }
    }

    @Test
    void graphManagementPersistsSnapshotWhenConfigured() {
        var storage = InMemoryStorageProvider.create(new FileSnapshotStore());
        var snapshotPath = tempDir.resolve("graph-management.snapshot.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.createEntity(CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Researcher")
            .build());
        rag.createEntity(CreateEntityRequest.builder()
            .name("Bob")
            .type("person")
            .description("Engineer")
            .build());
        rag.createRelation(CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .relationType("works_with")
            .description("collaboration")
            .weight(0.8d)
            .build());

        rag.editRelation(EditRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .currentRelationType("works_with")
            .newRelationType("reports_to")
            .description("reporting line")
            .weight(0.9d)
            .build());

        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.entities())
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(snapshot.relations())
            .extracting(GraphStore.RelationRecord::id)
            .containsExactly("relation:entity:alice|reports_to|entity:bob");
        assertThat(snapshot.vectors().get("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|reports_to|entity:bob");
    }

    @Test
    void postgresProviderSupportsGraphManagement() {
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

                rag.createEntity(CreateEntityRequest.builder()
                    .name("Alice")
                    .type("person")
                    .description("Researcher")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Bob")
                    .type("person")
                    .description("Engineer")
                    .build());
                rag.createRelation(CreateRelationRequest.builder()
                    .sourceEntityName("Alice")
                    .targetEntityName("Bob")
                    .relationType("works_with")
                    .description("collaboration")
                    .weight(0.8d)
                    .build());

                rag.editRelation(EditRelationRequest.builder()
                    .sourceEntityName("Alice")
                    .targetEntityName("Bob")
                    .currentRelationType("works_with")
                    .newRelationType("reports_to")
                    .description("reporting line")
                    .weight(0.9d)
                    .build());

                assertThat(storage.graphStore().allEntities())
                    .extracting(GraphStore.EntityRecord::id)
                    .containsExactly("entity:alice", "entity:bob");
                assertThat(storage.graphStore().allRelations())
                    .extracting(GraphStore.RelationRecord::id)
                    .containsExactly("relation:entity:alice|reports_to|entity:bob");
                assertThat(storage.vectorStore().list("entities"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("entity:alice", "entity:bob");
                assertThat(storage.vectorStore().list("relations"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("relation:entity:alice|reports_to|entity:bob");
            }
        }
    }

    @Test
    void postgresNeo4jProviderSupportsGraphManagement() {
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

                rag.createEntity(CreateEntityRequest.builder()
                    .name("Alice")
                    .type("person")
                    .description("Researcher")
                    .build());
                rag.createEntity(CreateEntityRequest.builder()
                    .name("Bob")
                    .type("person")
                    .description("Engineer")
                    .build());
                rag.createRelation(CreateRelationRequest.builder()
                    .sourceEntityName("Alice")
                    .targetEntityName("Bob")
                    .relationType("works_with")
                    .description("collaboration")
                    .weight(0.8d)
                    .build());

                rag.editEntity(EditEntityRequest.builder()
                    .entityName("Bob")
                    .newName("Robert")
                    .description("Principal investigator")
                    .build());

                assertThat(storage.graphStore().allEntities())
                    .extracting(GraphStore.EntityRecord::id)
                    .containsExactly("entity:alice", "entity:robert");
                assertThat(storage.graphStore().allRelations())
                    .extracting(GraphStore.RelationRecord::id)
                    .containsExactly("relation:entity:alice|works_with|entity:robert");
                assertThat(storage.vectorStore().list("entities"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("entity:alice", "entity:robert");
                assertThat(storage.vectorStore().list("relations"))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("relation:entity:alice|works_with|entity:robert");
            }
        }
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
        assertThat(chatModel.lastQueryRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Multiple Paragraphs.")
            .contains("Alice works with Bob");
        assertThat(chatModel.lastQueryRequest().userPrompt()).isEqualTo("Who works with Bob?");
    }

    @Test
    void querySupportsUserPromptAndConversationHistory() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .responseType("Bullet Points")
            .userPrompt("Answer in bullet points.")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            ))
            .build());

        assertThat(result.answer()).isEqualTo("Alice works with Bob.");
        assertThat(chatModel.lastQueryRequest()).isNotNull();
        assertThat(chatModel.lastQueryRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Bullet Points.")
            .contains("Additional Instructions:")
            .contains("Answer in bullet points.")
            .doesNotContain("Earlier question")
            .doesNotContain("Earlier answer");
        assertThat(chatModel.lastQueryRequest().userPrompt()).isEqualTo("Who works with Bob?");
        assertThat(chatModel.lastQueryRequest().conversationHistory())
            .containsExactly(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            );
    }

    @Test
    void queryReturnsAssembledContextWhenOnlyNeedContextIsEnabled() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .onlyNeedContext(true)
            .build());

        assertThat(result.answer())
            .contains("Entities:")
            .contains("Relations:")
            .contains("Chunks:")
            .contains("Alice works with Bob");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-1:0");
        assertThat(chatModel.lastQueryRequest()).isNull();
        assertThat(chatModel.queryCallCount()).isZero();
    }

    @Test
    void queryCanReturnStructuredReferencesFromDocumentSourceMetadata() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "team-notes.md"))
        ));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .includeReferences(true)
            .build());

        assertThat(result.references())
            .containsExactly(new QueryResult.Reference("1", "team-notes.md"));
        assertThat(result.contexts())
            .extracting(QueryResult.Context::referenceId)
            .containsExactly("1");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::source)
            .containsExactly("team-notes.md");
    }

    @Test
    void queryCanStreamRetrievedAnswersWhileKeepingMetadata() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "team-notes.md"))
        ));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .includeReferences(true)
            .stream(true)
            .build());

        assertThat(result.streaming()).isTrue();
        assertThat(result.answer()).isEmpty();
        assertThat(readAll(result.answerStream())).containsExactly("Alice ", "works with Bob.");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-1:0");
        assertThat(result.references())
            .containsExactly(new QueryResult.Reference("1", "team-notes.md"));
        assertThat(chatModel.lastQueryRequest()).isNotNull();
        assertThat(chatModel.lastStreamQueryRequest()).isNotNull();
    }

    @Test
    void queryCanOverrideDefaultModelForOneRequest() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var overrideModel = new OverrideChatModel("Override answer.", "Override bypass.", List.of("Override ", "answer."));
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .modelFunc(overrideModel)
            .build());

        assertThat(result.answer()).isEqualTo("Override answer.");
        assertThat(chatModel.lastQueryRequest()).isNull();
        assertThat(overrideModel.lastQueryRequest()).isNotNull();
    }

    @Test
    void queryCanStreamThroughQueryLevelModelOverride() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var overrideModel = new OverrideChatModel("Override answer.", "Override bypass.", List.of("Override ", "answer."));
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .stream(true)
            .modelFunc(overrideModel)
            .build());

        assertThat(readAll(result.answerStream())).containsExactly("Override ", "answer.");
        assertThat(chatModel.lastStreamQueryRequest()).isNull();
        assertThat(overrideModel.lastStreamQueryRequest()).isNotNull();
    }

    @Test
    void queryReturnsCompletePromptWhenOnlyNeedPromptIsEnabled() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            ))
            .responseType("")
            .onlyNeedPrompt(true)
            .build());

        assertThat(result.answer())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Multiple Paragraphs.")
            .contains("---User Query---")
            .contains("Who works with Bob?")
            .doesNotContain("Earlier question")
            .doesNotContain("Earlier answer");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-1:0");
        assertThat(chatModel.lastQueryRequest()).isNull();
        assertThat(chatModel.queryCallCount()).isZero();
    }

    @Test
    void querySupportsBypassMode() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var result = rag.query(QueryRequest.builder()
            .query("Talk directly to the model")
            .mode(QueryMode.BYPASS)
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question")
            ))
            .userPrompt("Answer in one sentence.")
            .build());

        assertThat(result.answer()).isEqualTo("Bypass answer.");
        assertThat(result.contexts()).isEmpty();
        assertThat(chatModel.lastBypassRequest()).isNotNull();
        assertThat(chatModel.lastBypassRequest().systemPrompt()).isEmpty();
        assertThat(chatModel.lastBypassRequest().userPrompt())
            .contains("Talk directly to the model")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
        assertThat(chatModel.lastBypassRequest().conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
    }

    @Test
    void queryCanStreamBypassMode() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var result = rag.query(QueryRequest.builder()
            .query("Talk directly to the model")
            .mode(QueryMode.BYPASS)
            .stream(true)
            .build());

        assertThat(result.streaming()).isTrue();
        assertThat(result.answer()).isEmpty();
        assertThat(readAll(result.answerStream())).containsExactly("Bypass ", "answer.");
        assertThat(result.contexts()).isEmpty();
        assertThat(chatModel.lastStreamBypassRequest()).isNotNull();
        assertThat(chatModel.lastStreamBypassRequest().userPrompt()).contains("Talk directly to the model");
    }

    @Test
    void queryReranksFinalContextsWhenConfigured() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .rerankModel(new ReverseChunkIdRerankModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Alice works with Bob near Carol", Map.of()),
            new Document("doc-3", "Title", "Alice works with Bob in Zurich", Map.of())
        ));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .chunkTopK(3)
            .build());

        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-3:0", "doc-2:0", "doc-1:0");
    }

    @Test
    void queryUsesNaiveSpecificPromptTemplate() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.NAIVE)
            .build());

        assertThat(result.answer()).isEqualTo("Alice works with Bob.");
        assertThat(chatModel.lastQueryRequest()).isNotNull();
        assertThat(chatModel.lastQueryRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .doesNotContain("Knowledge Graph Data");
        assertThat(chatModel.lastQueryRequest().userPrompt()).isEqualTo("Who works with Bob?");
    }

    @Test
    void querySupportsKeywordOverridesForGraphAwareModes() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Bob reports to Carol", Map.of())
        ));

        var local = rag.query(QueryRequest.builder()
            .query("Who reports to Carol?")
            .mode(QueryMode.LOCAL)
            .llKeywords(List.of("Alice"))
            .build());
        var global = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.GLOBAL)
            .hlKeywords(List.of("Carol"))
            .build());

        assertThat(local.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-1:0");
        assertThat(global.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-2:0");
    }

    @Test
    void queryBypassesConfiguredRerankWhenDisabledPerRequest() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .rerankModel(new ReverseChunkIdRerankModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Alice works with Bob near Carol", Map.of()),
            new Document("doc-3", "Title", "Alice works with Bob in Zurich", Map.of())
        ));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .chunkTopK(3)
            .enableRerank(false)
            .build());

        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-1:0", "doc-2:0", "doc-3:0");
    }

    @Test
    void naiveQueryReranksFinalContextsWhenConfigured() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .rerankModel(new ReverseChunkIdRerankModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of()),
            new Document("doc-2", "Title", "Alice works with Bob near Carol", Map.of()),
            new Document("doc-3", "Title", "Alice works with Bob in Zurich", Map.of())
        ));

        var result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.NAIVE)
            .chunkTopK(3)
            .build());

        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-3:0", "doc-2:0", "doc-1:0");
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
        var naive = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(io.github.lightragjava.api.QueryMode.NAIVE)
            .build());

        assertThat(local.contexts()).isNotEmpty();
        assertThat(local.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");

        assertThat(global.contexts()).isNotEmpty();
        assertThat(global.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-2:0");

        assertThat(hybrid.contexts()).isNotEmpty();
        assertThat(hybrid.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");

        assertThat(mix.contexts()).isNotEmpty();
        assertThat(mix.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");

        assertThat(naive.contexts()).isNotEmpty();
        assertThat(naive.contexts()).extracting(QueryResult.Context::sourceId).contains("doc-1:0");
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
                var naive = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.NAIVE)
                    .build());

                assertThat(local.contexts()).isNotEmpty();
                assertThat(global.contexts()).isNotEmpty();
                assertThat(hybrid.contexts()).isNotEmpty();
                assertThat(mix.contexts()).isNotEmpty();
                assertThat(naive.contexts()).isNotEmpty();
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
                var naive = rag.query(QueryRequest.builder()
                    .query("Who works with Bob?")
                    .mode(io.github.lightragjava.api.QueryMode.NAIVE)
                    .build());

                assertThat(local.contexts()).isNotEmpty();
                assertThat(global.contexts()).isNotEmpty();
                assertThat(hybrid.contexts()).isNotEmpty();
                assertThat(mix.contexts()).isNotEmpty();
                assertThat(naive.contexts()).isNotEmpty();
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
        private ChatRequest lastBypassRequest;
        private ChatRequest lastStreamQueryRequest;
        private ChatRequest lastStreamBypassRequest;
        private int queryCallCount;

        @Override
        public String generate(ChatRequest request) {
            if (isRetrievalPrompt(request)) {
                lastQueryRequest = request;
                queryCallCount++;
                return "Alice works with Bob.";
            }
            if (request.userPrompt().contains("Talk directly to the model")) {
                lastBypassRequest = request;
                return "Bypass answer.";
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

        @Override
        public io.github.lightragjava.model.CloseableIterator<String> stream(ChatRequest request) {
            var fragments = isRetrievalPrompt(request)
                ? recordQueryStream(request)
                : recordBypassStream(request);
            return new io.github.lightragjava.model.CloseableIterator<>() {
                private int index;
                private boolean closed;

                @Override
                public boolean hasNext() {
                    return !closed && index < fragments.size();
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return fragments.get(index++);
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        private List<String> recordQueryStream(ChatRequest request) {
            lastQueryRequest = request;
            lastStreamQueryRequest = request;
            queryCallCount++;
            return List.of("Alice ", "works with Bob.");
        }

        private List<String> recordBypassStream(ChatRequest request) {
            lastBypassRequest = request;
            lastStreamBypassRequest = request;
            return List.of("Bypass ", "answer.");
        }

        ChatRequest lastQueryRequest() {
            return lastQueryRequest;
        }

        ChatRequest lastBypassRequest() {
            return lastBypassRequest;
        }

        ChatRequest lastStreamQueryRequest() {
            return lastStreamQueryRequest;
        }

        ChatRequest lastStreamBypassRequest() {
            return lastStreamBypassRequest;
        }

        int queryCallCount() {
            return queryCallCount;
        }
    }

    private static final class OverrideChatModel implements ChatModel {
        private final String queryAnswer;
        private final String bypassAnswer;
        private final List<String> streamAnswer;
        private ChatRequest lastQueryRequest;
        private ChatRequest lastBypassRequest;
        private ChatRequest lastStreamQueryRequest;
        private ChatRequest lastStreamBypassRequest;

        private OverrideChatModel(String queryAnswer, String bypassAnswer, List<String> streamAnswer) {
            this.queryAnswer = queryAnswer;
            this.bypassAnswer = bypassAnswer;
            this.streamAnswer = streamAnswer;
        }

        @Override
        public String generate(ChatRequest request) {
            if (isRetrievalPrompt(request)) {
                lastQueryRequest = request;
                return queryAnswer;
            }
            lastBypassRequest = request;
            return bypassAnswer;
        }

        @Override
        public io.github.lightragjava.model.CloseableIterator<String> stream(ChatRequest request) {
            if (isRetrievalPrompt(request)) {
                lastStreamQueryRequest = request;
            } else {
                lastStreamBypassRequest = request;
            }
            return io.github.lightragjava.model.CloseableIterator.of(streamAnswer);
        }

        ChatRequest lastQueryRequest() {
            return lastQueryRequest;
        }

        ChatRequest lastBypassRequest() {
            return lastBypassRequest;
        }

        ChatRequest lastStreamQueryRequest() {
            return lastStreamQueryRequest;
        }

        ChatRequest lastStreamBypassRequest() {
            return lastStreamBypassRequest;
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
            if (text.contains("Principal investigator") || text.contains("Lead Alice")) {
                return List.of(0.3d, 0.7d);
            }
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
            if (isRetrievalPrompt(request)) {
                return "unreachable";
            }
            throw new IllegalStateException("extract failed");
        }
    }

    private static final class ReverseChunkIdRerankModel implements RerankModel {
        @Override
        public List<RerankResult> rerank(RerankRequest request) {
            return request.candidates().stream()
                .map(RerankCandidate::id)
                .sorted(java.util.Comparator.reverseOrder())
                .map(id -> new RerankResult(id, 1.0d))
                .toList();
        }
    }

    private static final class SelectiveFailingExtractionChatModel implements ChatModel {
        private final String failingDocumentId;

        private SelectiveFailingExtractionChatModel(String failingDocumentId) {
            this.failingDocumentId = failingDocumentId;
        }

        @Override
        public String generate(ChatRequest request) {
            if (isRetrievalPrompt(request)) {
                return "unreachable";
            }
            if (request.userPrompt().contains(failingDocumentId)) {
                throw new IllegalStateException("extract failed for " + failingDocumentId);
            }
            return new FakeChatModel().generate(request);
        }
    }

    private static boolean isRetrievalPrompt(ChatModel.ChatRequest request) {
        return request.systemPrompt().contains("---Role---")
            && request.systemPrompt().contains("---Instructions---")
            && request.systemPrompt().contains("---Context---")
            && request.systemPrompt().contains("The response should be presented in");
    }

    private static List<String> readAll(io.github.lightragjava.model.CloseableIterator<String> iterator) {
        try (iterator) {
            var values = new java.util.ArrayList<String>();
            while (iterator.hasNext()) {
                values.add(iterator.next());
            }
            return values;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static final class AtomicOnlyStorageProvider implements io.github.lightragjava.storage.AtomicStorageProvider {
        private final InMemoryStorageProvider delegate = InMemoryStorageProvider.create();
        private int restoreCalls;

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            return delegate.writeAtomically(operation);
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            restoreCalls++;
            throw new UnsupportedOperationException("restore should not be called");
        }

        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public io.github.lightragjava.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        int restoreCalls() {
            return restoreCalls;
        }
    }
}
