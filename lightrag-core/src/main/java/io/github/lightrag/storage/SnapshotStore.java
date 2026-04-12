package io.github.lightrag.storage;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface SnapshotStore {
    void save(Path path, Snapshot snapshot);

    Snapshot load(Path path);

    List<Path> list();

    record Snapshot(
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
        public Snapshot {
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            Objects.requireNonNull(vectors, "vectors");
            vectors = copyVectors(vectors);
            documentStatuses = List.copyOf(Objects.requireNonNull(documentStatuses, "documentStatuses"));
            documentGraphSnapshots = List.copyOf(Objects.requireNonNull(documentGraphSnapshots, "documentGraphSnapshots"));
            chunkGraphSnapshots = List.copyOf(Objects.requireNonNull(chunkGraphSnapshots, "chunkGraphSnapshots"));
            documentGraphJournals = List.copyOf(Objects.requireNonNull(documentGraphJournals, "documentGraphJournals"));
            chunkGraphJournals = List.copyOf(Objects.requireNonNull(chunkGraphJournals, "chunkGraphJournals"));
        }

        public Snapshot(
            List<DocumentStore.DocumentRecord> documents,
            List<ChunkStore.ChunkRecord> chunks,
            List<GraphStore.EntityRecord> entities,
            List<GraphStore.RelationRecord> relations,
            Map<String, List<VectorStore.VectorRecord>> vectors,
            List<DocumentStatusStore.StatusRecord> documentStatuses
        ) {
            this(
                documents,
                chunks,
                entities,
                relations,
                vectors,
                documentStatuses,
                List.of(),
                List.of(),
                List.of(),
                List.of()
            );
        }

        public Snapshot(
            List<DocumentStore.DocumentRecord> documents,
            List<ChunkStore.ChunkRecord> chunks,
            List<GraphStore.EntityRecord> entities,
            List<GraphStore.RelationRecord> relations,
            Map<String, List<VectorStore.VectorRecord>> vectors
        ) {
            this(documents, chunks, entities, relations, vectors, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        private static Map<String, List<VectorStore.VectorRecord>> copyVectors(
            Map<String, List<VectorStore.VectorRecord>> vectors
        ) {
            var copy = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
            for (var entry : vectors.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return Map.copyOf(copy);
        }
    }

    record DocumentGraphState(
        List<DocumentGraphSnapshotStore.DocumentGraphSnapshot> documentSnapshots,
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunkSnapshots,
        List<DocumentGraphJournalStore.DocumentGraphJournal> documentJournals,
        List<DocumentGraphJournalStore.ChunkGraphJournal> chunkJournals
    ) {
        public DocumentGraphState {
            documentSnapshots = List.copyOf(Objects.requireNonNull(documentSnapshots, "documentSnapshots"));
            chunkSnapshots = List.copyOf(Objects.requireNonNull(chunkSnapshots, "chunkSnapshots"));
            documentJournals = List.copyOf(Objects.requireNonNull(documentJournals, "documentJournals"));
            chunkJournals = List.copyOf(Objects.requireNonNull(chunkJournals, "chunkJournals"));
        }
    }
}
