package io.github.lightrag.indexing;

import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.memory.InMemoryDocumentStatusStore;
import io.github.lightrag.storage.memory.InMemoryTaskStageStore;
import io.github.lightrag.storage.memory.InMemoryTaskStore;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentIngestorTest {
    @Test
    void rejectsDuplicateDocumentIdsAlreadyInStorage() {
        var storage = InMemoryStorageProvider.create();
        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Existing", "Body", Map.of()));
        var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
        var document = new Document("doc-1", "Incoming", "abcdefgh", Map.of());

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("doc-1");

        assertThat(storage.documentStore().list())
            .containsExactly(new DocumentStore.DocumentRecord("doc-1", "Existing", "Body", Map.of()));
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void preparesParsedDocumentChunksFromWeakStructuredBlocksForGenericDocuments() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(
            storage,
            new FixedWindowChunker(4, 1),
            new DefaultChunkPreparationStrategy(),
            new ChunkingOrchestrator(
                new DocumentTypeResolver(),
                new SmartChunker(SmartChunkerConfig.builder()
                    .targetTokens(12)
                    .maxTokens(18)
                    .overlapTokens(2)
                    .build()),
                new RegexChunker(new FixedWindowChunker(8, 2)),
                new FixedWindowChunker(8, 2)
            )
        );
        var parsed = new ParsedDocument(
            "doc-1",
            "Title",
            "Alpha beta gamma delta epsilon zeta eta theta.",
            List.of(
                new ParsedBlock(
                    "title-1",
                    "title",
                    "第一章",
                    "第一章",
                    List.of("第一章"),
                    1,
                    null,
                    1,
                    Map.of("level", "1")
                ),
                new ParsedBlock(
                    "block-1",
                    "paragraph",
                    "结构化正文。",
                    "",
                    List.of(),
                    1,
                    null,
                    2,
                    Map.of()
                )
            ),
            Map.of("source", "unit-test")
        );

        var prepared = ingestor.prepareParsed(parsed, DocumentIngestOptions.defaults());

        assertThat(prepared.documentRecords())
            .containsExactly(new DocumentStore.DocumentRecord(
                "doc-1",
                "Title",
                "Alpha beta gamma delta epsilon zeta eta theta.",
                Map.of(
                    "source", "unit-test",
                    DocumentIngestOptions.METADATA_DOCUMENT_TYPE_HINT, DocumentTypeHint.AUTO.name(),
                    DocumentIngestOptions.METADATA_CHUNK_GRANULARITY, io.github.lightrag.api.ChunkGranularity.MEDIUM.name()
                )
            ));
        assertThat(prepared.chunks()).isNotEmpty();
        var combinedText = prepared.chunks().stream()
            .map(Chunk::text)
            .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(combinedText).contains("结构化正文。");
        assertThat(combinedText).doesNotContain("Alpha beta");
        assertThat(combinedText).doesNotContain("theta.");
        assertThat(prepared.chunks().get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "第一章")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "block-1");
        assertThat(prepared.chunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(ChunkingOrchestrator.METADATA_EFFECTIVE_MODE, ChunkingMode.SMART.name())
                .containsEntry(ChunkingOrchestrator.METADATA_DOWNGRADED_TO_FIXED, Boolean.FALSE.toString()));
    }

    @Test
    void preparesStructuredLawDocumentFromParsedBlocksWhenTemplateRequiresIt() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(
            storage,
            new FixedWindowChunker(4, 1),
            new DefaultChunkPreparationStrategy(),
            new ChunkingOrchestrator(
                new DocumentTypeResolver(),
                new SmartChunker(SmartChunkerConfig.builder()
                    .targetTokens(120)
                    .maxTokens(120)
                    .overlapTokens(8)
                    .semanticMergeEnabled(true)
                    .semanticMergeThreshold(0.2d)
                    .build()),
                new RegexChunker(new FixedWindowChunker(8, 2)),
                new FixedWindowChunker(8, 2)
            )
        );
        var parsed = new ParsedDocument(
            "doc-law",
            "Regulation",
            "PLAIN TEXT SHOULD NOT WIN",
            List.of(
                new ParsedBlock("law-1", "paragraph", "第一条 检索规则。", "第一条", List.of("第一条"), 1, null, 1, Map.of()),
                new ParsedBlock("law-2", "paragraph", "第二条 检索规则。", "第二条", List.of("第二条"), 1, null, 2, Map.of())
            ),
            Map.of("source", "mineru")
        );
        var options = new DocumentIngestOptions(
            DocumentTypeHint.LAW,
            io.github.lightrag.api.ChunkGranularity.MEDIUM
        );

        var prepared = ingestor.prepareParsed(parsed, options);

        assertThat(prepared.chunks())
            .extracting(Chunk::text)
            .containsExactly("第一条 检索规则。", "第二条 检索规则。");
        assertThat(prepared.chunks())
            .allSatisfy(chunk -> assertThat(chunk.text()).doesNotContain("PLAIN TEXT SHOULD NOT WIN"));
        assertThat(prepared.chunks().get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "第一条");
        assertThat(prepared.chunks().get(1).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "第二条");
    }

    @Test
    void rejectsDuplicateIdsInSingleBatch() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
        var first = new Document("doc-1", "First", "abcdefgh", Map.of());
        var second = new Document("doc-1", "Second", "ijklmnop", Map.of());

        assertThatThrownBy(() -> ingestor.ingest(List.of(first, second)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("doc-1");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void savesDocumentsAndGeneratedChunksAfterValidation() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
        var document = new Document("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test"));

        var chunks = ingestor.ingest(List.of(document));

        assertThat(storage.documentStore().load("doc-1"))
            .contains(new DocumentStore.DocumentRecord("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test")));
        assertThat(storage.chunkStore().listByDocument("doc-1"))
            .containsExactly(
                new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "abcd", 4, 0, Map.of("source", "unit-test")),
                new ChunkStore.ChunkRecord("doc-1:1", "doc-1", "defg", 4, 1, Map.of("source", "unit-test")),
                new ChunkStore.ChunkRecord("doc-1:2", "doc-1", "ghij", 4, 2, Map.of("source", "unit-test"))
            );
        assertThat(chunks)
            .extracting(Chunk::id)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    }

    @Test
    void validatesPreparedChunksReturnedByCustomPreparationStrategy() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(
            storage,
            new FixedWindowChunker(4, 1),
            (document, chunker) -> List.of(new Chunk("doc-1:0", "other-doc", "abcd", 4, 0, document.metadata()))
        );
        var document = new Document("doc-1", "Title", "abcdefgh", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk documentId must match source document id");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void leavesDocumentAndChunkStoresUntouchedWhenChunkingFails() {
        var storage = InMemoryStorageProvider.create();
        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));
        storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
        var ingestor = new DocumentIngestor(storage, document -> {
            throw new IllegalStateException("chunking failed");
        });
        var document = new Document("doc-1", "Title", "abcdefgh", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("chunking failed");

        assertThat(storage.documentStore().list())
            .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));
        assertThat(storage.chunkStore().list())
            .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
    }

    @Test
    void rejectsChunkerOutputWhenChunkDocumentIdDoesNotMatchSourceDocument() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(storage, document -> List.of(
            new Chunk("doc-1:0", "other-doc", "abcd", 4, 0, document.metadata())
        ));
        var document = new Document("doc-1", "Title", "abcdefgh", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk documentId must match source document id");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void rejectsChunkerOutputWhenChunkIdsAreDuplicated() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(storage, document -> List.of(
            new Chunk("doc-1:0", document.id(), "abcd", 4, 0, document.metadata()),
            new Chunk("doc-1:0", document.id(), "defg", 4, 1, document.metadata())
        ));
        var document = new Document("doc-1", "Title", "abcdefgh", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk id must be unique");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void rejectsChunkerOutputWhenChunkOrderIsNotContiguous() {
        var storage = InMemoryStorageProvider.create();
        var ingestor = new DocumentIngestor(storage, document -> List.of(
            new Chunk("doc-1:0", document.id(), "abcd", 4, 1, document.metadata())
        ));
        var document = new Document("doc-1", "Title", "abcdefgh", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("chunk order must be contiguous");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void rollsBackDocumentAndChunkWritesWhenChunkStoreSaveFails() {
        var storage = new AtomicFailureStorageProvider();
        var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
        var document = new Document("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test"));

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("chunk save failed");

        assertThat(storage.documentStore().list()).isEmpty();
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void rejectsDocumentIdsThatAppearDuringAtomicWrite() {
        var storage = new AtomicFailureStorageProvider();
        storage.insertDocumentBeforeAtomicWrite(new DocumentStore.DocumentRecord("doc-1", "Existing", "seed", Map.of("seed", "true")));
        var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
        var document = new Document("doc-1", "Incoming", "abcdefgh", Map.of());

        assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("doc-1");

        assertThat(storage.documentStore().list())
            .containsExactly(new DocumentStore.DocumentRecord("doc-1", "Existing", "seed", Map.of("seed", "true")));
        assertThat(storage.chunkStore().list()).isEmpty();
    }

    @Test
    void ingestsDocumentsIntoPostgresStorageProvider() {
        var image = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
        try (var container = new PostgreSQLContainer<>(image)) {
            container.start();

            var storage = new PostgresStorageProvider(new PostgresStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "lightrag",
                3,
                "rag_"
            ), new PostgresSnapshotStore());
            var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
            var document = new Document("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test"));

            try (storage) {
                var chunks = ingestor.ingest(List.of(document));

                assertThat(storage.documentStore().load("doc-1"))
                    .contains(new DocumentStore.DocumentRecord("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test")));
                assertThat(storage.chunkStore().listByDocument("doc-1"))
                    .containsExactly(
                        new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "abcd", 4, 0, Map.of("source", "unit-test")),
                        new ChunkStore.ChunkRecord("doc-1:1", "doc-1", "defg", 4, 1, Map.of("source", "unit-test")),
                        new ChunkStore.ChunkRecord("doc-1:2", "doc-1", "ghij", 4, 2, Map.of("source", "unit-test"))
                    );
                assertThat(chunks)
                    .extracting(Chunk::id)
                    .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
            }
        }
    }

    @Test
    void rollsBackPostgresDocumentWriteWhenChunkPersistenceFails() throws Exception {
        var image = DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
        try (var container = new PostgreSQLContainer<>(image)) {
            container.start();

            var storage = new PostgresStorageProvider(new PostgresStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "lightrag",
                3,
                "rag_"
            ), new PostgresSnapshotStore());

            try (storage) {
                try (
                    var connection = java.sql.DriverManager.getConnection(
                        container.getJdbcUrl(),
                        container.getUsername(),
                        container.getPassword()
                    );
                    var statement = connection.createStatement()
                ) {
                    statement.execute("DROP TABLE \"lightrag\".\"rag_chunks\"");
                }

                var ingestor = new DocumentIngestor(storage, new FixedWindowChunker(4, 1));
                var document = new Document("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test"));

                assertThatThrownBy(() -> ingestor.ingest(List.of(document)))
                    .isInstanceOf(io.github.lightrag.exception.StorageException.class)
                    .hasMessageContaining("JDBC operation failed");

                assertThat(storage.documentStore().list()).isEmpty();
            }
        }
    }

    private static final class AtomicFailureStorageProvider implements AtomicStorageProvider {
        private final AtomicTestDocumentStore documentStore = new AtomicTestDocumentStore();
        private final AtomicTestChunkStore chunkStore = new AtomicTestChunkStore();
        private final DocumentStatusStore documentStatusStore = new InMemoryDocumentStatusStore();
        private final TaskStore taskStore = new InMemoryTaskStore();
        private final TaskStageStore taskStageStore = new InMemoryTaskStageStore();
        private final io.github.lightrag.storage.DocumentGraphSnapshotStore documentGraphSnapshotStore =
            new io.github.lightrag.storage.memory.InMemoryDocumentGraphSnapshotStore();
        private final io.github.lightrag.storage.DocumentGraphJournalStore documentGraphJournalStore =
            new io.github.lightrag.storage.memory.InMemoryDocumentGraphJournalStore();
        private DocumentStore.DocumentRecord documentToInsertBeforeWrite;

        @Override
        public DocumentStore documentStore() {
            return documentStore;
        }

        @Override
        public ChunkStore chunkStore() {
            return chunkStore;
        }

        @Override
        public io.github.lightrag.storage.GraphStore graphStore() {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public io.github.lightrag.storage.VectorStore vectorStore() {
            throw new UnsupportedOperationException("not used in test");
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
        public io.github.lightrag.storage.SnapshotStore snapshotStore() {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public io.github.lightrag.storage.DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return documentGraphSnapshotStore;
        }

        @Override
        public io.github.lightrag.storage.DocumentGraphJournalStore documentGraphJournalStore() {
            return documentGraphJournalStore;
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            if (documentToInsertBeforeWrite != null) {
                documentStore.save(documentToInsertBeforeWrite);
                documentToInsertBeforeWrite = null;
            }
            var documentsBefore = documentStore.snapshot();
            var chunksBefore = chunkStore.snapshot();
            try {
                return operation.execute(new AtomicStorageView() {
                    @Override
                    public DocumentStore documentStore() {
                        return documentStore;
                    }

                    @Override
                    public ChunkStore chunkStore() {
                        return chunkStore;
                    }

                    @Override
                    public io.github.lightrag.storage.DocumentGraphSnapshotStore documentGraphSnapshotStore() {
                        return documentGraphSnapshotStore;
                    }

                    @Override
                    public io.github.lightrag.storage.DocumentGraphJournalStore documentGraphJournalStore() {
                        return documentGraphJournalStore;
                    }

                    @Override
                    public io.github.lightrag.storage.GraphStore graphStore() {
                        throw new UnsupportedOperationException("not used in test");
                    }

                    @Override
                    public io.github.lightrag.storage.VectorStore vectorStore() {
                        throw new UnsupportedOperationException("not used in test");
                    }

                    @Override
                    public DocumentStatusStore documentStatusStore() {
                        return documentStatusStore;
                    }
                });
            } catch (RuntimeException failure) {
                chunkStore.restore(chunksBefore);
                documentStore.restore(documentsBefore);
                throw failure;
            }
        }

        @Override
        public void restore(io.github.lightrag.storage.SnapshotStore.Snapshot snapshot) {
            throw new UnsupportedOperationException("not used in test");
        }

        void insertDocumentBeforeAtomicWrite(DocumentStore.DocumentRecord document) {
            documentToInsertBeforeWrite = document;
        }
    }

    private static final class AtomicTestDocumentStore implements DocumentStore {
        private final Map<String, DocumentStore.DocumentRecord> documents = new LinkedHashMap<>();

        @Override
        public void save(DocumentStore.DocumentRecord document) {
            documents.put(document.id(), document);
        }

        @Override
        public Optional<DocumentStore.DocumentRecord> load(String documentId) {
            return Optional.ofNullable(documents.get(documentId));
        }

        @Override
        public List<DocumentStore.DocumentRecord> list() {
            return List.copyOf(documents.values());
        }

        @Override
        public boolean contains(String documentId) {
            return documents.containsKey(documentId);
        }

        List<DocumentStore.DocumentRecord> snapshot() {
            return list();
        }

        void restore(List<DocumentStore.DocumentRecord> snapshot) {
            documents.clear();
            for (var document : snapshot) {
                documents.put(document.id(), document);
            }
        }
    }

    private static final class AtomicTestChunkStore implements ChunkStore {
        private final Map<String, ChunkStore.ChunkRecord> chunks = new LinkedHashMap<>();
        private int saveAttempts;

        @Override
        public void save(ChunkStore.ChunkRecord chunk) {
            saveAttempts++;
            if (saveAttempts == 2) {
                throw new IllegalStateException("chunk save failed");
            }
            chunks.put(chunk.id(), chunk);
        }

        @Override
        public Optional<ChunkStore.ChunkRecord> load(String chunkId) {
            return Optional.ofNullable(chunks.get(chunkId));
        }

        @Override
        public List<ChunkStore.ChunkRecord> list() {
            return List.copyOf(chunks.values());
        }

        @Override
        public List<ChunkStore.ChunkRecord> listByDocument(String documentId) {
            return chunks.values().stream()
                .filter(chunk -> chunk.documentId().equals(documentId))
                .toList();
        }

        List<ChunkStore.ChunkRecord> snapshot() {
            return list();
        }

        void restore(List<ChunkStore.ChunkRecord> snapshot) {
            chunks.clear();
            for (var chunk : snapshot) {
                chunks.put(chunk.id(), chunk);
            }
            saveAttempts = 0;
        }
    }

    private static final class PostgresSnapshotStore implements io.github.lightrag.storage.SnapshotStore {
        @Override
        public void save(java.nio.file.Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(java.nio.file.Path path) {
            throw new UnsupportedOperationException("Not used in DocumentIngestor PostgreSQL test");
        }

        @Override
        public List<java.nio.file.Path> list() {
            return List.of();
        }
    }

}
