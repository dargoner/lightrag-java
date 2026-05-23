package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.ChunkStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeChunkStore extends ArcadeStoreSupport implements ChunkStore {
    public ArcadeChunkStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(ChunkRecord chunk) {
        var record = Objects.requireNonNull(chunk, "chunk");
        var metadata = record.metadata();
        var properties = new LinkedHashMap<String, Object>();
        properties.put("documentId", record.documentId());
        properties.put("text", record.text());
        properties.put("tokenCount", record.tokenCount());
        properties.put("chunkOrder", record.order());
        properties.put("metadata", ArcadeJsonCodec.writeStringMap(metadata));
        properties.put("filePath", metadata.getOrDefault("file_path", metadata.getOrDefault("filePath", "")));
        properties.put("contentType", metadata.getOrDefault("content_type", metadata.getOrDefault("contentType", metadata.getOrDefault("smart_chunker.content_type", ""))));
        properties.put("sectionPath", metadata.getOrDefault("section_path", metadata.getOrDefault("sectionPath", metadata.getOrDefault("smart_chunker.section_path", ""))));
        properties.put("source", metadata.getOrDefault("source", ""));
        properties.put("tenantId", metadata.getOrDefault("tenant_id", metadata.getOrDefault("tenantId", "")));
        properties.put("createdAt", metadata.getOrDefault("created_at", metadata.getOrDefault("createdAt", "")));
        properties.put("searchable", Boolean.parseBoolean(metadata.getOrDefault("searchable", "true")));
        upsertByWorkspaceId("Chunk", "id", record.id(), properties);
    }

    @Override
    public Optional<ChunkRecord> load(String chunkId) {
        return first(selectBase() + " WHERE workspaceId = ? AND id = ? LIMIT 1", workspaceId, chunkId).map(this::readChunk);
    }

    @Override
    public Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds");
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        var chunksById = new LinkedHashMap<String, ChunkRecord>();
        for (var chunkId : chunkIds) {
            load(chunkId).ifPresent(chunk -> chunksById.put(chunkId, chunk));
        }
        return Map.copyOf(chunksById);
    }

    @Override
    public List<ChunkRecord> list() {
        return query(selectBase() + " WHERE workspaceId = ? ORDER BY id", workspaceId).stream()
            .map(this::readChunk)
            .toList();
    }

    @Override
    public List<ChunkRecord> listByDocument(String documentId) {
        return query(selectBase() + " WHERE workspaceId = ? AND documentId = ? ORDER BY chunkOrder, id", workspaceId, documentId)
            .stream()
            .map(this::readChunk)
            .toList();
    }

    void deleteAll() {
        deleteWorkspaceRows("Chunk");
    }

    private String selectBase() {
        return "SELECT id, documentId, text, tokenCount, chunkOrder, metadata FROM Chunk";
    }

    private ChunkRecord readChunk(Map<String, Object> row) {
        return new ChunkRecord(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.string(row, "text"),
            ArcadeRecordMapper.integer(row, "tokenCount"),
            ArcadeRecordMapper.integer(row, "chunkOrder"),
            ArcadeRecordMapper.stringMap(row, "metadata")
        );
    }
}
