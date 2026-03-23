package io.github.lightragjava.indexing;

@FunctionalInterface
public interface SemanticSimilarity {
    double score(String left, String right);
}
