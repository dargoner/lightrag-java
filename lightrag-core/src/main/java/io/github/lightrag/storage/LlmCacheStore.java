package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface LlmCacheStore {
    void save(CacheRecord record);

    Optional<CacheRecord> load(String cacheId);

    boolean contains(String cacheId);

    void delete(List<String> cacheIds);

    void drop();

    record CacheRecord(String id, String value) {
        public CacheRecord {
            id = requireNonBlank(id, "id");
            value = Objects.requireNonNull(value, "value");
        }

        private static String requireNonBlank(String value, String fieldName) {
            Objects.requireNonNull(value, fieldName);
            var normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
