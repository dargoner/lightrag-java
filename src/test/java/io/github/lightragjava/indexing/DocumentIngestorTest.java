package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
