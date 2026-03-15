package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
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
                    .isInstanceOf(io.github.lightragjava.exception.StorageException.class)
                    .hasMessageContaining("JDBC operation failed");

                assertThat(storage.documentStore().list()).isEmpty();
            }
        }
    }

    private static final class AtomicFailureStorageProvider implements AtomicStorageProvider {
        private final AtomicTestDocumentStore documentStore = new AtomicTestDocumentStore();
        private final AtomicTestChunkStore chunkStore = new AtomicTestChunkStore();
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
        public io.github.lightragjava.storage.GraphStore graphStore() {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public io.github.lightragjava.storage.VectorStore vectorStore() {
            throw new UnsupportedOperationException("not used in test");
        }

        @Override
        public io.github.lightragjava.storage.SnapshotStore snapshotStore() {
            throw new UnsupportedOperationException("not used in test");
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
                    public io.github.lightragjava.storage.GraphStore graphStore() {
                        throw new UnsupportedOperationException("not used in test");
                    }

                    @Override
                    public io.github.lightragjava.storage.VectorStore vectorStore() {
                        throw new UnsupportedOperationException("not used in test");
                    }
                });
            } catch (RuntimeException failure) {
                chunkStore.restore(chunksBefore);
                documentStore.restore(documentsBefore);
                throw failure;
            }
        }

        @Override
        public void restore(io.github.lightragjava.storage.SnapshotStore.Snapshot snapshot) {
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

    private static final class PostgresSnapshotStore implements io.github.lightragjava.storage.SnapshotStore {
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
