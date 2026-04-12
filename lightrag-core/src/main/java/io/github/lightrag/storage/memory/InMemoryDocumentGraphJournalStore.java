package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.DocumentGraphJournalStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryDocumentGraphJournalStore implements DocumentGraphJournalStore {
    private final ConcurrentNavigableMap<String, List<DocumentGraphJournal>> documentJournals = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<String, List<ChunkGraphJournal>> chunkJournals = new ConcurrentSkipListMap<>();
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
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documentJournals.put(entry.documentId(), append(documentJournals.get(entry.documentId()), entry));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<DocumentGraphJournal> listDocumentJournals(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var journals = documentJournals.get(normalizeDocumentId(documentId));
            if (journals == null) {
                return List.of();
            }
            return List.copyOf(journals);
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
            chunkJournals.put(normalizedDocumentId, append(chunkJournals.get(normalizedDocumentId), additions));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<ChunkGraphJournal> listChunkJournals(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var journals = chunkJournals.get(normalizeDocumentId(documentId));
            if (journals == null) {
                return List.of();
            }
            return List.copyOf(journals);
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

    private static List<DocumentGraphJournal> append(List<DocumentGraphJournal> existing, DocumentGraphJournal addition) {
        var combined = existing == null ? new ArrayList<DocumentGraphJournal>(1) : new ArrayList<>(existing);
        combined.add(addition);
        return List.copyOf(combined);
    }

    private static List<ChunkGraphJournal> append(List<ChunkGraphJournal> existing, List<ChunkGraphJournal> additions) {
        var combined = existing == null ? new ArrayList<ChunkGraphJournal>(additions.size()) : new ArrayList<>(existing);
        combined.addAll(additions);
        return List.copyOf(combined);
    }

    private static String normalizeDocumentId(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }
}
