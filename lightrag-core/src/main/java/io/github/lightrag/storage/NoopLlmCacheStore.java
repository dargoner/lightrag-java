package io.github.lightrag.storage;

import java.util.List;
import java.util.Optional;

/**
 * Cache store that stores nothing: every read misses and every write is ignored. Used by query-only
 * store sets where the per-workspace cache tables cannot be addressed across a workspace set and a
 * cache miss only costs a recomputation.
 */
public final class NoopLlmCacheStore implements LlmCacheStore {
    @Override
    public void save(CacheRecord record) {
        // Intentionally empty.
    }

    @Override
    public Optional<CacheRecord> load(String cacheId) {
        return Optional.empty();
    }

    @Override
    public boolean contains(String cacheId) {
        return false;
    }

    @Override
    public void delete(List<String> cacheIds) {
        // Intentionally empty.
    }

    @Override
    public void drop() {
        // Intentionally empty.
    }
}
