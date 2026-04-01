package io.github.lightrag.query;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

final class VectorSearches {
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
        var store = Objects.requireNonNull(vectorStore, "vectorStore");
        var normalizedNamespace = Objects.requireNonNull(namespace, "namespace");
        var normalizedVector = List.copyOf(Objects.requireNonNull(queryVector, "queryVector"));
        var normalizedKeywords = normalizeKeywords(keywords);
        if (!(store instanceof HybridVectorStore hybridVectorStore)) {
            return store.search(normalizedNamespace, normalizedVector, topK);
        }
        return hybridVectorStore.search(
            normalizedNamespace,
            new HybridVectorStore.SearchRequest(
                normalizedVector,
                queryText == null ? "" : queryText,
                normalizedKeywords,
                searchMode(normalizedVector, normalizedKeywords),
                topK
            )
        );
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
