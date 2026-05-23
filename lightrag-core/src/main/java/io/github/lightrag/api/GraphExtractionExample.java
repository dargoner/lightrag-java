package io.github.lightrag.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record GraphExtractionExample(
    String text,
    List<GraphExtractionNode> nodes,
    List<GraphExtractionRelation> relations
) {
    public GraphExtractionExample {
        text = requireNonBlank(text, "example text");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        if (relations.isEmpty()) {
            throw new IllegalArgumentException("relations must not be empty");
        }
        var nodeNames = new HashSet<String>();
        for (var node : nodes) {
            if (!nodeNames.add(node.name())) {
                throw new IllegalArgumentException("duplicate node name: " + node.name());
            }
        }
        for (var relation : relations) {
            if (!nodeNames.contains(relation.node1())) {
                throw new IllegalArgumentException("relation references unknown node1: " + relation.node1());
            }
            if (!nodeNames.contains(relation.node2())) {
                throw new IllegalArgumentException("relation references unknown node2: " + relation.node2());
            }
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
