package io.github.lightrag.storage.mysql;

import io.github.lightrag.storage.LlmCacheStore;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MySqlLlmCacheStore implements LlmCacheStore {
    private final MySqlJdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public MySqlLlmCacheStore(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, "default");
    }

    public MySqlLlmCacheStore(DataSource dataSource, MySqlStorageConfig config, String workspaceId) {
        this(MySqlJdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    MySqlLlmCacheStore(MySqlJdbcConnectionAccess connectionAccess, MySqlStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("llm_cache");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(CacheRecord record) {
        var cacheRecord = Objects.requireNonNull(record, "record");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, cache_id, value)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE value = VALUES(value)
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, cacheRecord.id());
                statement.setString(3, cacheRecord.value());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<CacheRecord> load(String cacheId) {
        var id = Objects.requireNonNull(cacheId, "cacheId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT cache_id, value
                FROM %s
                WHERE workspace_id = ?
                  AND cache_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new CacheRecord(
                        resultSet.getString("cache_id"),
                        resultSet.getString("value")
                    ));
                }
            }
        });
    }

    @Override
    public boolean contains(String cacheId) {
        return load(cacheId).isPresent();
    }

    @Override
    public void delete(List<String> cacheIds) {
        var ids = List.copyOf(Objects.requireNonNull(cacheIds, "cacheIds"));
        if (ids.isEmpty()) {
            return;
        }
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND cache_id = ?
                """.formatted(tableName)
            )) {
                for (var id : ids) {
                    statement.setString(1, workspaceId);
                    statement.setString(2, id);
                    statement.addBatch();
                }
                statement.executeBatch();
                return null;
            }
        });
    }

    @Override
    public void drop() {
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.executeUpdate();
                return null;
            }
        });
    }
}
