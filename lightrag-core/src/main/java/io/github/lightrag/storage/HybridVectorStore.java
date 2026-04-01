package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;

public interface HybridVectorStore extends VectorStore {
    void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records);

    List<VectorMatch> search(String namespace, SearchRequest request);

    enum SearchMode {
        SEMANTIC,
        KEYWORD,
        HYBRID
    }

    record EnrichedVectorRecord(
        String id,
        List<Double> vector,
        String searchableText,
        List<String> keywords
    ) {
        public EnrichedVectorRecord {
            id = Objects.requireNonNull(id, "id");
            vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
            searchableText = searchableText == null ? "" : searchableText;
            keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
        }

        public VectorRecord toVectorRecord() {
            return new VectorRecord(id, vector);
        }
    }

    record SearchRequest(
        List<Double> queryVector,
        String queryText,
        List<String> keywords,
        SearchMode mode,
        int topK
    ) {
        public SearchRequest {
            queryVector = List.copyOf(Objects.requireNonNull(queryVector, "queryVector"));
            queryText = queryText == null ? "" : queryText;
            keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
            mode = Objects.requireNonNull(mode, "mode");
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive");
            }
        }
    }
}
