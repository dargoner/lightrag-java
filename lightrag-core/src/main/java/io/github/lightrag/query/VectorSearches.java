package io.github.lightrag.query;

import io.github.lightrag.storage.BatchVectorStore;
import io.github.lightrag.storage.FilteredVectorStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class VectorSearches {
    private static final Logger log = LoggerFactory.getLogger(VectorSearches.class);

    private VectorSearches() {
    }

    static List<VectorStore.VectorMatch> search(
        VectorStore vectorStore,
        String namespace,
        List<Double> queryVector,
        String queryText,
        List<String> keywords,
        int topK
    ) {
        return search(
            vectorStore,
            namespace,
            queryVector,
            queryText,
            keywords,
            FilteredVectorStore.MetadataFilter.empty(),
            topK
        );
    }

    static List<VectorStore.VectorMatch> search(
        VectorStore vectorStore,
        String namespace,
        List<Double> queryVector,
        String queryText,
        List<String> keywords,
        FilteredVectorStore.MetadataFilter metadataFilter,
        int topK
    ) {
        var store = Objects.requireNonNull(vectorStore, "vectorStore");
        var normalizedNamespace = Objects.requireNonNull(namespace, "namespace");
        var normalizedVector = List.copyOf(Objects.requireNonNull(queryVector, "queryVector"));
        var normalizedKeywords = normalizeKeywords(keywords);
        var normalizedFilter = Objects.requireNonNull(metadataFilter, "metadataFilter");
        if (!(store instanceof HybridVectorStore hybridVectorStore)) {
            log.info(
                "LightRAG vector search dispatch: storeType={}, namespace={}, mode={}, topK={}, vectorDims={}, queryText={}, keywords={}, metadataFilter={}",
                store.getClass().getSimpleName(),
                normalizedNamespace,
                "SEMANTIC",
                topK,
                normalizedVector.size(),
                queryText == null ? "" : queryText,
                normalizedKeywords,
                !normalizedFilter.isEmpty()
            );
            if (store instanceof FilteredVectorStore filteredVectorStore && !normalizedFilter.isEmpty()) {
                return filteredVectorStore.search(normalizedNamespace, normalizedVector, topK, normalizedFilter);
            }
            return store.search(normalizedNamespace, normalizedVector, topK);
        }
        var mode = searchMode(normalizedVector, normalizedKeywords);
        log.info(
            "LightRAG vector search dispatch: storeType={}, namespace={}, mode={}, topK={}, vectorDims={}, queryText={}, keywords={}, metadataFilter={}",
            store.getClass().getSimpleName(),
            normalizedNamespace,
            mode,
            topK,
            normalizedVector.size(),
            queryText == null ? "" : queryText,
            normalizedKeywords,
            !normalizedFilter.isEmpty()
        );
        var request = new HybridVectorStore.SearchRequest(
            normalizedVector,
            queryText == null ? "" : queryText,
            normalizedKeywords,
            mode,
            topK
        );
        if (hybridVectorStore instanceof FilteredVectorStore filteredVectorStore && !normalizedFilter.isEmpty()) {
            return filteredVectorStore.search(normalizedNamespace, request, normalizedFilter);
        }
        return hybridVectorStore.search(normalizedNamespace, request);
    }

    static Map<String, List<VectorStore.VectorMatch>> batchSearch(VectorStore vectorStore, List<BatchVectorStore.SearchSpec> searches) {
        var store = Objects.requireNonNull(vectorStore, "vectorStore");
        var specs = List.copyOf(Objects.requireNonNull(searches, "searches"));
        if (specs.isEmpty()) {
            return Map.of();
        }
        if (store instanceof BatchVectorStore batchVectorStore) {
            log.info(
                "LightRAG vector batch search dispatch: storeType={}, searchCount={}, keys={}",
                store.getClass().getSimpleName(),
                specs.size(),
                specs.stream().map(BatchVectorStore.SearchSpec::key).toList()
            );
            return batchVectorStore.batchSearch(specs);
        }
        var results = new LinkedHashMap<String, List<VectorStore.VectorMatch>>();
        for (var spec : specs) {
            results.put(spec.key(), search(
                store,
                spec.namespace(),
                spec.queryVector(),
                spec.queryText(),
                spec.keywords(),
                spec.metadataFilter(),
                spec.topK()
            ));
        }
        return Map.copyOf(results);
    }

    static List<String> mergeKeywords(List<String> primary, List<String> secondary) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(normalizeKeywords(primary));
        merged.addAll(normalizeKeywords(secondary));
        return List.copyOf(merged);
    }

    private static HybridVectorStore.SearchMode searchMode(List<Double> queryVector, List<String> keywords) {
        if (keywords.isEmpty()) {
            return HybridVectorStore.SearchMode.SEMANTIC;
        }
        if (queryVector.isEmpty()) {
            return HybridVectorStore.SearchMode.KEYWORD;
        }
        return HybridVectorStore.SearchMode.HYBRID;
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            normalized.add(keyword.strip());
        }
        return List.copyOf(normalized);
    }
}
