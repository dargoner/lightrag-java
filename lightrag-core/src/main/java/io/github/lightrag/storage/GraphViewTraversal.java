package io.github.lightrag.storage;

import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.GraphRelation;
import io.github.lightrag.api.KnowledgeGraphView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Single implementation of the bounded traversal behind
 * {@link GraphStore#getKnowledgeGraph(String, int, int)}.
 *
 * <p>The default store method feeds it an in-memory snapshot; adapters with native traversal
 * override {@code getKnowledgeGraph} and drive the same algorithm through a {@link Support} that
 * pushes the data access down to the backend. Sharing the algorithm is what keeps an override
 * item-for-item equivalent to the default implementation, which is the contract the two are
 * tested against.
 */
public final class GraphViewTraversal {
    private GraphViewTraversal() {
    }

    /**
     * Backend data access for {@link #compute(Support, String, int, int)}. Implementations may
     * fetch lazily; every method only has to answer for the ids it is asked about.
     */
    public interface Support {
        /** Whether a stored (materialized) entity with this id exists. */
        boolean containsEntity(String id);

        /** Up to {@code limit} stored entity ids ordered by {@code (degree desc, id asc)}. */
        List<String> rankedEntityIds(int limit);

        /** Endpoint degree of each id: one per relation holding the id as source or target. */
        Map<String, Integer> degrees(Collection<String> ids);

        /** Distinct neighbors (either direction) per id; ids without relations are absent. */
        Map<String, Set<String>> adjacency(Collection<String> ids);

        /** Stored entity records per id; ids without a stored entity are absent. */
        Map<String, GraphStore.EntityRecord> entities(Collection<String> ids);

        /** Relations with both endpoints inside {@code included}, in stable store order. */
        List<GraphStore.RelationRecord> relationsWithin(Set<String> included);
    }

    static Support inMemory(List<GraphStore.EntityRecord> entities, List<GraphStore.RelationRecord> relations) {
        var entitiesById = new LinkedHashMap<String, GraphStore.EntityRecord>();
        for (var entity : entities) {
            entitiesById.put(entity.id(), entity);
        }
        var degrees = new LinkedHashMap<String, Integer>();
        var adjacency = new LinkedHashMap<String, Set<String>>();
        for (var relation : relations) {
            degrees.merge(relation.srcId(), 1, Integer::sum);
            degrees.merge(relation.tgtId(), 1, Integer::sum);
            adjacency.computeIfAbsent(relation.srcId(), ignored -> new LinkedHashSet<>()).add(relation.tgtId());
            adjacency.computeIfAbsent(relation.tgtId(), ignored -> new LinkedHashSet<>()).add(relation.srcId());
        }
        return new Support() {
            @Override
            public boolean containsEntity(String id) {
                return entitiesById.containsKey(id);
            }

            @Override
            public List<String> rankedEntityIds(int limit) {
                var ranked = new ArrayList<>(entitiesById.keySet());
                ranked.sort(byDegreeDescendingThenId(degrees));
                return List.copyOf(ranked.subList(0, Math.min(limit, ranked.size())));
            }

            @Override
            public Map<String, Integer> degrees(Collection<String> ids) {
                var resolved = new LinkedHashMap<String, Integer>();
                for (var id : ids) {
                    resolved.put(id, degrees.getOrDefault(id, 0));
                }
                return resolved;
            }

            @Override
            public Map<String, Set<String>> adjacency(Collection<String> ids) {
                var resolved = new LinkedHashMap<String, Set<String>>();
                for (var id : ids) {
                    var neighbors = adjacency.get(id);
                    if (neighbors != null) {
                        resolved.put(id, neighbors);
                    }
                }
                return resolved;
            }

            @Override
            public Map<String, GraphStore.EntityRecord> entities(Collection<String> ids) {
                var resolved = new LinkedHashMap<String, GraphStore.EntityRecord>();
                for (var id : ids) {
                    var entity = entitiesById.get(id);
                    if (entity != null) {
                        resolved.put(id, entity);
                    }
                }
                return resolved;
            }

            @Override
            public List<GraphStore.RelationRecord> relationsWithin(Set<String> included) {
                var filtered = new ArrayList<GraphStore.RelationRecord>();
                for (var relation : relations) {
                    if (included.contains(relation.srcId()) && included.contains(relation.tgtId())) {
                        filtered.add(relation);
                    }
                }
                return List.copyOf(filtered);
            }
        };
    }

    public static void validate(String nodeLabel, int maxDepth, int maxNodes) {
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
    }

    public static KnowledgeGraphView compute(Support support, String nodeLabel, int maxDepth, int maxNodes) {
        Objects.requireNonNull(support, "support");
        validate(nodeLabel, maxDepth, maxNodes);
        var label = nodeLabel.strip();

        if (label.equals("*")) {
            var limit = maxNodes == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxNodes + 1;
            var ranked = support.rankedEntityIds(limit);
            var truncated = ranked.size() > maxNodes;
            var includedIds = truncated ? List.copyOf(ranked.subList(0, maxNodes)) : ranked;
            return viewOf(includedIds, support, truncated);
        }

        if (!support.containsEntity(label)) {
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
            level.sort(byDegreeDescendingThenId(support.degrees(level)));
            var adjacency = support.adjacency(level);
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
        return viewOf(discovered, support, hasMaxNodesTruncation || hasUnexploredNeighbors);
    }

    private static Comparator<String> byDegreeDescendingThenId(Map<String, Integer> degrees) {
        return Comparator
            .comparingInt((String id) -> degrees.getOrDefault(id, 0))
            .reversed()
            .thenComparing(Comparator.naturalOrder());
    }

    private static KnowledgeGraphView viewOf(List<String> nodeIds, Support support, boolean truncated) {
        var included = new LinkedHashSet<>(nodeIds);
        var storedEntities = support.entities(included);
        var nodes = new ArrayList<GraphEntity>(included.size());
        for (var nodeId : included) {
            var entity = storedEntities.get(nodeId);
            if (entity != null) {
                nodes.add(new GraphEntity(
                    entity.id(),
                    entity.name(),
                    entity.type(),
                    entity.description(),
                    entity.aliases(),
                    entity.sourceChunkIds(),
                    entity.filePath()
                ));
            }
        }
        var seenRelationIds = new LinkedHashSet<String>();
        var edges = new ArrayList<GraphRelation>();
        for (var relation : support.relationsWithin(included)) {
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
}
