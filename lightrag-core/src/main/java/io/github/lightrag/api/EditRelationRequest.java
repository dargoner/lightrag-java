package io.github.lightrag.api;

import java.util.Objects;

public record EditRelationRequest(
    String sourceEntityName,
    String targetEntityName,
    String currentRelationType,
    String newRelationType,
    String description,
    Double weight
) {
    public EditRelationRequest {
        sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        currentRelationType = requireNonBlank(currentRelationType, "currentRelationType");
        newRelationType = normalizeOptional(newRelationType, "newRelationType");
        description = description == null ? null : description.strip();
        if (weight != null && !Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sourceEntityName;
        private String targetEntityName;
        private String currentRelationType;
        private String newRelationType;
        private String description;
        private Double weight;

        public Builder sourceEntityName(String sourceEntityName) {
            this.sourceEntityName = sourceEntityName;
            return this;
        }

        public Builder targetEntityName(String targetEntityName) {
            this.targetEntityName = targetEntityName;
            return this;
        }

        public Builder currentRelationType(String currentRelationType) {
            this.currentRelationType = currentRelationType;
            return this;
        }

        public Builder newRelationType(String newRelationType) {
            this.newRelationType = newRelationType;
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

        public EditRelationRequest build() {
            return new EditRelationRequest(
                sourceEntityName,
                targetEntityName,
                currentRelationType,
                newRelationType,
                description,
                weight
            );
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

    private static String normalizeOptional(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requireNonBlank(value, fieldName);
    }
}
