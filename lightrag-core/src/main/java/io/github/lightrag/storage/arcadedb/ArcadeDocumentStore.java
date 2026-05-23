package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.DocumentStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeDocumentStore extends ArcadeStoreSupport implements DocumentStore {
    public ArcadeDocumentStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(DocumentRecord document) {
        var record = Objects.requireNonNull(document, "document");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("title", record.title());
        properties.put("content", record.content());
        properties.put("metadata", ArcadeJsonCodec.writeStringMap(record.metadata()));
        upsertByWorkspaceId("Document", "id", record.id(), properties);
    }

    @Override
    public Optional<DocumentRecord> load(String documentId) {
        return first("SELECT id, title, content, metadata FROM Document WHERE workspaceId = ? AND id = ? LIMIT 1", workspaceId, documentId)
            .map(this::readDocument);
    }

    @Override
    public List<DocumentRecord> list() {
        return query("SELECT id, title, content, metadata FROM Document WHERE workspaceId = ? ORDER BY id", workspaceId)
            .stream()
            .map(this::readDocument)
            .toList();
    }

    @Override
    public boolean contains(String documentId) {
        return first("SELECT id FROM Document WHERE workspaceId = ? AND id = ? LIMIT 1", workspaceId, documentId).isPresent();
    }

    void deleteAll() {
        deleteWorkspaceRows("Document");
    }

    private DocumentRecord readDocument(Map<String, Object> row) {
        return new DocumentRecord(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "title"),
            ArcadeRecordMapper.string(row, "content"),
            ArcadeRecordMapper.stringMap(row, "metadata")
        );
    }
}
