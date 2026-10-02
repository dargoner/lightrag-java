package io.github.lightrag.storage;

import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.GraphRelation;
import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
     * Bounded traversal mirroring the upstream graph view ({@code kg/networkx_impl.py:1087-1250}):
     * {@code "*"} ranks the whole graph by {@code (degree desc, label asc)} and cuts to
     * {@code maxNodes}; any other label runs a frontier-capped BFS from that entity, ordering each
     * depth level the same way. An unknown label yields an empty view. Edges are returned only when
     * both endpoints are in the result; {@code truncated} reports the node budget, the depth limit,
     * or both.
     *
     * <p>The default implementation is O(graph) per call - it materializes entities, relations and
     * degrees once - which is fine for the visualization-sized budget this exists for, but adapters
     * with native traversal should override it.
     */
    default KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) {
        var label = Objects.requireNonNull(nodeLabel, "nodeLabel").strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("nodeLabel must not be blank");
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must not be negative");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
        var entitiesById = new LinkedHashMap<String, EntityRecord>();
        for (var entity : allEntities()) {
            entitiesById.put(entity.id(), entity);
        }
        var relations = allRelations();
        var degrees = new LinkedHashMap<String, Integer>();
        var adjacency = new LinkedHashMap<String, Set<String>>();
        for (var relation : relations) {
            degrees.merge(relation.srcId(), 1, Integer::sum);
            degrees.merge(relation.tgtId(), 1, Integer::sum);
            adjacency.computeIfAbsent(relation.srcId(), ignored -> new LinkedHashSet<>()).add(relation.tgtId());
            adjacency.computeIfAbsent(relation.tgtId(), ignored -> new LinkedHashSet<>()).add(relation.srcId());
        }

        if (label.equals("*")) {
            var ranked = new ArrayList<>(entitiesById.keySet());
            ranked.sort(Comparator
                .comparingInt((String id) -> degrees.getOrDefault(id, 0))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
            var truncated = ranked.size() > maxNodes;
            return viewOf(
                truncated ? List.copyOf(ranked.subList(0, maxNodes)) : ranked,
                entitiesById,
                relations,
                truncated
            );
        }

        if (!entitiesById.containsKey(label)) {
            return new KnowledgeGraphView(List.of(), List.of(), false);
        }
        var discovered = new ArrayList<String>();
        var visited = new LinkedHashSet<String>();
        var frontier = new ArrayList<String>();
        frontier.add(label);
        var depth = 0;
        var hasUnexploredNeighbors = false;
        var hasUnprocessedLevelNodes = false;
        while (!frontier.isEmpty() && discovered.size() < maxNodes) {
            var level = new ArrayList<>(frontier);
            frontier.clear();
            level.sort(Comparator
                .comparingInt((String id) -> degrees.getOrDefault(id, 0))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
            for (var index = 0; index < level.size(); index++) {
                var current = level.get(index);
                if (visited.add(current)) {
                    discovered.add(current);
                    var unvisitedNeighbors = adjacency.getOrDefault(current, Set.of()).stream()
                        .filter(neighbor -> !visited.contains(neighbor))
                        .toList();
                    if (depth < maxDepth) {
                        frontier.addAll(unvisitedNeighbors);
                    } else if (!unvisitedNeighbors.isEmpty()) {
                        hasUnexploredNeighbors = true;
                    }
                }
                if (discovered.size() >= maxNodes) {
                    hasUnprocessedLevelNodes = level.subList(index + 1, level.size()).stream()
                        .anyMatch(node -> !visited.contains(node));
                    break;
                }
            }
            depth++;
        }
        var hasUnvisitedInQueue = frontier.stream().anyMatch(node -> !visited.contains(node));
        var hasMaxNodesTruncation = discovered.size() >= maxNodes
            && (hasUnvisitedInQueue || hasUnprocessedLevelNodes || hasUnexploredNeighbors);
        return viewOf(discovered, entitiesById, relations, hasMaxNodesTruncation || hasUnexploredNeighbors);
    }

    private static KnowledgeGraphView viewOf(
        List<String> nodeIds,
        Map<String, EntityRecord> entitiesById,
        List<RelationRecord> relations,
        boolean truncated
    ) {
        var included = new LinkedHashSet<>(nodeIds);
        var nodes = new ArrayList<GraphEntity>(included.size());
        for (var nodeId : included) {
            var entity = entitiesById.get(nodeId);
            if (entity != null) {
                nodes.add(new GraphEntity(
                    entity.id(),
                    entity.name(),
                    entity.type(),
                    entity.description(),
                    entity.aliases(),
                    entity.sourceChunkIds()
                ));
            }
        }
        var seenRelationIds = new LinkedHashSet<String>();
        var edges = new ArrayList<GraphRelation>();
        for (var relation : relations) {
            if (!included.contains(relation.srcId()) || !included.contains(relation.tgtId())) {
                continue;
            }
            if (!seenRelationIds.add(relation.id())) {
                continue;
            }
            edges.add(new GraphRelation(
                relation.relationId(),
                relation.srcId(),
                relation.tgtId(),
                relation.keywords(),
                relation.description(),
                relation.weight(),
                relation.sourceId(),
                relation.filePath()
            ));
        }
        return new KnowledgeGraphView(nodes, edges, truncated);
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
