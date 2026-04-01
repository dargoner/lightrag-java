package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresGraphStore implements GraphStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresGraphStore.class);
    private final JdbcConnectionAccess connectionAccess;
    private final String entitiesTable;
    private final String entityAliasesTable;
    private final String entityChunksTable;
    private final String relationsTable;
    private final String relationChunksTable;
    private final String workspaceId;

    public PostgresGraphStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresGraphStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresGraphStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        var storageConfig = Objects.requireNonNull(config, "config");
        this.entitiesTable = storageConfig.qualifiedTableName("entities");
        this.entityAliasesTable = storageConfig.qualifiedTableName("entity_aliases");
        this.entityChunksTable = storageConfig.qualifiedTableName("entity_chunks");
        this.relationsTable = storageConfig.qualifiedTableName("relations");
        this.relationChunksTable = storageConfig.qualifiedTableName("relation_chunks");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        PostgresRetrySupport.execute("save entity '%s'".formatted(record.id()), () -> {
            long startedAtNanos = System.nanoTime();
            connectionAccess.withConnection(connection -> {
                inTransaction(connection, () -> {
                    upsertEntity(connection, record);
                    replaceEntityAliases(connection, record);
                    replaceEntityChunkIds(connection, record);
                });
                return null;
            });
            if (log.isDebugEnabled()) {
                log.debug(
                    "Saved PostgreSQL entity '{}' with {} aliases and {} chunk ids in {} ms",
                    record.id(),
                    record.aliases().size(),
                    record.sourceChunkIds().size(),
                    elapsedMillis(startedAtNanos)
                );
            }
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        PostgresRetrySupport.execute("save relation '%s'".formatted(record.id()), () -> {
            long startedAtNanos = System.nanoTime();
            connectionAccess.withConnection(connection -> {
                inTransaction(connection, () -> {
                    upsertRelation(connection, record);
                    replaceRelationChunkIds(connection, record);
                });
                return null;
            });
            if (log.isDebugEnabled()) {
                log.debug(
                    "Saved PostgreSQL relation '{}' with {} chunk ids in {} ms",
                    record.id(),
                    record.sourceChunkIds().size(),
                    elapsedMillis(startedAtNanos)
                );
            }
            return null;
        });
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return connectionAccess.withConnection(connection -> loadEntity(connection, id));
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var id = Objects.requireNonNull(relationId, "relationId");
        return connectionAccess.withConnection(connection -> loadRelation(connection, id));
    }

    @Override
    public List<EntityRecord> allEntities() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, name, type, description
                FROM %s
                WHERE workspace_id = ?
                ORDER BY id
                """.formatted(entitiesTable)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var entities = new java.util.ArrayList<EntityRecord>();
                    while (resultSet.next()) {
                        entities.add(readEntity(connection, resultSet));
                    }
                    return List.copyOf(entities);
                }
            }
        });
    }

    @Override
    public List<RelationRecord> allRelations() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, source_entity_id, target_entity_id, type, description, weight
                FROM %s
                WHERE workspace_id = ?
                ORDER BY id
                """.formatted(relationsTable)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var relations = new java.util.ArrayList<RelationRecord>();
                    while (resultSet.next()) {
                        relations.add(readRelation(connection, resultSet));
                    }
                    return List.copyOf(relations);
                }
            }
        });
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, source_entity_id, target_entity_id, type, description, weight
                FROM %s
                WHERE workspace_id = ?
                  AND (source_entity_id = ? OR target_entity_id = ?)
                ORDER BY id
                """.formatted(relationsTable)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                statement.setString(3, id);
                try (var resultSet = statement.executeQuery()) {
                    var relations = new java.util.ArrayList<RelationRecord>();
                    while (resultSet.next()) {
                        relations.add(readRelation(connection, resultSet));
                    }
                    return List.copyOf(relations);
                }
            }
        });
    }

    private Optional<EntityRecord> loadEntity(Connection connection, String entityId) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT id, name, type, description
            FROM %s
            WHERE workspace_id = ?
              AND id = ?
            """.formatted(entitiesTable)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, entityId);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEntity(connection, resultSet));
            }
        }
    }

    private Optional<RelationRecord> loadRelation(Connection connection, String relationId) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT id, source_entity_id, target_entity_id, type, description, weight
            FROM %s
            WHERE workspace_id = ?
              AND id = ?
            """.formatted(relationsTable)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, relationId);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRelation(connection, resultSet));
            }
        }
    }

    private EntityRecord readEntity(Connection connection, ResultSet resultSet) throws SQLException {
        var entityId = resultSet.getString("id");
        return new EntityRecord(
            entityId,
            resultSet.getString("name"),
            resultSet.getString("type"),
            resultSet.getString("description"),
            selectStringList(connection, entityAliasesTable, "entity_id", entityId, "alias"),
            selectStringList(connection, entityChunksTable, "entity_id", entityId, "chunk_id")
        );
    }

    private RelationRecord readRelation(Connection connection, ResultSet resultSet) throws SQLException {
        var relationId = resultSet.getString("id");
        return new RelationRecord(
            relationId,
            resultSet.getString("source_entity_id"),
            resultSet.getString("target_entity_id"),
            resultSet.getString("type"),
            resultSet.getString("description"),
            resultSet.getDouble("weight"),
            selectStringList(connection, relationChunksTable, "relation_id", relationId, "chunk_id")
        );
    }

    private List<String> selectStringList(
        Connection connection,
        String tableName,
        String idColumn,
        String idValue,
        String valueColumn
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT %s
            FROM %s
            WHERE workspace_id = ?
              AND %s = ?
            ORDER BY %s
            """.formatted(valueColumn, tableName, idColumn, valueColumn)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, idValue);
            try (var resultSet = statement.executeQuery()) {
                var values = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(valueColumn));
                }
                return List.copyOf(values);
            }
        }
    }

    private void upsertEntity(Connection connection, EntityRecord entity) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            INSERT INTO %s (workspace_id, id, name, type, description)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, id) DO UPDATE
            SET name = EXCLUDED.name,
                type = EXCLUDED.type,
                description = EXCLUDED.description
            """.formatted(entitiesTable)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, entity.id());
            statement.setString(3, entity.name());
            statement.setString(4, entity.type());
            statement.setString(5, entity.description());
            statement.executeUpdate();
        }
    }

    private void replaceEntityAliases(Connection connection, EntityRecord entity) throws SQLException {
        deleteById(connection, entityAliasesTable, "entity_id", entity.id());
        insertStringValues(connection, entityAliasesTable, "entity_id", entity.id(), "alias", entity.aliases());
    }

    private void replaceEntityChunkIds(Connection connection, EntityRecord entity) throws SQLException {
        deleteById(connection, entityChunksTable, "entity_id", entity.id());
        insertStringValues(connection, entityChunksTable, "entity_id", entity.id(), "chunk_id", entity.sourceChunkIds());
    }

    private void upsertRelation(Connection connection, RelationRecord relation) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            INSERT INTO %s (workspace_id, id, source_entity_id, target_entity_id, type, description, weight)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workspace_id, id) DO UPDATE
            SET source_entity_id = EXCLUDED.source_entity_id,
                target_entity_id = EXCLUDED.target_entity_id,
                type = EXCLUDED.type,
                description = EXCLUDED.description,
                weight = EXCLUDED.weight
            """.formatted(relationsTable)
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, relation.id());
            statement.setString(3, relation.sourceEntityId());
            statement.setString(4, relation.targetEntityId());
            statement.setString(5, relation.type());
            statement.setString(6, relation.description());
            statement.setDouble(7, relation.weight());
            statement.executeUpdate();
        }
    }

    private void replaceRelationChunkIds(Connection connection, RelationRecord relation) throws SQLException {
        deleteById(connection, relationChunksTable, "relation_id", relation.id());
        insertStringValues(connection, relationChunksTable, "relation_id", relation.id(), "chunk_id", relation.sourceChunkIds());
    }

    private void deleteById(Connection connection, String tableName, String idColumn, String idValue) throws SQLException {
        try (var statement = connection.prepareStatement(
            "DELETE FROM " + tableName + " WHERE workspace_id = ? AND " + idColumn + " = ?"
        )) {
            statement.setString(1, workspaceId);
            statement.setString(2, idValue);
            statement.executeUpdate();
        }
    }

    private void insertStringValues(
        Connection connection,
        String tableName,
        String idColumn,
        String idValue,
        String valueColumn,
        List<String> values
    ) throws SQLException {
        if (values.isEmpty()) {
            return;
        }
        try (var statement = connection.prepareStatement(
            "INSERT INTO " + tableName + " (workspace_id, " + idColumn + ", " + valueColumn + ") VALUES (?, ?, ?)"
        )) {
            for (var value : values) {
                statement.setString(1, workspaceId);
                statement.setString(2, idValue);
                statement.setString(3, value);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void inTransaction(Connection connection, SqlWork work) {
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
            try {
                work.execute();
                if (originalAutoCommit) {
                    connection.commit();
                }
            } catch (SQLException exception) {
                if (originalAutoCommit) {
                    connection.rollback();
                }
                throw new StorageException("Graph store write failed", exception);
            } finally {
                if (originalAutoCommit && connection.getAutoCommit() != originalAutoCommit) {
                    connection.setAutoCommit(originalAutoCommit);
                }
            }
        } catch (SQLException exception) {
            throw new StorageException("Graph store write failed", exception);
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    @FunctionalInterface
    private interface SqlWork {
        void execute() throws SQLException;
    }
}
