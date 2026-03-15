package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.IngestStorageProvider;
import io.github.lightragjava.storage.RollbackCapableChunkStore;
import io.github.lightragjava.storage.RollbackCapableDocumentStore;
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
    private final RollbackCapableDocumentStore rollbackDocumentStore;
    private final RollbackCapableChunkStore rollbackChunkStore;

    public DocumentIngestor(IngestStorageProvider storageProvider, Chunker chunker) {
        var storage = Objects.requireNonNull(storageProvider, "storageProvider");
        this.rollbackDocumentStore = Objects.requireNonNull(storage.documentStore(), "storageProvider.documentStore()");
        this.rollbackChunkStore = Objects.requireNonNull(storage.chunkStore(), "storageProvider.chunkStore()");
        this.documentStore = rollbackDocumentStore;
        this.chunkStore = rollbackChunkStore;
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    public List<Chunk> ingest(List<Document> documents) {
        var batch = List.copyOf(Objects.requireNonNull(documents, "documents"));
        validateIds(batch);
        var documentRecords = new ArrayList<DocumentStore.DocumentRecord>(batch.size());
        var chunkRecords = new ArrayList<ChunkStore.ChunkRecord>();
        var stagedChunks = new ArrayList<Chunk>();

        for (var document : batch) {
            documentRecords.add(new DocumentStore.DocumentRecord(
                document.id(),
                document.title(),
                document.content(),
                document.metadata()
            ));

            var chunks = chunker.chunk(document);
            for (var chunk : chunks) {
                chunkRecords.add(new ChunkStore.ChunkRecord(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.text(),
                    chunk.tokenCount(),
                    chunk.order(),
                    chunk.metadata()
                ));
            }
            stagedChunks.addAll(chunks);
        }

        persistAtomically(documentRecords, chunkRecords);
        return List.copyOf(stagedChunks);
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

    private void persistAtomically(
        List<DocumentStore.DocumentRecord> documentRecords,
        List<ChunkStore.ChunkRecord> chunkRecords
    ) {
        var documentsBefore = documentStore.list();
        var chunksBefore = chunkStore.list();
        try {
            for (var documentRecord : documentRecords) {
                documentStore.save(documentRecord);
            }
            for (var chunkRecord : chunkRecords) {
                chunkStore.save(chunkRecord);
            }
        } catch (RuntimeException failure) {
            rollback(documentsBefore, chunksBefore, failure);
        }
    }

    private void rollback(
        List<DocumentStore.DocumentRecord> documentsBefore,
        List<ChunkStore.ChunkRecord> chunksBefore,
        RuntimeException failure
    ) {
        RuntimeException rollbackFailure = null;

        try {
            restoreChunkStore(chunksBefore);
        } catch (RuntimeException exception) {
            rollbackFailure = exception;
        }

        try {
            restoreDocumentStore(documentsBefore);
        } catch (RuntimeException exception) {
            if (rollbackFailure == null) {
                rollbackFailure = exception;
            } else {
                rollbackFailure.addSuppressed(exception);
            }
        }

        if (rollbackFailure != null) {
            failure.addSuppressed(rollbackFailure);
        }
        throw failure;
    }

    private void restoreDocumentStore(List<DocumentStore.DocumentRecord> snapshot) {
        rollbackDocumentStore.restoreDocuments(snapshot);
    }

    private void restoreChunkStore(List<ChunkStore.ChunkRecord> snapshot) {
        rollbackChunkStore.restoreChunks(snapshot);
    }

}
