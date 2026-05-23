package io.github.lightrag.storage;

import io.github.lightrag.api.MetadataOperator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface FilteredVectorStore extends VectorStore {
    List<VectorMatch> search(String namespace, List<Double> queryVector, int topK, MetadataFilter filter);

    default List<VectorMatch> search(String namespace, HybridVectorStore.SearchRequest request, MetadataFilter filter) {
        if (request.mode() == HybridVectorStore.SearchMode.SEMANTIC) {
            return search(namespace, request.queryVector(), request.topK(), filter);
        }
        if (this instanceof HybridVectorStore hybridVectorStore) {
            return hybridVectorStore.search(namespace, request);
        }
        return search(namespace, request.queryVector(), request.topK(), filter);
    }

    record MetadataFilter(
        Map<String, List<String>> equalsAny,
        List<Condition> conditions
    ) {
        public MetadataFilter {
            equalsAny = Map.copyOf(Objects.requireNonNull(equalsAny, "equalsAny"));
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
        }

        public static MetadataFilter empty() {
            return new MetadataFilter(Map.of(), List.of());
        }

        public boolean isEmpty() {
            return equalsAny.isEmpty() && conditions.isEmpty();
        }

        public boolean hasEarlyApplicablePredicates() {
            return !equalsAny.isEmpty() || conditions.stream().anyMatch(Condition::isEarlyApplicable);
        }
    }

    record Condition(
        String field,
        MetadataOperator operator,
        List<String> stringValues
    ) {
        public Condition {
            field = Objects.requireNonNull(field, "field");
            operator = Objects.requireNonNull(operator, "operator");
            stringValues = List.copyOf(Objects.requireNonNull(stringValues, "stringValues"));
        }

        public boolean isEarlyApplicable() {
            return operator == MetadataOperator.EQ || operator == MetadataOperator.IN;
        }
    }
}
