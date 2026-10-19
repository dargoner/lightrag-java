package io.github.lightrag.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record MergeEntitiesRequest(
    List<String> sourceEntityNames,
    String targetEntityName,
    String targetType,
    String targetDescription,
    List<String> targetAliases,
    Map<String, Object> targetAttributes
) {
    public MergeEntitiesRequest(
        List<String> sourceEntityNames,
        String targetEntityName,
        String targetType,
        String targetDescription,
        List<String> targetAliases
    ) {
        this(sourceEntityNames, targetEntityName, targetType, targetDescription, targetAliases, null);
    }

    public MergeEntitiesRequest {
        sourceEntityNames = normalizeSourceEntityNames(sourceEntityNames);
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        targetType = normalizeNullable(targetType);
        targetDescription = normalizeNullable(targetDescription);
        targetAliases = targetAliases == null ? null : normalizeAliases(targetAliases);
        targetAttributes = AttributeRequestMaps.optional(targetAttributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> sourceEntityNames;
        private String targetEntityName;
        private String targetType;
        private String targetDescription;
        private List<String> targetAliases;
        private Map<String, Object> targetAttributes;

        public Builder sourceEntityNames(List<String> sourceEntityNames) {
            this.sourceEntityNames = sourceEntityNames;
            return this;
        }

        public Builder targetEntityName(String targetEntityName) {
            this.targetEntityName = targetEntityName;
            return this;
        }

        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }

        public Builder targetDescription(String targetDescription) {
            this.targetDescription = targetDescription;
            return this;
        }

        public Builder targetAliases(List<String> targetAliases) {
            this.targetAliases = targetAliases;
            return this;
        }

        public Builder targetAttributes(Map<String, Object> targetAttributes) {
            this.targetAttributes = targetAttributes;
            return this;
        }

        public MergeEntitiesRequest build() {
            return new MergeEntitiesRequest(
                sourceEntityNames,
                targetEntityName,
                targetType,
                targetDescription,
                targetAliases,
                targetAttributes
            );
        }
    }

    private static List<String> normalizeSourceEntityNames(List<String> sourceEntityNames) {
        var values = new ArrayList<String>();
        for (var sourceEntityName : List.copyOf(Objects.requireNonNull(sourceEntityNames, "sourceEntityNames"))) {
            values.add(requireNonBlank(sourceEntityName, "sourceEntityName"));
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("sourceEntityNames must not be empty");
        }
        return List.copyOf(values);
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        var normalizedAliases = new LinkedHashMap<String, String>();
        for (var alias : List.copyOf(Objects.requireNonNull(aliases, "targetAliases"))) {
            Objects.requireNonNull(alias, "targetAlias");
            var normalized = alias.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            normalizedAliases.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        return List.copyOf(normalizedAliases.values());
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : value.strip();
    }
}
