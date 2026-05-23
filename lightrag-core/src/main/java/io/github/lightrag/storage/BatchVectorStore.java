package io.github.lightrag.storage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface BatchVectorStore extends VectorStore {
    default Map<String, List<VectorMatch>> batchSearch(List<SearchSpec> searches) {
        var results = new LinkedHashMap<String, List<VectorMatch>>();
        for (var search : Objects.requireNonNull(searches, "searches")) {
            results.put(search.key(), search(search.namespace(), search.queryVector(), search.topK()));
        }
        return Map.copyOf(results);
    }

    record SearchSpec(
        String key,
        String namespace,
        List<Double> queryVector,
        String queryText,
        List<String> keywords,
        FilteredVectorStore.MetadataFilter metadataFilter,
        int topK
    ) {
        public SearchSpec(
            String key,
            String namespace,
            List<Double> queryVector,
            String queryText,
            List<String> keywords,
            int topK
        ) {
            this(key, namespace, queryVector, queryText, keywords, FilteredVectorStore.MetadataFilter.empty(), topK);
        }

        public SearchSpec {
            key = Objects.requireNonNull(key, "key");
            namespace = Objects.requireNonNull(namespace, "namespace");
            queryVector = List.copyOf(Objects.requireNonNull(queryVector, "queryVector"));
            queryText = queryText == null ? "" : queryText;
            keywords = List.copyOf(Objects.requireNonNull(keywords, "keywords"));
            metadataFilter = Objects.requireNonNull(metadataFilter, "metadataFilter");
            if (topK <= 0) {
                throw new IllegalArgumentException("topK must be positive");
            }
        }
    }
}
