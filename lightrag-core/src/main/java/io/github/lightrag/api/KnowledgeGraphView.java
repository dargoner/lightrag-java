package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

/**
 * A bounded slice of the knowledge graph: the nodes discovered by a traversal, the edges whose two
 * endpoints both survived the bound, and whether the traversal stopped early (node budget, depth
 * limit, or both).
 */
public record KnowledgeGraphView(
    List<GraphEntity> nodes,
    List<GraphRelation> edges,
    boolean truncated
) {
    public KnowledgeGraphView {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
    }
}
