package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.text.QueryLogSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class MilvusVectorStore implements HybridVectorStore, AutoCloseable {
    private static final List<Float> DEFAULT_HYBRID_WEIGHTS = List.of(0.5f, 0.5f);
    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    /**
     * Upper bound on the number of ids per {@code pk_id in [...]} point-read expression. {@link #technicalPrimaryKey}
     * yields {@code "pk-" + hex(MD5)} = 35 characters; with quotes and separators each id costs about 39 characters,
     * so 512 ids keep the expression near 20 KB — a 3x+ margin over the {@code max_expression_length} values
     * common on Milvus 3.x deployments. Re-verify against the actual deployment configuration before release.
     */
    static final int READ_ROWS_ID_BATCH_SIZE = 512;

    private final MilvusClientAdapter clientAdapter;
    private final MilvusVectorConfig config;
    private final List<String> workspaceIds;
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public MilvusVectorStore(MilvusVectorConfig config) {
        this(new MilvusSdkClientAdapter(config), config, "default");
    }

    public MilvusVectorStore(MilvusVectorConfig config, String workspaceId) {
        this(new MilvusSdkClientAdapter(config), config, workspaceId);
    }

    public MilvusVectorStore(MilvusClientAdapter clientAdapter, MilvusVectorConfig config) {
        this(clientAdapter, config, "default");
    }

    public MilvusVectorStore(MilvusClientAdapter clientAdapter, MilvusVectorConfig config, String workspaceId) {
        this(clientAdapter, config, List.of(workspaceId));
    }

    /**
     * Workspace-set store: searches and namespace listings match every workspace at once with a
     * {@code workspace_id in [...]} filter. Row-addressed operations (enriched writes, point reads,
     * id/namespace deletes) address single-workspace technical keys and reject multi-workspace
     * stores.
     */
    public MilvusVectorStore(MilvusClientAdapter clientAdapter, MilvusVectorConfig config, List<String> workspaceIds) {
        this.clientAdapter = Objects.requireNonNull(clientAdapter, "clientAdapter");
        this.config = Objects.requireNonNull(config, "config");
        var normalized = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(workspaceIds, "workspaceIds")));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("workspaceIds must not be empty");
        }
        this.workspaceIds = normalized;
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
        var singleWorkspaceId = singleWorkspaceId("saveAllEnriched");
        var normalizedNamespace = normalizeNamespace(namespace);
        var collectionName = collectionName();
        ensureCollection(namespace, collectionName);
        clientAdapter.upsert(collectionName, values.stream().map(record -> toStoredRow(singleWorkspaceId, normalizedNamespace, record)).toList());
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
        var normalizedNamespace = normalizeNamespace(namespace);
        var collectionName = collectionName();
        var filter = filter(normalizedNamespace);
        var searchRequest = Objects.requireNonNull(request, "request");
        return switch (searchRequest.mode()) {
            case SEMANTIC -> clientAdapter.semanticSearch(new MilvusClientAdapter.SemanticSearchRequest(
                collectionName,
                requireVector(searchRequest.queryVector(), "semantic"),
                searchRequest.topK(),
                filter
            ));
            case KEYWORD -> {
                var queryText = composeQueryText(searchRequest.queryText(), searchRequest.keywords());
                if (queryText.isBlank()) {
                    yield List.of();
                }
                yield clientAdapter.keywordSearch(new MilvusClientAdapter.KeywordSearchRequest(
                    collectionName,
                    queryText,
                    searchRequest.topK(),
                    filter
                ));
            }
            case HYBRID -> {
                var queryText = composeQueryText(searchRequest.queryText(), searchRequest.keywords());
                if (queryText.isBlank()) {
                    yield clientAdapter.semanticSearch(new MilvusClientAdapter.SemanticSearchRequest(
                        collectionName,
                        requireVector(searchRequest.queryVector(), "hybrid"),
                        searchRequest.topK(),
                        filter
                    ));
                }
                log.info(
                    "LightRAG Milvus hybrid search: collection={}, namespace={}, topK={}, ranker={}, rrfK={}, queryTextSignature={}, keywordCount={}",
                    collectionName,
                    normalizedNamespace,
                    searchRequest.topK(),
                    hybridRankerType(),
                    config.hybridRrfK(),
                    QueryLogSignatures.of(queryText),
                    QueryLogSignatures.count(searchRequest.keywords())
                );
                yield clientAdapter.hybridSearch(new MilvusClientAdapter.HybridSearchRequest(
                    collectionName,
                    requireVector(searchRequest.queryVector(), "hybrid"),
                    queryText,
                    searchRequest.topK(),
                    filter,
                    hybridRankerType(),
                    hybridRankerWeights(),
                    config.hybridRrfK()
                ));
            }
        };
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        return clientAdapter.list(new MilvusClientAdapter.ListRequest(collectionName(), filter(normalizeNamespace(namespace)))).stream()
            .sorted(java.util.Comparator.comparing(VectorRecord::id))
            .toList();
    }

    @Override
    public void close() {
        clientAdapter.close();
    }

    public void deleteNamespace(String namespace) {
        singleWorkspaceId("deleteNamespace");
        clientAdapter.deleteAll(new MilvusClientAdapter.DeleteRequest(collectionName(), filter(normalizeNamespace(namespace))));
    }

    public void deleteIds(String namespace, List<String> ids) {
        var values = List.copyOf(Objects.requireNonNull(ids, "ids"));
        if (values.isEmpty()) {
            return;
        }
        var singleWorkspaceId = singleWorkspaceId("deleteIds");
        var normalizedNamespace = normalizeNamespace(namespace);
        var idFilter = values.stream()
            .map(value -> technicalPrimaryKey(singleWorkspaceId, normalizedNamespace, value))
            .map(value -> "\"" + escapeFilterLiteral(value) + "\"")
            .collect(java.util.stream.Collectors.joining(", "));
        clientAdapter.deleteAll(new MilvusClientAdapter.DeleteRequest(
            collectionName(),
            filter(normalizedNamespace) + " && pk_id in [" + idFilter + "]"
        ));
    }

    public void flushNamespaces(List<String> namespaces) {
        if (!config.flushOnWrite()) {
            return;
        }
        if (namespaces.isEmpty()) {
            return;
        }
        clientAdapter.flush(List.of(collectionName()));
    }

    /**
     * Point-reads the rows for the given ids: input ids are deduplicated while keeping order, split into
     * {@link #READ_ROWS_ID_BATCH_SIZE} batches, and the merged result is rebuilt in deduplicated input order
     * (Milvus does not promise result order). An id with no row simply has no entry — that is the "absent" half
     * of the pre-image contract.
     */
    public List<MilvusClientAdapter.StoredVectorRow> readRows(String namespace, List<String> ids) {
        var values = List.copyOf(Objects.requireNonNull(ids, "ids"));
        if (values.isEmpty()) {
            return List.of();
        }
        var singleWorkspaceId = singleWorkspaceId("readRows");
        var normalizedNamespace = normalizeNamespace(namespace);
        var uniqueIds = new ArrayList<>(new LinkedHashSet<>(values));
        var rowsByVectorId = new LinkedHashMap<String, MilvusClientAdapter.StoredVectorRow>();
        for (int from = 0; from < uniqueIds.size(); from += READ_ROWS_ID_BATCH_SIZE) {
            var batch = uniqueIds.subList(from, Math.min(from + READ_ROWS_ID_BATCH_SIZE, uniqueIds.size()));
            var idFilter = batch.stream()
                .map(value -> technicalPrimaryKey(singleWorkspaceId, normalizedNamespace, value))
                .map(value -> "\"" + escapeFilterLiteral(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
            for (var row : clientAdapter.readRows(new MilvusClientAdapter.RowReadRequest(
                collectionName(),
                filter(normalizedNamespace) + " && pk_id in [" + idFilter + "]"
            ))) {
                rowsByVectorId.put(row.vectorId(), new MilvusClientAdapter.StoredVectorRow(
                    technicalPrimaryKey(singleWorkspaceId, normalizedNamespace, row.vectorId()),
                    row.vectorId(),
                    singleWorkspaceId,
                    normalizedNamespace,
                    row.vectorId(),
                    row.denseVector(),
                    row.searchableText(),
                    row.keywords(),
                    row.fullText(),
                    row.srcId(),
                    row.tgtId(),
                    row.filePath()
                ));
            }
        }
        return uniqueIds.stream()
            .map(rowsByVectorId::get)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Writes rows back verbatim: {@code full_text} is never recomputed here, because the persisted text already
     * carries the keywords that {@link #composeFullText} folded in (keywords themselves are not persisted).
     */
    public void writeRows(String namespace, List<MilvusClientAdapter.StoredVectorRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        singleWorkspaceId("writeRows");
        var normalizedNamespace = normalizeNamespace(namespace);
        var collectionName = collectionName();
        ensureCollection(normalizedNamespace, collectionName);
        clientAdapter.upsert(collectionName, rows);
    }

    private void ensureCollection(String namespace, String collectionName) {
        if (ensuredCollections.add(collectionName)) {
            clientAdapter.ensureCollection(new MilvusClientAdapter.CollectionDefinition(
                collectionName,
                namespace,
                config.vectorDimensions(),
                config.analyzerType()
            ));
        }
    }

    private MilvusClientAdapter.StoredVectorRow toStoredRow(String workspaceId, String namespace, EnrichedVectorRecord record) {
        validateVector(record.vector());
        return new MilvusClientAdapter.StoredVectorRow(
            technicalPrimaryKey(workspaceId, namespace, record.id()),
            record.id(),
            workspaceId,
            namespace,
            record.id(),
            record.vector(),
            record.searchableText(),
            record.keywords(),
            composeFullText(record.searchableText(), record.keywords()),
            record.srcId(),
            record.tgtId(),
            record.filePath()
        );
    }

    private String collectionName() {
        return config.sharedCollectionName();
    }

    private String normalizeNamespace(String namespace) {
        return Objects.requireNonNull(namespace, "namespace");
    }

    private String filter(String namespace) {
        return workspaceFilter() + " && record_type == \"" + escapeFilterLiteral(namespace) + "\"";
    }

    private String workspaceFilter() {
        if (workspaceIds.size() == 1) {
            return "workspace_id == \"" + escapeFilterLiteral(workspaceIds.get(0)) + "\"";
        }
        return "workspace_id in ["
            + workspaceIds.stream()
                .map(value -> "\"" + escapeFilterLiteral(value) + "\"")
                .collect(java.util.stream.Collectors.joining(", "))
            + "]";
    }

    /**
     * Row-addressed operations write the workspace into the technical key/row and therefore require
     * exactly one workspace; multi-workspace stores fail loudly instead of picking one.
     */
    private String singleWorkspaceId(String operation) {
        if (workspaceIds.size() == 1) {
            return workspaceIds.get(0);
        }
        throw new IllegalStateException(
            operation + " requires a single-workspace Milvus vector store; this store covers "
                + workspaceIds.size() + " workspaces");
    }

    private static String escapeFilterLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String technicalPrimaryKey(String workspaceId, String namespace, String vectorId) {
        try {
            var raw = workspaceId + "\u001F" + namespace + "\u001F" + vectorId;
            var digest = MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "pk-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is unavailable", exception);
        }
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
