package io.github.lightrag.storage.arcadedb;

import java.util.List;
import java.util.Objects;

public interface SparseEncoder {
    SparseVector encodeDocument(String text, List<String> keywords);

    SparseVector encodeQuery(String text, List<String> keywords);

    record SparseVector(List<Integer> tokens, List<Double> weights) {
        public SparseVector {
            tokens = List.copyOf(Objects.requireNonNull(tokens, "tokens"));
            weights = List.copyOf(Objects.requireNonNull(weights, "weights"));
            if (tokens.size() != weights.size()) {
                throw new IllegalArgumentException("sparse token and weight sizes must match");
            }
        }
    }
}
