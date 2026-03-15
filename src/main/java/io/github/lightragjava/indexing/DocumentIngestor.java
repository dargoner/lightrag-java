package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class DocumentIngestor {
    private final AtomicStorageProvider storageProvider;
    private final Chunker chunker;

    public DocumentIngestor(AtomicStorageProvider storageProvider, Chunker chunker) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
    }

    public List<Chunk> ingest(List<Document> documents) {
        var batch = List.copyOf(Objects.requireNonNull(documents, "documents"));
        validateUniqueBatchIds(batch);
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

        storageProvider.writeAtomically(storage -> {
            validateIdsNotInStorage(batch, storage.documentStore());
            persist(documentRecords, chunkRecords, storage.documentStore(), storage.chunkStore());
            return null;
        });
        return List.copyOf(stagedChunks);
    }

    private void validateUniqueBatchIds(List<Document> documents) {
        var seenIds = new HashSet<String>();
        for (var document : documents) {
            var source = Objects.requireNonNull(document, "document");
            if (!seenIds.add(source.id())) {
                throw new IllegalArgumentException("Duplicate document id in batch: " + source.id());
            }
        }
    }

    private void validateIdsNotInStorage(List<Document> documents, DocumentStore documentStore) {
        for (var document : documents) {
            if (documentStore.contains(document.id())) {
                throw new IllegalArgumentException("Document id already exists in storage: " + document.id());
            }
        }
    }

    private void persist(
        List<DocumentStore.DocumentRecord> documentRecords,
        List<ChunkStore.ChunkRecord> chunkRecords,
        DocumentStore documentStore,
        ChunkStore chunkStore
    ) {
        for (var documentRecord : documentRecords) {
            documentStore.save(documentRecord);
        }
        for (var chunkRecord : chunkRecords) {
            chunkStore.save(chunkRecord);
        }
    }

}
