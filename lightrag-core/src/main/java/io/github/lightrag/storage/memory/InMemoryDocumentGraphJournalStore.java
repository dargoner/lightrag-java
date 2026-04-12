package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.DocumentGraphJournalStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryDocumentGraphJournalStore implements DocumentGraphJournalStore {
    private final ConcurrentNavigableMap<String, DocumentGraphJournal> documentJournals = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<String, ConcurrentNavigableMap<String, ChunkGraphJournal>> chunkJournals = new ConcurrentSkipListMap<>();
    private final ReadWriteLock lock;

    public InMemoryDocumentGraphJournalStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryDocumentGraphJournalStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        var entry = Objects.requireNonNull(journal, "journal");
        var normalizedDocumentId = normalizeDocumentId(entry.documentId());
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documentJournals.put(normalizedDocumentId, entry);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<DocumentGraphJournal> listDocumentJournals(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var journal = documentJournals.get(normalizeDocumentId(documentId));
            if (journal == null) {
                return List.of();
            }
            return List.of(journal);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var additions = List.copyOf(Objects.requireNonNull(journals, "journals"));
        for (var journal : additions) {
            Objects.requireNonNull(journal, "journal");
            if (!journal.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("journal documentId must match documentId");
            }
        }
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            var perDocument = chunkJournals.computeIfAbsent(
                normalizedDocumentId,
                id -> new ConcurrentSkipListMap<>()
            );
            for (var journal : additions) {
                perDocument.put(journal.chunkId(), journal);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<ChunkGraphJournal> listChunkJournals(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var perDocument = chunkJournals.get(normalizeDocumentId(documentId));
            if (perDocument == null) {
                return List.of();
            }
            return List.copyOf(perDocument.values());
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
            documentJournals.remove(normalizedDocumentId);
            chunkJournals.remove(normalizedDocumentId);
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
