package io.github.lightragjava.model;

import java.util.List;
import java.util.Objects;

public interface RerankModel {
    List<RerankResult> rerank(RerankRequest request);

    record RerankRequest(String query, List<RerankCandidate> candidates) {
        public RerankRequest {
            query = Objects.requireNonNull(query, "query");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        }
    }

    record RerankCandidate(String id, String text) {
        public RerankCandidate {
            id = requireNonBlank(id, "id");
            text = Objects.requireNonNull(text, "text");
        }
    }

    record RerankResult(String id, double score) {
        public RerankResult {
            id = requireNonBlank(id, "id");
            if (!Double.isFinite(score)) {
                throw new IllegalArgumentException("score must be finite");
            }
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
