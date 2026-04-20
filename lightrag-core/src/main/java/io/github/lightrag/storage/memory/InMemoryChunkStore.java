package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.ChunkStore;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryChunkStore implements ChunkStore {
    private static final Comparator<ChunkRecord> DOCUMENT_ORDER =
        Comparator.comparingInt(ChunkRecord::order).thenComparing(ChunkRecord::id);

    private final ConcurrentNavigableMap<String, ChunkRecord> chunks = new ConcurrentSkipListMap<>();
    private final ReadWriteLock lock;

    public InMemoryChunkStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryChunkStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void save(ChunkRecord chunk) {
        var record = Objects.requireNonNull(chunk, "chunk");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            chunks.put(record.id(), record);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<ChunkRecord> load(String chunkId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(chunks.get(Objects.requireNonNull(chunkId, "chunkId")));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public java.util.Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
        var ids = List.copyOf(Objects.requireNonNull(chunkIds, "chunkIds"));
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var chunksById = new LinkedHashMap<String, ChunkRecord>();
            for (var chunkId : ids) {
                var chunk = chunks.get(chunkId);
                if (chunk != null) {
                    chunksById.put(chunkId, chunk);
                }
            }
            return Collections.unmodifiableMap(chunksById);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<ChunkRecord> list() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return List.copyOf(chunks.values());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<ChunkRecord> listByDocument(String documentId) {
        var targetDocumentId = Objects.requireNonNull(documentId, "documentId");
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return chunks.values().stream()
                .filter(chunk -> chunk.documentId().equals(targetDocumentId))
                .sorted(DOCUMENT_ORDER)
                .toList();
        } finally {
            readLock.unlock();
        }
    }

    public List<ChunkRecord> snapshot() {
        return list();
    }

    public void restore(List<ChunkRecord> snapshot) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            chunks.clear();
            for (var record : Objects.requireNonNull(snapshot, "snapshot")) {
                chunks.put(record.id(), record);
            }
        } finally {
            writeLock.unlock();
        }
    }
}
