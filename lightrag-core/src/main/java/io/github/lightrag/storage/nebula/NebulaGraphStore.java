package io.github.lightrag.storage.nebula;

import com.vesoft.nebula.client.graph.SessionPool;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.BindSpaceFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.InvalidValueException;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.indexing.RelationCanonicalizer;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.MutableGraphStore;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Knowledge-graph store backed by NebulaGraph 3.x with nGQL. Vertices are the {@code entity} tag
 * and relations the {@code directed} edge, and every workspace of a deployment shares one space
 * (see {@link NebulaGraphConfig}); a vertex id is a SHA-256 digest of workspace plus entity id
 * (see {@link NebulaSupport#scopedVid}), so workspaces cannot collide inside the shared space.
 *
 * <p>A relation whose endpoints were never saved still stores its edge: the endpoints are first
 * projected as placeholder vertices with {@code materialized = false}, and the read paths treat
 * those placeholders as absent entities while the edge stays visible - the same shell-vertex model
 * the Neo4j backend uses. Saving a relation deletes an edge between the same endpoint pair in the
 * opposite direction, keeping the upstream one-edge-per-pair invariant. The upstream reference
 * (dev-nebula-graph {@code nebula_impl.py}) targets NebulaGraph v5 Enterprise ISO GQL and is not
 * portable to the 3.x OSS nGQL dialect this store speaks; the observable contract mirrors the
 * other Java backends instead. There is no Cypher engine here, so {@link #executeCypher} stays
 * rejected and {@link #getKnowledgeGraph} uses the default traversal over the Java read paths.</p>
 */
public final class NebulaGraphStore implements MutableGraphStore, AutoCloseable {
    private final SessionPool pool;
    private final boolean ownsPool;
    private final String workspaceId;

    /** Boots the space (idempotently) and opens its session pool, closed by {@link #close()}. */
    public NebulaGraphStore(NebulaGraphConfig config, String workspaceId) {
        this(NebulaSupport.openSessionPool(Objects.requireNonNull(config, "config")), workspaceId, true);
    }

    /** Borrowed-pool constructor; {@link #close()} then leaves the pool untouched. */
    public NebulaGraphStore(SessionPool pool, String workspaceId) {
        this(pool, workspaceId, false);
    }

    NebulaGraphStore(SessionPool pool, String workspaceId, boolean ownsPool) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.ownsPool = ownsPool;
        this.workspaceId = NebulaSupport.requireNonBlank(workspaceId, "workspaceId");
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        inSpace("save entity '%s'".formatted(record.id()), () -> {
            execute("save entity '%s'".formatted(record.id()), saveEntityStatement(record));
            return null;
        });
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        inSpace("save relation '%s'".formatted(record.id()), () -> {
            var description = "save relation '%s'".formatted(record.id());
            var srcVid = NebulaSupport.scopedVid(workspaceId, record.srcId());
            var tgtVid = NebulaSupport.scopedVid(workspaceId, record.tgtId());
            execute(description, placeholderStatement(record, srcVid, tgtVid));
            execute(
                description,
                "INSERT EDGE directed(relation_id, src_id, tgt_id, keywords, description, weight, source_id, file_path, workspace_id) VALUES "
                    + NebulaSupport.quoted(srcVid) + "->" + NebulaSupport.quoted(tgtVid) + ":"
                    + tuple(
                        record.id(),
                        record.srcId(),
                        record.tgtId(),
                        record.keywords(),
                        record.description(),
                        record.weight(),
                        record.sourceId(),
                        record.filePath(),
                        workspaceId
                    )
                    + ";"
            );
            if (!srcVid.equals(tgtVid)) {
                // Upstream keeps at most one edge per endpoint pair: a save whose endpoints match an
                // existing edge in the opposite direction replaces it instead of leaving two.
                execute(
                    description,
                    "DELETE EDGE directed " + NebulaSupport.quoted(tgtVid) + "->" + NebulaSupport.quoted(srcVid) + ";"
                );
            }
            return null;
        });
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inSpace("load entity '%s'".formatted(id), () -> {
            var resultSet = execute(
                "load entity '%s'".formatted(id),
                "MATCH (v:entity) WHERE id(v) == "
                    + NebulaSupport.quoted(NebulaSupport.scopedVid(workspaceId, id))
                    + " RETURN properties(v) AS props;"
            );
            if (resultSet.rowsSize() == 0) {
                return Optional.empty();
            }
            var properties = propertyMap(resultSet.rowValues(0).get("props"));
            return isMaterialized(properties) ? Optional.of(toEntityRecord(properties)) : Optional.empty();
        });
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var id = Objects.requireNonNull(relationId, "relationId");
        return inSpace("load relation '%s'".formatted(id), () -> {
            var resultSet = execute(
                "load relation '%s'".formatted(id),
                relationLookupStatement(id, "properties(edge) AS props")
            );
            return resultSet.rowsSize() == 0
                ? Optional.empty()
                : Optional.of(toRelationRecord(propertyMap(resultSet.rowValues(0).get("props"))));
        });
    }

    @Override
    public List<EntityRecord> allEntities() {
        return inSpace("load all entities", () -> {
            var resultSet = execute(
                "load all entities",
                "LOOKUP ON entity WHERE entity.workspace_id == " + NebulaSupport.quoted(workspaceId)
                    + " YIELD properties(vertex) AS props;"
            );
            var entities = new ArrayList<EntityRecord>();
            for (var index = 0; index < resultSet.rowsSize(); index++) {
                var properties = propertyMap(resultSet.rowValues(index).get("props"));
                if (isMaterialized(properties)) {
                    entities.add(toEntityRecord(properties));
                }
            }
            entities.sort(Comparator.comparing(EntityRecord::id));
            return List.copyOf(entities);
        });
    }

    @Override
    public List<RelationRecord> allRelations() {
        return inSpace("load all relations", () -> {
            var resultSet = execute(
                "load all relations",
                "LOOKUP ON directed WHERE directed.workspace_id == " + NebulaSupport.quoted(workspaceId)
                    + " YIELD properties(edge) AS props;"
            );
            var relations = new ArrayList<RelationRecord>();
            for (var index = 0; index < resultSet.rowsSize(); index++) {
                relations.add(toRelationRecord(propertyMap(resultSet.rowValues(index).get("props"))));
            }
            relations.sort(Comparator.comparing(RelationRecord::id));
            return List.copyOf(relations);
        });
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var id = Objects.requireNonNull(entityId, "entityId");
        return inSpace("find relations of '%s'".formatted(id), () -> {
            var resultSet = execute(
                "find relations of '%s'".formatted(id),
                "MATCH (v:entity)-[e:directed]-() WHERE id(v) == "
                    + NebulaSupport.quoted(NebulaSupport.scopedVid(workspaceId, id))
                    + " RETURN properties(e) AS props;"
            );
            var relations = new ArrayList<RelationRecord>();
            for (var index = 0; index < resultSet.rowsSize(); index++) {
                relations.add(toRelationRecord(propertyMap(resultSet.rowValues(index).get("props"))));
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
        return inSpace("delete entities", () -> {
            var deleted = 0;
            for (var batch : NebulaSupport.batches(List.copyOf(new LinkedHashSet<>(ids)))) {
                var literals = batch.stream()
                    .map(id -> NebulaSupport.quoted(NebulaSupport.scopedVid(workspaceId, id)))
                    .collect(Collectors.joining(", "));
                // The vertices are counted before they are deleted because DELETE VERTEX reports no
                // rows; ids that never resolved to a vertex (real or placeholder) stay uncounted.
                var matched = execute(
                    "delete entities",
                    "MATCH (v:entity) WHERE id(v) IN [" + literals + "] RETURN id(v) AS vid;"
                );
                if (matched.rowsSize() == 0) {
                    continue;
                }
                deleted += matched.rowsSize();
                execute("delete entities", "DELETE VERTEX " + literals + " WITH EDGE;");
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
        return inSpace("delete relations", () -> {
            var deleted = 0;
            for (var batch : NebulaSupport.batches(List.copyOf(new LinkedHashSet<>(ids)))) {
                for (var relationId : batch) {
                    var matches = execute(
                        "delete relations",
                        relationLookupStatement(relationId, "src(edge) AS s, dst(edge) AS d")
                    );
                    for (var index = 0; index < matches.rowsSize(); index++) {
                        var row = matches.rowValues(index);
                        execute(
                            "delete relations",
                            "DELETE EDGE directed "
                                + NebulaSupport.quoted(NebulaSupport.string(row.get("s"), "edge source"))
                                + "->"
                                + NebulaSupport.quoted(NebulaSupport.string(row.get("d"), "edge destination"))
                                + ";"
                        );
                        deleted++;
                    }
                }
            }
            return deleted;
        });
    }

    /** Clears every vertex of the workspace (edges cascade); called by the adapter's restore path. */
    void clear() {
        inSpace("clear graph", () -> {
            var resultSet = execute(
                "clear graph",
                "LOOKUP ON entity WHERE entity.workspace_id == " + NebulaSupport.quoted(workspaceId)
                    + " YIELD id(vertex) AS vid;"
            );
            var vids = new ArrayList<String>(resultSet.rowsSize());
            for (var index = 0; index < resultSet.rowsSize(); index++) {
                vids.add(NebulaSupport.string(resultSet.rowValues(index).get("vid"), "vertex id"));
            }
            for (var batch : NebulaSupport.batches(vids)) {
                var literals = batch.stream().map(NebulaSupport::quoted).collect(Collectors.joining(", "));
                execute("clear graph", "DELETE VERTEX " + literals + " WITH EDGE;");
            }
            return null;
        });
    }

    @Override
    public void close() {
        if (ownsPool) {
            pool.close();
        }
    }

    private String saveEntityStatement(EntityRecord record) {
        return "INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, file_path, workspace_id, materialized) VALUES "
            + NebulaSupport.quoted(NebulaSupport.scopedVid(workspaceId, record.id()))
            + ":"
            + tuple(
                record.id(),
                record.name(),
                record.type(),
                record.description(),
                RelationCanonicalizer.joinValues(record.aliases()),
                RelationCanonicalizer.joinValues(record.sourceChunkIds()),
                record.filePath(),
                workspaceId,
                true
            )
            + ";";
    }

    /**
     * Creates the relation's missing endpoints as placeholder vertices. {@code IF NOT EXISTS}
     * leaves entities saved earlier (and their {@code materialized = true}) untouched, so a
     * placeholder only ever describes an endpoint that has no entity yet.
     */
    private String placeholderStatement(RelationRecord record, String srcVid, String tgtVid) {
        var statement = new StringBuilder(
            "INSERT VERTEX IF NOT EXISTS entity(entity_id, workspace_id, materialized) VALUES "
        );
        statement.append(NebulaSupport.quoted(srcVid)).append(':').append(tuple(record.srcId(), workspaceId, false));
        if (!srcVid.equals(tgtVid)) {
            statement.append(',').append(NebulaSupport.quoted(tgtVid)).append(':')
                .append(tuple(record.tgtId(), workspaceId, false));
        }
        return statement.append(';').toString();
    }

    private String relationLookupStatement(String relationId, String yield) {
        return "LOOKUP ON directed WHERE directed.workspace_id == " + NebulaSupport.quoted(workspaceId)
            + " AND directed.relation_id == " + NebulaSupport.quoted(relationId)
            + " YIELD " + yield + ";";
    }

    private <T> T inSpace(String description, Supplier<T> work) {
        try {
            return work.get();
        } catch (StorageException exception) {
            throw exception;
        } catch (RuntimeException | Error failure) {
            throw new StorageException("NebulaGraph %s failed".formatted(description), failure);
        }
    }

    private ResultSet execute(String description, String statement) {
        try {
            var resultSet = pool.execute(statement);
            if (resultSet == null || !resultSet.isSucceeded()) {
                throw new StorageException(
                    "NebulaGraph %s failed: %s".formatted(description, NebulaSupport.errorText(resultSet))
                );
            }
            return resultSet;
        } catch (StorageException exception) {
            throw exception;
        } catch (IOErrorException | ClientServerIncompatibleException | AuthFailedException
                 | BindSpaceFailedException exception) {
            throw new StorageException("NebulaGraph %s failed".formatted(description), exception);
        }
    }

    private static Map<String, Object> propertyMap(ValueWrapper wrapper) {
        if (wrapper == null || !wrapper.isMap()) {
            throw new StorageException("NebulaGraph returned a non-map properties value: " + wrapper);
        }
        try {
            var properties = new LinkedHashMap<String, Object>();
            for (var entry : wrapper.asMap().entrySet()) {
                properties.put(entry.getKey(), toJavaValue(entry.getValue()));
            }
            return properties;
        } catch (InvalidValueException | UnsupportedEncodingException exception) {
            throw new StorageException("NebulaGraph properties conversion failed", exception);
        }
    }

    private static Object toJavaValue(ValueWrapper wrapper) {
        try {
            if (wrapper == null || wrapper.isNull()) {
                return null;
            }
            if (wrapper.isBoolean()) {
                return wrapper.asBoolean();
            }
            if (wrapper.isLong()) {
                return wrapper.asLong();
            }
            if (wrapper.isDouble()) {
                return wrapper.asDouble();
            }
            if (wrapper.isString()) {
                return wrapper.asString();
            }
        } catch (InvalidValueException | UnsupportedEncodingException exception) {
            throw new StorageException("NebulaGraph property conversion failed", exception);
        }
        throw new StorageException("NebulaGraph returned an unsupported property value: " + wrapper);
    }

    /** Placeholder vertices of relation endpoints read as absent entities; anything else is real. */
    private static boolean isMaterialized(Map<String, Object> properties) {
        return !Boolean.FALSE.equals(properties.get("materialized"));
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
            RelationCanonicalizer.splitValues(textOrDefault(properties.get("aliases"), "")),
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
                "NebulaGraph properties are missing '%s': %s".formatted(key, preview(properties))
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

    private static String preview(Object value) {
        var text = String.valueOf(value);
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** One VALUES field list: strings become quoted literals, everything else a raw literal. */
    private static String tuple(Object... fields) {
        var parts = new ArrayList<String>(fields.length);
        for (var field : fields) {
            parts.add(field instanceof String text ? NebulaSupport.quoted(text) : String.valueOf(field));
        }
        return "(" + String.join(", ", parts) + ")";
    }
}
