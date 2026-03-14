package io.github.lightragjava.storage;

import java.util.List;
import java.util.Objects;

public interface VectorStore {
    void saveAll(String namespace, List<VectorRecord> vectors);

    List<VectorMatch> search(String namespace, List<Double> queryVector, int topK);

    List<VectorRecord> list(String namespace);

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
