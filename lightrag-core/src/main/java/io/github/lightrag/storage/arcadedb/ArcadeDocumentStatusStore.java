package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.DocumentStatusStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeDocumentStatusStore extends ArcadeStoreSupport implements DocumentStatusStore {
    public ArcadeDocumentStatusStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(StatusRecord statusRecord) {
        var record = Objects.requireNonNull(statusRecord, "statusRecord");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("status", record.status().name());
        properties.put("summary", record.summary());
        properties.put("errorMessage", record.errorMessage());
        upsertByWorkspaceId("DocumentStatus", "documentId", record.documentId(), properties);
    }

    @Override
    public Optional<StatusRecord> load(String documentId) {
        return first(selectBase() + " WHERE workspaceId = ? AND documentId = ? LIMIT 1", workspaceId, documentId)
            .map(this::readStatus);
    }

    @Override
    public List<StatusRecord> list() {
        return query(selectBase() + " WHERE workspaceId = ? ORDER BY documentId", workspaceId)
            .stream()
            .map(this::readStatus)
            .toList();
    }

    @Override
    public void delete(String documentId) {
        execute("DELETE FROM DocumentStatus WHERE workspaceId = ? AND documentId = ?", workspaceId, documentId);
    }

    void deleteAll() {
        deleteWorkspaceRows("DocumentStatus");
    }

    private String selectBase() {
        return "SELECT documentId, status, summary, errorMessage FROM DocumentStatus";
    }

    private StatusRecord readStatus(Map<String, Object> row) {
        return new StatusRecord(
            ArcadeRecordMapper.string(row, "documentId"),
            DocumentStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            ArcadeRecordMapper.string(row, "summary"),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }
}
