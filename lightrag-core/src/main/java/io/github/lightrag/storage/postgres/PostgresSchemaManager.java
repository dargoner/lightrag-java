package io.github.lightrag.storage.postgres;

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
                        rejectLegacySchemaIfPresent(connection);
                        applyMissingMigrations(statement, 0);
                    } else if (currentVersion.get() > latestSchemaVersion()) {
                        throw new IllegalStateException(
                            "Unsupported PostgreSQL schema version: found "
                                + currentVersion.get()
                                + " but this SDK supports up to "
                                + latestSchemaVersion()
                        );
                    } else if (currentVersion.get() < latestSchemaVersion()) {
                        throw new IllegalStateException(
                            "Detected legacy PostgreSQL schema version "
                                + currentVersion.get()
                                + "; automatic migration to workspace column isolation is not supported"
                        );
                    } else {
                        replayAppliedMigrations(statement, currentVersion.get());
                    }
                    validateWorkspaceColumns(connection);
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

    private void replayAppliedMigrations(Statement statement, int currentVersion) throws SQLException {
        for (Migration migration : migrations()) {
            if (migration.version() > currentVersion) {
                continue;
            }
            for (String sql : migration.statements()) {
                statement.execute(sql);
            }
        }
    }

    private List<Migration> migrations() {
        return List.of(
            new Migration(3, versionThreeStatements()),
            new Migration(4, versionFourStatements())
        );
    }

    private int latestSchemaVersion() {
        return migrations().get(migrations().size() - 1).version();
    }

    private List<String> versionThreeStatements() {
        if (bootstrapStatements != null) {
            return bootstrapStatements;
        }
        return List.of(
            "CREATE EXTENSION IF NOT EXISTS vector",
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    content TEXT NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    PRIMARY KEY (workspace_id, id)
                )
                """.formatted(config.qualifiedTableName("documents")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    token_count INTEGER NOT NULL,
                    chunk_order INTEGER NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    PRIMARY KEY (workspace_id, id)
                )
                """.formatted(config.qualifiedTableName("chunks")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    PRIMARY KEY (workspace_id, id)
                )
                """.formatted(config.qualifiedTableName("entities")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    alias TEXT NOT NULL,
                    PRIMARY KEY (workspace_id, entity_id, alias)
                )
                """.formatted(config.qualifiedTableName("entity_aliases")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    PRIMARY KEY (workspace_id, entity_id, chunk_id)
                )
                """.formatted(config.qualifiedTableName("entity_chunks")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    id TEXT NOT NULL,
                    source_entity_id TEXT NOT NULL,
                    target_entity_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    weight DOUBLE PRECISION NOT NULL,
                    PRIMARY KEY (workspace_id, id)
                )
                """.formatted(config.qualifiedTableName("relations")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    relation_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    PRIMARY KEY (workspace_id, relation_id, chunk_id)
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
                    workspace_id TEXT NOT NULL,
                    namespace TEXT NOT NULL,
                    vector_id TEXT NOT NULL,
                    embedding vector(%d) NOT NULL,
                    PRIMARY KEY (workspace_id, namespace, vector_id)
                )
                """.formatted(config.qualifiedTableName("vectors"), config.vectorDimensions()),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    error_message TEXT,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_status"))
        );
    }

    private List<String> versionFourStatements() {
        return List.of(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    task_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    requested_at TIMESTAMPTZ NOT NULL,
                    started_at TIMESTAMPTZ NULL,
                    finished_at TIMESTAMPTZ NULL,
                    summary TEXT NOT NULL DEFAULT '',
                    error_message TEXT NULL,
                    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    PRIMARY KEY (workspace_id, task_id)
                )
                """.formatted(config.qualifiedTableName("task")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    status TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    started_at TIMESTAMPTZ NULL,
                    finished_at TIMESTAMPTZ NULL,
                    message TEXT NOT NULL DEFAULT '',
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, task_id, stage)
                )
                """.formatted(config.qualifiedTableName("task_stage")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    entity_count INTEGER NOT NULL,
                    relation_count INTEGER NOT NULL,
                    chunk_vector_count INTEGER NOT NULL,
                    entity_vector_count INTEGER NOT NULL,
                    relation_vector_count INTEGER NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, task_id, document_id)
                )
                """.formatted(config.qualifiedTableName("task_document")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    source TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_graph_snapshots")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    chunk_order INTEGER NOT NULL,
                    content_hash TEXT NOT NULL,
                    extract_status TEXT NOT NULL,
                    entities JSONB NOT NULL DEFAULT '[]'::jsonb,
                    relations JSONB NOT NULL DEFAULT '[]'::jsonb,
                    updated_at TIMESTAMPTZ NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id, chunk_id)
                )
                """.formatted(config.qualifiedTableName("chunk_graph_snapshots")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    snapshot_version INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    last_mode TEXT NOT NULL,
                    expected_entity_count INTEGER NOT NULL,
                    expected_relation_count INTEGER NOT NULL,
                    materialized_entity_count INTEGER NOT NULL,
                    materialized_relation_count INTEGER NOT NULL,
                    last_failure_stage TEXT NULL,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_graph_journals")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id TEXT NOT NULL,
                    document_id TEXT NOT NULL,
                    chunk_id TEXT NOT NULL,
                    snapshot_version INTEGER NOT NULL,
                    merge_status TEXT NOT NULL,
                    graph_status TEXT NOT NULL,
                    expected_entity_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
                    expected_relation_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
                    materialized_entity_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
                    materialized_relation_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
                    last_failure_stage TEXT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id, chunk_id)
                )
                """.formatted(config.qualifiedTableName("chunk_graph_journals"))
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

    private void rejectLegacySchemaIfPresent(Connection connection) throws SQLException {
        if (!storageTableExists(connection, "documents")) {
            return;
        }
        if (!columnExists(connection, "documents", "workspace_id")) {
            throw new IllegalStateException(
                "Detected legacy PostgreSQL schema without workspace column isolation; automatic migration is not supported"
            );
        }
    }

    private void validateWorkspaceColumns(Connection connection) throws SQLException {
        if (bootstrapStatements != null) {
            return;
        }
        for (String tableName : List.of(
            "documents",
            "chunks",
            "entities",
            "entity_aliases",
            "entity_chunks",
            "relations",
            "relation_chunks",
            "vectors",
            "document_status",
            "document_graph_snapshots",
            "chunk_graph_snapshots",
            "document_graph_journals",
            "chunk_graph_journals"
        )) {
            if (!columnExists(connection, tableName, "workspace_id")) {
                throw new IllegalStateException("PostgreSQL shared workspace table is missing workspace_id: " + tableName);
            }
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
                if (!expectedType.equals(normalizeTypeName(actualType))) {
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

    private static String normalizeTypeName(String typeName) {
        if (typeName == null) {
            return null;
        }
        int parameterIndex = typeName.indexOf('(');
        int schemaSeparatorIndex = typeName.lastIndexOf('.', parameterIndex >= 0 ? parameterIndex : typeName.length());
        if (schemaSeparatorIndex < 0) {
            return typeName;
        }
        return typeName.substring(schemaSeparatorIndex + 1);
    }

    private boolean storageTableExists(Connection connection, String baseTableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = ?
                  AND table_name = ?
                """
        )) {
            statement.setString(1, config.schema());
            statement.setString(2, config.tableName(baseTableName));
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean columnExists(Connection connection, String baseTableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                  AND column_name = ?
                """
        )) {
            statement.setString(1, config.schema());
            statement.setString(2, config.tableName(baseTableName));
            statement.setString(3, columnName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
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
