package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.TaskDocumentStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArcadeTaskDocumentStore extends ArcadeStoreSupport implements TaskDocumentStore {
    public ArcadeTaskDocumentStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(TaskDocumentRecord record) {
        var existing = first(
            "SELECT @rid FROM TaskDocument WHERE workspaceId = ? AND taskId = ? AND documentId = ? LIMIT 1",
            workspaceId,
            record.taskId(),
            record.documentId()
        );
        var params = List.of(
            record.status().name(),
            record.chunkCount(),
            record.entityCount(),
            record.relationCount(),
            record.chunkVectorCount(),
            record.entityVectorCount(),
            record.relationVectorCount(),
            record.errorMessage()
        );
        if (existing.isPresent()) {
            execute(
                "UPDATE TaskDocument SET status = ?, chunkCount = ?, entityCount = ?, relationCount = ?, chunkVectorCount = ?, entityVectorCount = ?, relationVectorCount = ?, errorMessage = ? WHERE workspaceId = ? AND taskId = ? AND documentId = ?",
                record.status().name(),
                record.chunkCount(),
                record.entityCount(),
                record.relationCount(),
                record.chunkVectorCount(),
                record.entityVectorCount(),
                record.relationVectorCount(),
                record.errorMessage(),
                workspaceId,
                record.taskId(),
                record.documentId()
            );
        } else {
            execute(
                "INSERT INTO TaskDocument SET workspaceId = ?, taskId = ?, documentId = ?, status = ?, chunkCount = ?, entityCount = ?, relationCount = ?, chunkVectorCount = ?, entityVectorCount = ?, relationVectorCount = ?, errorMessage = ?",
                workspaceId,
                record.taskId(),
                record.documentId(),
                params.get(0),
                params.get(1),
                params.get(2),
                params.get(3),
                params.get(4),
                params.get(5),
                params.get(6),
                params.get(7)
            );
        }
    }

    @Override
    public Optional<TaskDocumentRecord> load(String taskId, String documentId) {
        return first(selectBase() + " WHERE workspaceId = ? AND taskId = ? AND documentId = ? LIMIT 1", workspaceId, taskId, documentId)
            .map(this::readRecord);
    }

    @Override
    public List<TaskDocumentRecord> listByTask(String taskId) {
        return query(selectBase() + " WHERE workspaceId = ? AND taskId = ? ORDER BY documentId", workspaceId, taskId)
            .stream()
            .map(this::readRecord)
            .toList();
    }

    @Override
    public void deleteByTask(String taskId) {
        execute("DELETE FROM TaskDocument WHERE workspaceId = ? AND taskId = ?", workspaceId, taskId);
    }

    void deleteAll() {
        deleteWorkspaceRows("TaskDocument");
    }

    private String selectBase() {
        return "SELECT taskId, documentId, status, chunkCount, entityCount, relationCount, chunkVectorCount, entityVectorCount, relationVectorCount, errorMessage FROM TaskDocument";
    }

    private TaskDocumentRecord readRecord(Map<String, Object> row) {
        return new TaskDocumentRecord(
            ArcadeRecordMapper.string(row, "taskId"),
            ArcadeRecordMapper.string(row, "documentId"),
            DocumentStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            ArcadeRecordMapper.integer(row, "chunkCount"),
            ArcadeRecordMapper.integer(row, "entityCount"),
            ArcadeRecordMapper.integer(row, "relationCount"),
            ArcadeRecordMapper.integer(row, "chunkVectorCount"),
            ArcadeRecordMapper.integer(row, "entityVectorCount"),
            ArcadeRecordMapper.integer(row, "relationVectorCount"),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }
}
