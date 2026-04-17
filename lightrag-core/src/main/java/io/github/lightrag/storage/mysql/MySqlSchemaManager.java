package io.github.lightrag.storage.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

public final class MySqlSchemaManager {
    private static final String STORAGE_SCHEMA_KEY = "storage";
    private static final String CHUNKS_DOCUMENT_ORDER_INDEX = "doc_order_idx";
    private static final String CHUNK_GRAPH_SNAPSHOTS_DOCUMENT_ORDER_INDEX = "doc_order_idx";
    private static final String CHUNK_GRAPH_JOURNALS_DOCUMENT_CHUNK_INDEX = "doc_chunk_idx";

    private final DataSource dataSource;
    private final MySqlStorageConfig config;
    private final List<String> bootstrapStatements;

    public MySqlSchemaManager(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, null);
    }

    MySqlSchemaManager(DataSource dataSource, MySqlStorageConfig config, List<String> bootstrapStatements) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
        this.bootstrapStatements = bootstrapStatements;
    }

    public void bootstrap() {
        try (var connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                try {
                    ensureSchemaVersionTable(statement);
                    var currentVersion = loadCurrentVersion(connection);
                    if (currentVersion.isEmpty()) {
                        rejectLegacySchemaIfPresent(connection);
                        applyMissingMigrations(statement, 0);
                    } else if (currentVersion.get() > latestSchemaVersion()) {
                        throw new IllegalStateException(
                            "Unsupported MySQL schema version: found "
                                + currentVersion.get()
                                + " but this SDK supports up to "
                                + latestSchemaVersion()
                        );
                    } else if (currentVersion.get() < latestSchemaVersion()) {
                        applyMissingMigrations(statement, currentVersion.get());
                    } else {
                        replayAppliedMigrations(statement, currentVersion.get());
                    }
                    validateWorkspaceColumns(connection);
                    connection.commit();
                } catch (RuntimeException exception) {
                    rollback(connection, exception);
                    throw exception;
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw new IllegalStateException("Failed to bootstrap MySQL schema", exception);
                }
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to bootstrap MySQL schema", exception);
        }
    }

    private void applyMissingMigrations(Statement statement, int currentVersion) throws SQLException {
        for (var migration : migrations()) {
            if (migration.version() <= currentVersion) {
                continue;
            }
            for (var sql : migration.statements()) {
                statement.execute(sql);
            }
            storeSchemaVersion(statement.getConnection(), migration.version());
        }
    }

    private void replayAppliedMigrations(Statement statement, int currentVersion) throws SQLException {
        for (var migration : migrations()) {
            if (migration.version() > currentVersion) {
                continue;
            }
            for (var sql : migration.statements()) {
                statement.execute(sql);
            }
        }
    }

    private List<Migration> migrations() {
        return List.of(
            new Migration(1, versionOneStatements()),
            new Migration(2, versionTwoStatements()),
            new Migration(3, versionThreeStatements())
        );
    }

    private int latestSchemaVersion() {
        return migrations().get(migrations().size() - 1).version();
    }

    private List<String> versionOneStatements() {
        if (bootstrapStatements != null) {
            return bootstrapStatements;
        }
        return List.of(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    id VARCHAR(191) NOT NULL,
                    title TEXT NOT NULL,
                    content LONGTEXT NOT NULL,
                    metadata LONGTEXT NOT NULL,
                    PRIMARY KEY (workspace_id, id)
                )
                """.formatted(config.qualifiedTableName("documents")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    text LONGTEXT NOT NULL,
                    token_count INT NOT NULL,
                    chunk_order INT NOT NULL,
                    metadata LONGTEXT NOT NULL,
                    PRIMARY KEY (workspace_id, id),
                    KEY %s (workspace_id, document_id, chunk_order, id)
                )
                """.formatted(
                config.qualifiedTableName("chunks"),
                CHUNKS_DOCUMENT_ORDER_INDEX
            ),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    summary TEXT NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_status"))
        );
    }

    private List<String> versionTwoStatements() {
        return List.of(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    task_id VARCHAR(191) NOT NULL,
                    task_type VARCHAR(64) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    requested_at TIMESTAMP NOT NULL,
                    started_at TIMESTAMP NULL,
                    finished_at TIMESTAMP NULL,
                    summary TEXT NOT NULL,
                    error_message TEXT NULL,
                    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
                    metadata LONGTEXT NOT NULL,
                    PRIMARY KEY (workspace_id, task_id)
                )
                """.formatted(config.qualifiedTableName("task")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    task_id VARCHAR(191) NOT NULL,
                    stage VARCHAR(64) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    sequence INT NOT NULL,
                    started_at TIMESTAMP NULL,
                    finished_at TIMESTAMP NULL,
                    message TEXT NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, task_id, stage)
                )
                """.formatted(config.qualifiedTableName("task_stage"))
        );
    }

    private List<String> versionThreeStatements() {
        return List.of(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    version INT NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    chunk_count INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_graph_snapshots")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    chunk_id VARCHAR(191) NOT NULL,
                    chunk_order INT NOT NULL,
                    content_hash VARCHAR(255) NOT NULL,
                    extract_status VARCHAR(64) NOT NULL,
                    entities LONGTEXT NOT NULL,
                    relations LONGTEXT NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id, chunk_id),
                    KEY %s (workspace_id, document_id, chunk_order, chunk_id)
                )
                """.formatted(
                config.qualifiedTableName("chunk_graph_snapshots"),
                CHUNK_GRAPH_SNAPSHOTS_DOCUMENT_ORDER_INDEX
            ),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    snapshot_version INT NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    last_mode VARCHAR(64) NOT NULL,
                    expected_entity_count INT NOT NULL,
                    expected_relation_count INT NOT NULL,
                    materialized_entity_count INT NOT NULL,
                    materialized_relation_count INT NOT NULL,
                    last_failure_stage VARCHAR(64) NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id)
                )
                """.formatted(config.qualifiedTableName("document_graph_journals")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    workspace_id VARCHAR(191) NOT NULL,
                    document_id VARCHAR(191) NOT NULL,
                    chunk_id VARCHAR(191) NOT NULL,
                    snapshot_version INT NOT NULL,
                    merge_status VARCHAR(64) NOT NULL,
                    graph_status VARCHAR(64) NOT NULL,
                    expected_entity_keys LONGTEXT NOT NULL,
                    expected_relation_keys LONGTEXT NOT NULL,
                    materialized_entity_keys LONGTEXT NOT NULL,
                    materialized_relation_keys LONGTEXT NOT NULL,
                    last_failure_stage VARCHAR(64) NULL,
                    updated_at TIMESTAMP NOT NULL,
                    error_message TEXT NULL,
                    PRIMARY KEY (workspace_id, document_id, chunk_id),
                    KEY %s (workspace_id, document_id, chunk_id)
                )
                """.formatted(
                config.qualifiedTableName("chunk_graph_journals"),
                CHUNK_GRAPH_JOURNALS_DOCUMENT_CHUNK_INDEX
            )
        );
    }

    private void ensureSchemaVersionTable(Statement statement) throws SQLException {
        statement.execute(
            """
                CREATE TABLE IF NOT EXISTS %s (
                    schema_key VARCHAR(64) PRIMARY KEY,
                    version INT NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
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
                INSERT INTO %s (schema_key, version)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE version = VALUES(version)
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
                "Detected legacy MySQL schema without workspace column isolation; automatic migration is not supported"
            );
        }
    }

    private void validateWorkspaceColumns(Connection connection) throws SQLException {
        if (bootstrapStatements != null) {
            return;
        }
        for (var tableName : List.of(
            "documents",
            "chunks",
            "document_status",
            "document_graph_snapshots",
            "chunk_graph_snapshots",
            "document_graph_journals",
            "chunk_graph_journals"
        )) {
            if (!columnExists(connection, tableName, "workspace_id")) {
                throw new IllegalStateException("MySQL shared workspace table is missing workspace_id: " + tableName);
            }
        }
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
            statement.setString(1, currentCatalog(connection));
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
            statement.setString(1, currentCatalog(connection));
            statement.setString(2, config.tableName(baseTableName));
            statement.setString(3, columnName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String currentCatalog(Connection connection) throws SQLException {
        var catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) {
            throw new IllegalStateException("MySQL connection is missing catalog");
        }
        return catalog;
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

    private record Migration(int version, List<String> statements) {
    }
}
