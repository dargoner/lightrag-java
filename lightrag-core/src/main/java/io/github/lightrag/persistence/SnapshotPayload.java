package io.github.lightrag.persistence;

import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
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
    List<DocumentStatusStore.StatusRecord> documentStatuses,
    List<DocumentGraphSnapshotStore.DocumentGraphSnapshot> documentGraphSnapshots,
    List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunkGraphSnapshots,
    List<DocumentGraphJournalStore.DocumentGraphJournal> documentGraphJournals,
    List<DocumentGraphJournalStore.ChunkGraphJournal> chunkGraphJournals
) {
    public SnapshotPayload {
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        Objects.requireNonNull(vectors, "vectors");
        documentStatuses = documentStatuses == null ? List.of() : List.copyOf(documentStatuses);
        documentGraphSnapshots = documentGraphSnapshots == null ? List.of() : List.copyOf(documentGraphSnapshots);
        chunkGraphSnapshots = chunkGraphSnapshots == null ? List.of() : List.copyOf(chunkGraphSnapshots);
        documentGraphJournals = documentGraphJournals == null ? List.of() : List.copyOf(documentGraphJournals);
        chunkGraphJournals = chunkGraphJournals == null ? List.of() : List.copyOf(chunkGraphJournals);
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
            source.documentStatuses(),
            source.documentGraphSnapshots(),
            source.chunkGraphSnapshots(),
            source.documentGraphJournals(),
            source.chunkGraphJournals()
        );
    }

    public SnapshotStore.Snapshot toSnapshot() {
        return new SnapshotStore.Snapshot(
            documents,
            chunks,
            entities,
            relations,
            vectors,
            documentStatuses,
            documentGraphSnapshots,
            chunkGraphSnapshots,
            documentGraphJournals,
            chunkGraphJournals
        );
    }
}
