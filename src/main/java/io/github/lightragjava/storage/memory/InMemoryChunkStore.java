package io.github.lightragjava.storage.memory;

import io.github.lightragjava.storage.ChunkStore;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class InMemoryChunkStore implements ChunkStore {
    private static final Comparator<ChunkRecord> DOCUMENT_ORDER =
        Comparator.comparingInt(ChunkRecord::order).thenComparing(ChunkRecord::id);

    private final ConcurrentNavigableMap<String, ChunkRecord> chunks = new ConcurrentSkipListMap<>();

    @Override
    public void save(ChunkRecord chunk) {
        var record = Objects.requireNonNull(chunk, "chunk");
        chunks.put(record.id(), record);
    }

    @Override
    public Optional<ChunkRecord> load(String chunkId) {
        return Optional.ofNullable(chunks.get(Objects.requireNonNull(chunkId, "chunkId")));
    }

    @Override
    public List<ChunkRecord> list() {
        return List.copyOf(chunks.values());
    }

    @Override
    public List<ChunkRecord> listByDocument(String documentId) {
        var targetDocumentId = Objects.requireNonNull(documentId, "documentId");
        return chunks.values().stream()
            .filter(chunk -> chunk.documentId().equals(targetDocumentId))
            .sorted(DOCUMENT_ORDER)
            .toList();
    }
}
