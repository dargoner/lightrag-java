package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.LlmCacheStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeLlmCacheStore extends ArcadeStoreSupport implements LlmCacheStore {
    public ArcadeLlmCacheStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(CacheRecord record) {
        var cacheRecord = Objects.requireNonNull(record, "record");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("value", cacheRecord.value());
        upsertByWorkspaceId("LlmCache", "cacheId", cacheRecord.id(), properties);
    }

    @Override
    public Optional<CacheRecord> load(String cacheId) {
        var id = Objects.requireNonNull(cacheId, "cacheId");
        return first(
            "SELECT cacheId, value FROM LlmCache WHERE workspaceId = ? AND cacheId = ? LIMIT 1",
            workspaceId,
            id
        ).map(row -> new CacheRecord(
            ArcadeRecordMapper.string(row, "cacheId"),
            ArcadeRecordMapper.string(row, "value")
        ));
    }

    @Override
    public boolean contains(String cacheId) {
        return load(cacheId).isPresent();
    }

    @Override
    public void delete(List<String> cacheIds) {
        var ids = List.copyOf(Objects.requireNonNull(cacheIds, "cacheIds"));
        for (var cacheId : ids) {
            execute("DELETE FROM LlmCache WHERE workspaceId = ? AND cacheId = ?", workspaceId, cacheId);
        }
    }

    @Override
    public void drop() {
        deleteWorkspaceRows("LlmCache");
    }

    void deleteAll() {
        drop();
    }
}
