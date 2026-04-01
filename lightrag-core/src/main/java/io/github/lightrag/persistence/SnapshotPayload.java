package io.github.lightrag.persistence;

import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record SnapshotPayload(
    List<DocumentStore.DocumentRecord> documents,
    List<ChunkStore.ChunkRecord> chunks,
    List<GraphStore.EntityRecord> entities,
    List<GraphStore.RelationRecord> relations,
    Map<String, List<VectorStore.VectorRecord>> vectors,
    List<DocumentStatusStore.StatusRecord> documentStatuses
) {
    public SnapshotPayload {
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        Objects.requireNonNull(vectors, "vectors");
        documentStatuses = documentStatuses == null ? List.of() : List.copyOf(documentStatuses);
        var copy = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        for (var entry : vectors.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        vectors = Map.copyOf(copy);
    }

    public static SnapshotPayload fromSnapshot(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        return new SnapshotPayload(
            source.documents(),
            source.chunks(),
            source.entities(),
            source.relations(),
            source.vectors(),
            source.documentStatuses()
        );
    }

    public SnapshotStore.Snapshot toSnapshot() {
        return new SnapshotStore.Snapshot(documents, chunks, entities, relations, vectors, documentStatuses);
    }
}
