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

    public PostgresDocumentStore(DataSource dataSource, PostgresStorageConfig config) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config);
    }

    PostgresDocumentStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("documents");
    }

    @Override
    public void save(DocumentRecord document) {
        var record = Objects.requireNonNull(document, "document");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (id, title, content, metadata)
                VALUES (?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (id) DO UPDATE
                SET title = EXCLUDED.title,
                    content = EXCLUDED.content,
                    metadata = EXCLUDED.metadata
                """.formatted(tableName)
            )) {
                statement.setString(1, record.id());
                statement.setString(2, record.title());
                statement.setString(3, record.content());
                statement.setString(4, JdbcJsonCodec.writeStringMap(record.metadata()));
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
                WHERE id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
                ORDER BY id
                """.formatted(tableName)
            ); var resultSet = statement.executeQuery()) {
                var documents = new java.util.ArrayList<DocumentRecord>();
                while (resultSet.next()) {
                    documents.add(readDocument(resultSet));
                }
                return List.copyOf(documents);
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
                WHERE id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
