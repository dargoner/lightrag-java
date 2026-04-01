package io.github.lightrag.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record EditEntityRequest(
    String entityName,
    String newName,
    String type,
    String description,
    List<String> aliases
) {
    public EditEntityRequest {
        entityName = requireNonBlank(entityName, "entityName");
        newName = normalizeOptional(newName, "newName");
        type = normalizeNullable(type);
        description = normalizeNullable(description);
        aliases = aliases == null ? null : normalizeAliases(aliases);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String entityName;
        private String newName;
        private String type;
        private String description;
        private List<String> aliases;

        public Builder entityName(String entityName) {
            this.entityName = entityName;
            return this;
        }

        public Builder newName(String newName) {
            this.newName = newName;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder aliases(List<String> aliases) {
            this.aliases = aliases;
            return this;
        }

        public EditEntityRequest build() {
            return new EditEntityRequest(entityName, newName, type, description, aliases);
        }
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        var normalizedAliases = new LinkedHashMap<String, String>();
        for (var alias : List.copyOf(Objects.requireNonNull(aliases, "aliases"))) {
            Objects.requireNonNull(alias, "alias");
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

    private static String normalizeOptional(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requireNonBlank(value, fieldName);
    }

    private static String normalizeNullable(String value) {
        return value == null ? null : value.strip();
    }
}
