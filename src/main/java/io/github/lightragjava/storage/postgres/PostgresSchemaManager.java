package io.github.lightragjava.storage.postgres;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresSchemaManager {
    private static final String STORAGE_SCHEMA_KEY = "storage";

    private final DataSource dataSource;
    private final PostgresStorageConfig config;
    private final List<String> bootstrapStatements;

    public PostgresSchemaManager(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, null);
    }

    PostgresSchemaManager(DataSource dataSource, PostgresStorageConfig config, List<String> bootstrapStatements) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.bootstrapStatements = bootstrapStatements;
    }

    public void bootstrap() {
        try (var connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                try {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
                    ensureSchemaVersionTable(statement);

                    Optional<Integer> currentVersion = loadCurrentVersion(connection);
                    if (currentVersion.isEmpty()) {
                        applyMissingMigrations(statement, 0);
                    } else if (currentVersion.get() > latestSchemaVersion()) {
                        throw new IllegalStateException(
                            "Unsupported PostgreSQL schema version: found "
                                + currentVersion.get()
                                + " but this SDK supports up to "
                                + latestSchemaVersion()
                        );
                    } else {
                        applyMissingMigrations(statement, currentVersion.get());
                    }
                    validateVectorDimensions(connection);
                    connection.commit();
                } catch (RuntimeException exception) {
                    rollback(connection, exception);
                    throw exception;
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
                }
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
        }
    }

    private void applyMissingMigrations(Statement statement, int currentVersion) throws SQLException {
        for (Migration migration : migrations()) {
            if (migration.version() <= currentVersion) {
                continue;
            }
            for (String sql : migration.statements()) {
                statement.execute(sql);
            }
            storeSchemaVersion(statement.getConnection(), migration.version());
        }
    }

    private List<Migration> migrations() {
        return List.of(new Migration(1, versionOneStatements()));
    }

    private int latestSchemaVersion() {
        return migrations().get(migrations().size() - 1).version();
    }

    private List<String> versionOneStatements() {
        if (bootstrapStatements != null) {
            return bootstrapStatements;
        }
        return List.of(
            "CREATE EXTENSION IF NOT EXISTS vector",
            """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.formatted(config.qualifiedTableName("documents")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    token_count INTEGER NOT NULL,
                    chunk_order INTEGER NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.formatted(config.qualifiedTableName("chunks")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL
                )
                """.formatted(config.qualifiedTableName("entities")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    entity_id TEXT NOT NULL,
                    alias TEXT NOT NULL,
                    PRIMARY KEY (entity_id, alias)
                )
                """.formatted(config.qualifiedTableName("entity_aliases")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    entity_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    PRIMARY KEY (entity_id, chunk_id)
                )
                """.formatted(config.qualifiedTableName("entity_chunks")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    source_entity_id TEXT NOT NULL,
                    target_entity_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    weight DOUBLE PRECISION NOT NULL
                )
                """.formatted(config.qualifiedTableName("relations")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    relation_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    PRIMARY KEY (relation_id, chunk_id)
                )
                """.formatted(config.qualifiedTableName("relation_chunks")),
            dropConstraintSql("chunks", config.tableName("chunks") + "_document_id_fkey"),
            dropConstraintSql("entity_aliases", config.tableName("entity_aliases") + "_entity_id_fkey"),
            dropConstraintSql("entity_chunks", config.tableName("entity_chunks") + "_entity_id_fkey"),
            dropConstraintSql("entity_chunks", config.tableName("entity_chunks") + "_chunk_id_fkey"),
            dropConstraintSql("relations", config.tableName("relations") + "_source_entity_id_fkey"),
            dropConstraintSql("relations", config.tableName("relations") + "_target_entity_id_fkey"),
            dropConstraintSql("relation_chunks", config.tableName("relation_chunks") + "_relation_id_fkey"),
            dropConstraintSql("relation_chunks", config.tableName("relation_chunks") + "_chunk_id_fkey"),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    namespace TEXT NOT NULL,
                    vector_id TEXT NOT NULL,
                    embedding vector(%d) NOT NULL,
                    PRIMARY KEY (namespace, vector_id)
                )
                """.formatted(config.qualifiedTableName("vectors"), config.vectorDimensions())
        );
    }

    private void ensureSchemaVersionTable(Statement statement) throws SQLException {
        statement.execute(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    schema_key TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(config.qualifiedTableName("schema_version"))
        );
    }

    private Optional<Integer> loadCurrentVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
                SELECT version
                FROM %s
                WHERE schema_key = ?
                """.formatted(config.qualifiedTableName("schema_version"))
        )) {
            statement.setString(1, STORAGE_SCHEMA_KEY);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(resultSet.getInt("version"));
            }
        }
    }

    private void storeSchemaVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
                INSERT INTO %s (schema_key, version, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (schema_key)
                DO UPDATE SET version = EXCLUDED.version, updated_at = EXCLUDED.updated_at
                """.formatted(config.qualifiedTableName("schema_version"))
        )) {
            statement.setString(1, STORAGE_SCHEMA_KEY);
            statement.setInt(2, version);
            statement.executeUpdate();
        }
    }

    private void validateVectorDimensions(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
                SELECT format_type(attribute.atttypid, attribute.atttypmod)
                FROM pg_attribute attribute
                JOIN pg_class klass ON klass.oid = attribute.attrelid
                JOIN pg_namespace namespace ON namespace.oid = klass.relnamespace
                WHERE namespace.nspname = ?
                  AND klass.relname = ?
                  AND attribute.attname = 'embedding'
                  AND NOT attribute.attisdropped
                """
        )) {
            statement.setString(1, config.schema());
            statement.setString(2, config.tableName("vectors"));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL vector table is missing embedding column");
                }

                String actualType = resultSet.getString(1);
                String expectedType = "vector(" + config.vectorDimensions() + ")";
                if (!expectedType.equals(actualType)) {
                    throw new IllegalStateException(
                        "Configured vector dimensions do not match existing schema: expected "
                            + expectedType
                            + " but found "
                            + actualType
                    );
                }
            }
        }
    }

    private String dropConstraintSql(String tableBaseName, String constraintName) {
        return "ALTER TABLE "
            + config.qualifiedTableName(tableBaseName)
            + " DROP CONSTRAINT IF EXISTS "
            + quoteIdentifier(constraintName);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean originalAutoCommit) throws SQLException {
        if (connection.getAutoCommit() != originalAutoCommit) {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value + "\"";
    }

    private record Migration(int version, List<String> statements) {
    }
}
