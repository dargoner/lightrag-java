package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;

public interface VectorStore {
    void saveAll(String namespace, List<VectorRecord> vectors);

    List<VectorMatch> search(String namespace, List<Double> queryVector, int topK);

    List<VectorRecord> list(String namespace);

    /**
     * Removes the given ids from the namespace. Stores without deletion support keep the default
     * behaviour and fail loudly instead of silently ignoring the request.
     */
    default void deleteIds(String namespace, List<String> ids) {
        throw new UnsupportedOperationException("deleteIds is not supported by " + getClass().getName());
    }

    default void deleteNamespace(String namespace) {
        throw new UnsupportedOperationException("deleteNamespace is not supported by " + getClass().getName());
    }

    record VectorRecord(String id, List<Double> vector) {
        public VectorRecord {
            id = Objects.requireNonNull(id, "id");
            vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
        }
    }

    record VectorMatch(String id, double score) {
        public VectorMatch {
            id = Objects.requireNonNull(id, "id");
        }
    }
}
