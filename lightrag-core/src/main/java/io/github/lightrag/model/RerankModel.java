package io.github.lightrag.model;

import java.util.List;
import java.util.Objects;

public interface RerankModel {
    List<RerankResult> rerank(RerankRequest request);

    record RerankRequest(String query, List<RerankCandidate> candidates, int topN) {
        public RerankRequest {
            query = Objects.requireNonNull(query, "query");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
            topN = topN <= 0 ? candidates.size() : topN;
        }

        public RerankRequest(String query, List<RerankCandidate> candidates) {
            this(query, candidates, 0);
        }
    }

    record RerankCandidate(String id, String text) {
        public RerankCandidate {
            id = requireNonBlank(id, "id");
            text = Objects.requireNonNull(text, "text");
        }
    }

    /** Scores are validated by the query engine (non-finite entries are ignored), not here. */
    record RerankResult(String id, double score) {
        public RerankResult {
            id = requireNonBlank(id, "id");
        }
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
