package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class DocumentIngestor {
    private final DocumentStore documentStore;
    private final ChunkStore chunkStore;
    private final Chunker chunker;

    public DocumentIngestor(StorageProvider storageProvider, Chunker chunker) {
        var storage = Objects.requireNonNull(storageProvider, "storageProvider");
        this.documentStore = Objects.requireNonNull(storage.documentStore(), "storageProvider.documentStore()");
        this.chunkStore = Objects.requireNonNull(storage.chunkStore(), "storageProvider.chunkStore()");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    public List<Chunk> ingest(List<Document> documents) {
        var batch = List.copyOf(Objects.requireNonNull(documents, "documents"));
        validateIds(batch);

        var persistedChunks = new ArrayList<Chunk>();
        for (var document : batch) {
            documentStore.save(new DocumentStore.DocumentRecord(
                document.id(),
                document.title(),
                document.content(),
                document.metadata()
            ));

            var chunks = chunker.chunk(document);
            for (var chunk : chunks) {
                chunkStore.save(new ChunkStore.ChunkRecord(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.text(),
                    chunk.tokenCount(),
                    chunk.order(),
                    chunk.metadata()
                ));
            }
            persistedChunks.addAll(chunks);
        }

        return List.copyOf(persistedChunks);
    }

    private void validateIds(List<Document> documents) {
        var seenIds = new HashSet<String>();
        for (var document : documents) {
            var source = Objects.requireNonNull(document, "document");
            if (!seenIds.add(source.id())) {
                throw new IllegalArgumentException("Duplicate document id in batch: " + source.id());
            }
            if (documentStore.contains(source.id())) {
                throw new IllegalArgumentException("Document id already exists in storage: " + source.id());
            }
        }
    }
}
