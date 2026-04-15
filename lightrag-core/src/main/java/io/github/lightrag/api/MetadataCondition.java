package io.github.lightrag.api;

import java.util.Objects;

public record MetadataCondition(String field, MetadataOperator operator, Object value) {
    public MetadataCondition {
        field = Objects.requireNonNull(field, "field").trim();
        if (field.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        operator = Objects.requireNonNull(operator, "operator");
        value = Objects.requireNonNull(value, "value");
    }
}
