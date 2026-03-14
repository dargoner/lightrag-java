package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.memory.InMemoryChunkStore;
import io.github.lightragjava.storage.memory.InMemoryDocumentStore;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (documentStore instanceof RestorableDocumentStore restorableDocumentStore) {
            restorableDocumentStore.restoreDocuments(snapshot);
            return;
        }
        if (documentStore instanceof InMemoryDocumentStore inMemoryDocumentStore) {
            replaceBackingMap(inMemoryDocumentStore, "documents", indexDocuments(snapshot));
            return;
        }
        assertStoreMatchesSnapshot(documentStore.list(), snapshot, "documentStore");
    }

    private void restoreChunkStore(List<ChunkStore.ChunkRecord> snapshot) {
        if (chunkStore instanceof RestorableChunkStore restorableChunkStore) {
            restorableChunkStore.restoreChunks(snapshot);
            return;
        }
        if (chunkStore instanceof InMemoryChunkStore inMemoryChunkStore) {
            replaceBackingMap(inMemoryChunkStore, "chunks", indexChunks(snapshot));
            return;
        }
        assertStoreMatchesSnapshot(chunkStore.list(), snapshot, "chunkStore");
    }

    private static void assertStoreMatchesSnapshot(List<?> actual, List<?> snapshot, String storeName) {
        if (!actual.equals(snapshot)) {
            throw new IllegalStateException(storeName + " does not support rollback");
        }
    }

    private static Map<String, DocumentStore.DocumentRecord> indexDocuments(List<DocumentStore.DocumentRecord> snapshot) {
        var indexed = new LinkedHashMap<String, DocumentStore.DocumentRecord>(snapshot.size());
        for (var document : snapshot) {
            indexed.put(document.id(), document);
        }
        return indexed;
    }

    private static Map<String, ChunkStore.ChunkRecord> indexChunks(List<ChunkStore.ChunkRecord> snapshot) {
        var indexed = new LinkedHashMap<String, ChunkStore.ChunkRecord>(snapshot.size());
        for (var chunk : snapshot) {
            indexed.put(chunk.id(), chunk);
        }
        return indexed;
    }

    @SuppressWarnings("unchecked")
    private static <T> void replaceBackingMap(Object store, String fieldName, Map<String, T> snapshot) {
        try {
            var field = store.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            var map = (Map<String, T>) field.get(store);
            map.clear();
            map.putAll(snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to restore " + store.getClass().getSimpleName(), exception);
        }
    }

    interface RestorableDocumentStore extends DocumentStore {
        void restoreDocuments(List<DocumentStore.DocumentRecord> snapshot);
    }

    interface RestorableChunkStore extends ChunkStore {
        void restoreChunks(List<ChunkStore.ChunkRecord> snapshot);
    }
}
