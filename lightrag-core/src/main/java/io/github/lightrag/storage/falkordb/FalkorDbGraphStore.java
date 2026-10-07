package io.github.lightrag.storage.falkordb;

import com.falkordb.Driver;
import com.falkordb.Graph;
import com.falkordb.exceptions.GraphException;
import com.falkordb.graph_entities.Edge;
import com.falkordb.graph_entities.GraphEntity;
import com.falkordb.graph_entities.Node;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.indexing.RelationCanonicalizer;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.MutableGraphStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Knowledge-graph store backed by a FalkorDB graph, one named graph per workspace (see
 * {@link FalkorDbSupport#graphName}). Vertices use the {@code base} label with the entity id in the
 * {@code entity_id} property, relations the {@code DIRECTED} label - the same shape the Apache AGE
 * backend stores, so graphs written by one can be read by the other.
 *
 * <p>Deviations from the Apache AGE statements, both deliberate: writes let the client encode
 * parameters as Cypher literals instead of binding them server-side (JFalkorDB does the escaping),
 * and edge writes use {@code SET r += $properties} on a {@code MERGE}, which FalkorDB supports
 * (AGE only persists edge properties when they are inlined in {@code CREATE}). Deletions count
 * through FalkorDB's result statistics rather than returned rows, since {@code DETACH DELETE} rows
 * cannot report the deleted entities back.</p>
 */
public final class FalkorDbGraphStore implements MutableGraphStore, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FalkorDbGraphStore.class);

    /** Upstream {@code DEFAULT_PG_DELETE_MAX_RECORDS_PER_BATCH}, shared by the AGE backend. */
    private static final int DELETE_BATCH_SIZE = 1000;

    private static final String ENTITY_LABEL = "base";
    private static final String RELATION_TYPE = "DIRECTED";

    private static final String INDEX_EXISTS_QUERY =
        """
        CALL db.indexes() YIELD label, properties, entitytype
        WHERE entitytype = 'NODE' AND label = 'base' AND 'entity_id' IN properties
        RETURN label
        """;

    private static final String CREATE_INDEX_QUERY = "CREATE INDEX FOR (n:base) ON (n.entity_id)";

    private final Driver driver;
    private final boolean ownsDriver;
    private final String graphName;
    private final Graph graph;

    public FalkorDbGraphStore(FalkorDbGraphConfig config, String workspaceId) {
        this(FalkorDbSupport.createDriver(Objects.requireNonNull(config, "config")), workspaceId, true);
    }

    /** Borrowed-driver constructor; {@link #close()} then leaves the driver untouched. */
    public FalkorDbGraphStore(Driver driver, String workspaceId) {
        this(driver, workspaceId, false);
    }

    FalkorDbGraphStore(Driver driver, String workspaceId, boolean ownsDriver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.ownsDriver = ownsDriver;
        this.graphName = FalkorDbSupport.graphName(Objects.requireNonNull(workspaceId, "workspaceId"));
        var targetGraph = driver.graph(graphName);
        try {
            bootstrapEntityIdIndex(targetGraph);
        } catch (RuntimeException exception) {
            if (ownsDriver) {
                FalkorDbSupport.closeDriver(this.driver);
            }
            throw exception;
        }
        this.graph = targetGraph;
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        inGraph("save entity '%s'".formatted(record.id()), () -> {
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("entity_id", record.id());
            parameters.put("properties", entityProperties(record));
            graph.query(
                "MERGE (n:base {entity_id: $entity_id})\n"
                    + "SET n += $properties",
                parameters
            );
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        inGraph("save relation '%s'".formatted(record.id()), () -> {
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("src_id", record.srcId());
            parameters.put("tgt_id", record.tgtId());
            parameters.put("properties", relationProperties(record));
            var resultSet = graph.query(
                "MATCH (source:base {entity_id: $src_id})\n"
                    + "WITH source\n"
                    + "MATCH (target:base {entity_id: $tgt_id})\n"
                    + "WITH source, target\n"
                    // Delete any edge between the pair in either direction so a re-save replaces
                    // rather than duplicates, mirroring the upstream one-edge-per-pair model.
                    + "OPTIONAL MATCH (source)-[old:DIRECTED]-(target)\n"
                    + "DELETE old\n"
                    + "WITH source, target\n"
                    + "MERGE (source)-[r:DIRECTED]->(target)\n"
                    + "SET r += $properties\n"
                    + "RETURN r",
                parameters
            );
            if (resultSet.size() == 0) {
                // FalkorDB reports no error when an endpoint MATCH finds nothing; the empty result
                // is the only signal.
                throw new StorageException(edgeWriteLostMessage(record));
            }
            return null;
        });
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inGraph("load entity '%s'".formatted(id), () -> {
            var resultSet = graph.query(
                "MATCH (n:base {entity_id: $entity_id})\n"
                    + "RETURN n\n"
                    + "LIMIT 1",
                Map.of("entity_id", id)
            );
            var records = resultSet.iterator();
            return records.hasNext()
                ? Optional.of(toEntityRecord(nodeProperties(records.next().getValue(0))))
                : Optional.empty();
        });
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var id = Objects.requireNonNull(relationId, "relationId");
        return inGraph("load relation '%s'".formatted(id), () -> {
            var resultSet = graph.query(
                "MATCH ()-[r:DIRECTED]->()\n"
                    + "WHERE r.relation_id = $relation_id\n"
                    + "RETURN r\n"
                    + "LIMIT 1",
                Map.of("relation_id", id)
            );
            var records = resultSet.iterator();
            return records.hasNext()
                ? Optional.of(toRelationRecord(edgeProperties(records.next().getValue(0))))
                : Optional.empty();
        });
    }

    @Override
    public List<EntityRecord> allEntities() {
        return inGraph("load all entities", () -> {
            var resultSet = graph.query("MATCH (n:base)\nRETURN n");
            var entities = new ArrayList<EntityRecord>();
            for (var record : resultSet) {
                entities.add(toEntityRecord(nodeProperties(record.getValue(0))));
            }
            entities.sort(Comparator.comparing(EntityRecord::id));
            return List.copyOf(entities);
        });
    }

    @Override
    public List<RelationRecord> allRelations() {
        return inGraph("load all relations", () -> {
            var resultSet = graph.query("MATCH ()-[r:DIRECTED]->()\nRETURN r");
            var relations = new ArrayList<RelationRecord>();
            for (var record : resultSet) {
                relations.add(toRelationRecord(edgeProperties(record.getValue(0))));
            }
            relations.sort(Comparator.comparing(RelationRecord::id));
            return List.copyOf(relations);
        });
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inGraph("find relations of '%s'".formatted(id), () -> {
            var resultSet = graph.query(
                "MATCH (n:base {entity_id: $entity_id})-[r:DIRECTED]-()\n"
                    + "RETURN r",
                Map.of("entity_id", id)
            );
            var relations = new ArrayList<RelationRecord>();
            for (var record : resultSet) {
                relations.add(toRelationRecord(edgeProperties(record.getValue(0))));
            }
            relations.sort(Comparator.comparing(RelationRecord::id));
            return List.copyOf(relations);
        });
    }

    @Override
    public int deleteEntities(List<String> entityIds) {
        var ids = Objects.requireNonNull(entityIds, "entityIds");
        if (ids.isEmpty()) {
            return 0;
        }
        return inGraph("delete entities", () -> {
            var deleted = 0;
            for (var batch : batches(ids)) {
                var resultSet = graph.query(
                    "MATCH (n:base)\n"
                        + "WHERE n.entity_id IN $ids\n"
                        + "DETACH DELETE n",
                    Map.of("ids", batch)
                );
                deleted += resultSet.getStatistics().nodesDeleted();
            }
            return deleted;
        });
    }

    @Override
    public int deleteRelations(List<String> relationIds) {
        var ids = Objects.requireNonNull(relationIds, "relationIds");
        if (ids.isEmpty()) {
            return 0;
        }
        return inGraph("delete relations", () -> {
            var deleted = 0;
            for (var batch : batches(ids)) {
                // Fully-unbound undirected patterns expand to one row per direction, so the delete
                // matches the directed edge instead; a self-loop is deleted and counted once.
                var resultSet = graph.query(
                    "MATCH ()-[r:DIRECTED]->()\n"
                        + "WHERE r.relation_id IN $ids\n"
                        + "DELETE r",
                    Map.of("ids", batch)
                );
                deleted += resultSet.getStatistics().relationshipsDeleted();
            }
            return deleted;
        });
    }

    /** Entity ids in code-point order, upstream {@code get_all_labels}. */
    @Override
    public List<String> labels() {
        return inGraph("list entity ids", () -> {
            var resultSet = graph.query(
                "MATCH (n:base)\n"
                    + "WHERE n.entity_id IS NOT NULL\n"
                    + "RETURN DISTINCT n.entity_id AS value"
            );
            var labels = new ArrayList<String>();
            for (var record : resultSet) {
                var value = record.getString("value");
                if (value != null) {
                    labels.add(value);
                }
            }
            labels.sort(Comparator.naturalOrder());
            return List.copyOf(labels);
        });
    }

    /**
     * Store-native text search: Cypher filters the four candidate fields case-insensitively and
     * returns the matching vertices. Superset of the caller-side ranking per
     * {@link GraphStore#searchEntitiesByText}.
     */
    @Override
    public List<EntityRecord> searchEntitiesByText(String query) {
        var needle = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        return inGraph("search entities by text", () -> {
            var resultSet = graph.query(
                "MATCH (n:base)\n"
                    + "WHERE toLower(coalesce(n.name, '')) CONTAINS $query\n"
                    + "   OR toLower(coalesce(n.entity_type, '')) CONTAINS $query\n"
                    + "   OR toLower(coalesce(n.description, '')) CONTAINS $query\n"
                    + "   OR size([alias IN coalesce(n.aliases, []) WHERE toLower(alias) CONTAINS $query]) > 0\n"
                    + "RETURN n",
                Map.of("query", needle)
            );
            var entities = new ArrayList<EntityRecord>();
            for (var record : resultSet) {
                entities.add(toEntityRecord(nodeProperties(record.getValue(0))));
            }
            entities.sort(Comparator.comparing(EntityRecord::id));
            return List.copyOf(entities);
        });
    }

    /**
     * Executes one caller-supplied Cypher statement against this workspace graph and converts the
     * result to plain Java values: graph elements become structural maps ({@code {id, labels,
     * properties}} for nodes, {@code {id, type, source, destination, properties}} for edges), lists
     * and maps convert recursively. A statement without RETURN yields no columns and no records.
     */
    @Override
    public CypherQueryResult executeCypher(String cypher, Map<String, Object> parameters) {
        var query = Objects.requireNonNull(cypher, "cypher");
        var boundParameters = parameters == null ? Map.<String, Object>of() : parameters;
        return inGraph("execute cypher statement", () -> {
            var resultSet = graph.query(query, boundParameters);
            var columns = resultSet.getHeader().getSchemaNames();
            var records = new ArrayList<Map<String, Object>>();
            for (var record : resultSet) {
                var row = new LinkedHashMap<String, Object>();
                for (var index = 0; index < columns.size(); index++) {
                    row.put(columns.get(index), toJavaValue(record.getValue(index)));
                }
                records.add(Collections.unmodifiableMap(row));
            }
            return new CypherQueryResult(columns, records);
        });
    }

    /** Clears the graph; called by the adapter's restore path. */
    void clear() {
        inGraph("clear graph", () -> {
            graph.query("MATCH (n)\nDETACH DELETE n");
            return null;
        });
    }

    @Override
    public void close() {
        if (ownsDriver) {
            FalkorDbSupport.closeDriver(driver);
        }
    }

    /**
     * FalkorDB has no {@code IF NOT EXISTS} form for index creation and rejects a duplicate with
     * "Attribute 'entity_id' is already indexed", so existence is checked first and the duplicate
     * error is tolerated as the race between two concurrent store constructions.
     */
    private static void bootstrapEntityIdIndex(Graph graph) {
        if (graph.query(INDEX_EXISTS_QUERY).size() > 0) {
            return;
        }
        try {
            graph.query(CREATE_INDEX_QUERY);
        } catch (GraphException exception) {
            if (!isAlreadyIndexed(exception)) {
                throw exception;
            }
        }
    }

    private static boolean isAlreadyIndexed(GraphException exception) {
        var message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("already indexed");
    }

    private String edgeWriteLostMessage(RelationRecord relation) {
        var endpoints = List.of(relation.srcId(), relation.tgtId());
        List<String> present;
        try {
            present = presentEndpointIds(endpoints);
        } catch (RuntimeException probeFailure) {
            // Diagnostics only: the write already failed, so a probe failure must not mask it.
            log.debug("Could not probe FalkorDB endpoints for '{}'", relation.id(), probeFailure);
            return "FalkorDB edge upsert wrote nothing for '%s' -> '%s' (endpoint probe failed)"
                .formatted(relation.srcId(), relation.tgtId());
        }
        var missing = endpoints.stream().distinct().filter(endpoint -> !present.contains(endpoint)).toList();
        return ("FalkorDB edge upsert wrote nothing for '%s' -> '%s': missing endpoint(s) %s. FalkorDB reports no "
            + "error when a MATCH endpoint is absent from the graph.")
            .formatted(relation.srcId(), relation.tgtId(), missing);
    }

    private List<String> presentEndpointIds(List<String> entityIds) {
        var resultSet = graph.query(
            "UNWIND $ids AS id\n"
                + "OPTIONAL MATCH (n:base {entity_id: id})\n"
                + "WITH id, n\n"
                + "WHERE n IS NOT NULL\n"
                + "RETURN id",
            Map.of("ids", List.copyOf(new LinkedHashSet<>(entityIds)))
        );
        var present = new ArrayList<String>();
        for (var record : resultSet) {
            var id = record.getString(0);
            if (id != null) {
                present.add(id);
            }
        }
        return present;
    }

    private <T> T inGraph(String description, Supplier<T> work) {
        try {
            return work.get();
        } catch (StorageException exception) {
            throw exception;
        } catch (RuntimeException | Error failure) {
            throw new StorageException("FalkorDB %s failed".formatted(description), failure);
        }
    }

    private static Map<String, Object> nodeProperties(Object value) {
        return propertiesOf(requireGraphEntity(value, Node.class, "entity"));
    }

    private static Map<String, Object> edgeProperties(Object value) {
        return propertiesOf(requireGraphEntity(value, Edge.class, "relation"));
    }

    private static GraphEntity requireGraphEntity(Object value, Class<? extends GraphEntity> type, String label) {
        if (!type.isInstance(value)) {
            throw new StorageException(
                "FalkorDB returned a non-%s value for a %s row: %s".formatted(type.getSimpleName(), label, value)
            );
        }
        return (GraphEntity) value;
    }

    private static Map<String, Object> propertiesOf(GraphEntity entity) {
        var properties = new LinkedHashMap<String, Object>();
        for (var name : entity.getEntityPropertyNames()) {
            var property = entity.getProperty(name);
            properties.put(name, property == null ? null : property.getValue());
        }
        return properties;
    }

    private static EntityRecord toEntityRecord(Map<String, Object> properties) {
        var id = requiredText(properties, "entity_id");
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

    private static RelationRecord toRelationRecord(Map<String, Object> properties) {
        var srcId = requiredText(properties, "src_id");
        var tgtId = requiredText(properties, "tgt_id");
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

    private static String requiredText(Map<String, Object> properties, String key) {
        var value = textOrDefault(properties.get(key), "");
        if (value.isEmpty()) {
            throw new StorageException(
                "FalkorDB properties are missing '%s': %s".formatted(key, preview(properties))
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

    private static String preview(Object value) {
        var text = String.valueOf(value);
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
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

    private static Object toJavaValue(Object value) {
        if (value instanceof Node node) {
            var element = new LinkedHashMap<String, Object>();
            element.put("id", node.getId());
            var labels = new ArrayList<String>();
            for (var index = 0; index < node.getNumberOfLabels(); index++) {
                labels.add(node.getLabel(index));
            }
            element.put("labels", List.copyOf(labels));
            element.put("properties", propertiesOf(node));
            return Collections.unmodifiableMap(element);
        }
        if (value instanceof Edge edge) {
            var element = new LinkedHashMap<String, Object>();
            element.put("id", edge.getId());
            element.put("type", edge.getRelationshipType());
            element.put("source", edge.getSource());
            element.put("destination", edge.getDestination());
            element.put("properties", propertiesOf(edge));
            return Collections.unmodifiableMap(element);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(FalkorDbGraphStore::toJavaValue).toList();
        }
        if (value instanceof Map<?, ?> map) {
            var converted = new LinkedHashMap<String, Object>();
            map.forEach((key, element) -> converted.put(String.valueOf(key), toJavaValue(element)));
            return Collections.unmodifiableMap(converted);
        }
        return value;
    }
}
