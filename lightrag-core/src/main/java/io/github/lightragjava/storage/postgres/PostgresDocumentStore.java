package io.github.lightragjava.storage.postgres;

import io.github.lightragjava.storage.DocumentStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresDocumentStore implements DocumentStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresDocumentStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresDocumentStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresDocumentStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("documents");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(DocumentRecord document) {
        var record = Objects.requireNonNull(document, "document");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, id, title, content, metadata)
                VALUES (?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (workspace_id, id) DO UPDATE
                SET title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.id());
                statement.setString(3, record.title());
                statement.setString(4, record.content());
                statement.setString(5, JdbcJsonCodec.writeStringMap(record.metadata()));
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<DocumentRecord> load(String documentId) {
        var id = Objects.requireNonNull(documentId, "documentId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, title, content, metadata
                FROM %s
                WHERE workspace_id = ?
                  AND id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readDocument(resultSet));
                }
            }
        });
    }

    @Override
    public List<DocumentRecord> list() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, title, content, metadata
                FROM %s
                WHERE workspace_id = ?
                ORDER BY id
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var documents = new java.util.ArrayList<DocumentRecord>();
                    while (resultSet.next()) {
                        documents.add(readDocument(resultSet));
                    }
                    return List.copyOf(documents);
                }
            }
        });
    }

    @Override
    public boolean contains(String documentId) {
        var id = Objects.requireNonNull(documentId, "documentId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT 1
                FROM %s
                WHERE workspace_id = ?
                  AND id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    return resultSet.next();
                }
            }
        });
    }

    private static DocumentRecord readDocument(ResultSet resultSet) throws SQLException {
        return new DocumentRecord(
            resultSet.getString("id"),
            resultSet.getString("title"),
            resultSet.getString("content"),
            JdbcJsonCodec.readStringMap(resultSet.getString("metadata"))
        );
    }
}
