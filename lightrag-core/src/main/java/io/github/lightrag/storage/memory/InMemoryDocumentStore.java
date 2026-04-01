package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.DocumentStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryDocumentStore implements DocumentStore {
    private final ConcurrentNavigableMap<String, DocumentRecord> documents = new ConcurrentSkipListMap<>();
    private final ReadWriteLock lock;

    public InMemoryDocumentStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryDocumentStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void save(DocumentRecord document) {
        var record = Objects.requireNonNull(document, "document");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documents.put(record.id(), record);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<DocumentRecord> load(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(documents.get(Objects.requireNonNull(documentId, "documentId")));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<DocumentRecord> list() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return List.copyOf(documents.values());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean contains(String documentId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return documents.containsKey(Objects.requireNonNull(documentId, "documentId"));
        } finally {
            readLock.unlock();
        }
    }

    public List<DocumentRecord> snapshot() {
        return list();
    }

    public void restore(List<DocumentRecord> snapshot) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            documents.clear();
            for (var record : Objects.requireNonNull(snapshot, "snapshot")) {
                documents.put(record.id(), record);
            }
        } finally {
            writeLock.unlock();
        }
    }
}
