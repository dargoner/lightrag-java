package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.storage.DocumentGraphJournalStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ArcadeDocumentGraphJournalStore extends ArcadeStoreSupport implements DocumentGraphJournalStore {
    public ArcadeDocumentGraphJournalStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        execute(
            "DELETE FROM DocumentGraphJournal WHERE workspaceId = ? AND documentId = ?",
            workspaceId,
            journal.documentId()
        );
        execute(
            "INSERT INTO DocumentGraphJournal SET workspaceId = ?, documentId = ?, snapshotVersion = ?, status = ?, lastMode = ?, expectedEntityCount = ?, expectedRelationCount = ?, materializedEntityCount = ?, materializedRelationCount = ?, lastFailureStage = ?, createdAt = ?, updatedAt = ?, errorMessage = ?",
            workspaceId,
            journal.documentId(),
            journal.snapshotVersion(),
            journal.status().name(),
            journal.lastMode().name(),
            journal.expectedEntityCount(),
            journal.expectedRelationCount(),
            journal.materializedEntityCount(),
            journal.materializedRelationCount(),
            journal.lastFailureStage() == null ? null : journal.lastFailureStage().name(),
            journal.createdAt().toString(),
            journal.updatedAt().toString(),
            journal.errorMessage()
        );
    }

    @Override
    public List<DocumentGraphJournal> listDocumentJournals(String documentId) {
        return query(selectDocumentBase() + " WHERE workspaceId = ? AND documentId = ? ORDER BY updatedAt", workspaceId, documentId)
            .stream()
            .map(this::readDocumentJournal)
            .toList();
    }

    @Override
    public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
        for (var journal : List.copyOf(journals)) {
            execute(
                "DELETE FROM ChunkGraphJournal WHERE workspaceId = ? AND documentId = ? AND chunkId = ?",
                workspaceId,
                journal.documentId(),
                journal.chunkId()
            );
            execute(
                "INSERT INTO ChunkGraphJournal SET workspaceId = ?, documentId = ?, chunkId = ?, snapshotVersion = ?, mergeStatus = ?, graphStatus = ?, expectedEntityKeys = ?, expectedRelationKeys = ?, materializedEntityKeys = ?, materializedRelationKeys = ?, lastFailureStage = ?, updatedAt = ?, errorMessage = ?",
                workspaceId,
                journal.documentId(),
                journal.chunkId(),
                journal.snapshotVersion(),
                journal.mergeStatus().name(),
                journal.graphStatus().name(),
                ArcadeJsonCodec.writeStringList(journal.expectedEntityKeys()),
                ArcadeJsonCodec.writeStringList(journal.expectedRelationKeys()),
                ArcadeJsonCodec.writeStringList(journal.materializedEntityKeys()),
                ArcadeJsonCodec.writeStringList(journal.materializedRelationKeys()),
                journal.lastFailureStage() == null ? null : journal.lastFailureStage().name(),
                journal.updatedAt().toString(),
                journal.errorMessage()
            );
        }
    }

    @Override
    public List<ChunkGraphJournal> listChunkJournals(String documentId) {
        return query(selectChunkBase() + " WHERE workspaceId = ? AND documentId = ? ORDER BY chunkId", workspaceId, documentId)
            .stream()
            .map(this::readChunkJournal)
            .toList();
    }

    @Override
    public void delete(String documentId) {
        execute("DELETE FROM DocumentGraphJournal WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
        execute("DELETE FROM ChunkGraphJournal WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
    }

    List<DocumentGraphJournal> listAllDocuments() {
        return query(selectDocumentBase() + " WHERE workspaceId = ? ORDER BY documentId, updatedAt", workspaceId)
            .stream()
            .map(this::readDocumentJournal)
            .toList();
    }

    List<ChunkGraphJournal> listAllChunks() {
        return query(selectChunkBase() + " WHERE workspaceId = ? ORDER BY documentId, chunkId", workspaceId)
            .stream()
            .map(this::readChunkJournal)
            .toList();
    }

    void deleteAll() {
        deleteWorkspaceRows("ChunkGraphJournal");
        deleteWorkspaceRows("DocumentGraphJournal");
    }

    private String selectDocumentBase() {
        return "SELECT documentId, snapshotVersion, status, lastMode, expectedEntityCount, expectedRelationCount, materializedEntityCount, materializedRelationCount, lastFailureStage, createdAt, updatedAt, errorMessage FROM DocumentGraphJournal";
    }

    private String selectChunkBase() {
        return "SELECT documentId, chunkId, snapshotVersion, mergeStatus, graphStatus, expectedEntityKeys, expectedRelationKeys, materializedEntityKeys, materializedRelationKeys, lastFailureStage, updatedAt, errorMessage FROM ChunkGraphJournal";
    }

    private DocumentGraphJournal readDocumentJournal(Map<String, Object> row) {
        return new DocumentGraphJournal(
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.integer(row, "snapshotVersion"),
            GraphMaterializationStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            GraphMaterializationMode.valueOf(ArcadeRecordMapper.string(row, "lastMode")),
            ArcadeRecordMapper.integer(row, "expectedEntityCount"),
            ArcadeRecordMapper.integer(row, "expectedRelationCount"),
            ArcadeRecordMapper.integer(row, "materializedEntityCount"),
            ArcadeRecordMapper.integer(row, "materializedRelationCount"),
            parseFailureStage(ArcadeRecordMapper.nullableString(row, "lastFailureStage")),
            Instant.parse(ArcadeRecordMapper.string(row, "createdAt")),
            Instant.parse(ArcadeRecordMapper.string(row, "updatedAt")),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }

    private ChunkGraphJournal readChunkJournal(Map<String, Object> row) {
        return new ChunkGraphJournal(
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.string(row, "chunkId"),
            ArcadeRecordMapper.integer(row, "snapshotVersion"),
            ChunkMergeStatus.valueOf(ArcadeRecordMapper.string(row, "mergeStatus")),
            ChunkGraphStatus.valueOf(ArcadeRecordMapper.string(row, "graphStatus")),
            ArcadeRecordMapper.stringList(row, "expectedEntityKeys"),
            ArcadeRecordMapper.stringList(row, "expectedRelationKeys"),
            ArcadeRecordMapper.stringList(row, "materializedEntityKeys"),
            ArcadeRecordMapper.stringList(row, "materializedRelationKeys"),
            parseFailureStage(ArcadeRecordMapper.nullableString(row, "lastFailureStage")),
            Instant.parse(ArcadeRecordMapper.string(row, "updatedAt")),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }

    private static FailureStage parseFailureStage(String value) {
        return value == null || value.isBlank() ? null : FailureStage.valueOf(value);
    }
}
