package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.EmbeddingSpaceStore;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

public final class PostgresEmbeddingSpaceStore implements EmbeddingSpaceStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresEmbeddingSpaceStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresEmbeddingSpaceStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresEmbeddingSpaceStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("embedding_space");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(Marker marker) {
        var record = Objects.requireNonNull(marker, "marker");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, model_identity, dimensions, recorded_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id) DO UPDATE
                SET model_identity = EXCLUDED.model_identity,
                    dimensions = EXCLUDED.dimensions,
                    recorded_at = EXCLUDED.recorded_at
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.modelIdentity());
                statement.setInt(3, record.dimensions());
                statement.setString(4, record.recordedAt());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<Marker> load() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT model_identity, dimensions, recorded_at
                FROM %s
                WHERE workspace_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Marker(
                        resultSet.getString("model_identity"),
                        resultSet.getInt("dimensions"),
                        resultSet.getString("recorded_at")
                    ));
                }
            }
        });
    }

    @Override
    public void delete() {
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
