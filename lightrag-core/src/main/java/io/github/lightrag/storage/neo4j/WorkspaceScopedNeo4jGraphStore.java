package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class WorkspaceScopedNeo4jGraphStore implements GraphStore, AutoCloseable {
    private static final String ENTITY_LABEL = "Entity";
    private static final String RELATION_TYPE = "RELATION";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopedNeo4jGraphStore.class);

    private final Driver driver;
    private final boolean ownsDriver;
    private final SessionConfig sessionConfig;
    private final String workspaceId;

    public WorkspaceScopedNeo4jGraphStore(Neo4jGraphConfig config, WorkspaceScope scope) {
        this(
            createDriver(config),
            sessionConfig(config),
            scope,
            true
        );
    }

    public WorkspaceScopedNeo4jGraphStore(Driver driver, String database, WorkspaceScope scope) {
        this(
            driver,
            SessionConfig.forDatabase(requireNonBlank(database, "database")),
            scope,
            false
        );
    }

    private WorkspaceScopedNeo4jGraphStore(
        Driver driver,
        SessionConfig sessionConfig,
        WorkspaceScope scope,
        boolean ownsDriver
    ) {
        this.workspaceId = Objects.requireNonNull(scope, "scope").workspaceId();
        this.driver = Objects.requireNonNull(driver, "driver");
        this.sessionConfig = Objects.requireNonNull(sessionConfig, "sessionConfig");
        this.ownsDriver = ownsDriver;
        try {
            this.driver.verifyConnectivity();
            bootstrap();
        } catch (RuntimeException exception) {
            if (ownsDriver) {
                this.driver.close();
            }
            throw exception;
        }
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        write(tx -> {
            saveEntity(tx, record);
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        write(tx -> {
            saveRelation(tx, record);
            return null;
        });
    }

    @Override
    public void saveEntities(List<EntityRecord> entities) {
        var records = Objects.requireNonNull(entities, "entities");
        if (records.isEmpty()) {
            return;
        }
        write(tx -> {
            saveEntities(tx, records);
            return null;
        });
    }

    @Override
    public void saveRelations(List<RelationRecord> relations) {
        var records = Objects.requireNonNull(relations, "relations");
        if (records.isEmpty()) {
            return;
        }
        write(tx -> {
            saveRelations(tx, records);
            return null;
        });
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return read(tx -> single(
            tx.run(
                """
                MATCH (entity:%s {workspaceId: $workspaceId, id: $id})
                WHERE entity.materialized = true
                RETURN entity
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "id", id
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toEntity
        ));
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var id = Objects.requireNonNull(relationId, "relationId");
        return read(tx -> single(
            tx.run(
                """
                MATCH (source:%s {workspaceId: $workspaceId})-[relation:%s {workspaceId: $workspaceId, scopedId: $scopedRelationId}]->(target:%s {workspaceId: $workspaceId})
                RETURN source.id AS sourceEntityId, relation, target.id AS targetEntityId
                """.formatted(ENTITY_LABEL, RELATION_TYPE, ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedRelationId", scopedId(id)
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
    }

    @Override
    public List<EntityRecord> loadEntities(List<String> entityIds) {
        var ids = Objects.requireNonNull(entityIds, "entityIds");
        if (ids.isEmpty()) {
            return List.of();
        }
        var scopedIds = ids.stream()
            .map(this::scopedId)
            .toList();
        return read(tx -> list(
            tx.run(
                """
                UNWIND range(0, size($scopedEntityIds) - 1) AS idx
                WITH idx, $scopedEntityIds[idx] AS scopedEntityId
                OPTIONAL MATCH (entity:%s {workspaceId: $workspaceId, scopedId: scopedEntityId})
                WITH idx, entity
                WHERE entity IS NOT NULL AND entity.materialized = true
                RETURN idx, entity
                ORDER BY idx
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedEntityIds", scopedIds
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toEntity
        ));
    }

    @Override
    public List<RelationRecord> loadRelations(List<String> relationIds) {
        var ids = Objects.requireNonNull(relationIds, "relationIds");
        if (ids.isEmpty()) {
            return List.of();
        }
        var scopedIds = ids.stream()
            .map(this::scopedId)
            .toList();
        return read(tx -> list(
            tx.run(
                """
                UNWIND range(0, size($scopedRelationIds) - 1) AS idx
                WITH idx, $scopedRelationIds[idx] AS scopedRelationId
                OPTIONAL MATCH (source:%s {workspaceId: $workspaceId})-[relation:%s {workspaceId: $workspaceId, scopedId: scopedRelationId}]->(target:%s {workspaceId: $workspaceId})
                WITH idx, source, relation, target
                WHERE relation IS NOT NULL
                RETURN idx, source.id AS sourceEntityId, relation, target.id AS targetEntityId
                ORDER BY idx
                """.formatted(ENTITY_LABEL, RELATION_TYPE, ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedRelationIds", scopedIds
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
    }

    @Override
    public List<EntityRecord> allEntities() {
        return read(tx -> list(
            tx.run(
                """
                MATCH (entity:%s {workspaceId: $workspaceId})
                WHERE entity.materialized = true
                RETURN entity
                ORDER BY entity.id
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
            ),
            WorkspaceScopedNeo4jGraphStore::toEntity
        ));
    }

    @Override
    public List<RelationRecord> allRelations() {
        return read(tx -> list(
            tx.run(
                """
                MATCH (source:%s {workspaceId: $workspaceId})-[relation:%s {workspaceId: $workspaceId}]->(target:%s {workspaceId: $workspaceId})
                RETURN source.id AS sourceEntityId, relation, target.id AS targetEntityId
                ORDER BY relation.id
                """.formatted(ENTITY_LABEL, RELATION_TYPE, ENTITY_LABEL),
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return read(tx -> list(
            tx.run(
                """
                MATCH (entity:%s {workspaceId: $workspaceId, id: $id})-[relation:%s {workspaceId: $workspaceId}]-(adjacent:%s {workspaceId: $workspaceId})
                RETURN startNode(relation).id AS sourceEntityId, relation, endNode(relation).id AS targetEntityId
                ORDER BY relation.id
                """.formatted(ENTITY_LABEL, RELATION_TYPE, ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "id", id
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
    }

    public Neo4jGraphSnapshot captureSnapshot() {
        return new Neo4jGraphSnapshot(allEntities(), allRelations());
    }

    public void restore(Neo4jGraphSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        write(tx -> {
            tx.run(
                """
                MATCH (node:%s {workspaceId: $workspaceId})
                DETACH DELETE node
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
            );
            for (var entity : source.entities()) {
                saveEntity(tx, entity);
            }
            for (var relation : source.relations()) {
                saveRelation(tx, relation);
            }
            return null;
        });
    }

    @Override
    public void close() {
        if (ownsDriver) {
            driver.close();
        }
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    private static Driver createDriver(Neo4jGraphConfig config) {
        var source = Objects.requireNonNull(config, "config");
        return GraphDatabase.driver(
            source.boltUri(),
            AuthTokens.basic(source.username(), source.password())
        );
    }

    private static SessionConfig sessionConfig(Neo4jGraphConfig config) {
        return SessionConfig.forDatabase(Objects.requireNonNull(config, "config").database());
    }

    private void bootstrap() {
        write(tx -> {
            tx.run("DROP CONSTRAINT neo4j_entity_id IF EXISTS");
            tx.run("DROP CONSTRAINT neo4j_relation_id IF EXISTS");
            tx.run(
                """
                CREATE CONSTRAINT neo4j_entity_scoped_id IF NOT EXISTS
                FOR (entity:%s) REQUIRE entity.scopedId IS UNIQUE
                """.formatted(ENTITY_LABEL)
            );
            tx.run(
                """
                CREATE CONSTRAINT neo4j_relation_scoped_id IF NOT EXISTS
                FOR ()-[relation:%s]-() REQUIRE relation.scopedId IS UNIQUE
                """.formatted(RELATION_TYPE)
            );
            return null;
        });
    }

    private void saveEntity(TransactionContext tx, EntityRecord record) {
        tx.run(
            """
            MERGE (entity:%s {scopedId: $scopedEntityId})
            SET entity.workspaceId = $workspaceId,
                entity.id = $id,
                entity.name = $name,
                entity.type = $type,
                entity.description = $description,
                entity.aliases = $aliases,
                entity.sourceChunkIds = $sourceChunkIds,
                entity.materialized = true
            """.formatted(ENTITY_LABEL),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "scopedEntityId", scopedId(record.id()),
                "id", record.id(),
                "name", record.name(),
                "type", record.type(),
                "description", record.description(),
                "aliases", record.aliases(),
                "sourceChunkIds", record.sourceChunkIds()
            )
        );
    }

    private void saveEntities(TransactionContext tx, List<EntityRecord> records) {
        tx.run(
            """
            UNWIND range(0, size($rows) - 1) AS idx
            WITH idx, $rows[idx] AS row
            ORDER BY idx
            MERGE (entity:%s {scopedId: row.scopedEntityId})
            SET entity.workspaceId = $workspaceId,
                entity.id = row.id,
                entity.name = row.name,
                entity.type = row.type,
                entity.description = row.description,
                entity.aliases = row.aliases,
                entity.sourceChunkIds = row.sourceChunkIds,
                entity.materialized = true
            """.formatted(ENTITY_LABEL),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "rows", records.stream()
                    .map(record -> Map.<String, Object>of(
                        "scopedEntityId", scopedId(record.id()),
                        "id", record.id(),
                        "name", record.name(),
                        "type", record.type(),
                        "description", record.description(),
                        "aliases", record.aliases(),
                        "sourceChunkIds", record.sourceChunkIds()
                    ))
                    .toList()
            )
        );
    }

    private void saveRelation(TransactionContext tx, RelationRecord record) {
        tx.run(
            """
            MATCH ()-[relation:%s {workspaceId: $workspaceId, scopedId: $scopedRelationId}]-()
            DELETE relation
            """.formatted(RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "scopedRelationId", scopedId(record.id())
            )
        );
        tx.run(
            """
            MERGE (source:%s {scopedId: $scopedSourceEntityId})
            ON CREATE SET source.workspaceId = $workspaceId,
                          source.id = $sourceEntityId,
                          source.materialized = false,
                          source.name = '',
                          source.type = '',
                          source.description = '',
                          source.aliases = [],
                          source.sourceChunkIds = []
            MERGE (target:%s {scopedId: $scopedTargetEntityId})
            ON CREATE SET target.workspaceId = $workspaceId,
                          target.id = $targetEntityId,
                          target.materialized = false,
                          target.name = '',
                          target.type = '',
                          target.description = '',
                          target.aliases = [],
                          target.sourceChunkIds = []
            MERGE (source)-[relation:%s {scopedId: $scopedRelationId}]->(target)
            SET relation.workspaceId = $workspaceId,
                relation.id = $id,
                relation.type = $type,
                relation.description = $description,
                relation.weight = $weight,
                relation.sourceChunkIds = $sourceChunkIds
            """.formatted(ENTITY_LABEL, ENTITY_LABEL, RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "scopedRelationId", scopedId(record.id()),
                "id", record.id(),
                "sourceEntityId", record.sourceEntityId(),
                "targetEntityId", record.targetEntityId(),
                "scopedSourceEntityId", scopedId(record.sourceEntityId()),
                "scopedTargetEntityId", scopedId(record.targetEntityId()),
                "type", record.type(),
                "description", record.description(),
                "weight", record.weight(),
                "sourceChunkIds", record.sourceChunkIds()
            )
        );
    }

    private void saveRelations(TransactionContext tx, List<RelationRecord> records) {
        var rows = records.stream()
            .map(record -> Map.<String, Object>of(
                "scopedRelationId", scopedId(record.id()),
                "id", record.id(),
                "sourceEntityId", record.sourceEntityId(),
                "targetEntityId", record.targetEntityId(),
                "scopedSourceEntityId", scopedId(record.sourceEntityId()),
                "scopedTargetEntityId", scopedId(record.targetEntityId()),
                "type", record.type(),
                "description", record.description(),
                "weight", record.weight(),
                "sourceChunkIds", record.sourceChunkIds()
            ))
            .toList();
        var lastIndexByScopedRelationId = new HashMap<String, Integer>();
        for (int index = 0; index < rows.size(); index++) {
            lastIndexByScopedRelationId.put(rows.get(index).get("scopedRelationId").toString(), index);
        }
        var lastWriteRows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var scopedRelationId = row.get("scopedRelationId").toString();
            if (lastIndexByScopedRelationId.get(scopedRelationId) == index) {
                lastWriteRows.add(row);
            }
        }
        tx.run(
            """
            UNWIND range(0, size($allRows) - 1) AS idx
            WITH idx, $allRows[idx] AS row
            ORDER BY idx
            MERGE (source:%s {scopedId: row.scopedSourceEntityId})
            ON CREATE SET source.workspaceId = $workspaceId,
                          source.id = row.sourceEntityId,
                          source.materialized = false,
                          source.name = '',
                          source.type = '',
                          source.description = '',
                          source.aliases = [],
                          source.sourceChunkIds = []
            MERGE (target:%s {scopedId: row.scopedTargetEntityId})
            ON CREATE SET target.workspaceId = $workspaceId,
                          target.id = row.targetEntityId,
                          target.materialized = false,
                          target.name = '',
                          target.type = '',
                          target.description = '',
                          target.aliases = [],
                          target.sourceChunkIds = []
            """.formatted(ENTITY_LABEL, ENTITY_LABEL),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "allRows", rows
            )
        );
        tx.run(
            """
            UNWIND $lastRows AS row
            OPTIONAL MATCH ()-[relation:%s {workspaceId: $workspaceId, scopedId: row.scopedRelationId}]-()
            DELETE relation
            """.formatted(RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "lastRows", lastWriteRows
            )
        );
        tx.run(
            """
            UNWIND range(0, size($lastRows) - 1) AS idx
            WITH idx, $lastRows[idx] AS row
            ORDER BY idx
            MATCH (source:%s {workspaceId: $workspaceId, scopedId: row.scopedSourceEntityId})
            MATCH (target:%s {workspaceId: $workspaceId, scopedId: row.scopedTargetEntityId})
            MERGE (source)-[relation:%s {scopedId: row.scopedRelationId}]->(target)
            SET relation.workspaceId = $workspaceId,
                relation.id = row.id,
                relation.type = row.type,
                relation.description = row.description,
                relation.weight = row.weight,
                relation.sourceChunkIds = row.sourceChunkIds
            """.formatted(ENTITY_LABEL, ENTITY_LABEL, RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "lastRows", lastWriteRows
            )
        );
    }

    private String scopedId(String id) {
        return workspaceId + ":" + Objects.requireNonNull(id, "id");
    }

    private static EntityRecord toEntity(Record record) {
        var entity = record.get("entity");
        return new EntityRecord(
            entity.get("id").asString(),
            entity.get("name").asString(""),
            entity.get("type").asString(""),
            entity.get("description").asString(""),
            stringList(entity.get("aliases")),
            stringList(entity.get("sourceChunkIds"))
        );
    }

    private static RelationRecord toRelation(Record record) {
        var relation = record.get("relation");
        return new RelationRecord(
            relation.get("id").asString(),
            record.get("sourceEntityId").asString(),
            record.get("targetEntityId").asString(),
            relation.get("type").asString(""),
            relation.get("description").asString(""),
            relation.get("weight").asDouble(0.0d),
            stringList(relation.get("sourceChunkIds"))
        );
    }

    private static List<String> stringList(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        return value.asList(Value::asString);
    }

    private <T> T read(TransactionWork<T> work) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeRead(work::apply);
        } catch (RuntimeException exception) {
            throw new StorageException("Neo4j graph read failed", exception);
        }
    }

    private <T> T write(TransactionWork<T> work) {
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(work::apply);
        } catch (RuntimeException exception) {
            log.error("Neo4j graph write failed: workspaceId={}", workspaceId, exception);
            throw new StorageException("Neo4j graph write failed", exception);
        }
    }

    private static <T> Optional<T> single(Result result, java.util.function.Function<Record, T> mapper) {
        if (!result.hasNext()) {
            return Optional.empty();
        }
        return Optional.of(mapper.apply(result.next()));
    }

    private static <T> List<T> list(Result result, java.util.function.Function<Record, T> mapper) {
        var records = result.list(mapper::apply);
        return List.copyOf(records);
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T apply(TransactionContext transaction);
    }
}
