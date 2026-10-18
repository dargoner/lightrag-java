package io.github.lightrag.storage.postgres;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Resolves the physical vector type once per provider bootstrap. Vastbase G100 exposes the
 * {@code floatvector} type without a {@code vector} extension, so the type catalog is authoritative
 * and portable across both backends ({@code to_regtype} does not exist on Vastbase).
 */
final class PostgresVectorDialectResolver {
    private static final String FLOATVECTOR_TYPE_PROBE =
        "SELECT 1 FROM pg_type WHERE typname = 'floatvector' LIMIT 1";

    private PostgresVectorDialectResolver() {
    }

    static PostgresStorageConfig resolve(DataSource dataSource, PostgresStorageConfig config) {
        var source = Objects.requireNonNull(dataSource, "dataSource");
        var input = Objects.requireNonNull(config, "config");
        if (input.vectorDialect() != PostgresVectorDialect.AUTO) {
            return input;
        }
        try (var connection = source.getConnection()) {
            return input.withVectorDialect(detect(connection));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to resolve PostgreSQL vector dialect", exception);
        }
    }

    static PostgresVectorDialect detect(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(FLOATVECTOR_TYPE_PROBE)) {
            return resultSet.next()
                ? PostgresVectorDialect.VASTBASE
                : PostgresVectorDialect.PGVECTOR;
        }
    }
}
