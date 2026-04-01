package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MilvusVectorStore implements HybridVectorStore, AutoCloseable {
    private static final List<Float> DEFAULT_HYBRID_WEIGHTS = List.of(0.5f, 0.5f);

    private final MilvusClientAdapter clientAdapter;
    private final MilvusVectorConfig config;
    private final String workspaceId;
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public MilvusVectorStore(MilvusVectorConfig config) {
        this(new MilvusSdkClientAdapter(config), config, "default");
    }

    public MilvusVectorStore(MilvusVectorConfig config, String workspaceId) {
        this(new MilvusSdkClientAdapter(config), config, workspaceId);
    }

    MilvusVectorStore(MilvusClientAdapter clientAdapter, MilvusVectorConfig config, String workspaceId) {
        this.clientAdapter = Objects.requireNonNull(clientAdapter, "clientAdapter");
        this.config = Objects.requireNonNull(config, "config");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        var values = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
        if (values.isEmpty()) {
            return;
        }
        saveAllEnriched(namespace, values.stream()
            .map(vector -> new EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
            .toList());
    }

    @Override
    public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
        var values = List.copyOf(Objects.requireNonNull(records, "records"));
        if (values.isEmpty()) {
            return;
        }
        var collectionName = collectionName(namespace);
        ensureCollection(collectionName);
        clientAdapter.upsert(collectionName, values.stream().map(this::toStoredRow).toList());
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        return search(namespace, new SearchRequest(
            List.copyOf(Objects.requireNonNull(queryVector, "queryVector")),
            "",
            List.of(),
            SearchMode.SEMANTIC,
            topK
        ));
    }

    @Override
    public List<VectorMatch> search(String namespace, SearchRequest request) {
        var collectionName = collectionName(namespace);
        var searchRequest = Objects.requireNonNull(request, "request");
        return switch (searchRequest.mode()) {
            case SEMANTIC -> clientAdapter.semanticSearch(new MilvusClientAdapter.SemanticSearchRequest(
                collectionName,
                requireVector(searchRequest.queryVector(), "semantic"),
                searchRequest.topK()
            ));
            case KEYWORD -> {
                var queryText = composeQueryText(searchRequest.queryText(), searchRequest.keywords());
                if (queryText.isBlank()) {
                    yield List.of();
                }
                yield clientAdapter.keywordSearch(new MilvusClientAdapter.KeywordSearchRequest(
                    collectionName,
                    queryText,
                    searchRequest.topK()
                ));
            }
            case HYBRID -> {
                var queryText = composeQueryText(searchRequest.queryText(), searchRequest.keywords());
                if (queryText.isBlank()) {
                    yield clientAdapter.semanticSearch(new MilvusClientAdapter.SemanticSearchRequest(
                        collectionName,
                        requireVector(searchRequest.queryVector(), "hybrid"),
                        searchRequest.topK()
                    ));
                }
                yield clientAdapter.hybridSearch(new MilvusClientAdapter.HybridSearchRequest(
                    collectionName,
                    requireVector(searchRequest.queryVector(), "hybrid"),
                    queryText,
                    searchRequest.topK(),
                    hybridRankerType(),
                    hybridRankerWeights(),
                    config.hybridRrfK()
                ));
            }
        };
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        return clientAdapter.list(collectionName(namespace)).stream()
            .sorted(java.util.Comparator.comparing(VectorRecord::id))
            .toList();
    }

    @Override
    public void close() {
        clientAdapter.close();
    }

    public void deleteNamespace(String namespace) {
        clientAdapter.deleteAll(collectionName(namespace));
    }

    public void flushNamespaces(List<String> namespaces) {
        clientAdapter.flush(namespaces.stream().map(this::collectionName).distinct().toList());
    }

    private void ensureCollection(String collectionName) {
        if (ensuredCollections.add(collectionName)) {
            clientAdapter.ensureCollection(new MilvusClientAdapter.CollectionDefinition(
                collectionName,
                config.vectorDimensions(),
                config.analyzerType()
            ));
        }
    }

    private MilvusClientAdapter.StoredVectorRow toStoredRow(EnrichedVectorRecord record) {
        validateVector(record.vector());
        return new MilvusClientAdapter.StoredVectorRow(
            record.id(),
            record.vector(),
            record.searchableText(),
            record.keywords(),
            composeFullText(record.searchableText(), record.keywords())
        );
    }

    private String collectionName(String namespace) {
        return config.collectionName(workspaceId, Objects.requireNonNull(namespace, "namespace"));
    }

    private List<Double> requireVector(List<Double> vector, String label) {
        validateVector(vector);
        return List.copyOf(vector);
    }

    private void validateVector(List<Double> vector) {
        var values = List.copyOf(Objects.requireNonNull(vector, "vector"));
        if (values.size() != config.vectorDimensions()) {
            throw new IllegalArgumentException("vector dimensions must match configured dimensions");
        }
    }

    private static String composeFullText(String searchableText, List<String> keywords) {
        var normalizedText = searchableText == null ? "" : searchableText.strip();
        var normalizedKeywords = normalizeKeywords(keywords);
        if (normalizedText.isBlank()) {
            return String.join(" ", normalizedKeywords);
        }
        if (normalizedKeywords.isEmpty()) {
            return normalizedText;
        }
        return normalizedText + "\n" + String.join(" ", normalizedKeywords);
    }

    private static String composeQueryText(String queryText, List<String> keywords) {
        var values = new LinkedHashSet<String>();
        if (queryText != null && !queryText.isBlank()) {
            values.add(queryText.strip());
        }
        values.addAll(normalizeKeywords(keywords));
        return String.join(" ", values);
    }

    private static List<String> normalizeKeywords(List<String> keywords) {
        var values = new LinkedHashSet<String>();
        for (var keyword : Objects.requireNonNull(keywords, "keywords")) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            values.add(keyword.strip());
        }
        return List.copyOf(values);
    }

    private MilvusClientAdapter.HybridRankerType hybridRankerType() {
        return switch (config.hybridRanker()) {
            case "weighted" -> MilvusClientAdapter.HybridRankerType.WEIGHTED;
            default -> MilvusClientAdapter.HybridRankerType.RRF;
        };
    }

    private List<Float> hybridRankerWeights() {
        return hybridRankerType() == MilvusClientAdapter.HybridRankerType.WEIGHTED
            ? DEFAULT_HYBRID_WEIGHTS
            : List.of();
    }
}
