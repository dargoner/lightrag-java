package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.indexing.RelationCanonicalizer;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.MutableGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Knowledge-graph store backed by an Apache AGE graph, mirroring the upstream Python
 * {@code PGGraphStorage} ({@code kg/postgres_impl.py}). The graph lives in the workspace graph
 * created by {@link PostgresAgeBootstrap}; vertices use the {@code base} label with the entity id
 * in the {@code entity_id} property, relations the {@code DIRECTED} label.
 *
 * <p>Deviations from upstream, both deliberate: relation identity is Java's
 * {@code RelationCanonicalizer.relationId} (upstream has no relation ids), so edges additionally
 * carry {@code relation_id}, {@code src_id} and {@code tgt_id} properties; and every Cypher
 * argument is bound as a parameter rather than inlined, which removes the need for the string
 * escaping upstream performs when it interpolates ids.</p>
 */
public final class PostgresAgeGraphStore implements MutableGraphStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresAgeGraphStore.class);

    /** Upstream {@code DEFAULT_PG_DELETE_MAX_RECORDS_PER_BATCH}. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private static final String ENTITY_ID_PREDICATE =
        "ag_catalog.agtype_access_operator(VARIADIC ARRAY[a.properties, '\"entity_id\"'::ag_catalog.agtype]) "
            + "= (to_json(?::text)::text)::ag_catalog.agtype";

    private final JdbcConnectionAccess connectionAccess;
    private final String graphName;

    public PostgresAgeGraphStore(DataSource dataSource) {
        this(dataSource, "default");
    }

    public PostgresAgeGraphStore(DataSource dataSource, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), workspaceId);
    }

    PostgresAgeGraphStore(JdbcConnectionAccess connectionAccess, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.graphName = PostgresAgeSupport.graphName(Objects.requireNonNull(workspaceId, "workspaceId"));
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        PostgresRetrySupport.execute("save Apache AGE entity '%s'".formatted(record.id()), () -> {
            inAgeSession(connection -> {
                var cypher = "MERGE (n:base {entity_id: $entity_id})\n"
                    + "SET n += " + PostgresAgeSupport.formatProperties(entityProperties(record)) + "\n"
                    + "RETURN n";
                queryCypher(connection, cypher, Map.of("entity_id", record.id()), resultSet -> null);
                return null;
            });
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        PostgresRetrySupport.execute("save Apache AGE relation '%s'".formatted(record.id()), () -> {
            inAgeSession(connection -> {
                // Edge properties only persist when inlined in CREATE: AGE ignores SET r += / SET r.k =.
                var cypher = "MATCH (source:base {entity_id: $src_id})\n"
                    + "WITH source\n"
                    + "MATCH (target:base {entity_id: $tgt_id})\n"
                    + "WITH source, target\n"
                    + "OPTIONAL MATCH (source)-[old:DIRECTED]-(target)\n"
                    + "DELETE old\n"
                    + "WITH source, target\n"
                    + "CREATE (source)-[r:DIRECTED " + PostgresAgeSupport.formatProperties(relationProperties(record)) + "]->(target)\n"
                    + "RETURN r";
                var parameters = Map.<String, Object>of("src_id", record.srcId(), "tgt_id", record.tgtId());
                var created = queryCypher(connection, cypher, parameters, ResultSet::next);
                if (!created) {
                    // AGE reports no error when an endpoint MATCH finds nothing; the empty result is the only signal.
                    throw new StorageException(edgeWriteLostMessage(connection, record));
                }
                return null;
            });
            return null;
        });
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement(
                "SELECT properties FROM " + qualifiedLabel("base")
                    + " WHERE ag_catalog.agtype_access_operator(VARIADIC ARRAY[properties, '\"entity_id\"'::ag_catalog.agtype])"
                    + " = (to_json(?::text)::text)::ag_catalog.agtype LIMIT 1"
            )) {
                statement.setString(1, id);
                try (var resultSet = statement.executeQuery()) {
                    return resultSet.next()
                        ? Optional.of(toEntityRecord(resultSet.getString(1)))
                        : Optional.empty();
                }
            }
        });
    }

    /**
     * One session, one statement for the whole batch: the default loops {@link #loadEntity} with a
     * fresh connection and transaction per id. Output stays default-equivalent - request order,
     * missing ids skipped, duplicates preserved.
     */
    @Override
    public List<EntityRecord> loadEntities(List<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return List.of();
        }
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement(
                "SELECT v.properties FROM " + qualifiedLabel("base") + " v WHERE "
                    + "ag_catalog.agtype_access_operator(VARIADIC ARRAY[v.properties, '\"entity_id\"'::ag_catalog.agtype])"
                    + " IN (SELECT (to_json(u.value::text)::text)::ag_catalog.agtype FROM unnest(?::text[]) AS u(value))"
            )) {
                statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
                try (var resultSet = statement.executeQuery()) {
                    var entitiesById = new LinkedHashMap<String, EntityRecord>();
                    while (resultSet.next()) {
                        var properties = resultSet.getString(1);
                        if (properties != null) {
                            var entity = toEntityRecord(properties);
                            entitiesById.putIfAbsent(entity.id(), entity);
                        }
                    }
                    var entities = new ArrayList<EntityRecord>(ids.size());
                    for (var id : ids) {
                        var entity = entitiesById.get(id);
                        if (entity != null) {
                            entities.add(entity);
                        }
                    }
                    return List.copyOf(entities);
                }
            }
        });
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var id = Objects.requireNonNull(relationId, "relationId");
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement(
                "SELECT properties FROM " + qualifiedLabel("DIRECTED")
                    + " WHERE ag_catalog.agtype_access_operator(VARIADIC ARRAY[properties, '\"relation_id\"'::ag_catalog.agtype])"
                    + " = (to_json(?::text)::text)::ag_catalog.agtype LIMIT 1"
            )) {
                statement.setString(1, id);
                try (var resultSet = statement.executeQuery()) {
                    return resultSet.next()
                        ? Optional.of(toRelationRecord(resultSet.getString(1)))
                        : Optional.empty();
                }
            }
        });
    }

    /**
     * One session, one statement for the whole batch; see {@link #loadEntities(List)} for the
     * default-equivalence rules (request order, missing ids skipped, duplicates preserved).
     */
    @Override
    public List<RelationRecord> loadRelations(List<String> relationIds) {
        var ids = List.copyOf(Objects.requireNonNull(relationIds, "relationIds"));
        if (ids.isEmpty()) {
            return List.of();
        }
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement(
                "SELECT r.properties FROM " + qualifiedLabel("DIRECTED") + " r WHERE "
                    + "ag_catalog.agtype_access_operator(VARIADIC ARRAY[r.properties, '\"relation_id\"'::ag_catalog.agtype])"
                    + " IN (SELECT (to_json(u.value::text)::text)::ag_catalog.agtype FROM unnest(?::text[]) AS u(value))"
            )) {
                statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
                try (var resultSet = statement.executeQuery()) {
                    var relationsById = new LinkedHashMap<String, RelationRecord>();
                    while (resultSet.next()) {
                        var properties = resultSet.getString(1);
                        if (properties != null) {
                            var relation = toRelationRecord(properties);
                            relationsById.putIfAbsent(relation.id(), relation);
                        }
                    }
                    var relations = new ArrayList<RelationRecord>(ids.size());
                    for (var id : ids) {
                        var relation = relationsById.get(id);
                        if (relation != null) {
                            relations.add(relation);
                        }
                    }
                    return List.copyOf(relations);
                }
            }
        });
    }

    @Override
    public List<EntityRecord> allEntities() {
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement("SELECT properties FROM " + qualifiedLabel("base"));
                 var resultSet = statement.executeQuery()) {
                var entities = new ArrayList<EntityRecord>();
                while (resultSet.next()) {
                    var properties = resultSet.getString(1);
                    if (properties != null) {
                        entities.add(toEntityRecord(properties));
                    }
                }
                entities.sort(Comparator.comparing(EntityRecord::id));
                return List.copyOf(entities);
            }
        });
    }

    @Override
    public List<RelationRecord> allRelations() {
        return inAgeSession(connection -> {
            try (var statement = connection.prepareStatement("SELECT properties FROM " + qualifiedLabel("DIRECTED"));
                 var resultSet = statement.executeQuery()) {
                var relations = new ArrayList<RelationRecord>();
                while (resultSet.next()) {
                    var properties = resultSet.getString(1);
                    if (properties != null) {
                        relations.add(toRelationRecord(properties));
                    }
                }
                relations.sort(Comparator.comparing(RelationRecord::id));
                return List.copyOf(relations);
            }
        });
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inAgeSession(connection -> {
            var outgoing = "SELECT r.properties FROM " + qualifiedLabel("DIRECTED") + " r"
                + " JOIN " + qualifiedLabel("base") + " a ON r.start_id = a.id WHERE " + ENTITY_ID_PREDICATE;
            var incoming = "SELECT r.properties FROM " + qualifiedLabel("DIRECTED") + " r"
                + " JOIN " + qualifiedLabel("base") + " a ON r.end_id = a.id WHERE " + ENTITY_ID_PREDICATE;
            try (var statement = connection.prepareStatement(outgoing + " UNION " + incoming)) {
                statement.setString(1, id);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    var relations = new ArrayList<RelationRecord>();
                    while (resultSet.next()) {
                        relations.add(toRelationRecord(resultSet.getString(1)));
                    }
                    relations.sort(Comparator.comparing(RelationRecord::id));
                    return List.copyOf(relations);
                }
            }
        });
    }

    /**
     * One session, one statement for the whole batch: the default loops {@link #findRelations(String)}
     * with a fresh connection and transaction per id. The UNION deduplicates per entity exactly like
     * the single-id form (a self-loop counts once), and each per-entity list is ordered and immutable
     * the same way.
     */
    @Override
    public Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return inAgeSession(connection -> {
            var predicate = "ag_catalog.agtype_access_operator(VARIADIC ARRAY[a.properties, '\"entity_id\"'::ag_catalog.agtype])"
                + " = (to_json(c.entity_id::text)::text)::ag_catalog.agtype";
            var sql = "WITH candidates AS ("
                + " SELECT u.value::text AS entity_id FROM unnest(?::text[]) AS u(value)"
                + ")"
                + " SELECT c.entity_id AS entity_id, r.properties AS properties"
                + " FROM candidates c"
                + " JOIN " + qualifiedLabel("base") + " a ON " + predicate
                + " JOIN " + qualifiedLabel("DIRECTED") + " r ON r.start_id = a.id"
                + " UNION"
                + " SELECT c.entity_id AS entity_id, r.properties AS properties"
                + " FROM candidates c"
                + " JOIN " + qualifiedLabel("base") + " a ON " + predicate
                + " JOIN " + qualifiedLabel("DIRECTED") + " r ON r.end_id = a.id";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
                try (var resultSet = statement.executeQuery()) {
                    var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
                    for (var id : ids) {
                        relationsByEntityId.put(id, new ArrayList<>());
                    }
                    while (resultSet.next()) {
                        var entityId = resultSet.getString(1);
                        var properties = resultSet.getString(2);
                        var relations = entityId == null ? null : relationsByEntityId.get(entityId);
                        if (relations != null && properties != null) {
                            relations.add(toRelationRecord(properties));
                        }
                    }
                    var immutable = new LinkedHashMap<String, List<RelationRecord>>();
                    relationsByEntityId.forEach((entityId, relations) -> {
                        relations.sort(Comparator.comparing(RelationRecord::id));
                        immutable.put(entityId, List.copyOf(relations));
                    });
                    return Collections.unmodifiableMap(immutable);
                }
            }
        });
    }

    /**
     * Native incident-relation count: the UNION deduplicates per entity exactly like
     * {@link #findRelations(String)} (a self-loop counts once), so the count equals that method's
     * size while the degree-ranked ordering no longer has to materialize the adjacent edges.
     */
    @Override
    public Map<String, Integer> degrees(Collection<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        return inAgeSession(connection -> {
            var predicate = "ag_catalog.agtype_access_operator(VARIADIC ARRAY[a.properties, '\"entity_id\"'::ag_catalog.agtype])"
                + " = (to_json(c.entity_id::text)::text)::ag_catalog.agtype";
            var sql = "WITH candidates AS ("
                + " SELECT u.value::text AS entity_id FROM unnest(?::text[]) AS u(value)"
                + ")"
                + " SELECT incident.entity_id AS entity_id, COUNT(*) AS degree"
                + " FROM ("
                + " SELECT c.entity_id AS entity_id, r.properties AS properties"
                + " FROM candidates c"
                + " JOIN " + qualifiedLabel("base") + " a ON " + predicate
                + " JOIN " + qualifiedLabel("DIRECTED") + " r ON r.start_id = a.id"
                + " UNION"
                + " SELECT c.entity_id AS entity_id, r.properties AS properties"
                + " FROM candidates c"
                + " JOIN " + qualifiedLabel("base") + " a ON " + predicate
                + " JOIN " + qualifiedLabel("DIRECTED") + " r ON r.end_id = a.id"
                + " ) incident"
                + " GROUP BY incident.entity_id";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setArray(1, connection.createArrayOf("text", ids.toArray()));
                try (var resultSet = statement.executeQuery()) {
                    var counted = new LinkedHashMap<String, Integer>();
                    while (resultSet.next()) {
                        counted.put(resultSet.getString(1), resultSet.getInt(2));
                    }
                    var degrees = new LinkedHashMap<String, Integer>();
                    for (var id : ids) {
                        degrees.putIfAbsent(id, counted.getOrDefault(id, 0));
                    }
                    return Collections.unmodifiableMap(degrees);
                }
            }
        });
    }

    @Override
    public int deleteEntities(List<String> entityIds) {
        var ids = Objects.requireNonNull(entityIds, "entityIds");
        if (ids.isEmpty()) {
            return 0;
        }
        return PostgresRetrySupport.execute("delete Apache AGE entities", () -> inAgeSession(connection -> {
            var deleted = 0;
            for (var batch : batches(ids)) {
                var cypher = "MATCH (n:base)\n"
                    + "WHERE n.entity_id IN $ids\n"
                    + "DETACH DELETE n\n"
                    + "RETURN n.entity_id AS value";
                deleted += queryCypher(connection, cypher, Map.of("ids", batch), PostgresAgeGraphStore::countRows);
            }
            return deleted;
        }));
    }

    @Override
    public int deleteRelations(List<String> relationIds) {
        var ids = Objects.requireNonNull(relationIds, "relationIds");
        if (ids.isEmpty()) {
            return 0;
        }
        return PostgresRetrySupport.execute("delete Apache AGE relations", () -> inAgeSession(connection -> {
            var deleted = 0;
            for (var batch : batches(ids)) {
                var cypher = "MATCH ()-[r:DIRECTED]->()\n"
                    + "WHERE r.relation_id IN $ids\n"
                    + "DELETE r\n"
                    + "RETURN r.relation_id AS value";
                deleted += queryCypher(connection, cypher, Map.of("ids", batch), PostgresAgeGraphStore::countRows);
            }
            return deleted;
        }));
    }

    /** Entity ids in code-point order, upstream {@code get_all_labels}. */
    @Override
    public List<String> labels() {
        return inAgeSession(connection -> {
            var cypher = "MATCH (n:base)\n"
                + "WHERE n.entity_id IS NOT NULL\n"
                + "RETURN DISTINCT n.entity_id AS value\n"
                + "ORDER BY n.entity_id";
            var sql = "SELECT * FROM ag_catalog.cypher("
                + PostgresAgeSupport.dollarQuote(graphName) + ", "
                + PostgresAgeSupport.dollarQuote(cypher) + ") AS (value text)";
            try (var statement = connection.prepareStatement(sql);
                 var resultSet = statement.executeQuery()) {
                var labels = new ArrayList<String>();
                while (resultSet.next()) {
                    labels.add(resultSet.getString(1));
                }
                labels.sort(Comparator.naturalOrder());
                return List.copyOf(labels);
            }
        });
    }

    /**
     * Store-native text search: Cypher filters the four candidate fields case-insensitively, then a
     * single follow-up statement fetches the matched property rows by id (the same id-row shape
     * {@link #presentEndpointIds} probes). Superset of the caller-side ranking per
     * {@link GraphStore#searchEntitiesByText}.
     */
    @Override
    public List<EntityRecord> searchEntitiesByText(String query) {
        var needle = Objects.requireNonNull(query, "query").strip();
        if (needle.isEmpty()) {
            return List.of();
        }
        return inAgeSession(connection -> {
            var cypher = "MATCH (n:base)\n"
                + "WHERE toLower(coalesce(n.name, '')) CONTAINS $query\n"
                + "   OR toLower(coalesce(n.entity_type, '')) CONTAINS $query\n"
                + "   OR toLower(coalesce(n.description, '')) CONTAINS $query\n"
                // AGE < 1.8 rejects the any(... WHERE ...) predicate function, but accepts a filtered
                // list comprehension, so the alias clause counts matches instead.
                + "   OR size([alias IN coalesce(n.aliases, []) WHERE toLower(alias) CONTAINS $query]) > 0\n"
                + "RETURN n.entity_id AS value";
            var matchedIds = searchMatchedIds(connection, cypher, needle);
            if (matchedIds.isEmpty()) {
                return List.of();
            }
            var fetch = "SELECT v.properties FROM " + qualifiedLabel("base") + " v WHERE "
                + "ag_catalog.agtype_access_operator(VARIADIC ARRAY[v.properties, '\"entity_id\"'::ag_catalog.agtype])"
                + " IN (SELECT (to_json(u.value::text)::text)::ag_catalog.agtype FROM unnest(?::text[]) AS u(value))";
            try (var statement = connection.prepareStatement(fetch)) {
                statement.setArray(1, connection.createArrayOf("text", matchedIds.toArray()));
                try (var resultSet = statement.executeQuery()) {
                    var entities = new ArrayList<EntityRecord>();
                    while (resultSet.next()) {
                        var properties = resultSet.getString(1);
                        if (properties != null) {
                            entities.add(toEntityRecord(properties));
                        }
                    }
                    entities.sort(Comparator.comparing(EntityRecord::id));
                    return List.copyOf(entities);
                }
            }
        });
    }

    /**
     * Executes one caller-supplied Cypher statement against this workspace graph. Parameters bind
     * as agtype through the same session wrapper as every other native statement (search_path,
     * transaction ownership, transient-failure retry), and each top-level RETURN item becomes a
     * positional agtype column that {@link PostgresAgeCypherSupport} converts back to plain Java
     * values. Statements without RETURN run for their effect and return an empty result.
     */
    @Override
    public CypherQueryResult executeCypher(String cypher, Map<String, Object> parameters) {
        var query = Objects.requireNonNull(cypher, "cypher");
        var boundParameters = parameters == null ? Map.<String, Object>of() : parameters;
        var returnItems = PostgresAgeCypherSupport.returnItems(query);
        var columnNames = PostgresAgeCypherSupport.columnNames(returnItems);
        return PostgresRetrySupport.execute("execute Apache AGE cypher statement", () -> inAgeSession(connection -> {
            var sql = "SELECT * FROM ag_catalog.cypher("
                + PostgresAgeSupport.dollarQuote(graphName) + "::name, "
                + PostgresAgeSupport.dollarQuote(query) + "::cstring, "
                + "?::ag_catalog.agtype) AS ("
                + PostgresAgeCypherSupport.columnDefinitionList(returnItems.size()) + ")";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, JdbcJsonCodec.writeObjectMap(boundParameters), Types.OTHER);
                try (var resultSet = statement.executeQuery()) {
                    if (returnItems.isEmpty()) {
                        while (resultSet.next()) {
                            // Drain: a no-RETURN statement still executes inside cypher().
                        }
                        return new CypherQueryResult(List.of(), List.of());
                    }
                    var records = new ArrayList<Map<String, Object>>();
                    while (resultSet.next()) {
                        var record = new LinkedHashMap<String, Object>();
                        for (var index = 0; index < columnNames.size(); index++) {
                            record.put(
                                columnNames.get(index),
                                PostgresAgeCypherSupport.toJavaValue(resultSet.getString(index + 1))
                            );
                        }
                        records.add(Collections.unmodifiableMap(record));
                    }
                    return new CypherQueryResult(columnNames, records);
                }
            }
        }));
    }

    /**
     * The shared {@code queryCypher} helper declares its output as agtype, whose text form quotes
     * string scalars; declaring {@code text} yields the bare id, same as {@link #labels()}.
     */
    private List<String> searchMatchedIds(Connection connection, String cypher, String needle) throws SQLException {
        var sql = "SELECT * FROM ag_catalog.cypher("
            + PostgresAgeSupport.dollarQuote(graphName) + "::name, "
            + PostgresAgeSupport.dollarQuote(cypher) + "::cstring, "
            + "?::ag_catalog.agtype) AS (value text)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, JdbcJsonCodec.writeObjectMap(Map.of("query", needle)), Types.OTHER);
            try (var resultSet = statement.executeQuery()) {
                var ids = new ArrayList<String>();
                while (resultSet.next()) {
                    var id = resultSet.getString(1);
                    if (id != null) {
                        ids.add(id);
                    }
                }
                return ids;
            }
        }
    }

    /** Clears the graph; called by the provider's truncate/restore path. */
    void clear() {
        inAgeSession(connection -> {
            queryCypher(connection, "MATCH (n)\nDETACH DELETE n", resultSet -> null);
            return null;
        });
    }

    private String edgeWriteLostMessage(Connection connection, RelationRecord relation) {
        var endpoints = List.of(relation.srcId(), relation.tgtId());
        List<String> present;
        try {
            present = presentEndpointIds(connection, endpoints);
        } catch (SQLException | RuntimeException probeFailure) {
            // Diagnostics only: the transaction is about to be rolled back, so a probe failure
            // must not mask the actual problem.
            log.debug("Could not probe Apache AGE endpoints for '{}'", relation.id(), probeFailure);
            return "Apache AGE edge upsert wrote nothing for '%s' -> '%s' (endpoint probe failed)"
                .formatted(relation.srcId(), relation.tgtId());
        }
        var missing = endpoints.stream().distinct().filter(endpoint -> !present.contains(endpoint)).toList();
        return ("Apache AGE edge upsert wrote nothing for '%s' -> '%s': missing endpoint(s) %s. AGE reports no error "
            + "when a MATCH endpoint is absent from the graph.")
            .formatted(relation.srcId(), relation.tgtId(), missing);
    }

    private List<String> presentEndpointIds(Connection connection, List<String> entityIds) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT candidate.entity_id AS entity_id
            FROM unnest(?::text[]) AS candidate(entity_id)
            WHERE EXISTS (
                SELECT 1
                FROM %s v
                WHERE ag_catalog.agtype_access_operator(VARIADIC ARRAY[v.properties, '"entity_id"'::ag_catalog.agtype])
                    = (to_json(candidate.entity_id::text)::text)::ag_catalog.agtype
            )
            """.formatted(qualifiedLabel("base"))
        )) {
            statement.setArray(1, connection.createArrayOf("text", entityIds.toArray()));
            try (var resultSet = statement.executeQuery()) {
                var present = new ArrayList<String>();
                while (resultSet.next()) {
                    present.add(resultSet.getString(1));
                }
                return present;
            }
        }
    }

    private <T> T inAgeSession(JdbcConnectionAccess.SqlFunction<T> work) {
        return connectionAccess.withConnection(connection -> {
            var originalAutoCommit = connection.getAutoCommit();
            var ownsTransaction = originalAutoCommit;
            if (ownsTransaction) {
                connection.setAutoCommit(false);
            }
            try {
                // AGE's MERGE rewrite resolves its helper operators through search_path, so the
                // session needs ag_catalog on it (upstream configure_age sets it per checkout).
                // SET LOCAL reverts at commit/rollback, so nothing leaks back to the pool.
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL search_path = ag_catalog, \"$user\", public");
                }
                var result = work.apply(connection);
                if (ownsTransaction) {
                    connection.commit();
                }
                return result;
            } catch (SQLException exception) {
                if (ownsTransaction) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                }
                throw exception;
            } finally {
                if (ownsTransaction) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException restoreFailure) {
                        log.debug("Could not restore auto-commit on the Apache AGE connection", restoreFailure);
                    }
                }
            }
        });
    }

    private <T> T queryCypher(
        Connection connection,
        String cypher,
        CypherResultMapper<T> resultMapper
    ) throws SQLException {
        var sql = "SELECT * FROM ag_catalog.cypher("
            + PostgresAgeSupport.dollarQuote(graphName) + ", "
            + PostgresAgeSupport.dollarQuote(cypher) + ") AS (value ag_catalog.agtype)";
        try (var statement = connection.prepareStatement(sql);
             var resultSet = statement.executeQuery()) {
            return resultMapper.map(resultSet);
        }
    }

    private <T> T queryCypher(
        Connection connection,
        String cypher,
        Map<String, Object> parameters,
        CypherResultMapper<T> resultMapper
    ) throws SQLException {
        var sql = "SELECT * FROM ag_catalog.cypher("
            + PostgresAgeSupport.dollarQuote(graphName) + "::name, "
            + PostgresAgeSupport.dollarQuote(cypher) + "::cstring, "
            + "?::ag_catalog.agtype) AS (value ag_catalog.agtype)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, JdbcJsonCodec.writeObjectMap(parameters), Types.OTHER);
            try (var resultSet = statement.executeQuery()) {
                return resultMapper.map(resultSet);
            }
        }
    }

    @FunctionalInterface
    private interface CypherResultMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private static int countRows(ResultSet resultSet) throws SQLException {
        var count = 0;
        while (resultSet.next()) {
            count++;
        }
        return count;
    }

    private static List<List<String>> batches(List<String> values) {
        if (values.size() <= DELETE_BATCH_SIZE) {
            return List.of(List.copyOf(values));
        }
        var batches = new ArrayList<List<String>>();
        for (var index = 0; index < values.size(); index += DELETE_BATCH_SIZE) {
            batches.add(List.copyOf(values.subList(index, Math.min(index + DELETE_BATCH_SIZE, values.size()))));
        }
        return List.copyOf(batches);
    }

    /**
     * The graph schema is double-quoted: {@code create_graph} preserves the workspace's case, and
     * an unquoted mixed-case schema would fold to lower case and not be found (upstream has this
     * latent issue; the graph name itself is unchanged).
     */
    private String qualifiedLabel(String label) {
        return "\"" + graphName + "\".\"" + label + "\"";
    }

    private static Map<String, Object> entityProperties(EntityRecord entity) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("name", entity.name());
        properties.put("entity_type", entity.type());
        properties.put("description", entity.description());
        properties.put("aliases", entity.aliases());
        properties.put("source_id", RelationCanonicalizer.joinValues(entity.sourceChunkIds()));
        properties.put("file_path", entity.filePath());
        return properties;
    }

    private static Map<String, Object> relationProperties(RelationRecord relation) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("relation_id", relation.id());
        properties.put("src_id", relation.srcId());
        properties.put("tgt_id", relation.tgtId());
        properties.put("keywords", relation.keywords());
        properties.put("description", relation.description());
        properties.put("weight", relation.weight());
        properties.put("source_id", relation.sourceId());
        properties.put("file_path", relation.filePath());
        return properties;
    }

    private EntityRecord toEntityRecord(String propertiesText) {
        var properties = parseProperties(propertiesText);
        var id = requiredText(properties, "entity_id", propertiesText);
        return new EntityRecord(
            id,
            // Upstream uses entity ids as entity names, so an id is the best available fallback
            // for graphs written without the Java-specific 'name' property.
            textOrDefault(properties.get("name"), id),
            textOrDefault(properties.get("entity_type"), ""),
            textOrDefault(properties.get("description"), ""),
            stringList(properties.get("aliases")),
            RelationCanonicalizer.splitValues(textOrDefault(properties.get("source_id"), "")),
            textOrDefault(properties.get("file_path"), "")
        );
    }

    private RelationRecord toRelationRecord(String propertiesText) {
        var properties = parseProperties(propertiesText);
        var srcId = requiredText(properties, "src_id", propertiesText);
        var tgtId = requiredText(properties, "tgt_id", propertiesText);
        var relationId = textOrDefault(properties.get("relation_id"), "");
        if (relationId.isBlank()) {
            relationId = srcId.equals(tgtId)
                ? RelationCanonicalizer.relationId(srcId, tgtId)
                : RelationCanonicalizer.canonicalize(srcId, tgtId).relationId();
        }
        return new RelationRecord(
            relationId,
            srcId,
            tgtId,
            textOrDefault(properties.get("keywords"), ""),
            textOrDefault(properties.get("description"), ""),
            number(properties.get("weight")),
            textOrDefault(properties.get("source_id"), ""),
            textOrDefault(properties.get("file_path"), "")
        );
    }

    private Map<String, Object> parseProperties(String propertiesText) {
        try {
            return JdbcJsonCodec.readObjectMap(propertiesText);
        } catch (IllegalArgumentException exception) {
            // Complete-or-raise, mirroring upstream: enumeration feeds whole-graph consumers
            // (storage migration, KG integrity audit) that treat the result as the full graph.
            throw new StorageException(
                "Corrupt Apache AGE properties in graph '%s': %s".formatted(graphName, preview(propertiesText)),
                exception
            );
        }
    }

    private static String requiredText(Map<String, Object> properties, String key, String propertiesText) {
        var value = textOrDefault(properties.get(key), "");
        if (value.isEmpty()) {
            throw new StorageException(
                "Apache AGE properties are missing '%s': %s".formatted(key, preview(propertiesText))
            );
        }
        return value;
    }

    private static String textOrDefault(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double number(Object value) {
        return value instanceof Number numeric ? numeric.doubleValue() : 0.0;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var values = new ArrayList<String>(list.size());
        for (var element : list) {
            if (element != null) {
                values.add(String.valueOf(element));
            }
        }
        return List.copyOf(values);
    }

    private static String preview(String value) {
        var text = String.valueOf(value);
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
