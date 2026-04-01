package io.github.lightrag.api;

import java.util.Objects;

public record CreateRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String relationType,
    String description,
    double weight
) {
    public static final String DEFAULT_DESCRIPTION = "";
    public static final double DEFAULT_WEIGHT = 1.0d;

    public CreateRelationRequest {
        sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        relationType = requireNonBlank(relationType, "relationType");
        description = description == null ? "" : description.strip();
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
        private String relationType;
        private String description = DEFAULT_DESCRIPTION;
        private double weight = DEFAULT_WEIGHT;

        public Builder sourceEntityName(String sourceEntityName) {
            this.sourceEntityName = sourceEntityName;
            return this;
        }

        public Builder targetEntityName(String targetEntityName) {
            this.targetEntityName = targetEntityName;
            return this;
        }

        public Builder relationType(String relationType) {
            this.relationType = relationType;
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

        public CreateRelationRequest build() {
            return new CreateRelationRequest(sourceEntityName, targetEntityName, relationType, description, weight);
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
