package io.github.lightrag.storage.postgres;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

final class PostgresSchemaResolver {
    private PostgresSchemaResolver() {
    }

    static PostgresStorageConfig alignWithDataSourceSchema(DataSource dataSource, PostgresStorageConfig config) {
        var source = Objects.requireNonNull(dataSource, "dataSource");
        var input = Objects.requireNonNull(config, "config");
        var schema = resolveCurrentSchema(source);
        if (schema == null || schema.equals(input.schema())) {
            return input;
        }
        return new PostgresStorageConfig(
            input.jdbcUrl(),
            input.username(),
            input.password(),
            schema,
            input.vectorDimensions(),
            input.tablePrefix()
        );
    }

    private static String resolveCurrentSchema(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var fromConnection = normalize(connection.getSchema());
            if (fromConnection != null) {
                return fromConnection;
            }
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT current_schema()")) {
                if (result.next()) {
                    return normalize(result.getString(1));
                }
            }
            return null;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to resolve PostgreSQL schema from DataSource", exception);
        }
    }

    private static String normalize(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        return schema.strip();
    }
}
