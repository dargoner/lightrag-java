package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.RelationalStorageAdapter;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListSet;

public final class PostgresRelationalStorageAdapter implements RelationalStorageAdapter {
    private static final int DEFAULT_POOL_SIZE = 4;

    private final DataSource dataSource;
    private final HikariDataSource ownedDataSource;
    private final boolean ownsDataSource;
    private final PostgresStorageConfig config;
    private final SnapshotStore snapshotStore;
    private final String workspaceId;
    private final DocumentStore documentStore;
    private final ChunkStore chunkStore;
    private final GraphStore graphStore;
    private final DocumentStatusStore documentStatusStore;
    private final TaskStore taskStore;
    private final TaskStageStore taskStageStore;
    private final TaskDocumentStore taskDocumentStore;
    private final DocumentGraphSnapshotStore documentGraphSnapshotStore;
    private final DocumentGraphJournalStore documentGraphJournalStore;
    private final java.util.Set<String> trackedDocumentGraphIds;

    public PostgresRelationalStorageAdapter(
        DataSource dataSource,
        PostgresStorageConfig config,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            Objects.requireNonNull(dataSource, "dataSource"),
            false,
            Objects.requireNonNull(config, "config"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
        );
    }

    public PostgresRelationalStorageAdapter(
        DataSource dataSource,
        PostgresStorageConfig config,
        SnapshotStore snapshotStore,
        String workspaceId
    ) {
        this(
            Objects.requireNonNull(dataSource, "dataSource"),
            false,
            Objects.requireNonNull(config, "config"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceId, "workspaceId")
        );
    }

    public PostgresRelationalStorageAdapter(
        PostgresStorageConfig config,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            createDataSource(Objects.requireNonNull(config, "config"), "lightrag-postgres"),
            true,
            config,
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
        );
    }

    private PostgresRelationalStorageAdapter(
        DataSource dataSource,
        boolean ownsDataSource,
        PostgresStorageConfig config,
        SnapshotStore snapshotStore,
        String workspaceId
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ownedDataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
        this.ownsDataSource = ownsDataSource;
        this.config = ownsDataSource
            ? Objects.requireNonNull(config, "config")
            : PostgresSchemaResolver.alignWithDataSourceSchema(
                dataSource,
                Objects.requireNonNull(config, "config")
            );
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.workspaceId = new WorkspaceScope(Objects.requireNonNull(workspaceId, "workspaceId")).workspaceId();
        new PostgresMilvusNeo4jSchemaManager(this.dataSource, this.config).bootstrap();
        this.documentStore = new PostgresDocumentStore(this.dataSource, this.config, this.workspaceId);
        this.chunkStore = new PostgresChunkStore(this.dataSource, this.config, this.workspaceId);
        this.graphStore = new PostgresGraphStore(this.dataSource, this.config, this.workspaceId);
        this.documentStatusStore = new PostgresDocumentStatusStore(this.dataSource, this.config, this.workspaceId);
        this.taskStore = new PostgresTaskStore(this.dataSource, this.config, this.workspaceId);
        this.taskStageStore = new PostgresTaskStageStore(this.dataSource, this.config, this.workspaceId);
        this.taskDocumentStore = new PostgresTaskDocumentStore(this.dataSource, this.config, this.workspaceId);
        this.trackedDocumentGraphIds = new ConcurrentSkipListSet<>();
        this.documentGraphSnapshotStore = DocumentGraphStateSupport.trackedSnapshotStore(
            new PostgresDocumentGraphSnapshotStore(this.dataSource, this.config, this.workspaceId),
            trackedDocumentGraphIds
        );
        this.documentGraphJournalStore = DocumentGraphStateSupport.trackedJournalStore(
            new PostgresDocumentGraphJournalStore(this.dataSource, this.config, this.workspaceId),
            trackedDocumentGraphIds
        );
    }

    @Override
    public DocumentStore documentStore() {
        return documentStore;
    }

    @Override
    public ChunkStore chunkStore() {
        return chunkStore;
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return documentStatusStore;
    }

    @Override
    public TaskStore taskStore() {
        return taskStore;
    }

    @Override
    public TaskStageStore taskStageStore() {
        return taskStageStore;
    }

    @Override
    public TaskDocumentStore taskDocumentStore() {
        return taskDocumentStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        return documentGraphSnapshotStore;
    }

    @Override
    public DocumentGraphJournalStore documentGraphJournalStore() {
        return documentGraphJournalStore;
    }

    @Override
    public SnapshotStore.Snapshot captureSnapshot() {
        var documents = documentStore.list();
        var documentStatuses = documentStatusStore.list();
        var documentGraphState = DocumentGraphStateSupport.capture(
            documentGraphSnapshotStore,
            documentGraphJournalStore,
            trackedDocumentGraphIds,
            documents,
            documentStatuses
        );
        return new SnapshotStore.Snapshot(
            documents,
            chunkStore.list(),
            graphStore.allEntities(),
            graphStore.allRelations(),
            Map.of(),
            documentStatuses,
            documentGraphState.documentSnapshots(),
            documentGraphState.chunkSnapshots(),
            documentGraphState.documentJournals(),
            documentGraphState.chunkJournals()
        );
    }

    @Override
    public SnapshotStore.Snapshot toRelationalRestoreSnapshot(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        return new SnapshotStore.Snapshot(
            source.documents(),
            source.chunks(),
            source.entities(),
            source.relations(),
            Map.of(),
            source.documentStatuses(),
            source.documentGraphSnapshots(),
            source.chunkGraphSnapshots(),
            source.documentGraphJournals(),
            source.chunkGraphJournals()
        );
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = toRelationalRestoreSnapshot(snapshot);
        PostgresRetrySupport.execute(
            "restore postgres rows for workspace '%s'".formatted(workspaceId),
            () -> {
                try (var connection = dataSource.getConnection()) {
                    var connectionAccess = JdbcConnectionAccess.forConnection(connection);
                    withTransaction(connection, () -> {
                        truncateAll(connection);
                        var transactionalDocumentStore = new PostgresDocumentStore(connectionAccess, config, workspaceId);
                        var transactionalChunkStore = new PostgresChunkStore(connectionAccess, config, workspaceId);
                        var transactionalGraphStore = new PostgresGraphStore(connectionAccess, config, workspaceId);
                        var transactionalStatusStore = new PostgresDocumentStatusStore(connectionAccess, config, workspaceId);
                        var transactionalTaskDocumentStore = new PostgresTaskDocumentStore(connectionAccess, config, workspaceId);
                        var transactionalSnapshotStore = new PostgresDocumentGraphSnapshotStore(connectionAccess, config, workspaceId);
                        var transactionalJournalStore = new PostgresDocumentGraphJournalStore(connectionAccess, config, workspaceId);
                        for (var document : source.documents()) {
                            transactionalDocumentStore.save(document);
                        }
                        for (var chunk : source.chunks()) {
                            transactionalChunkStore.save(chunk);
                        }
                        for (var entity : source.entities()) {
                            transactionalGraphStore.saveEntity(entity);
                        }
                        for (var relation : source.relations()) {
                            transactionalGraphStore.saveRelation(relation);
                        }
                        for (var status : source.documentStatuses()) {
                            transactionalStatusStore.save(status);
                        }
                        DocumentGraphStateSupport.restore(
                            transactionalSnapshotStore,
                            transactionalJournalStore,
                            java.util.Set.of(),
                            source
                        );
                        return null;
                    });
                    return null;
                } catch (SQLException exception) {
                    throw new StorageException("Failed to open PostgreSQL transaction for restore", exception);
                }
            }
        );
    }

    @Override
    public <T> T writeInTransaction(RelationalWriteOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return PostgresRetrySupport.execute(
            "atomic write for workspace '%s'".formatted(workspaceId),
            () -> {
                try (var connection = dataSource.getConnection()) {
                    var connectionAccess = JdbcConnectionAccess.forConnection(connection);
                    return withTransaction(connection, () -> operation.execute(new RelationalStorageView() {
                        @Override
                        public DocumentStore documentStore() {
                            return new PostgresDocumentStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public ChunkStore chunkStore() {
                            return new PostgresChunkStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public DocumentStatusStore documentStatusStore() {
                            return new PostgresDocumentStatusStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
                            return new PostgresDocumentGraphSnapshotStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public DocumentGraphJournalStore documentGraphJournalStore() {
                            return new PostgresDocumentGraphJournalStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public TaskStore taskStore() {
                            return new PostgresTaskStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public TaskStageStore taskStageStore() {
                            return new PostgresTaskStageStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public TaskDocumentStore taskDocumentStore() {
                            return new PostgresTaskDocumentStore(connectionAccess, config, workspaceId);
                        }

                        @Override
                        public Optional<GraphStore> transactionalGraphStore() {
                            return Optional.of(new PostgresGraphStore(connectionAccess, config, workspaceId));
                        }
                    }));
                } catch (SQLException exception) {
                    throw new StorageException("Failed to open PostgreSQL transaction", exception);
                }
            }
        );
    }

    public GraphStore graphStore() {
        return graphStore;
    }

    DataSource dataSource() {
        return dataSource;
    }

    PostgresStorageConfig config() {
        return config;
    }

    String workspaceId() {
        return workspaceId;
    }

    @Override
    public void close() {
        if (ownsDataSource && ownedDataSource != null) {
            ownedDataSource.close();
        }
    }

    private <T> T withTransaction(Connection connection, SqlSupplier<T> supplier) {
        Throwable primaryFailure = null;
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = supplier.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                rollback(connection, failure);
                throw failure;
            } catch (SQLException exception) {
                primaryFailure = exception;
                rollback(connection, exception);
                throw new StorageException("PostgreSQL transaction failed", exception);
            } finally {
                try {
                    restoreAutoCommit(connection, originalAutoCommit);
                } catch (SQLException exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(exception);
                    } else {
                        throw new StorageException("Failed to restore PostgreSQL connection state", exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to configure PostgreSQL transaction", exception);
        }
    }

    private void truncateAll(Connection connection) throws SQLException {
        deleteWorkspaceRows(connection, "entity_chunks");
        deleteWorkspaceRows(connection, "entity_aliases");
        deleteWorkspaceRows(connection, "relations");
        deleteWorkspaceRows(connection, "entities");
        deleteWorkspaceRows(connection, "chunk_graph_journals");
        deleteWorkspaceRows(connection, "document_graph_journals");
        deleteWorkspaceRows(connection, "chunk_graph_snapshots");
        deleteWorkspaceRows(connection, "document_graph_snapshots");
        deleteWorkspaceRows(connection, "chunks");
        deleteWorkspaceRows(connection, "document_status");
        deleteWorkspaceRows(connection, "documents");
    }

    private void deleteWorkspaceRows(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + config.qualifiedTableName(tableName) + " WHERE workspace_id = ?"
        )) {
            statement.setString(1, workspaceId);
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection, Throwable original) {
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

    private static HikariDataSource createDataSource(PostgresStorageConfig config, String poolName) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setPoolName(poolName);
        return new HikariDataSource(hikariConfig);
    }

    private static final class PostgresMilvusNeo4jSchemaManager {
        private final DataSource dataSource;
        private final PostgresStorageConfig config;

        private PostgresMilvusNeo4jSchemaManager(DataSource dataSource, PostgresStorageConfig config) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            this.config = Objects.requireNonNull(config, "config");
        }

        private void bootstrap() {
            try (Connection connection = dataSource.getConnection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
                    for (String sql : statements()) {
                        statement.execute(sql);
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
                } finally {
                    restoreAutoCommit(connection, originalAutoCommit);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
            }
        }

        private List<String> statements() {
            return List.of(
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
                        id VARCHAR(64) NOT NULL,
                        src_id VARCHAR(256) NOT NULL,
                        tgt_id VARCHAR(256) NOT NULL,
                        keywords TEXT NOT NULL,
                        description TEXT NOT NULL,
                        weight DOUBLE PRECISION NOT NULL,
                        source_id TEXT NOT NULL DEFAULT '',
                        file_path VARCHAR(32768) NOT NULL DEFAULT '',
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
                        document_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL DEFAULT '',
                        error_message TEXT,
                        PRIMARY KEY (workspace_id, document_id)
                    )
                    """.formatted(config.qualifiedTableName("document_status")),
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

        private String dropConstraintSql(String tableName, String constraintName) {
            return """
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = '%s'
                          AND table_name = '%s'
                          AND constraint_name = '%s'
                    ) THEN
                        EXECUTE 'ALTER TABLE %s DROP CONSTRAINT %s';
                    END IF;
                END $$;
                """.formatted(
                config.schema(),
                config.tableName(tableName),
                constraintName,
                config.qualifiedTableName(tableName),
                constraintName
            );
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
