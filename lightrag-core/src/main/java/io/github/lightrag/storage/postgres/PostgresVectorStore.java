package io.github.lightrag.storage.postgres;

import com.pgvector.PGvector;
import io.github.lightrag.storage.VectorStore;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class PostgresVectorStore implements VectorStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final int vectorDimensions;
    private final PostgresVectorDialect vectorDialect;
    private final List<String> workspaceIds;
    private final String workspaceId;

    public PostgresVectorStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresVectorStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, List.of(workspaceId));
    }

    /**
     * Workspace-set constructor: searches and listings read every workspace in one IN-filtered
     * statement; writes require a single workspace and fail loudly on a multi-workspace store.
     */
    public PostgresVectorStore(DataSource dataSource, PostgresStorageConfig config, List<String> workspaceIds) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceIds);
    }

    PostgresVectorStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this(connectionAccess, config, List.of(workspaceId));
    }

    PostgresVectorStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, List<String> workspaceIds) {
        var normalized = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(workspaceIds, "workspaceIds")));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("workspaceIds must not be empty");
        }
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        var storageConfig = Objects.requireNonNull(config, "config");
        this.tableName = storageConfig.qualifiedTableName("vectors");
        this.vectorDimensions = storageConfig.vectorDimensions();
        this.vectorDialect = storageConfig.vectorDialect().resolved();
        this.workspaceIds = normalized;
        this.workspaceId = normalized.get(0);
    }

    private boolean isMultiWorkspace() {
        return workspaceIds.size() > 1;
    }

    private void requireSingleWorkspace(String operation) {
        if (isMultiWorkspace()) {
            throw new IllegalStateException(
                operation + " requires a single-workspace vector store; this store covers "
                    + workspaceIds.size() + " workspaces");
        }
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> "?")
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private int bindWorkspaces(PreparedStatement statement, int parameterIndex) throws SQLException {
        for (var workspace : workspaceIds) {
            statement.setString(parameterIndex++, workspace);
        }
        return parameterIndex;
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(vectors, "vectors");
        if (vectors.isEmpty()) {
            return;
        }
        requireSingleWorkspace("saveAll");

        connectionAccess.withConnection(connection -> {
            registerVectorTypes(connection);
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, namespace, vector_id, embedding)
                VALUES (?, ?, ?, %s)
                ON CONFLICT (workspace_id, namespace, vector_id) DO UPDATE
                SET embedding = EXCLUDED.embedding
                """.formatted(tableName, vectorPlaceholder())
            )) {
                for (var vector : vectors) {
                    var record = Objects.requireNonNull(vector, "vector");
                    statement.setString(1, workspaceId);
                    statement.setString(2, targetNamespace);
                    statement.setString(3, record.id());
                    bindVector(statement, 4, record.vector());
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

            registerVectorTypes(connection);
            var query = Objects.requireNonNull(queryVector, "queryVector");
            validateDimensions(query);
            var queryPlaceholder = vectorPlaceholder();
            try (var statement = connection.prepareStatement(
                """
                SELECT vector_id, -(embedding <#> %s) AS score
                FROM %s
                WHERE workspace_id IN (%s)
                  AND namespace = ?
                ORDER BY embedding <#> %s, vector_id
                LIMIT ?
                """.formatted(
                    queryPlaceholder,
                    tableName,
                    placeholders(workspaceIds.size()),
                    queryPlaceholder
                )
            )) {
                bindVector(statement, 1, query);
                int parameterIndex = bindWorkspaces(statement, 2);
                statement.setString(parameterIndex++, targetNamespace);
                bindVector(statement, parameterIndex++, query);
                statement.setInt(parameterIndex, topK);

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
            registerVectorTypes(connection);
            try (var statement = connection.prepareStatement(
                """
                SELECT vector_id, embedding
                FROM %s
                WHERE workspace_id IN (%s)
                  AND namespace = ?
                ORDER BY vector_id
                """.formatted(tableName, placeholders(workspaceIds.size()))
            )) {
                int parameterIndex = bindWorkspaces(statement, 1);
                statement.setString(parameterIndex, targetNamespace);
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

    @Override
    public void deleteIds(String namespace, List<String> ids) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return;
        }
        requireSingleWorkspace("deleteIds");

        connectionAccess.withConnection(connection -> {
            var placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND namespace = ?
                  AND vector_id IN (%s)
                """.formatted(tableName, placeholders)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, targetNamespace);
                var index = 3;
                for (var id : ids) {
                    statement.setString(index++, Objects.requireNonNull(id, "id"));
                }
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public void deleteNamespace(String namespace) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        requireSingleWorkspace("deleteNamespace");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND namespace = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, targetNamespace);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private VectorRecord readVector(ResultSet resultSet) throws SQLException {
        var embedding = resultSet.getString("embedding");
        if (vectorDialect == PostgresVectorDialect.VASTBASE) {
            return new VectorRecord(
                resultSet.getString("vector_id"),
                parseVectorLiteral(embedding)
            );
        }
        var vector = new PGvector(embedding);
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
            WHERE workspace_id IN (%s)
              AND namespace = ?
            LIMIT 1
            """.formatted(tableName, placeholders(workspaceIds.size()))
        )) {
            int parameterIndex = bindWorkspaces(statement, 1);
            statement.setString(parameterIndex, namespace);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void registerVectorTypes(java.sql.Connection connection) throws SQLException {
        if (vectorDialect == PostgresVectorDialect.PGVECTOR) {
            PGvector.registerTypes(connection);
        }
    }

    private String vectorPlaceholder() {
        return vectorDialect == PostgresVectorDialect.VASTBASE ? "CAST(? AS floatvector)" : "?";
    }

    private void bindVector(PreparedStatement statement, int parameterIndex, List<Double> vector)
        throws SQLException {
        var values = Objects.requireNonNull(vector, "vector");
        validateDimensions(values);
        if (vectorDialect == PostgresVectorDialect.VASTBASE) {
            statement.setString(parameterIndex, toVectorLiteral(values));
            return;
        }
        statement.setObject(parameterIndex, new PGvector(values));
    }

    private void validateDimensions(List<Double> vector) {
        if (vector.size() != vectorDimensions) {
            throw new IllegalArgumentException("vector dimensions must match configured dimensions");
        }
    }

    private static String toVectorLiteral(List<Double> vector) {
        var builder = new StringBuilder(vector.size() * 8 + 2);
        builder.append('[');
        for (int index = 0; index < vector.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            var value = Objects.requireNonNull(vector.get(index), "vector component");
            builder.append(value);
        }
        return builder.append(']').toString();
    }

    private static List<Double> parseVectorLiteral(String literal) {
        var text = Objects.requireNonNull(literal, "embedding").strip();
        if (text.startsWith("[")) {
            text = text.substring(1);
        }
        if (text.endsWith("]")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isBlank()) {
            return List.of();
        }
        var parts = text.split(",");
        var values = new java.util.ArrayList<Double>(parts.length);
        for (var part : parts) {
            values.add(Double.parseDouble(part.strip()));
        }
        return List.copyOf(values);
    }

    private static List<Double> toDoubleList(float[] vector) {
        var values = new java.util.ArrayList<Double>(vector.length);
        for (float value : vector) {
            values.add((double) value);
        }
        return List.copyOf(values);
    }
}
