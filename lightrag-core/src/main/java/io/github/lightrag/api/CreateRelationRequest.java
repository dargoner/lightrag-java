package io.github.lightrag.api;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.Objects;

public record CreateRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) {
    public static final String DEFAULT_DESCRIPTION = "";
    public static final double DEFAULT_WEIGHT = 1.0d;
    public static final String DEFAULT_SOURCE_ID = "";
    public static final String DEFAULT_FILE_PATH = "";

    public CreateRelationRequest {
        sourceEntityName = normalizeEndpoint(sourceEntityName, "sourceEntityName");
        targetEntityName = normalizeEndpoint(targetEntityName, "targetEntityName");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        sourceId = normalizeOptional(sourceId);
        filePath = normalizeOptional(filePath);
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sourceEntityName;
        private String targetEntityName;
        private String keywords;
        private String description = DEFAULT_DESCRIPTION;
        private double weight = DEFAULT_WEIGHT;
        private String sourceId = DEFAULT_SOURCE_ID;
        private String filePath = DEFAULT_FILE_PATH;

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

        public Builder weight(double weight) {
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

        public CreateRelationRequest build() {
            return new CreateRelationRequest(sourceEntityName, targetEntityName, keywords, description, weight, sourceId, filePath);
        }
    }

    public static String normalizeEndpoint(String value, String fieldName) {
        var normalized = requireNonBlank(value, fieldName);
        if (normalized.length() > RelationCanonicalizer.DEFAULT_ENTITY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " must be at most "
                + RelationCanonicalizer.DEFAULT_ENTITY_NAME_MAX_LENGTH + " characters");
        }
        return normalized;
    }

    public static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return "";
        }
        return value.strip();
    }
}
