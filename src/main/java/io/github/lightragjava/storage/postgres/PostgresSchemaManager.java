package io.github.lightragjava.storage.postgres;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

public final class PostgresSchemaManager {
    private final DataSource dataSource;
    private final PostgresStorageConfig config;

    public PostgresSchemaManager(DataSource dataSource, PostgresStorageConfig config) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void bootstrap() {
        try (var connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : bootstrapStatements()) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
        }
    }

    private List<String> bootstrapStatements() {
        return List.of(
            "CREATE EXTENSION IF NOT EXISTS vector",
            "CREATE SCHEMA IF NOT EXISTS " + config.schemaName(),
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
                    document_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    text TEXT NOT NULL,
                    token_count INTEGER NOT NULL,
                    chunk_order INTEGER NOT NULL,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.formatted(config.qualifiedTableName("chunks"), config.qualifiedTableName("documents")),
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
                    entity_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    alias TEXT NOT NULL,
                    PRIMARY KEY (entity_id, alias)
                )
                """.formatted(config.qualifiedTableName("entity_aliases"), config.qualifiedTableName("entities")),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    entity_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    chunk_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    PRIMARY KEY (entity_id, chunk_id)
                )
                """.formatted(
                config.qualifiedTableName("entity_chunks"),
                config.qualifiedTableName("entities"),
                config.qualifiedTableName("chunks")
            ),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    id TEXT PRIMARY KEY,
                    source_entity_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    target_entity_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    type TEXT NOT NULL,
                    description TEXT NOT NULL,
                    weight DOUBLE PRECISION NOT NULL
                )
                """.formatted(
                config.qualifiedTableName("relations"),
                config.qualifiedTableName("entities"),
                config.qualifiedTableName("entities")
            ),
            """
                CREATE TABLE IF NOT EXISTS %s (
                    relation_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    chunk_id TEXT NOT NULL REFERENCES %s (id) ON DELETE CASCADE,
                    PRIMARY KEY (relation_id, chunk_id)
                )
                """.formatted(
                config.qualifiedTableName("relation_chunks"),
                config.qualifiedTableName("relations"),
                config.qualifiedTableName("chunks")
            ),
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
}
