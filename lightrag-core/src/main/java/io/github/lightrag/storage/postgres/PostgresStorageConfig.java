package io.github.lightrag.storage.postgres;

import java.util.Objects;
import java.util.regex.Pattern;

public record PostgresStorageConfig(
    String jdbcUrl,
    String username,
    String password,
    String schema,
    int vectorDimensions,
    String tablePrefix
) {
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public PostgresStorageConfig {
        jdbcUrl = requireNonBlank(jdbcUrl, "jdbcUrl");
        username = requireNonBlank(username, "username");
        password = Objects.requireNonNull(password, "password");
        schema = validateIdentifier(schema, "schema");
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be positive");
        }
        tablePrefix = validatePrefix(tablePrefix);
    }

    public String tableName(String baseName) {
        return validateIdentifier(tablePrefix + requireNonBlank(baseName, "baseName"), "table name");
    }

    public String qualifiedTableName(String baseName) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(tableName(baseName));
    }

    public String schemaName() {
        return quoteIdentifier(schema);
    }

    private static String validatePrefix(String prefix) {
        Objects.requireNonNull(prefix, "tablePrefix");
        if (prefix.isEmpty()) {
            return prefix;
        }
        if (!IDENTIFIER_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("tablePrefix must be a valid identifier prefix");
        }
        return prefix;
    }

    private static String validateIdentifier(String value, String label) {
        String normalized = requireNonBlank(value, label);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(label + " must be a valid SQL identifier");
        }
        return normalized;
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier + "\"";
    }
}
