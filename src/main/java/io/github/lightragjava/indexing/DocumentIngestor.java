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
    private final RollbackCapableDocumentStore rollbackDocumentStore;
    private final RollbackCapableChunkStore rollbackChunkStore;

    public DocumentIngestor(StorageProvider storageProvider, Chunker chunker) {
        var storage = Objects.requireNonNull(storageProvider, "storageProvider");
        this.documentStore = Objects.requireNonNull(storage.documentStore(), "storageProvider.documentStore()");
        this.chunkStore = Objects.requireNonNull(storage.chunkStore(), "storageProvider.chunkStore()");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.rollbackDocumentStore = requireRollbackDocumentStore(documentStore);
        this.rollbackChunkStore = requireRollbackChunkStore(chunkStore);
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

    private static RollbackCapableDocumentStore requireRollbackDocumentStore(DocumentStore documentStore) {
        if (documentStore instanceof RollbackCapableDocumentStore rollbackCapableDocumentStore) {
            return rollbackCapableDocumentStore;
        }
        if (documentStore instanceof InMemoryDocumentStore inMemoryDocumentStore) {
            return new InMemoryRollbackDocumentStore(inMemoryDocumentStore);
        }
        throw new IllegalStateException("DocumentIngestor requires rollback-capable documentStore");
    }

    private static RollbackCapableChunkStore requireRollbackChunkStore(ChunkStore chunkStore) {
        if (chunkStore instanceof RollbackCapableChunkStore rollbackCapableChunkStore) {
            return rollbackCapableChunkStore;
        }
        if (chunkStore instanceof InMemoryChunkStore inMemoryChunkStore) {
            return new InMemoryRollbackChunkStore(inMemoryChunkStore);
        }
        throw new IllegalStateException("DocumentIngestor requires rollback-capable chunkStore");
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

    public interface RollbackCapableDocumentStore extends DocumentStore {
        void restoreDocuments(List<DocumentStore.DocumentRecord> snapshot);
    }

    public interface RollbackCapableChunkStore extends ChunkStore {
        void restoreChunks(List<ChunkStore.ChunkRecord> snapshot);
    }

    private static final class InMemoryRollbackDocumentStore implements RollbackCapableDocumentStore {
        private final InMemoryDocumentStore delegate;

        private InMemoryRollbackDocumentStore(InMemoryDocumentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(DocumentStore.DocumentRecord document) {
            delegate.save(document);
        }

        @Override
        public java.util.Optional<DocumentStore.DocumentRecord> load(String documentId) {
            return delegate.load(documentId);
        }

        @Override
        public List<DocumentStore.DocumentRecord> list() {
            return delegate.list();
        }

        @Override
        public boolean contains(String documentId) {
            return delegate.contains(documentId);
        }

        @Override
        public void restoreDocuments(List<DocumentStore.DocumentRecord> snapshot) {
            replaceBackingMap(delegate, "documents", indexDocuments(snapshot));
        }
    }

    private static final class InMemoryRollbackChunkStore implements RollbackCapableChunkStore {
        private final InMemoryChunkStore delegate;

        private InMemoryRollbackChunkStore(InMemoryChunkStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ChunkStore.ChunkRecord chunk) {
            delegate.save(chunk);
        }

        @Override
        public java.util.Optional<ChunkStore.ChunkRecord> load(String chunkId) {
            return delegate.load(chunkId);
        }

        @Override
        public List<ChunkStore.ChunkRecord> list() {
            return delegate.list();
        }

        @Override
        public List<ChunkStore.ChunkRecord> listByDocument(String documentId) {
            return delegate.listByDocument(documentId);
        }

        @Override
        public void restoreChunks(List<ChunkStore.ChunkRecord> snapshot) {
            replaceBackingMap(delegate, "chunks", indexChunks(snapshot));
        }
    }
}
