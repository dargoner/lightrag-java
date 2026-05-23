package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.BatchVectorStore;
import io.github.lightrag.storage.FilteredVectorStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ArcadeVectorStore extends ArcadeStoreSupport implements BatchVectorStore, HybridVectorStore, FilteredVectorStore {
    private static final int RRF_K = 60;
    private static final Map<String, String> PUSH_DOWN_FIELDS = Map.ofEntries(
        Map.entry("documentId", "documentId"),
        Map.entry("document_id", "documentId"),
        Map.entry("sourceId", "sourceId"),
        Map.entry("source_id", "sourceId"),
        Map.entry("filePath", "filePath"),
        Map.entry("file_path", "filePath"),
        Map.entry("contentType", "contentType"),
        Map.entry("content_type", "contentType"),
        Map.entry("smart_chunker.content_type", "contentType"),
        Map.entry("sectionPath", "sectionPath"),
        Map.entry("section_path", "sectionPath"),
        Map.entry("smart_chunker.section_path", "sectionPath"),
        Map.entry("source", "source"),
        Map.entry("tenantId", "tenantId"),
        Map.entry("tenant_id", "tenantId"),
        Map.entry("createdAt", "createdAt"),
        Map.entry("created_at", "createdAt"),
        Map.entry("searchable", "searchable")
    );

    private final int vectorDimensions;
    private final SparseEncoder sparseEncoder;

    public ArcadeVectorStore(ArcadeDbClient client, String workspaceId, int vectorDimensions) {
        this(client, workspaceId, vectorDimensions, new NgramSparseEncoder());
    }

    public ArcadeVectorStore(
        ArcadeDbClient client,
        String workspaceId,
        int vectorDimensions,
        SparseEncoder sparseEncoder
    ) {
        super(client, workspaceId);
        this.vectorDimensions = vectorDimensions;
        this.sparseEncoder = Objects.requireNonNull(sparseEncoder, "sparseEncoder");
    }

    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        saveAllEnriched(
            namespace,
            Objects.requireNonNull(vectors, "vectors").stream()
                .map(vector -> new EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
                .toList()
        );
    }

    @Override
    public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
        var targetNamespace = Objects.requireNonNull(namespace, "namespace");
        for (var record : Objects.requireNonNull(records, "records")) {
            validate(record.vector());
            var existing = first(
                "SELECT @rid FROM VectorEntry WHERE workspaceId = ? AND namespace = ? AND id = ? LIMIT 1",
                workspaceId,
                targetNamespace,
                record.id()
            );
            if (existing.isPresent()) {
                var sparseVector = sparseEncoder.encodeDocument(record.searchableText(), record.keywords());
                execute(
                    "UPDATE VectorEntry SET embedding = ?, searchableText = ?, keywords = ?, sparseTokens = ?, sparseWeights = ?, srcId = ?, tgtId = ?, filePath = ?, documentId = ?, sourceId = ?, contentType = ?, sectionPath = ?, source = ?, tenantId = ?, createdAt = ?, searchable = ? WHERE workspaceId = ? AND namespace = ? AND id = ?",
                    record.vector(),
                    record.searchableText(),
                    ArcadeJsonCodec.writeJson(record.keywords()),
                    sparseVector.tokens(),
                    sparseVector.weights(),
                    record.srcId(),
                    record.tgtId(),
                    record.filePath(),
                    record.documentId(),
                    record.sourceId(),
                    record.contentType(),
                    record.sectionPath(),
                    record.source(),
                    record.tenantId(),
                    record.createdAt(),
                    record.searchable(),
                    workspaceId,
                    targetNamespace,
                    record.id()
                );
            } else {
                var sparseVector = sparseEncoder.encodeDocument(record.searchableText(), record.keywords());
                execute(
                    "INSERT INTO VectorEntry SET workspaceId = ?, namespace = ?, id = ?, embedding = ?, searchableText = ?, keywords = ?, sparseTokens = ?, sparseWeights = ?, srcId = ?, tgtId = ?, filePath = ?, documentId = ?, sourceId = ?, contentType = ?, sectionPath = ?, source = ?, tenantId = ?, createdAt = ?, searchable = ?",
                    workspaceId,
                    targetNamespace,
                    record.id(),
                    record.vector(),
                    record.searchableText(),
                    ArcadeJsonCodec.writeJson(record.keywords()),
                    sparseVector.tokens(),
                    sparseVector.weights(),
                    record.srcId(),
                    record.tgtId(),
                    record.filePath(),
                    record.documentId(),
                    record.sourceId(),
                    record.contentType(),
                    record.sectionPath(),
                    record.source(),
                    record.tenantId(),
                    record.createdAt(),
                    record.searchable()
                );
            }
            saveVectorMetadata(targetNamespace, record.id(), metadataFor(record));
        }
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        return search(namespace, queryVector, topK, FilteredVectorStore.MetadataFilter.empty());
    }

    @Override
    public List<VectorMatch> search(
        String namespace,
        List<Double> queryVector,
        int topK,
        FilteredVectorStore.MetadataFilter filter
    ) {
        if (topK <= 0) {
            return List.of();
        }
        validate(queryVector);
        var filterClause = vectorFilterClause(namespace, filter, false);
        if (!filterClause.metadataFiltered()) {
            filterClause = vectorFilterClause(namespace, filter, true);
            if (filterClause.allowedRids().isEmpty()) {
                return List.of();
            }
        }
        try {
            var params = new LinkedHashMap<String, Object>();
            params.put("queryVector", queryVector);
            params.put("topK", topK);
            params.put("efSearch", Math.max(topK * 4, 50));
            params.putAll(filterClause.params());
            if (!filterClause.metadataFiltered()) {
                params.put("allowedRids", filterClause.allowedRids());
            }
            var filterExpression = filterClause.metadataFiltered()
                ? "(" + filterClause.sql() + ")"
                : ":allowedRids";
            var rows = query(
                ("""
                SELECT id, distance
                FROM (
                    SELECT expand(vector.neighbors(
                        'VectorEntry[embedding]',
                        :queryVector,
                        :topK,
                        {
                            efSearch: :efSearch,
                            filter: %s
                        }
                    ))
                )
                WHERE %s
                LIMIT :topK
                """).formatted(filterExpression, filterClause.whereSql()),
                params
            );
            var matches = rows.stream()
                .map(this::readNativeMatch)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id))
                .limit(topK)
                .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
        } catch (RuntimeException exception) {
            // Fall back to deterministic local scoring when the server-side vector path is unavailable.
        }
        return list(namespace, filterClause).stream()
            .map(record -> new VectorMatch(record.id(), cosine(queryVector, record.vector())))
            .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id))
            .limit(topK)
            .toList();
    }

    @Override
    public List<VectorMatch> search(String namespace, SearchRequest request) {
        return search(namespace, request, FilteredVectorStore.MetadataFilter.empty());
    }

    @Override
    public List<VectorMatch> search(
        String namespace,
        SearchRequest request,
        FilteredVectorStore.MetadataFilter filter
    ) {
        var searchRequest = Objects.requireNonNull(request, "request");
        if (searchRequest.mode() == SearchMode.SEMANTIC) {
            return search(namespace, searchRequest.queryVector(), searchRequest.topK(), filter);
        }
        if (searchRequest.mode() == SearchMode.KEYWORD) {
            return bm25Search(namespace, searchRequest, searchRequest.topK(), filter);
        }
        validate(searchRequest.queryVector());
        return hybridSearch(namespace, searchRequest, filter);
    }

    private List<VectorMatch> bm25Search(
        String namespace,
        SearchRequest request,
        int topK,
        FilteredVectorStore.MetadataFilter filter
    ) {
        var queryText = composeQueryText(request.queryText(), request.keywords());
        if (queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        var querySparse = sparseEncoder.encodeQuery(request.queryText(), request.keywords());
        var candidateTopK = candidateTopK(topK);
        if (querySparse.tokens().isEmpty()) {
            return List.of();
        }
        var filterClause = vectorFilterClause(namespace, filter, true);
        try {
            if (filterClause.allowedRids().isEmpty()) {
                return List.of();
            }
            var params = new LinkedHashMap<String, Object>();
            params.putAll(filterClause.params());
            params.put("sparseTokens", querySparse.tokens());
            params.put("sparseWeights", querySparse.weights());
            params.put("candidateTopK", candidateTopK);
            params.put("allowedRids", filterClause.allowedRids());
            params.put("topK", topK);
            var rows = query(
                ("""
                SELECT id, score
                FROM (
                    SELECT expand(vector.sparseNeighbors(
                        'VectorEntry[sparseTokens,sparseWeights]',
                        :sparseTokens,
                        :sparseWeights,
                        :candidateTopK,
                        {
                            filter: :allowedRids
                        }
                    ))
                )
                WHERE %s
                ORDER BY score DESC
                LIMIT :topK
                """).formatted(filterClause.whereSql()),
                params
            );
            var matches = rows.stream()
                .map(this::readKeywordMatch)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id))
                .limit(topK)
                .toList();
            if (!matches.isEmpty()) {
                return matches;
            }
        } catch (RuntimeException exception) {
            throw new io.github.lightrag.exception.StorageException("ArcadeDB sparse BM25 search failed", exception);
        }
        return List.of();
    }

    private List<VectorMatch> hybridSearch(
        String namespace,
        SearchRequest request,
        FilteredVectorStore.MetadataFilter filter
    ) {
        var queryText = composeQueryText(request.queryText(), request.keywords());
        if (queryText.isBlank()) {
            return search(namespace, request.queryVector(), request.topK(), filter);
        }
        var querySparse = sparseEncoder.encodeQuery(request.queryText(), request.keywords());
        if (querySparse.tokens().isEmpty()) {
            return search(namespace, request.queryVector(), request.topK(), filter);
        }
        var candidateTopK = candidateTopK(request.topK());
        var filterClause = vectorFilterClause(namespace, filter, true);
        try {
            if (filterClause.allowedRids().isEmpty()) {
                return List.of();
            }
            var params = new LinkedHashMap<String, Object>();
            params.putAll(filterClause.params());
            params.put("queryVector", request.queryVector());
            params.put("candidateTopK", candidateTopK);
            params.put("efSearch", Math.max(candidateTopK * 4, 50));
            params.put("sparseTokens", querySparse.tokens());
            params.put("sparseWeights", querySparse.weights());
            params.put("allowedRids", filterClause.allowedRids());
            params.put("rrfK", RRF_K);
            params.put("topK", request.topK());
            var rows = query(
                ("""
                SELECT id, score
                FROM (
                    SELECT expand(vector.fuse(
                        vector.neighbors(
                            'VectorEntry[embedding]',
                            :queryVector,
                            :candidateTopK,
                            {
                                efSearch: :efSearch,
                                filter: :allowedRids
                            }
                        ),
                        vector.sparseNeighbors(
                            'VectorEntry[sparseTokens,sparseWeights]',
                            :sparseTokens,
                            :sparseWeights,
                            :candidateTopK,
                            {
                                filter: :allowedRids
                            }
                        ),
                        {
                            fusion: 'RRF',
                            k: :rrfK
                        }
                    ))
                )
                WHERE %s
                LIMIT :topK
                """).formatted(filterClause.whereSql()),
                params
            );
            return rows.stream()
                .map(this::readFusedMatch)
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed().thenComparing(VectorMatch::id))
                .limit(request.topK())
                .toList();
        } catch (RuntimeException exception) {
            throw new io.github.lightrag.exception.StorageException("ArcadeDB RRF hybrid search failed", exception);
        }
    }

    @Override
    public Map<String, List<VectorMatch>> batchSearch(List<SearchSpec> searches) {
        var results = new LinkedHashMap<String, List<VectorMatch>>();
        for (var search : Objects.requireNonNull(searches, "searches")) {
            validate(search.queryVector());
            var request = new SearchRequest(
                    search.queryVector(),
                    search.queryText(),
                    search.keywords(),
                    searchMode(search.queryVector(), search.keywords()),
                    search.topK()
                );
            results.put(search.key(), search(search.namespace(), request, search.metadataFilter()));
        }
        return Map.copyOf(results);
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        return list(namespace, vectorFilterClause(namespace, FilteredVectorStore.MetadataFilter.empty(), false));
    }

    private List<VectorRecord> list(String namespace, FilterClause filterClause) {
        return query(
            "SELECT id, embedding FROM VectorEntry WHERE " + filterClause.whereSql() + " ORDER BY id",
            filterClause.params()
        ).stream().map(row -> new VectorRecord(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.doubleList(row, "embedding")
        )).toList();
    }

    void deleteAll() {
        deleteWorkspaceRows("VectorMetadata");
        deleteWorkspaceRows("VectorEntry");
    }

    private void saveVectorMetadata(String namespace, String vectorId, Map<String, String> metadata) {
        execute(
            "DELETE FROM VectorMetadata WHERE workspaceId = ? AND namespace = ? AND vectorId = ?",
            workspaceId,
            namespace,
            vectorId
        );
        for (var entry : metadata.entrySet()) {
            var field = normalizeMetadataField(entry.getKey());
            var value = entry.getValue();
            if (field.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            execute(
                "INSERT INTO VectorMetadata SET workspaceId = ?, namespace = ?, vectorId = ?, field = ?, value = ?",
                workspaceId,
                namespace,
                vectorId,
                field,
                value.strip()
            );
        }
    }

    private void validate(List<Double> vector) {
        Objects.requireNonNull(vector, "vector");
        if (vector.size() != vectorDimensions) {
            throw new IllegalArgumentException("vector dimensions must match configured dimensions");
        }
    }

    private static double cosine(List<Double> left, List<Double> right) {
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.size(); i++) {
            var l = left.get(i);
            var r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static int candidateTopK(int topK) {
        return Math.max(topK * 4, 50);
    }

    private static SearchMode searchMode(List<Double> queryVector, List<String> keywords) {
        if (keywords.isEmpty()) {
            return SearchMode.SEMANTIC;
        }
        if (queryVector.isEmpty()) {
            return SearchMode.KEYWORD;
        }
        return SearchMode.HYBRID;
    }

    private static String composeQueryText(String queryText, List<String> keywords) {
        if (queryText != null && !queryText.isBlank()) {
            return queryText.strip();
        }
        return Objects.requireNonNull(keywords, "keywords").stream()
            .filter(keyword -> keyword != null && !keyword.isBlank())
            .map(String::strip)
            .findFirst()
            .orElse("");
    }

    private FilterClause vectorFilterClause(
        String namespace,
        FilteredVectorStore.MetadataFilter filter,
        boolean resolveRids
    ) {
        var where = new StringBuilder("workspaceId = :workspaceId AND namespace = :namespace");
        var params = new LinkedHashMap<String, Object>();
        params.put("workspaceId", workspaceId);
        params.put("namespace", namespace);
        var normalizedFilter = filter == null ? FilteredVectorStore.MetadataFilter.empty() : filter;
        appendFilterPredicates(namespace, where, params, normalizedFilter);
        var baseWhereSql = "workspaceId = :workspaceId AND namespace = :namespace";
        var whereSql = where.toString();
        var sql = "SELECT @rid FROM VectorEntry WHERE " + whereSql;
        var allowedRids = resolveRids
            ? query(sql.replace("@rid", "@rid AS rid"), params).stream()
                .map(row -> ArcadeRecordMapper.string(row, "rid"))
                .filter(rid -> !rid.isBlank())
                .toList()
            : List.<String>of();
        return new FilterClause(sql, whereSql, Map.copyOf(params), allowedRids, !whereSql.equals(baseWhereSql));
    }

    private static void appendFilterPredicates(
        String namespace,
        StringBuilder where,
        LinkedHashMap<String, Object> params,
        FilteredVectorStore.MetadataFilter filter
    ) {
        var seen = new HashMap<String, Integer>();
        for (var entry : filter.equalsAny().entrySet()) {
            appendEqualsAny(namespace, where, params, seen, entry.getKey(), entry.getValue());
        }
        for (var condition : filter.conditions()) {
            if (!condition.isEarlyApplicable()) {
                continue;
            }
            appendEqualsAny(namespace, where, params, seen, condition.field(), condition.stringValues());
        }
    }

    private static void appendEqualsAny(
        String namespace,
        StringBuilder where,
        LinkedHashMap<String, Object> params,
        HashMap<String, Integer> seen,
        String field,
        List<String> values
    ) {
        var column = pushDownColumn(namespace, field);
        if (values == null || values.isEmpty()) {
            return;
        }
        var normalizedValues = values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::strip)
            .distinct()
            .toList();
        if (normalizedValues.isEmpty()) {
            return;
        }
        if (column != null) {
            var key = nextParam(column, seen);
            where.append(" AND ").append(column).append(" IN :").append(key);
            if ("searchable".equals(column)) {
                params.put(key, normalizedValues.stream().map(Boolean::parseBoolean).toList());
            } else {
                params.put(key, normalizedValues);
            }
        } else {
            appendDynamicMetadataFilter(namespace, where, params, seen, field, normalizedValues);
        }
    }

    private static String pushDownColumn(String namespace, String field) {
        var column = PUSH_DOWN_FIELDS.get(field);
        if (column == null) {
            return null;
        }
        return switch (namespace) {
            case "chunks" -> column;
            case "relations" -> ("filePath".equals(column) || "sourceId".equals(column)) ? column : null;
            default -> null;
        };
    }

    private static void appendDynamicMetadataFilter(
        String namespace,
        StringBuilder where,
        LinkedHashMap<String, Object> params,
        HashMap<String, Integer> seen,
        String field,
        List<String> values
    ) {
        if (!"chunks".equals(namespace)) {
            return;
        }
        var fieldKey = nextParam("metadataField", seen);
        var valueKey = nextParam("metadataValue", seen);
        where.append("""
             AND id IN (
                 SELECT vectorId
                 FROM VectorMetadata
                 WHERE workspaceId = :workspaceId
                   AND namespace = :namespace
                   AND field = :%s
                   AND value IN :%s
             )
            """.formatted(fieldKey, valueKey));
        params.put(fieldKey, normalizeMetadataField(field));
        params.put(valueKey, values);
    }

    private static Map<String, String> metadataFor(EnrichedVectorRecord record) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.putAll(record.metadata());
        putIfNotBlank(metadata, "documentId", record.documentId());
        putIfNotBlank(metadata, "document_id", record.documentId());
        putIfNotBlank(metadata, "sourceId", record.sourceId());
        putIfNotBlank(metadata, "source_id", record.sourceId());
        putIfNotBlank(metadata, "filePath", record.filePath());
        putIfNotBlank(metadata, "file_path", record.filePath());
        putIfNotBlank(metadata, "contentType", record.contentType());
        putIfNotBlank(metadata, "content_type", record.contentType());
        putIfNotBlank(metadata, "sectionPath", record.sectionPath());
        putIfNotBlank(metadata, "section_path", record.sectionPath());
        putIfNotBlank(metadata, "source", record.source());
        putIfNotBlank(metadata, "tenantId", record.tenantId());
        putIfNotBlank(metadata, "tenant_id", record.tenantId());
        putIfNotBlank(metadata, "createdAt", record.createdAt());
        putIfNotBlank(metadata, "created_at", record.createdAt());
        metadata.put("searchable", Boolean.toString(record.searchable()));
        return metadata;
    }

    private static void putIfNotBlank(Map<String, String> metadata, String field, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(field, value.strip());
        }
    }

    private static String normalizeMetadataField(String field) {
        return field == null ? "" : field.strip();
    }

    private static String nextParam(String column, HashMap<String, Integer> seen) {
        var count = seen.merge(column, 1, Integer::sum);
        return "filter_" + column + "_" + count;
    }

    private record FilterClause(
        String sql,
        String whereSql,
        Map<String, Object> params,
        List<String> allowedRids,
        boolean metadataFiltered
    ) {
    }

    private VectorMatch readNativeMatch(java.util.Map<String, Object> row) {
        var score = row.containsKey("score")
            ? ArcadeRecordMapper.decimal(row, "score")
            : row.containsKey("distance")
                ? 1.0d - ArcadeRecordMapper.decimal(row, "distance")
                : 0.0d;
        var id = row.containsKey("id") ? ArcadeRecordMapper.string(row, "id") : ArcadeRecordMapper.string(row, "@rid");
        return new VectorMatch(id, score);
    }

    private VectorMatch readKeywordMatch(java.util.Map<String, Object> row) {
        var score = row.containsKey("score")
            ? ArcadeRecordMapper.decimal(row, "score")
            : row.containsKey("$score")
                ? ArcadeRecordMapper.decimal(row, "$score")
                : 0.0d;
        return new VectorMatch(ArcadeRecordMapper.string(row, "id"), score);
    }

    private VectorMatch readFusedMatch(java.util.Map<String, Object> row) {
        return new VectorMatch(
            row.containsKey("id") ? ArcadeRecordMapper.string(row, "id") : ArcadeRecordMapper.string(row, "@rid"),
            row.containsKey("score") ? ArcadeRecordMapper.decimal(row, "score") : 0.0d
        );
    }
}
