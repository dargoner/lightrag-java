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

    public PostgresDocumentStatusStore(DataSource dataSource, PostgresStorageConfig config) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config);
    }

    PostgresDocumentStatusStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("document_status");
    }

    @Override
    public void save(StatusRecord statusRecord) {
        var record = Objects.requireNonNull(statusRecord, "statusRecord");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (document_id, status, summary, error_message)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (document_id) DO UPDATE
                SET status = EXCLUDED.status,
                    summary = EXCLUDED.summary,
                    error_message = EXCLUDED.error_message
                """.formatted(tableName)
            )) {
                statement.setString(1, record.documentId());
                statement.setString(2, record.status().name());
                statement.setString(3, record.summary());
                statement.setString(4, record.errorMessage());
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
                WHERE document_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
                ORDER BY document_id
                """.formatted(tableName)
            ); var resultSet = statement.executeQuery()) {
                var statuses = new java.util.ArrayList<StatusRecord>();
                while (resultSet.next()) {
                    statuses.add(readStatus(resultSet));
                }
                return List.copyOf(statuses);
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
                WHERE document_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
