package io.github.lightrag.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record CreateEntityRequest(
    String name,
    String type,
    String description,
    List<String> aliases
) {
    public static final String DEFAULT_TYPE = "";
    public static final String DEFAULT_DESCRIPTION = "";

    public CreateEntityRequest {
        name = requireNonBlank(name, "name");
        type = normalizeOptional(type);
        description = normalizeOptional(description);
        aliases = normalizeAliases(name, aliases);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String type = DEFAULT_TYPE;
        private String description = DEFAULT_DESCRIPTION;
        private List<String> aliases = List.of();

        public Builder name(String name) {
            this.name = name;
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

        public CreateEntityRequest build() {
            return new CreateEntityRequest(name, type, description, aliases);
        }
    }

    private static List<String> normalizeAliases(String name, List<String> aliases) {
        var normalizedName = normalizeKey(name);
        var normalizedAliases = new LinkedHashMap<String, String>();
        for (var alias : List.copyOf(Objects.requireNonNull(aliases, "aliases"))) {
            var normalizedAlias = normalizeOptionalKey(alias);
            if (normalizedAlias == null || normalizedAlias.equals(normalizedName)) {
                continue;
            }
            normalizedAliases.putIfAbsent(normalizedAlias, alias.strip());
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

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeKey(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptionalKey(String value) {
        Objects.requireNonNull(value, "alias");
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
