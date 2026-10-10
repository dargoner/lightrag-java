package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.GraphViewTraversal;
import io.github.lightrag.storage.MutableGraphStore;
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
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Workspace-scoped graph store on a Bolt-compatible Cypher engine: every read and write is pushed
 * into the database with the workspace id attached to each node and relationship.
 *
 * <p>Subclasses may retarget the bootstrap DDL for engines with a narrower dialect (see
 * {@link #bootstrapStatements()}); all workspace-scoped statements are shared as-is.</p>
 */
public class WorkspaceScopedNeo4jGraphStore implements MutableGraphStore, AutoCloseable {
    private static final String ENTITY_LABEL = "Entity";
    private static final String RELATION_TYPE = "RELATION";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceScopedNeo4jGraphStore.class);

    private final Driver driver;
    private final boolean ownsDriver;
    private final SessionConfig sessionConfig;
    private final List<String> workspaceIds;
    private final String workspaceId;

    public WorkspaceScopedNeo4jGraphStore(Neo4jGraphConfig config, WorkspaceScope scope) {
        this(
            createDriver(config),
            sessionConfig(config),
            List.of(Objects.requireNonNull(scope, "scope").workspaceId()),
            true
        );
    }

    public WorkspaceScopedNeo4jGraphStore(Driver driver, String database, WorkspaceScope scope) {
        this(
            driver,
            SessionConfig.forDatabase(requireNonBlank(database, "database")),
            List.of(Objects.requireNonNull(scope, "scope").workspaceId()),
            false
        );
    }

    /**
     * Workspace-set constructor: reads over the query surface dispatch to IN-batched statements that
     * cover every workspace in one round trip; writes and single-target helpers (snapshot, delete,
     * Cypher escape hatch, graph view) require a single workspace and fail loudly on a
     * multi-workspace store instead of silently addressing the first one.
     */
    public WorkspaceScopedNeo4jGraphStore(Driver driver, String database, List<String> workspaceIds) {
        this(
            driver,
            SessionConfig.forDatabase(requireNonBlank(database, "database")),
            workspaceIds,
            false
        );
    }

    protected WorkspaceScopedNeo4jGraphStore(
        Driver driver,
        SessionConfig sessionConfig,
        WorkspaceScope scope,
        boolean ownsDriver
    ) {
        this(
            driver,
            sessionConfig,
            List.of(Objects.requireNonNull(scope, "scope").workspaceId()),
            ownsDriver
        );
    }

    /**
     * Extension constructor for subclass stores: {@code ownsDriver} controls whether {@link #close()}
     * shuts the driver down.
     */
    protected WorkspaceScopedNeo4jGraphStore(
        Driver driver,
        SessionConfig sessionConfig,
        List<String> workspaceIds,
        boolean ownsDriver
    ) {
        var normalized = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(workspaceIds, "workspaceIds")));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("workspaceIds must not be empty");
        }
        this.workspaceIds = normalized;
        this.workspaceId = normalized.get(0);
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

    private boolean isMultiWorkspace() {
        return workspaceIds.size() > 1;
    }

    /**
     * Guard for statements that carry a single workspace in the pattern (scoped ids, row deletes,
     * snapshot restore, Cypher escape hatch): a workspace-set store must not silently target its
     * first workspace.
     */
    private void requireSingleWorkspace(String operation) {
        if (isMultiWorkspace()) {
            throw new IllegalStateException(
                operation + " requires a single-workspace graph store; this store covers "
                    + workspaceIds.size() + " workspaces");
        }
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        requireSingleWorkspace("saveEntity");
        var record = Objects.requireNonNull(entity, "entity");
        write(tx -> {
            saveEntity(tx, record);
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        requireSingleWorkspace("saveRelation");
        var record = Objects.requireNonNull(relation, "relation");
        write(tx -> {
            saveRelation(tx, record);
            return null;
        });
    }

    @Override
    public void saveEntities(List<EntityRecord> entities) {
        requireSingleWorkspace("saveEntities");
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
        requireSingleWorkspace("saveRelations");
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
        if (isMultiWorkspace()) {
            // The same logical id may exist in several workspaces: one IN-batched statement reads
            // all candidates and the smallest workspace id wins deterministically.
            return read(tx -> single(
                tx.run(
                    """
                    MATCH (entity:%s {id: $id})
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.materialized = true
                    RETURN entity
                    ORDER BY entity.workspaceId
                    LIMIT 1
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "id", id
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toEntity
            ));
        }
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
        if (isMultiWorkspace()) {
            return read(tx -> single(
                tx.run(
                    """
                    MATCH ()-[relation:%s {relation_id: $relationId}]->()
                    WHERE relation.workspaceId IN $workspaceIds
                    RETURN relation
                    ORDER BY relation.workspaceId
                    LIMIT 1
                    """.formatted(RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "relationId", id
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toRelation
            ));
        }
        return read(tx -> single(
            tx.run(
                """
                MATCH ()-[relation:%s {workspaceId: $workspaceId, scopedId: $scopedRelationId}]->()
                RETURN relation
                """.formatted(RELATION_TYPE),
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
        if (isMultiWorkspace()) {
            var startedAt = System.nanoTime();
            var records = read(tx -> list(
                tx.run(
                    """
                    MATCH (entity:%s)
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.id IN $entityIds
                      AND entity.materialized = true
                    RETURN entity
                    ORDER BY entity.workspaceId, entity.id
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "entityIds", ids
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toEntity
            ));
            var deduplicated = dedupeById(records, EntityRecord::id);
            log.info(
                "Neo4j graph batch loadEntities completed: workspaceCount={}, requestedCount={}, returnedCount={}, elapsedMs={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                ids.size(),
                deduplicated.size(),
                elapsedMillis(startedAt)
            );
            if (log.isDebugEnabled()) {
                log.debug("Neo4j graph batch loadEntities workspaces: {}", workspaceIds);
            }
            return deduplicated;
        }
        var startedAt = System.nanoTime();
        var scopedIds = ids.stream()
            .map(this::scopedId)
            .toList();
        var records = read(tx -> list(
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
        log.info(
            "Neo4j graph loadEntities completed: workspaceId={}, requestedCount={}, returnedCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            records.size(),
            elapsedMillis(startedAt)
        );
        return records;
    }

    @Override
    public List<RelationRecord> loadRelations(List<String> relationIds) {
        var ids = Objects.requireNonNull(relationIds, "relationIds");
        if (ids.isEmpty()) {
            return List.of();
        }
        if (isMultiWorkspace()) {
            var startedAt = System.nanoTime();
            var records = read(tx -> list(
                tx.run(
                    """
                    MATCH ()-[relation:%s]->()
                    WHERE relation.workspaceId IN $workspaceIds
                      AND relation.relation_id IN $relationIds
                    RETURN relation
                    ORDER BY relation.workspaceId, relation.relation_id
                    """.formatted(RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "relationIds", ids
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toRelation
            ));
            var deduplicated = dedupeById(records, RelationRecord::id);
            log.info(
                "Neo4j graph batch loadRelations completed: workspaceCount={}, requestedCount={}, returnedCount={}, elapsedMs={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                ids.size(),
                deduplicated.size(),
                elapsedMillis(startedAt)
            );
            if (log.isDebugEnabled()) {
                log.debug("Neo4j graph batch loadRelations workspaces: {}", workspaceIds);
            }
            return deduplicated;
        }
        var startedAt = System.nanoTime();
        var scopedIds = ids.stream()
            .map(this::scopedId)
            .toList();
        var records = read(tx -> list(
            tx.run(
                """
                UNWIND range(0, size($scopedRelationIds) - 1) AS idx
                WITH idx, $scopedRelationIds[idx] AS scopedRelationId
                OPTIONAL MATCH ()-[relation:%s {workspaceId: $workspaceId, scopedId: scopedRelationId}]->()
                WITH idx, relation
                WHERE relation IS NOT NULL
                RETURN idx, relation
                ORDER BY idx
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedRelationIds", scopedIds
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
        log.info(
            "Neo4j graph loadRelations completed: workspaceId={}, requestedCount={}, returnedCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            records.size(),
            elapsedMillis(startedAt)
        );
        return records;
    }

    @Override
    public List<EntityRecord> allEntities() {
        if (isMultiWorkspace()) {
            return read(tx -> list(
                tx.run(
                    """
                    MATCH (entity:%s)
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.materialized = true
                    RETURN entity
                    ORDER BY entity.workspaceId, entity.id
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters("workspaceIds", workspaceIds)
                ),
                WorkspaceScopedNeo4jGraphStore::toEntity
            ));
        }
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

    /**
     * Store-native text search over the four candidate fields, mirroring the default
     * {@link GraphStore#searchEntitiesByText} contract with the match pushed into Cypher.
     */
    @Override
    public List<EntityRecord> searchEntitiesByText(String query) {
        var needle = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        if (isMultiWorkspace()) {
            var records = read(tx -> list(
                tx.run(
                    """
                    MATCH (entity:%s)
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.materialized = true
                      AND (
                        toLower(coalesce(entity.name, '')) CONTAINS $query
                        OR toLower(coalesce(entity.type, '')) CONTAINS $query
                        OR toLower(coalesce(entity.description, '')) CONTAINS $query
                        OR any(alias IN coalesce(entity.aliases, []) WHERE toLower(alias) CONTAINS $query)
                      )
                    RETURN entity
                    ORDER BY entity.workspaceId, entity.id
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "query", needle
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toEntity
            ));
            return dedupeById(records, EntityRecord::id);
        }
        return read(tx -> list(
            tx.run(
                """
                MATCH (entity:%s {workspaceId: $workspaceId})
                WHERE entity.materialized = true
                  AND (
                    toLower(coalesce(entity.name, '')) CONTAINS $query
                    OR toLower(coalesce(entity.type, '')) CONTAINS $query
                    OR toLower(coalesce(entity.description, '')) CONTAINS $query
                    OR any(alias IN coalesce(entity.aliases, []) WHERE toLower(alias) CONTAINS $query)
                  )
                RETURN entity
                ORDER BY entity.id
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "query", needle
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toEntity
        ));
    }

    @Override
    public List<RelationRecord> allRelations() {
        if (isMultiWorkspace()) {
            return read(tx -> list(
                tx.run(
                    """
                    MATCH ()-[relation:%s]->()
                    WHERE relation.workspaceId IN $workspaceIds
                    RETURN relation
                    ORDER BY relation.workspaceId, relation.relation_id
                    """.formatted(RELATION_TYPE),
                    org.neo4j.driver.Values.parameters("workspaceIds", workspaceIds)
                ),
                WorkspaceScopedNeo4jGraphStore::toRelation
            ));
        }
        return read(tx -> list(
            tx.run(
                """
                MATCH ()-[relation:%s {workspaceId: $workspaceId}]->()
                RETURN relation
                ORDER BY relation.relation_id
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        if (isMultiWorkspace()) {
            var startedAt = System.nanoTime();
            var records = read(tx -> list(
                tx.run(
                    """
                    MATCH (entity:%s {id: $entityId})-[relation:%s]-()
                    WHERE entity.workspaceId IN $workspaceIds
                      AND relation.workspaceId = entity.workspaceId
                    RETURN relation
                    ORDER BY relation.workspaceId, relation.relation_id
                    """.formatted(ENTITY_LABEL, RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "entityId", id
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toRelation
            ));
            var deduplicated = dedupeById(records, RelationRecord::id);
            log.info(
                "Neo4j graph batch findRelations completed: workspaceCount={}, entityId={}, relationCount={}, elapsedMs={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                id,
                deduplicated.size(),
                elapsedMillis(startedAt)
            );
            if (log.isDebugEnabled()) {
                log.debug("Neo4j graph batch findRelations workspaces: {}", workspaceIds);
            }
            return deduplicated;
        }
        var scopedEntityId = scopedId(id);
        var startedAt = System.nanoTime();
        var records = read(tx -> list(
            tx.run(
                """
                MATCH (:Entity {workspaceId: $workspaceId, scopedId: $scopedEntityId})-[relation:%s {workspaceId: $workspaceId}]-()
                RETURN relation
                ORDER BY relation.relation_id
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedEntityId", scopedEntityId
                )
            ),
            WorkspaceScopedNeo4jGraphStore::toRelation
        ));
        log.info(
            "Neo4j graph findRelations completed: workspaceId={}, entityId={}, relationCount={}, elapsedMs={}",
            workspaceId,
            id,
            records.size(),
            elapsedMillis(startedAt)
        );
        return records;
    }

    @Override
    public Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        if (isMultiWorkspace()) {
            var startedAt = System.nanoTime();
            var relationsMap = read(tx -> {
                var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
                for (var entityId : ids) {
                    relationsByEntityId.put(entityId, new ArrayList<>());
                }
                var seenRelationIds = new LinkedHashMap<String, Set<String>>();
                var queryResult = tx.run(
                    """
                    MATCH (entity:%s)-[relation:%s]-()
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.id IN $entityIds
                      AND relation.workspaceId = entity.workspaceId
                    RETURN entity.id AS entityId, relation
                    ORDER BY entityId, relation.workspaceId, relation.relation_id
                    """.formatted(ENTITY_LABEL, RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "entityIds", ids
                    )
                );
                while (queryResult.hasNext()) {
                    var record = queryResult.next();
                    var entityId = record.get("entityId").asString();
                    var relation = toRelation(record);
                    if (seenRelationIds
                        .computeIfAbsent(entityId, ignored -> new LinkedHashSet<>())
                        .add(relation.id())) {
                        relationsByEntityId.get(entityId).add(relation);
                    }
                }
                var immutable = new LinkedHashMap<String, List<RelationRecord>>();
                relationsByEntityId.forEach((entityId, relations) -> immutable.put(entityId, List.copyOf(relations)));
                return Collections.unmodifiableMap(immutable);
            });
            log.info(
                "Neo4j graph batch findRelations completed: workspaceCount={}, requestedCount={}, totalRelationCount={}, elapsedMs={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                ids.size(),
                relationsMap.values().stream().mapToInt(List::size).sum(),
                elapsedMillis(startedAt)
            );
            if (log.isDebugEnabled()) {
                log.debug("Neo4j graph batch findRelations workspaces: {}", workspaceIds);
            }
            return relationsMap;
        }
        var startedAt = System.nanoTime();
        var scopedIds = ids.stream()
            .map(this::scopedId)
            .toList();
        var relationsMap = read(tx -> {
            var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
            for (var entityId : ids) {
                relationsByEntityId.put(entityId, new ArrayList<>());
            }
            var queryResult = tx.run(
                """
                UNWIND range(0, size($entityIds) - 1) AS idx
                WITH idx, $entityIds[idx] AS entityId, $scopedEntityIds[idx] AS scopedEntityId
                OPTIONAL MATCH (:Entity {workspaceId: $workspaceId, scopedId: scopedEntityId})-[relation:%s {workspaceId: $workspaceId}]-()
                WITH entityId, relation
                WHERE relation IS NOT NULL
                RETURN entityId, relation
                ORDER BY entityId, relation.relation_id
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "entityIds", ids,
                    "scopedEntityIds", scopedIds
                )
            );
            while (queryResult.hasNext()) {
                var record = queryResult.next();
                relationsByEntityId.get(record.get("entityId").asString()).add(toRelation(record));
            }
            var immutable = new LinkedHashMap<String, List<RelationRecord>>();
            relationsByEntityId.forEach((entityId, relations) -> immutable.put(entityId, List.copyOf(relations)));
            return Collections.unmodifiableMap(immutable);
        });
        log.info(
            "Neo4j graph batch findRelations completed: workspaceId={}, requestedCount={}, totalRelationCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            relationsMap.values().stream().mapToInt(List::size).sum(),
            elapsedMillis(startedAt)
        );
        return relationsMap;
    }

    /**
     * Native degree count: the same contract as the {@link GraphStore#degrees(Collection)} default
     * (every distinct requested id gets one entry - repeated ids collapse to their first position -
     * zero-filled when unknown, in first-occurrence order, unmodifiable), answered with one grouped
     * count instead of materializing every incident relation.
     *
     * <p>Workspace-set stores count distinct relation ids per logical entity id, which mirrors the
     * first-win dedup {@link #findRelations(List)} applies across workspaces; the single-workspace
     * path applies no dedup and therefore counts matched relationships directly.</p>
     */
    @Override
    public Map<String, Integer> degrees(Collection<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        var distinctIds = List.copyOf(new LinkedHashSet<>(ids));
        var startedAt = System.nanoTime();
        Map<String, Integer> degrees;
        try {
            degrees = nativeDegrees(distinctIds);
        } catch (RuntimeException exception) {
            // Native counting failed (unsupported query or server error): fall back to the
            // GraphStore.degrees default semantics - materialize relations per id and count them,
            // so callers still get results consistent with findRelations.
            log.warn(
                "Neo4j graph native degrees failed; falling back to relation materialization: workspaceCount={}, requestedCount={}, cause={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                distinctIds.size(),
                exception.toString()
            );
            var relationsByEntityId = findRelations(distinctIds);
            var fallback = new LinkedHashMap<String, Integer>();
            for (var entityId : distinctIds) {
                fallback.put(entityId, relationsByEntityId.getOrDefault(entityId, List.of()).size());
            }
            degrees = Collections.unmodifiableMap(fallback);
        }
        log.info(
            "Neo4j graph degrees completed: workspaceCount={}, requestedCount={}, matchedCount={}, elapsedMs={}",
            workspaceIds == null ? 0 : workspaceIds.size(),
            distinctIds.size(),
            degrees.values().stream().filter(degree -> degree > 0).count(),
            elapsedMillis(startedAt)
        );
        if (log.isDebugEnabled()) {
            log.debug("Neo4j graph degrees workspaces: {}", workspaceIds);
        }
        return degrees;
    }

    private Map<String, Integer> nativeDegrees(List<String> distinctIds) {
        return read(tx -> {
            var counts = new LinkedHashMap<String, Integer>();
            for (var entityId : distinctIds) {
                counts.put(entityId, 0);
            }
            var queryResult = isMultiWorkspace()
                ? tx.run(
                    """
                    MATCH (entity:%s)-[relation:%s]-()
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.id IN $entityIds
                      AND relation.workspaceId = entity.workspaceId
                    RETURN entity.id AS entityId, count(DISTINCT relation.relation_id) AS degree
                    """.formatted(ENTITY_LABEL, RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "entityIds", distinctIds
                    )
                )
                : tx.run(
                    """
                    MATCH (entity:%s {workspaceId: $workspaceId})-[relation:%s {workspaceId: $workspaceId}]-()
                    WHERE entity.scopedId IN $scopedEntityIds
                    RETURN entity.id AS entityId, count(relation) AS degree
                    """.formatted(ENTITY_LABEL, RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "scopedEntityIds", distinctIds.stream().map(this::scopedId).toList()
                    )
                );
            while (queryResult.hasNext()) {
                var record = queryResult.next();
                counts.put(record.get("entityId").asString(), record.get("degree").asInt());
            }
            return Collections.unmodifiableMap(counts);
        });
    }

    /**
     * Narrow projection of {@link #findRelations(List)}: the same per-id structure and ordering
     * (workspace-set stores de-duplicate by relation id with the first workspace winning; the
     * single-workspace path applies no dedup), but only relation id, source and target cross the
     * wire, so score-propagation callers skip materializing full relation records. Like
     * {@link #degrees}, a repeated id is looked up once instead of once per input slot.
     */
    @Override
    public Map<String, List<RelationEndpointRecord>> findRelationEndpoints(List<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        var distinctIds = List.copyOf(new LinkedHashSet<>(ids));
        if (isMultiWorkspace()) {
            var startedAt = System.nanoTime();
            var endpointsMap = read(tx -> {
                var endpointsByEntityId = new LinkedHashMap<String, List<RelationEndpointRecord>>();
                for (var entityId : ids) {
                    endpointsByEntityId.put(entityId, new ArrayList<>());
                }
                var seenRelationIds = new LinkedHashMap<String, Set<String>>();
                var queryResult = tx.run(
                    """
                    MATCH (entity:%s)-[relation:%s]-()
                    WHERE entity.workspaceId IN $workspaceIds
                      AND entity.id IN $entityIds
                      AND relation.workspaceId = entity.workspaceId
                    RETURN entity.id AS entityId, relation.relation_id AS relationId, relation.src_id AS srcId, relation.tgt_id AS tgtId
                    ORDER BY entityId, relation.workspaceId, relation.relation_id
                    """.formatted(ENTITY_LABEL, RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceIds", workspaceIds,
                        "entityIds", distinctIds
                    )
                );
                while (queryResult.hasNext()) {
                    var record = queryResult.next();
                    var entityId = record.get("entityId").asString();
                    if (seenRelationIds
                        .computeIfAbsent(entityId, ignored -> new LinkedHashSet<>())
                        .add(record.get("relationId").asString())) {
                        endpointsByEntityId.get(entityId).add(new RelationEndpointRecord(
                            record.get("relationId").asString(),
                            record.get("srcId").asString(),
                            record.get("tgtId").asString()
                        ));
                    }
                }
                var immutable = new LinkedHashMap<String, List<RelationEndpointRecord>>();
                endpointsByEntityId.forEach((entityId, endpoints) -> immutable.put(entityId, List.copyOf(endpoints)));
                return Collections.unmodifiableMap(immutable);
            });
            log.info(
                "Neo4j graph findRelationEndpoints completed: workspaceCount={}, requestedCount={}, totalRelationCount={}, elapsedMs={}",
                workspaceIds == null ? 0 : workspaceIds.size(),
                ids.size(),
                endpointsMap.values().stream().mapToInt(List::size).sum(),
                elapsedMillis(startedAt)
            );
            if (log.isDebugEnabled()) {
                log.debug("Neo4j graph findRelationEndpoints workspaces: {}", workspaceIds);
            }
            return endpointsMap;
        }
        var startedAt = System.nanoTime();
        var scopedIds = distinctIds.stream()
            .map(this::scopedId)
            .toList();
        var endpointsMap = read(tx -> {
            var endpointsByEntityId = new LinkedHashMap<String, List<RelationEndpointRecord>>();
            for (var entityId : ids) {
                endpointsByEntityId.put(entityId, new ArrayList<>());
            }
            var queryResult = tx.run(
                """
                UNWIND range(0, size($entityIds) - 1) AS idx
                WITH idx, $entityIds[idx] AS entityId, $scopedEntityIds[idx] AS scopedEntityId
                OPTIONAL MATCH (:Entity {workspaceId: $workspaceId, scopedId: scopedEntityId})-[relation:%s {workspaceId: $workspaceId}]-()
                WITH entityId, relation
                WHERE relation IS NOT NULL
                RETURN entityId, relation.relation_id AS relationId, relation.src_id AS srcId, relation.tgt_id AS tgtId
                ORDER BY entityId, relation.relation_id
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "entityIds", distinctIds,
                    "scopedEntityIds", scopedIds
                )
            );
            while (queryResult.hasNext()) {
                var record = queryResult.next();
                endpointsByEntityId.get(record.get("entityId").asString()).add(new RelationEndpointRecord(
                    record.get("relationId").asString(),
                    record.get("srcId").asString(),
                    record.get("tgtId").asString()
                ));
            }
            var immutable = new LinkedHashMap<String, List<RelationEndpointRecord>>();
            endpointsByEntityId.forEach((entityId, endpoints) -> immutable.put(entityId, List.copyOf(endpoints)));
            return Collections.unmodifiableMap(immutable);
        });
        log.info(
            "Neo4j graph findRelationEndpoints completed: workspaceId={}, requestedCount={}, totalRelationCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            endpointsMap.values().stream().mapToInt(List::size).sum(),
            elapsedMillis(startedAt)
        );
        return endpointsMap;
    }

    @Override
    public KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) {
        requireSingleWorkspace("getKnowledgeGraph");
        return GraphViewTraversal.compute(new Neo4jGraphViewSupport(), nodeLabel, maxDepth, maxNodes);
    }

    private final class Neo4jGraphViewSupport implements GraphViewTraversal.Support {
        @Override
        public boolean containsEntity(String id) {
            return read(tx -> single(
                tx.run(
                    """
                    MATCH (entity:%s {workspaceId: $workspaceId, scopedId: $scopedEntityId})
                    WHERE entity.materialized = true
                    RETURN entity.id AS id
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "scopedEntityId", scopedId(id)
                    )
                ),
                record -> record.get("id").asString()
            )).isPresent();
        }

        @Override
        public List<String> rankedEntityIds(int limit) {
            var ids = read(tx -> list(
                tx.run(
                    """
                    MATCH (entity:%s {workspaceId: $workspaceId})
                    WHERE entity.materialized = true
                    RETURN entity.id AS id
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
                ),
                record -> record.get("id").asString()
            ));
            if (ids.isEmpty()) {
                return List.of();
            }
            var degrees = endpointDegrees(ids);
            var ranked = new ArrayList<>(ids);
            ranked.sort(Comparator
                .comparingInt((String id) -> degrees.getOrDefault(id, 0))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
            return List.copyOf(ranked.subList(0, Math.min(limit, ranked.size())));
        }

        @Override
        public Map<String, Integer> degrees(Collection<String> ids) {
            var requested = List.copyOf(ids);
            if (requested.isEmpty()) {
                return Map.of();
            }
            return endpointDegrees(requested);
        }

        @Override
        public Map<String, Set<String>> adjacency(Collection<String> ids) {
            var requested = List.copyOf(ids);
            if (requested.isEmpty()) {
                return Map.of();
            }
            var requestedIds = new LinkedHashSet<>(requested);
            return read(tx -> {
                var adjacency = new LinkedHashMap<String, Set<String>>();
                var result = tx.run(
                    """
                    MATCH ()-[relation:%s {workspaceId: $workspaceId}]->()
                    WHERE relation.src_id IN $entityIds OR relation.tgt_id IN $entityIds
                    RETURN relation.src_id AS srcId, relation.tgt_id AS tgtId
                    """.formatted(RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "entityIds", requested
                    )
                );
                while (result.hasNext()) {
                    var record = result.next();
                    var srcId = record.get("srcId").asString();
                    var tgtId = record.get("tgtId").asString();
                    if (requestedIds.contains(srcId)) {
                        adjacency.computeIfAbsent(srcId, ignored -> new LinkedHashSet<>()).add(tgtId);
                    }
                    if (requestedIds.contains(tgtId)) {
                        adjacency.computeIfAbsent(tgtId, ignored -> new LinkedHashSet<>()).add(srcId);
                    }
                }
                return adjacency;
            });
        }

        @Override
        public Map<String, EntityRecord> entities(Collection<String> ids) {
            var requested = List.copyOf(ids);
            if (requested.isEmpty()) {
                return Map.of();
            }
            var scopedIds = requested.stream().map(WorkspaceScopedNeo4jGraphStore.this::scopedId).toList();
            return read(tx -> {
                var entitiesById = new LinkedHashMap<String, EntityRecord>();
                var result = tx.run(
                    """
                    MATCH (entity:%s {workspaceId: $workspaceId})
                    WHERE entity.materialized = true AND entity.scopedId IN $scopedEntityIds
                    RETURN entity
                    """.formatted(ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "scopedEntityIds", scopedIds
                    )
                );
                while (result.hasNext()) {
                    var entity = toEntity(result.next());
                    entitiesById.put(entity.id(), entity);
                }
                return entitiesById;
            });
        }

        @Override
        public List<RelationRecord> relationsWithin(Set<String> included) {
            if (included.isEmpty()) {
                return List.of();
            }
            var scopedIds = included.stream().map(WorkspaceScopedNeo4jGraphStore.this::scopedId).toList();
            return read(tx -> list(
                tx.run(
                    """
                    MATCH (source:%s)-[relation:%s {workspaceId: $workspaceId}]->(target:%s)
                    WHERE source.scopedId IN $scopedEntityIds AND target.scopedId IN $scopedEntityIds
                    RETURN relation
                    ORDER BY relation.relation_id
                    """.formatted(ENTITY_LABEL, RELATION_TYPE, ENTITY_LABEL),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "scopedEntityIds", scopedIds
                    )
                ),
                WorkspaceScopedNeo4jGraphStore::toRelation
            ));
        }

        private Map<String, Integer> endpointDegrees(List<String> ids) {
            return read(tx -> {
                var degrees = new LinkedHashMap<String, Integer>();
                var result = tx.run(
                    """
                    MATCH ()-[relation:%s {workspaceId: $workspaceId}]->()
                    WHERE relation.src_id IN $entityIds OR relation.tgt_id IN $entityIds
                    UNWIND [relation.src_id, relation.tgt_id] AS endpoint
                    WITH endpoint
                    WHERE endpoint IN $entityIds
                    RETURN endpoint, count(*) AS degree
                    """.formatted(RELATION_TYPE),
                    org.neo4j.driver.Values.parameters(
                        "workspaceId", workspaceId,
                        "entityIds", ids
                    )
                );
                while (result.hasNext()) {
                    var record = result.next();
                    degrees.put(record.get("endpoint").asString(), record.get("degree").asInt(0));
                }
                return degrees;
            });
        }
    }

    @Override
    public int deleteEntities(List<String> entityIds) {
        requireSingleWorkspace("deleteEntities");
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return 0;
        }
        var startedAt = System.nanoTime();
        var scopedIds = ids.stream().map(this::scopedId).toList();
        var deleted = write(tx -> {
            var result = tx.run(
                """
                UNWIND $scopedEntityIds AS scopedEntityId
                MATCH (entity:%s {scopedId: scopedEntityId})
                WHERE entity.workspaceId = $workspaceId
                WITH collect(entity) AS entities, count(entity) AS deletedCount
                FOREACH (entity IN entities | DETACH DELETE entity)
                RETURN deletedCount
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedEntityIds", scopedIds
                )
            );
            return result.hasNext() ? result.next().get("deletedCount").asInt(0) : 0;
        });
        log.info(
            "Neo4j graph deleteEntities completed: workspaceId={}, requestedCount={}, deletedCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            deleted,
            elapsedMillis(startedAt)
        );
        return deleted;
    }

    @Override
    public int deleteRelations(List<String> relationIds) {
        requireSingleWorkspace("deleteRelations");
        var ids = List.copyOf(Objects.requireNonNull(relationIds, "relationIds"));
        if (ids.isEmpty()) {
            return 0;
        }
        var startedAt = System.nanoTime();
        var scopedIds = ids.stream().map(this::scopedId).toList();
        var deleted = write(tx -> {
            // DISTINCT guards engines that expand a fully-unbound undirected pattern into one row
            // per direction: the same relationship must still be deleted and counted once.
            var result = tx.run(
                """
                UNWIND $scopedRelationIds AS scopedRelationId
                MATCH ()-[relation:%s {scopedId: scopedRelationId}]-()
                WHERE relation.workspaceId = $workspaceId
                WITH collect(DISTINCT relation) AS relations, count(DISTINCT relation) AS deletedCount
                FOREACH (relation IN relations | DELETE relation)
                RETURN deletedCount
                """.formatted(RELATION_TYPE),
                org.neo4j.driver.Values.parameters(
                    "workspaceId", workspaceId,
                    "scopedRelationIds", scopedIds
                )
            );
            return result.hasNext() ? result.next().get("deletedCount").asInt(0) : 0;
        });
        log.info(
            "Neo4j graph deleteRelations completed: workspaceId={}, requestedCount={}, deletedCount={}, elapsedMs={}",
            workspaceId,
            ids.size(),
            deleted,
            elapsedMillis(startedAt)
        );
        return deleted;
    }

    public Neo4jGraphSnapshot captureSnapshot() {
        requireSingleWorkspace("captureSnapshot");
        return new Neo4jGraphSnapshot(allEntities(), allRelations());
    }

    public void restore(Neo4jGraphSnapshot snapshot) {
        requireSingleWorkspace("restore");
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

    /**
     * Executes one caller-supplied statement through an auto-commit session, mirroring the
     * driver-level executor: the caller owns the statement kind (read or write) and its workspace
     * scoping. Values convert to plain Java structures - graph elements keep their structural form
     * as unmodifiable maps (see {@link #toPlainValue}) - and a statement without RETURN yields no
     * columns and no records.
     */
    @Override
    public CypherQueryResult executeCypher(String cypher, Map<String, Object> parameters) {
        requireSingleWorkspace("executeCypher");
        var statement = Objects.requireNonNull(cypher, "cypher");
        var boundParameters = parameters == null ? Map.<String, Object>of() : parameters;
        var startedAt = System.nanoTime();
        try (var session = driver.session(sessionConfig)) {
            var result = session.run(statement, boundParameters);
            var columns = List.copyOf(result.keys());
            var records = new ArrayList<Map<String, Object>>();
            while (result.hasNext()) {
                var record = result.next();
                var row = new LinkedHashMap<String, Object>();
                for (var column : columns) {
                    row.put(column, toPlainValue(record.get(column)));
                }
                records.add(Collections.unmodifiableMap(row));
            }
            log.info(
                "Neo4j graph executeCypher completed: workspaceId={}, columnCount={}, recordCount={}, elapsedMs={}",
                workspaceId,
                columns.size(),
                records.size(),
                elapsedMillis(startedAt)
            );
            return new CypherQueryResult(columns, records);
        } catch (RuntimeException exception) {
            log.error("Neo4j graph executeCypher failed: workspaceId={}", workspaceId, exception);
            throw new StorageException("Neo4j graph executeCypher failed", exception);
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
        // Auto-commit sessions, not a managed transaction: Memgraph rejects constraint and index
        // manipulation inside multi-command transactions, and Neo4j accepts the same statements in
        // either mode.
        try (var session = driver.session(sessionConfig)) {
            for (var statement : bootstrapStatements()) {
                session.run(statement).consume();
            }
        } catch (RuntimeException exception) {
            throw new StorageException("Neo4j graph bootstrap failed", exception);
        }
    }

    /**
     * Bootstrap DDL, executed in order via auto-commit sessions on every store construction. The
     * defaults target Neo4j; subclasses retarget them for engines with a narrower dialect.
     */
    protected List<String> bootstrapStatements() {
        return List.of(
            "DROP CONSTRAINT neo4j_entity_id IF EXISTS",
            "DROP CONSTRAINT neo4j_relation_id IF EXISTS",
            """
            CREATE CONSTRAINT neo4j_entity_scoped_id IF NOT EXISTS
            FOR (entity:%s) REQUIRE entity.scopedId IS UNIQUE
            """.formatted(ENTITY_LABEL),
            """
            CREATE CONSTRAINT neo4j_relation_scoped_id IF NOT EXISTS
            FOR ()-[relation:%s]-() REQUIRE relation.scopedId IS UNIQUE
            """.formatted(RELATION_TYPE)
        );
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
                entity.filePath = $filePath,
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
                "sourceChunkIds", record.sourceChunkIds(),
                "filePath", record.filePath()
            )
        );
    }

    private void saveEntities(TransactionContext tx, List<EntityRecord> records) {
        var startedAt = System.nanoTime();
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
                entity.filePath = row.filePath,
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
                        "sourceChunkIds", record.sourceChunkIds(),
                        "filePath", record.filePath()
                    ))
                    .toList()
            )
        );
        log.info(
            "Neo4j graph saveEntities completed: workspaceId={}, count={}, elapsedMs={}",
            workspaceId,
            records.size(),
            elapsedMillis(startedAt)
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
                          source.id = $srcId,
                          source.materialized = false,
                          source.name = '',
                          source.type = '',
                          source.description = '',
                          source.aliases = [],
                          source.sourceChunkIds = [],
                          source.filePath = ''
            MERGE (target:%s {scopedId: $scopedTargetEntityId})
            ON CREATE SET target.workspaceId = $workspaceId,
                          target.id = $tgtId,
                          target.materialized = false,
                          target.name = '',
                          target.type = '',
                          target.description = '',
                          target.aliases = [],
                          target.sourceChunkIds = [],
                          target.filePath = ''
            MERGE (source)-[relation:%s {scopedId: $scopedRelationId}]->(target)
            SET relation.workspaceId = $workspaceId,
                relation.relation_id = $relationId,
                relation.src_id = $srcId,
                relation.tgt_id = $tgtId,
                relation.keywords = $keywords,
                relation.description = $description,
                relation.weight = $weight,
                relation.source_id = $sourceId,
                relation.file_path = $filePath
            """.formatted(ENTITY_LABEL, ENTITY_LABEL, RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "scopedRelationId", scopedId(record.id()),
                "relationId", record.id(),
                "srcId", record.srcId(),
                "tgtId", record.tgtId(),
                "scopedSourceEntityId", scopedId(record.srcId()),
                "scopedTargetEntityId", scopedId(record.tgtId()),
                "keywords", record.keywords(),
                "description", record.description(),
                "weight", record.weight(),
                "sourceId", record.sourceId(),
                "filePath", record.filePath()
            )
        );
    }

    private void saveRelations(TransactionContext tx, List<RelationRecord> records) {
        var startedAt = System.nanoTime();
        var rows = records.stream()
            .map(record -> {
                var row = new LinkedHashMap<String, Object>();
                row.put("scopedRelationId", scopedId(record.id()));
                row.put("relationId", record.id());
                row.put("srcId", record.srcId());
                row.put("tgtId", record.tgtId());
                row.put("scopedSourceEntityId", scopedId(record.srcId()));
                row.put("scopedTargetEntityId", scopedId(record.tgtId()));
                row.put("keywords", record.keywords());
                row.put("description", record.description());
                row.put("weight", record.weight());
                row.put("sourceId", record.sourceId());
                row.put("filePath", record.filePath());
                return Map.copyOf(row);
            })
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
        var rowsPreparedAt = System.nanoTime();
        tx.run(
            """
            UNWIND range(0, size($allRows) - 1) AS idx
            WITH idx, $allRows[idx] AS row
            ORDER BY idx
            MERGE (source:%s {scopedId: row.scopedSourceEntityId})
            ON CREATE SET source.workspaceId = $workspaceId,
                          source.id = row.srcId,
                          source.materialized = false,
                          source.name = '',
                          source.type = '',
                          source.description = '',
                          source.aliases = [],
                          source.sourceChunkIds = [],
                          source.filePath = ''
            MERGE (target:%s {scopedId: row.scopedTargetEntityId})
            ON CREATE SET target.workspaceId = $workspaceId,
                          target.id = row.tgtId,
                          target.materialized = false,
                          target.name = '',
                          target.type = '',
                          target.description = '',
                          target.aliases = [],
                          target.sourceChunkIds = [],
                          target.filePath = ''
            """.formatted(ENTITY_LABEL, ENTITY_LABEL),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "allRows", rows
            )
        );
        var endpointsSavedAt = System.nanoTime();
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
        var relationsDeletedAt = System.nanoTime();
        tx.run(
            """
            UNWIND range(0, size($lastRows) - 1) AS idx
            WITH idx, $lastRows[idx] AS row
            ORDER BY idx
            MATCH (source:%s {workspaceId: $workspaceId, scopedId: row.scopedSourceEntityId})
            MATCH (target:%s {workspaceId: $workspaceId, scopedId: row.scopedTargetEntityId})
            MERGE (source)-[relation:%s {scopedId: row.scopedRelationId}]->(target)
            SET relation.workspaceId = $workspaceId,
                relation.relation_id = row.relationId,
                relation.src_id = row.srcId,
                relation.tgt_id = row.tgtId,
                relation.keywords = row.keywords,
                relation.description = row.description,
                relation.weight = row.weight,
                relation.source_id = row.sourceId,
                relation.file_path = row.filePath
            """.formatted(ENTITY_LABEL, ENTITY_LABEL, RELATION_TYPE),
            org.neo4j.driver.Values.parameters(
                "workspaceId", workspaceId,
                "lastRows", lastWriteRows
            )
        );
        var relationsSavedAt = System.nanoTime();
        log.info(
            "Neo4j graph saveRelations completed: workspaceId={}, inputCount={}, distinctCount={}, prepareMs={}, endpointMergeMs={}, deleteMs={}, relationMergeMs={}, totalMs={}",
            workspaceId,
            records.size(),
            lastWriteRows.size(),
            elapsedMillis(startedAt, rowsPreparedAt),
            elapsedMillis(rowsPreparedAt, endpointsSavedAt),
            elapsedMillis(endpointsSavedAt, relationsDeletedAt),
            elapsedMillis(relationsDeletedAt, relationsSavedAt),
            elapsedMillis(startedAt, relationsSavedAt)
        );
    }

    private String scopedId(String id) {
        return workspaceId + ":" + Objects.requireNonNull(id, "id");
    }

    /**
     * Workspace-set reads may surface the same logical id once per workspace; records arrive
     * ordered by workspace id, so the first occurrence is the deterministic winner.
     */
    private static <T> List<T> dedupeById(
        List<T> records, java.util.function.Function<T, String> idExtractor) {
        var seen = new LinkedHashSet<String>();
        var deduplicated = new ArrayList<T>(records.size());
        for (var record : records) {
            if (seen.add(idExtractor.apply(record))) {
                deduplicated.add(record);
            }
        }
        return List.copyOf(deduplicated);
    }

    private static EntityRecord toEntity(Record record) {
        var entity = record.get("entity");
        return new EntityRecord(
            entity.get("id").asString(),
            entity.get("name").asString(""),
            entity.get("type").asString(""),
            entity.get("description").asString(""),
            stringList(entity.get("aliases")),
            stringList(entity.get("sourceChunkIds")),
            entity.get("filePath").asString("")
        );
    }

    private static RelationRecord toRelation(Record record) {
        var relation = record.get("relation");
        return new RelationRecord(
            relation.get("relation_id").asString(),
            relation.get("src_id").asString(),
            relation.get("tgt_id").asString(),
            relation.get("keywords").asString(""),
            relation.get("description").asString(""),
            relation.get("weight").asDouble(0.0d),
            relation.get("source_id").asString(""),
            relation.get("file_path").asString("")
        );
    }

    private static List<String> stringList(Value value) {
        if (value == null || value.isNull()) {
            return List.of();
        }
        return value.asList(Value::asString);
    }

    private static Object toPlainValue(Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return toPlainObject(value.asObject());
    }

    /**
     * Mirrors the platform's driver-value normalisation so every Bolt-backed executor returns the
     * same JSON-like shapes: nodes as {@code {elementId, labels, properties}}, relationships as
     * {@code {elementId, type, startNodeElementId, endNodeElementId, properties}}, paths as
     * {@code {length, nodes, relationships}}; maps and lists convert recursively and scalars pass
     * through, with anything else falling back to its string form.
     */
    private static Object toPlainObject(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Node node) {
            var element = new LinkedHashMap<String, Object>();
            element.put("elementId", node.elementId());
            element.put("labels", plainStrings(node.labels()));
            element.put("properties", plainMap(node.asMap()));
            return Collections.unmodifiableMap(element);
        }
        if (value instanceof Relationship relationship) {
            var element = new LinkedHashMap<String, Object>();
            element.put("elementId", relationship.elementId());
            element.put("type", relationship.type());
            element.put("startNodeElementId", relationship.startNodeElementId());
            element.put("endNodeElementId", relationship.endNodeElementId());
            element.put("properties", plainMap(relationship.asMap()));
            return Collections.unmodifiableMap(element);
        }
        if (value instanceof Path path) {
            var element = new LinkedHashMap<String, Object>();
            element.put("length", path.length());
            element.put("nodes", plainIterable(path.nodes()));
            element.put("relationships", plainIterable(path.relationships()));
            return Collections.unmodifiableMap(element);
        }
        if (value instanceof Map<?, ?> map) {
            var converted = new LinkedHashMap<String, Object>();
            map.forEach((key, element) -> converted.put(String.valueOf(key), toPlainObject(element)));
            return Collections.unmodifiableMap(converted);
        }
        if (value instanceof Iterable<?> iterable) {
            return plainIterable(iterable);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        return value.toString();
    }

    private static List<Object> plainIterable(Iterable<?> values) {
        var converted = new ArrayList<Object>();
        for (var value : values) {
            converted.add(toPlainObject(value));
        }
        return List.copyOf(converted);
    }

    private static Map<String, Object> plainMap(Map<String, Object> values) {
        var converted = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> converted.put(key, toPlainObject(value)));
        return Collections.unmodifiableMap(converted);
    }

    private static List<String> plainStrings(Iterable<String> values) {
        var converted = new ArrayList<String>();
        values.forEach(converted::add);
        return List.copyOf(converted);
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static long elapsedMillis(long startedAt, long endedAt) {
        return Math.max(0L, (endedAt - startedAt) / 1_000_000L);
    }
}
