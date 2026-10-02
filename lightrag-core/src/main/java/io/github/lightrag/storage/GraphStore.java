package io.github.lightrag.storage;

import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

public interface GraphStore {
    void saveEntity(EntityRecord entity);

    void saveRelation(RelationRecord relation);

    default void saveEntities(List<EntityRecord> entities) {
        Objects.requireNonNull(entities, "entities");
        for (var entity : entities) {
            saveEntity(entity);
        }
    }

    default void saveRelations(List<RelationRecord> relations) {
        Objects.requireNonNull(relations, "relations");
        for (var relation : relations) {
            saveRelation(relation);
        }
    }

    Optional<EntityRecord> loadEntity(String entityId);

    Optional<RelationRecord> loadRelation(String relationId);

    default Optional<RelationRecord> loadRelation(String sourceEntityId, String targetEntityId) {
        var canonical = RelationCanonicalizer.canonicalize(sourceEntityId, targetEntityId);
        return findRelations(canonical.srcId()).stream()
            .filter(relation ->
                relation.srcId().equals(canonical.srcId())
                    && relation.tgtId().equals(canonical.tgtId()))
            .findFirst();
    }

    default List<EntityRecord> loadEntities(List<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        var entities = new ArrayList<EntityRecord>(entityIds.size());
        for (var entityId : entityIds) {
            loadEntity(entityId).ifPresent(entities::add);
        }
        return entities;
    }

    default List<RelationRecord> loadRelations(List<String> relationIds) {
        Objects.requireNonNull(relationIds, "relationIds");
        var relations = new ArrayList<RelationRecord>(relationIds.size());
        for (var relationId : relationIds) {
            loadRelation(relationId).ifPresent(relations::add);
        }
        return relations;
    }

    List<EntityRecord> allEntities();

    List<RelationRecord> allRelations();

    List<RelationRecord> findRelations(String entityId);

    default Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
        for (var entityId : entityIds) {
            relationsByEntityId.put(entityId, List.copyOf(findRelations(entityId)));
        }
        return java.util.Collections.unmodifiableMap(relationsByEntityId);
    }

    /**
     * All entity ids, sorted by code point (upstream {@code get_all_labels}, {@code base.py:1111-1117}).
     * The default implementation scans {@link #allEntities()}; graph-database adapters that can answer
     * this natively should override it.
     */
    default List<String> labels() {
        var ids = new TreeSet<String>();
        for (var entity : allEntities()) {
            ids.add(entity.id());
        }
        return List.copyOf(ids);
    }

    /**
     * Entity ids whose id or name contains {@code query}, case-insensitive. Case-insensitive exact
     * matches come first, the rest follow in code-point order, and the result is cut to {@code limit}.
     * A blank query or a non-positive limit yields an empty list.
     */
    default List<String> searchLabels(String query, int limit) {
        var normalizedQuery = Objects.requireNonNull(query, "query").strip();
        if (normalizedQuery.isEmpty() || limit <= 0) {
            return List.of();
        }
        var needle = normalizedQuery.toLowerCase(Locale.ROOT);
        var matches = new LinkedHashMap<String, Integer>();
        for (var entity : allEntities()) {
            if (entity.id().toLowerCase(Locale.ROOT).contains(needle)
                || entity.name().toLowerCase(Locale.ROOT).contains(needle)) {
                var exact = entity.id().equalsIgnoreCase(normalizedQuery)
                    || entity.name().equalsIgnoreCase(normalizedQuery);
                matches.put(entity.id(), exact ? 0 : 1);
            }
        }
        var ordered = new ArrayList<>(matches.keySet());
        ordered.sort(Comparator.comparingInt(matches::get));
        return List.copyOf(ordered.subList(0, Math.min(limit, ordered.size())));
    }

    /**
     * Bounded graph view for visualization-style consumers. This Java contract is normative:
     * {@code "*"} ranks all entities by {@code (degree desc, entity id asc)} and cuts to
     * {@code maxNodes}; any other label runs a frontier-capped BFS from that entity, ordering each
     * depth level the same way. An unknown label yields an empty view. Edges are returned only when
     * both endpoints are in the result, even if an endpoint has no stored entity; {@code truncated}
     * reports the node budget, the depth limit, or nodes left unprocessed inside a depth level.
     *
     * <p>The ranking/traversal semantics originated in the upstream networkx view
     * ({@code kg/networkx_impl.py:1087-1250}), which keeps evolving and is not a per-item authority;
     * the Java contract above and its tests are. The default implementation is O(graph) per call -
     * it materializes entities, relations and degrees once - which is fine for the
     * visualization-sized budget this exists for, but adapters with native traversal should
     * override it through {@link GraphViewTraversal} so overrides stay item-for-item equivalent.
     */
    default KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) {
        GraphViewTraversal.validate(nodeLabel, maxDepth, maxNodes);
        return GraphViewTraversal.compute(
            GraphViewTraversal.inMemory(allEntities(), allRelations()),
            nodeLabel,
            maxDepth,
            maxNodes
        );
    }

    record EntityRecord(
        String id,
        String name,
        String type,
        String description,
        List<String> aliases,
        List<String> sourceChunkIds,
        String filePath
    ) {
        public EntityRecord(
            String id,
            String name,
            String type,
            String description,
            List<String> aliases,
            List<String> sourceChunkIds
        ) {
            this(id, name, type, description, aliases, sourceChunkIds, "");
        }

        public EntityRecord {
            id = Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            description = Objects.requireNonNull(description, "description");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
            sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
            filePath = filePath == null ? "" : filePath.strip();
        }

        public List<String> filePaths() {
            return RelationCanonicalizer.splitValues(filePath);
        }
    }

    record RelationRecord(
        String relationId,
        String srcId,
        String tgtId,
        String keywords,
        String description,
        double weight,
        String sourceId,
        String filePath
    ) {
        public RelationRecord(
            String relationId,
            String srcId,
            String tgtId,
            String keywords,
            String description,
            double weight,
            List<String> sourceChunkIds
        ) {
            this(
                relationId,
                srcId,
                tgtId,
                keywords,
                description,
                weight,
                RelationCanonicalizer.joinValues(sourceChunkIds),
                ""
            );
        }

        public RelationRecord {
            relationId = Objects.requireNonNull(relationId, "relationId");
            srcId = Objects.requireNonNull(srcId, "srcId");
            tgtId = Objects.requireNonNull(tgtId, "tgtId");
            keywords = Objects.requireNonNull(keywords, "keywords");
            description = Objects.requireNonNull(description, "description");
            sourceId = sourceId == null ? "" : sourceId;
            filePath = filePath == null ? "" : filePath.strip();
        }

        public String id() {
            return relationId;
        }

        public List<String> sourceChunkIds() {
            return RelationCanonicalizer.splitValues(sourceId);
        }

        public List<String> filePaths() {
            return RelationCanonicalizer.splitValues(filePath);
        }
    }
}
