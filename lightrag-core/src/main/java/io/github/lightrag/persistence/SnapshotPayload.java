package io.github.lightrag.persistence;

import io.github.lightrag.ontology.AttributeJsonCodec;
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
            entities.stream().map(SnapshotPayload::canonicalEntity).toList(),
            relations.stream().map(SnapshotPayload::canonicalRelation).toList(),
            vectors,
            documentStatuses,
            documentGraphSnapshots,
            chunkGraphSnapshots.stream().map(SnapshotPayload::canonicalChunkGraph).toList(),
            documentGraphJournals,
            chunkGraphJournals
        );
    }

    /**
     * Attribute bags are canonical JSON values: integral numbers are {@link Long}
     * and fractional numbers are {@link Double}. JSON deserialization yields
     * {@link Integer} for small integrals, so re-encoding through the codec makes
     * a reloaded bag identical to the value that was saved. Other snapshot maps
     * (for example document-status metadata) keep their plain JSON types.
     */
    private static Map<String, Object> canonicalAttributes(Map<String, Object> attributes) {
        return AttributeJsonCodec.fromJson(AttributeJsonCodec.toJson(attributes));
    }

    private static GraphStore.EntityRecord canonicalEntity(GraphStore.EntityRecord entity) {
        return new GraphStore.EntityRecord(
            entity.id(),
            entity.name(),
            entity.type(),
            entity.description(),
            entity.aliases(),
            entity.sourceChunkIds(),
            entity.filePath(),
            canonicalAttributes(entity.attributes())
        );
    }

    private static GraphStore.RelationRecord canonicalRelation(GraphStore.RelationRecord relation) {
        return new GraphStore.RelationRecord(
            relation.id(),
            relation.srcId(),
            relation.tgtId(),
            relation.keywords(),
            relation.description(),
            relation.weight(),
            relation.sourceId(),
            relation.filePath(),
            canonicalAttributes(relation.attributes())
        );
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot canonicalChunkGraph(
        DocumentGraphSnapshotStore.ChunkGraphSnapshot chunk
    ) {
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            chunk.documentId(),
            chunk.chunkId(),
            chunk.chunkOrder(),
            chunk.contentHash(),
            chunk.extractStatus(),
            chunk.entities().stream()
                .map(entity -> new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                    entity.name(),
                    entity.type(),
                    entity.description(),
                    entity.aliases(),
                    canonicalAttributes(entity.attributes())
                ))
                .toList(),
            chunk.relations().stream()
                .map(relation -> new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                    relation.sourceEntityName(),
                    relation.targetEntityName(),
                    relation.keywords(),
                    relation.description(),
                    relation.weight(),
                    canonicalAttributes(relation.attributes())
                ))
                .toList(),
            chunk.updatedAt(),
            chunk.errorMessage()
        );
    }
}
