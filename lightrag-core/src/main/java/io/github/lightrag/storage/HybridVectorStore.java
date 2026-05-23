package io.github.lightrag.storage;

import java.util.List;
import java.util.Map;
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
        List<String> keywords,
        String srcId,
        String tgtId,
        String filePath,
        String documentId,
        String sourceId,
        String contentType,
        String sectionPath,
        String source,
        String tenantId,
        String createdAt,
        boolean searchable,
        Map<String, String> metadata
    ) {
        public EnrichedVectorRecord(
            String id,
            List<Double> vector,
            String searchableText,
            List<String> keywords
        ) {
            this(id, vector, searchableText, keywords, "", "", "");
        }

        public EnrichedVectorRecord(
            String id,
            List<Double> vector,
            String searchableText,
            List<String> keywords,
            String srcId,
            String tgtId,
            String filePath
        ) {
            this(id, vector, searchableText, keywords, srcId, tgtId, filePath, "", "", "", "", "", "", "", true, Map.of());
        }

        public EnrichedVectorRecord {
            id = Objects.requireNonNull(id, "id");
            vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
            searchableText = searchableText == null ? "" : searchableText;
            keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
            srcId = srcId == null ? "" : srcId;
            tgtId = tgtId == null ? "" : tgtId;
            filePath = filePath == null ? "" : filePath;
            documentId = documentId == null ? "" : documentId;
            sourceId = sourceId == null ? "" : sourceId;
            contentType = contentType == null ? "" : contentType;
            sectionPath = sectionPath == null ? "" : sectionPath;
            source = source == null ? "" : source;
            tenantId = tenantId == null ? "" : tenantId;
            createdAt = createdAt == null ? "" : createdAt;
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
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
