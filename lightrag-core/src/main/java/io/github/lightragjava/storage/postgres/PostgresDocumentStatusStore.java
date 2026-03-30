package io.github.lightragjava.storage.postgres;

import io.github.lightragjava.api.DocumentStatus;
import io.github.lightragjava.storage.DocumentStatusStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresDocumentStatusStore implements DocumentStatusStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresDocumentStatusStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresDocumentStatusStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresDocumentStatusStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("document_status");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(StatusRecord statusRecord) {
        var record = Objects.requireNonNull(statusRecord, "statusRecord");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, document_id, status, summary, error_message)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, document_id) DO UPDATE
                SET status = EXCLUDED.status,
                    summary = EXCLUDED.summary,
                    error_message = EXCLUDED.error_message
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.documentId());
                statement.setString(3, record.status().name());
                statement.setString(4, record.summary());
                statement.setString(5, record.errorMessage());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<StatusRecord> load(String documentId) {
        var id = Objects.requireNonNull(documentId, "documentId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, status, summary, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readStatus(resultSet));
                }
            }
        });
    }

    @Override
    public List<StatusRecord> list() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, status, summary, error_message
                FROM %s
                WHERE workspace_id = ?
                ORDER BY document_id
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var statuses = new java.util.ArrayList<StatusRecord>();
                    while (resultSet.next()) {
                        statuses.add(readStatus(resultSet));
                    }
                    return List.copyOf(statuses);
                }
            }
        });
    }

    @Override
    public void delete(String documentId) {
        var id = Objects.requireNonNull(documentId, "documentId");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private static StatusRecord readStatus(ResultSet resultSet) throws SQLException {
        return new StatusRecord(
            resultSet.getString("document_id"),
            DocumentStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("summary"),
            resultSet.getString("error_message")
        );
    }
}
