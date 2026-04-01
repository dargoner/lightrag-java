package io.github.lightrag.indexing;

@FunctionalInterface
public interface SemanticSimilarity {
    double score(String left, String right);
}
