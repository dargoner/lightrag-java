package io.github.lightragjava.storage.postgres;

import com.pgvector.PGvector;
import io.github.lightragjava.storage.VectorStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class PostgresVectorStore implements VectorStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final int vectorDimensions;
    private final String workspaceId;

    public PostgresVectorStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresVectorStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresVectorStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        var storageConfig = Objects.requireNonNull(config, "config");
        this.tableName = storageConfig.qualifiedTableName("vectors");
        this.vectorDimensions = storageConfig.vectorDimensions();
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(vectors, "vectors");
        if (vectors.isEmpty()) {
            return;
        }

        connectionAccess.withConnection(connection -> {
            PGvector.registerTypes(connection);
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, namespace, vector_id, embedding)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id, namespace, vector_id) DO UPDATE
                SET embedding = EXCLUDED.embedding
                """.formatted(tableName)
            )) {
                for (var vector : vectors) {
                    var record = Objects.requireNonNull(vector, "vector");
                    statement.setString(1, workspaceId);
                    statement.setString(2, targetNamespace);
                    statement.setString(3, record.id());
                    statement.setObject(4, toPgVector(record.vector()));
                    statement.addBatch();
                }
                statement.executeBatch();
                return null;
            }
        });
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        if (topK <= 0) {
            return List.of();
        }

        return connectionAccess.withConnection(connection -> {
            if (!namespaceExists(connection, targetNamespace)) {
                return List.of();
            }

            PGvector.registerTypes(connection);
            var query = toPgVector(Objects.requireNonNull(queryVector, "queryVector"));
            try (var statement = connection.prepareStatement(
                """
                SELECT vector_id, -(embedding <#> ?) AS score
                FROM %s
                WHERE workspace_id = ?
                  AND namespace = ?
                ORDER BY embedding <#> ?, vector_id
                LIMIT ?
                """.formatted(tableName)
            )) {
                statement.setObject(1, query);
                statement.setString(2, workspaceId);
                statement.setString(3, targetNamespace);
                statement.setObject(4, query);
                statement.setInt(5, topK);

                try (var resultSet = statement.executeQuery()) {
                    var matches = new java.util.ArrayList<VectorMatch>();
                    while (resultSet.next()) {
                        matches.add(new VectorMatch(
                            resultSet.getString("vector_id"),
                            resultSet.getDouble("score")
                        ));
                    }
                    return List.copyOf(matches);
                }
            }
        });
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        return connectionAccess.withConnection(connection -> {
            PGvector.registerTypes(connection);
            try (var statement = connection.prepareStatement(
                """
                SELECT vector_id, embedding
                FROM %s
                WHERE workspace_id = ?
                  AND namespace = ?
                ORDER BY vector_id
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, targetNamespace);
                try (var resultSet = statement.executeQuery()) {
                    var vectors = new java.util.ArrayList<VectorRecord>();
                    while (resultSet.next()) {
                        vectors.add(readVector(resultSet));
                    }
                    return List.copyOf(vectors);
                }
            }
        });
    }

    private VectorRecord readVector(ResultSet resultSet) throws SQLException {
        var vector = new PGvector(resultSet.getString("embedding"));
        return new VectorRecord(
            resultSet.getString("vector_id"),
            toDoubleList(vector.toArray())
        );
    }

    private boolean namespaceExists(java.sql.Connection connection, String namespace) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT 1
            FROM %s
            WHERE workspace_id = ?
              AND namespace = ?
            LIMIT 1
            """.formatted(tableName)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, namespace);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private PGvector toPgVector(List<Double> vector) {
        var values = Objects.requireNonNull(vector, "vector");
        if (values.size() != vectorDimensions) {
            throw new IllegalArgumentException("vector dimensions must match configured dimensions");
        }
        return new PGvector(values);
    }

    private static List<Double> toDoubleList(float[] vector) {
        var values = new java.util.ArrayList<Double>(vector.length);
        for (float value : vector) {
            values.add((double) value);
        }
        return List.copyOf(values);
    }
}
