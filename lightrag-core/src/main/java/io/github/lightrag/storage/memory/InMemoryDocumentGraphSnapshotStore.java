package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryDocumentGraphSnapshotStore implements DocumentGraphSnapshotStore {
    private final ConcurrentNavigableMap<String, DocumentGraphSnapshot> documentSnapshots = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<String, List<ChunkGraphSnapshot>> chunkSnapshots = new ConcurrentSkipListMap<>();
    private final ReadWriteLock lock;

    public InMemoryDocumentGraphSnapshotStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryDocumentGraphSnapshotStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void saveDocument(DocumentGraphSnapshot snapshot) {
        var value = Objects.requireNonNull(snapshot, "snapshot");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documentSnapshots.put(value.documentId(), value);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<DocumentGraphSnapshot> loadDocument(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(documentSnapshots.get(normalizeDocumentId(documentId)));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var copy = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        for (var chunk : copy) {
            Objects.requireNonNull(chunk, "chunk");
            if (!chunk.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("chunk documentId must match documentId");
            }
        }
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            chunkSnapshots.put(normalizedDocumentId, copy);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<ChunkGraphSnapshot> listChunks(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var chunks = chunkSnapshots.get(normalizeDocumentId(documentId));
            if (chunks == null) {
                return List.of();
            }
            return List.copyOf(chunks);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void delete(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documentSnapshots.remove(normalizedDocumentId);
            chunkSnapshots.remove(normalizedDocumentId);
        } finally {
            writeLock.unlock();
        }
    }

    private static String normalizeDocumentId(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }
}
