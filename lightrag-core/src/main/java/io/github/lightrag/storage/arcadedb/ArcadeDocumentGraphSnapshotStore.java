package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArcadeDocumentGraphSnapshotStore extends ArcadeStoreSupport implements DocumentGraphSnapshotStore {
    public ArcadeDocumentGraphSnapshotStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void saveDocument(DocumentGraphSnapshot snapshot) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("version", snapshot.version());
        properties.put("status", snapshot.status().name());
        properties.put("source", snapshot.source().name());
        properties.put("chunkCount", snapshot.chunkCount());
        properties.put("createdAt", snapshot.createdAt().toString());
        properties.put("updatedAt", snapshot.updatedAt().toString());
        properties.put("errorMessage", snapshot.errorMessage());
        upsertByWorkspaceId("DocumentGraphSnapshot", "documentId", snapshot.documentId(), properties);
    }

    @Override
    public Optional<DocumentGraphSnapshot> loadDocument(String documentId) {
        return first(
            "SELECT documentId, version, status, source, chunkCount, createdAt, updatedAt, errorMessage FROM DocumentGraphSnapshot WHERE workspaceId = ? AND documentId = ? LIMIT 1",
            workspaceId,
            documentId
        ).map(this::readDocumentSnapshot);
    }

    @Override
    public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
        execute("DELETE FROM ChunkGraphSnapshot WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
        for (var chunk : List.copyOf(chunks)) {
            execute(
                "INSERT INTO ChunkGraphSnapshot SET workspaceId = ?, documentId = ?, chunkId = ?, chunkOrder = ?, contentHash = ?, extractStatus = ?, entities = ?, relations = ?, updatedAt = ?, errorMessage = ?",
                workspaceId,
                chunk.documentId(),
                chunk.chunkId(),
                chunk.chunkOrder(),
                chunk.contentHash(),
                chunk.extractStatus().name(),
                ArcadeJsonCodec.writeExtractedEntityRecordList(chunk.entities()),
                ArcadeJsonCodec.writeExtractedRelationRecordList(chunk.relations()),
                chunk.updatedAt().toString(),
                chunk.errorMessage()
            );
        }
    }

    @Override
    public List<ChunkGraphSnapshot> listChunks(String documentId) {
        return query(
            "SELECT documentId, chunkId, chunkOrder, contentHash, extractStatus, entities, relations, updatedAt, errorMessage FROM ChunkGraphSnapshot WHERE workspaceId = ? AND documentId = ? ORDER BY chunkOrder, chunkId",
            workspaceId,
            documentId
        ).stream().map(this::readChunkSnapshot).toList();
    }

    @Override
    public void delete(String documentId) {
        execute("DELETE FROM DocumentGraphSnapshot WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
        execute("DELETE FROM ChunkGraphSnapshot WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
    }

    List<DocumentGraphSnapshot> listDocuments() {
        return query(
            "SELECT documentId, version, status, source, chunkCount, createdAt, updatedAt, errorMessage FROM DocumentGraphSnapshot WHERE workspaceId = ? ORDER BY documentId",
            workspaceId
        ).stream().map(this::readDocumentSnapshot).toList();
    }

    List<ChunkGraphSnapshot> listAllChunks() {
        return query(
            "SELECT documentId, chunkId, chunkOrder, contentHash, extractStatus, entities, relations, updatedAt, errorMessage FROM ChunkGraphSnapshot WHERE workspaceId = ? ORDER BY documentId, chunkOrder, chunkId",
            workspaceId
        ).stream().map(this::readChunkSnapshot).toList();
    }

    void deleteAll() {
        deleteWorkspaceRows("ChunkGraphSnapshot");
        deleteWorkspaceRows("DocumentGraphSnapshot");
    }

    private DocumentGraphSnapshot readDocumentSnapshot(Map<String, Object> row) {
        return new DocumentGraphSnapshot(
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.integer(row, "version"),
            SnapshotStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            SnapshotSource.valueOf(ArcadeRecordMapper.string(row, "source")),
            ArcadeRecordMapper.integer(row, "chunkCount"),
            Instant.parse(ArcadeRecordMapper.string(row, "createdAt")),
            Instant.parse(ArcadeRecordMapper.string(row, "updatedAt")),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }

    private ChunkGraphSnapshot readChunkSnapshot(Map<String, Object> row) {
        return new ChunkGraphSnapshot(
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.string(row, "chunkId"),
            ArcadeRecordMapper.integer(row, "chunkOrder"),
            ArcadeRecordMapper.string(row, "contentHash"),
            ChunkExtractStatus.valueOf(ArcadeRecordMapper.string(row, "extractStatus")),
            ArcadeJsonCodec.readExtractedEntityRecordList(row.get("entities")),
            ArcadeJsonCodec.readExtractedRelationRecordList(row.get("relations")),
            Instant.parse(ArcadeRecordMapper.string(row, "updatedAt")),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }
}
