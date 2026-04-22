package io.github.lightrag.api;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.Objects;

public record UpdateRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    Double weight,
    String sourceId,
    String filePath
) {
    public UpdateRelationRequest {
        sourceEntityName = CreateRelationRequest.normalizeEndpoint(sourceEntityName, "sourceEntityName");
        targetEntityName = CreateRelationRequest.normalizeEndpoint(targetEntityName, "targetEntityName");
        keywords = normalizeNullableKeywords(keywords);
        description = normalizeNullable(description);
        sourceId = normalizeNullable(sourceId);
        filePath = normalizeNullable(filePath);
        if (weight != null && !Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        if (keywords == null && description == null && weight == null && sourceId == null && filePath == null) {
            throw new IllegalArgumentException("at least one mutable relation field must be provided");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sourceEntityName;
        private String targetEntityName;
        private String keywords;
        private String description;
        private Double weight;
        private String sourceId;
        private String filePath;

        public Builder sourceEntityName(String sourceEntityName) {
            this.sourceEntityName = sourceEntityName;
            return this;
        }

        public Builder targetEntityName(String targetEntityName) {
            this.targetEntityName = targetEntityName;
            return this;
        }

        public Builder keywords(String keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder weight(Double weight) {
            this.weight = weight;
            return this;
        }

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public UpdateRelationRequest build() {
            return new UpdateRelationRequest(sourceEntityName, targetEntityName, keywords, description, weight, sourceId, filePath);
        }
    }

    private static String normalizeNullableKeywords(String value) {
        if (value == null) {
            return null;
        }
        return CreateRelationRequest.requireNonBlank(value, "keywords");
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
