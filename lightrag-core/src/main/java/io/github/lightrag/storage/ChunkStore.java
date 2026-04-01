package io.github.lightrag.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface ChunkStore {
    void save(ChunkRecord chunk);

    Optional<ChunkRecord> load(String chunkId);

    List<ChunkRecord> list();

    List<ChunkRecord> listByDocument(String documentId);

    record ChunkRecord(
        String id,
        String documentId,
        String text,
        int tokenCount,
        int order,
        Map<String, String> metadata
    ) {
        public ChunkRecord {
            id = Objects.requireNonNull(id, "id");
            documentId = Objects.requireNonNull(documentId, "documentId");
            text = Objects.requireNonNull(text, "text");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }
    }
}
