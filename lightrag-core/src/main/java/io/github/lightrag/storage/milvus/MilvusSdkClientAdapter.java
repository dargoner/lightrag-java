package io.github.lightrag.storage.milvus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.VectorStore;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MilvusSdkClientAdapter implements MilvusClientAdapter {
    private static final String ID_FIELD = "id";
    private static final String DENSE_VECTOR_FIELD = "dense_vector";
    private static final String SEARCHABLE_TEXT_FIELD = "searchable_text";
    private static final String FULL_TEXT_FIELD = "full_text";
    private static final String SPARSE_VECTOR_FIELD = "sparse_vector";
    private static final int MAX_VARCHAR_LENGTH = 65_535;
    private static final int QUERY_PAGE_SIZE = 1_000;

    private final MilvusVectorConfig config;
    private final MilvusClientV2 client;
    private final boolean ownsClient;
    private final ConsistencyLevel queryConsistency;

    public MilvusSdkClientAdapter(MilvusVectorConfig config) {
        this(Objects.requireNonNull(config, "config"), createOwnedClient(config), true);
    }

    public MilvusSdkClientAdapter(MilvusVectorConfig config, MilvusClientV2 client) {
        this(config, client, false);
    }

    private MilvusSdkClientAdapter(MilvusVectorConfig config, MilvusClientV2 client, boolean ownsClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.ownsClient = ownsClient;
        this.queryConsistency = resolveQueryConsistency(config);
    }

    private static MilvusClientV2 createOwnedClient(MilvusVectorConfig config) {
        return new MilvusClientV2(connectConfig(Objects.requireNonNull(config, "config")));
    }

    @Override
    public void ensureCollection(CollectionDefinition collectionDefinition) {
        var definition = Objects.requireNonNull(collectionDefinition, "collectionDefinition");
        try {
            if (!hasCollection(definition.collectionName())) {
                createCollection(definition);
            } else {
                var incompatibility = firstSchemaIncompatibility(definition);
                if (incompatibility != null) {
                    handleSchemaDrift(definition, incompatibility);
                }
            }
            ensureLoaded(definition.collectionName());
        } catch (StorageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to ensure Milvus collection: " + definition.collectionName(), exception);
        }
    }

    @Override
    public void upsert(String collectionName, List<StoredVectorRow> rows) {
        var targetCollection = Objects.requireNonNull(collectionName, "collectionName");
        var values = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (values.isEmpty()) {
            return;
        }
        try {
            client.upsert(UpsertReq.builder()
                .databaseName(config.databaseName())
                .collectionName(targetCollection)
                .data(values.stream().map(MilvusSdkClientAdapter::toJsonRow).toList())
                .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to upsert rows into Milvus collection: " + targetCollection, exception);
        }
    }

    @Override
    public List<VectorStore.VectorRecord> list(String collectionName) {
        var targetCollection = Objects.requireNonNull(collectionName, "collectionName");
        if (!hasCollection(targetCollection)) {
            return List.of();
        }
        try {
            var results = new ArrayList<VectorStore.VectorRecord>();
            long offset = 0;
            while (true) {
                var response = client.query(QueryReq.builder()
                    .databaseName(config.databaseName())
                    .collectionName(targetCollection)
                    .filter(ID_FIELD + " != \"\"")
                    .outputFields(List.of(ID_FIELD, DENSE_VECTOR_FIELD))
                    .limit(QUERY_PAGE_SIZE)
                    .offset(offset)
                    .consistencyLevel(queryConsistency)
                    .build());
                var page = response.getQueryResults();
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (var row : page) {
                    var entity = row.getEntity();
                    results.add(new VectorStore.VectorRecord(
                        Objects.toString(entity.get(ID_FIELD)),
                        toDoubleList(entity.get(DENSE_VECTOR_FIELD))
                    ));
                }
                if (page.size() < QUERY_PAGE_SIZE) {
                    break;
                }
                offset += page.size();
            }
            return results.stream()
                .sorted(java.util.Comparator.comparing(VectorStore.VectorRecord::id))
                .toList();
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to list vectors from Milvus collection: " + targetCollection, exception);
        }
    }

    @Override
    public List<VectorStore.VectorMatch> semanticSearch(SemanticSearchRequest request) {
        var searchRequest = Objects.requireNonNull(request, "request");
        if (!hasCollection(searchRequest.collectionName())) {
            return List.of();
        }
        try {
            return toMatches(client.search(SearchReq.builder()
                .databaseName(config.databaseName())
                .collectionName(searchRequest.collectionName())
                .annsField(DENSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(searchRequest.topK())
                .outputFields(List.of(ID_FIELD))
                .data(List.of(new FloatVec(toFloatList(searchRequest.queryVector()))))
                .consistencyLevel(queryConsistency)
                .build()));
        } catch (RuntimeException exception) {
            throw new StorageException("Failed semantic search in Milvus collection: " + searchRequest.collectionName(), exception);
        }
    }

    @Override
    public List<VectorStore.VectorMatch> keywordSearch(KeywordSearchRequest request) {
        var searchRequest = Objects.requireNonNull(request, "request");
        if (!hasCollection(searchRequest.collectionName())) {
            return List.of();
        }
        try {
            return toMatches(client.search(SearchReq.builder()
                .databaseName(config.databaseName())
                .collectionName(searchRequest.collectionName())
                .annsField(SPARSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .topK(searchRequest.topK())
                .outputFields(List.of(ID_FIELD))
                .data(List.of(new EmbeddedText(searchRequest.queryText())))
                .consistencyLevel(queryConsistency)
                .build()));
        } catch (RuntimeException exception) {
            throw new StorageException("Failed keyword search in Milvus collection: " + searchRequest.collectionName(), exception);
        }
    }

    @Override
    public List<VectorStore.VectorMatch> hybridSearch(HybridSearchRequest request) {
        var searchRequest = Objects.requireNonNull(request, "request");
        if (!hasCollection(searchRequest.collectionName())) {
            return List.of();
        }
        try {
            var denseSearch = AnnSearchReq.builder()
                .vectorFieldName(DENSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.COSINE)
                .topK(searchRequest.topK())
                .vectors(List.of(new FloatVec(toFloatList(searchRequest.queryVector()))))
                .build();
            var sparseSearch = AnnSearchReq.builder()
                .vectorFieldName(SPARSE_VECTOR_FIELD)
                .metricType(IndexParam.MetricType.BM25)
                .topK(searchRequest.topK())
                .vectors(List.of(new EmbeddedText(searchRequest.queryText())))
                .build();
            return toMatches(client.hybridSearch(HybridSearchReq.builder()
                .databaseName(config.databaseName())
                .collectionName(searchRequest.collectionName())
                .searchRequests(List.of(denseSearch, sparseSearch))
                .ranker(buildRanker(searchRequest))
                .topK(searchRequest.topK())
                .outFields(List.of(ID_FIELD))
                .consistencyLevel(queryConsistency)
                .build()));
        } catch (RuntimeException exception) {
            throw new StorageException("Failed hybrid search in Milvus collection: " + searchRequest.collectionName(), exception);
        }
    }

    @Override
    public void deleteAll(String collectionName) {
        var targetCollection = Objects.requireNonNull(collectionName, "collectionName");
        if (!hasCollection(targetCollection)) {
            return;
        }
        try {
            client.delete(DeleteReq.builder()
                .databaseName(config.databaseName())
                .collectionName(targetCollection)
                .filter(ID_FIELD + " != \"\"")
                .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to clear Milvus collection: " + targetCollection, exception);
        }
    }

    @Override
    public void flush(List<String> collectionNames) {
        var names = List.copyOf(Objects.requireNonNull(collectionNames, "collectionNames")).stream()
            .filter(this::hasCollection)
            .distinct()
            .toList();
        if (names.isEmpty()) {
            return;
        }
        try {
            client.flush(FlushReq.builder()
                .databaseName(config.databaseName())
                .collectionNames(names)
                .waitFlushedTimeoutMs(30_000L)
                .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to flush Milvus collections", exception);
        }
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    static ConsistencyLevel resolveQueryConsistency(MilvusVectorConfig config) {
        return Objects.requireNonNull(config, "config").queryConsistencyLevel();
    }

    void dropCollection(String collectionName) {
        var targetCollection = Objects.requireNonNull(collectionName, "collectionName");
        if (!hasCollection(targetCollection)) {
            return;
        }
        try {
            client.dropCollection(DropCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(targetCollection)
                .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to drop Milvus collection: " + targetCollection, exception);
        }
    }

    private String firstSchemaIncompatibility(CollectionDefinition definition) {
        var response = client.describeCollection(DescribeCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(definition.collectionName())
            .build());
        if (response == null || response.getCollectionSchema() == null) {
            return "missing collection schema";
        }
        var schema = response.getCollectionSchema();
        if (schema.isEnableDynamicField()) {
            return "dynamic field setting differs";
        }
        if (!hasCompatibleIdField(schema)) {
            return "id field differs";
        }
        if (!hasCompatibleDenseField(schema, definition.vectorDimensions())) {
            return "dense vector field differs";
        }
        if (!hasCompatibleSearchableTextField(schema)) {
            return "searchable_text field differs";
        }
        if (!hasCompatibleFullTextField(schema, definition.analyzerType())) {
            return "full_text field differs";
        }
        if (!hasCompatibleSparseField(schema)) {
            return "sparse vector field differs";
        }
        if (!hasCompatibleBm25Function(schema)) {
            return "bm25 function differs";
        }
        return null;
    }

    private void createCollection(CollectionDefinition definition) {
        client.createCollection(CreateCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(definition.collectionName())
            .enableDynamicField(false)
            .consistencyLevel(ConsistencyLevel.BOUNDED)
            .collectionSchema(collectionSchema(definition.vectorDimensions(), definition.analyzerType()))
            .indexParams(indexParams())
            .build());
    }

    private void handleSchemaDrift(CollectionDefinition definition, String incompatibility) {
        switch (config.schemaDriftStrategy()) {
            case STRICT_REBUILD -> {
                dropCollection(definition.collectionName());
                createCollection(definition);
            }
            case IGNORE -> {
                return;
            }
            case STRICT_FAIL -> throw new StorageException(
                "Milvus schema drift detected for collection "
                    + definition.collectionName()
                    + ": "
                    + incompatibility
            );
        }
    }

    private boolean hasCollection(String collectionName) {
        try {
            return client.hasCollection(HasCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(collectionName)
                .build());
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to inspect Milvus collection: " + collectionName, exception);
        }
    }

    private void ensureLoaded(String collectionName) {
        var loadState = client.getLoadState(GetLoadStateReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .build());
        if (Boolean.TRUE.equals(loadState)) {
            return;
        }
        client.loadCollection(LoadCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .sync(true)
            .build());
    }

    private boolean hasCompatibleIdField(CreateCollectionReq.CollectionSchema schema) {
        var field = schema.getField(ID_FIELD);
        return field != null
            && field.getDataType() == DataType.VarChar
            && Integer.valueOf(191).equals(field.getMaxLength())
            && Boolean.TRUE.equals(field.getIsPrimaryKey())
            && Boolean.FALSE.equals(field.getAutoID());
    }

    private boolean hasCompatibleDenseField(CreateCollectionReq.CollectionSchema schema, int vectorDimensions) {
        var field = schema.getField(DENSE_VECTOR_FIELD);
        return field != null
            && field.getDataType() == DataType.FloatVector
            && Integer.valueOf(vectorDimensions).equals(field.getDimension());
    }

    private boolean hasCompatibleSearchableTextField(CreateCollectionReq.CollectionSchema schema) {
        var field = schema.getField(SEARCHABLE_TEXT_FIELD);
        return field != null
            && field.getDataType() == DataType.VarChar
            && Integer.valueOf(MAX_VARCHAR_LENGTH).equals(field.getMaxLength());
    }

    private boolean hasCompatibleFullTextField(CreateCollectionReq.CollectionSchema schema, String analyzerType) {
        var field = schema.getField(FULL_TEXT_FIELD);
        return field != null
            && field.getDataType() == DataType.VarChar
            && Integer.valueOf(MAX_VARCHAR_LENGTH).equals(field.getMaxLength())
            && Boolean.TRUE.equals(field.getEnableAnalyzer())
            && Boolean.TRUE.equals(field.getEnableMatch())
            && analyzerType.equals(resolveAnalyzerType(field));
    }

    private boolean hasCompatibleSparseField(CreateCollectionReq.CollectionSchema schema) {
        var field = schema.getField(SPARSE_VECTOR_FIELD);
        return field != null && field.getDataType() == DataType.SparseFloatVector;
    }

    private boolean hasCompatibleBm25Function(CreateCollectionReq.CollectionSchema schema) {
        var functions = schema.getFunctionList();
        if (functions == null || functions.isEmpty()) {
            return false;
        }
        return functions.stream().anyMatch(function ->
            function.getFunctionType() == FunctionType.BM25
                && List.of(FULL_TEXT_FIELD).equals(function.getInputFieldNames())
                && List.of(SPARSE_VECTOR_FIELD).equals(function.getOutputFieldNames())
        );
    }

    private CreateCollectionReq.CollectionSchema collectionSchema(int vectorDimensions, String analyzerType) {
        var schema = CreateCollectionReq.CollectionSchema.builder()
            .enableDynamicField(false)
            .fieldSchemaList(List.of(
                CreateCollectionReq.FieldSchema.builder()
                    .name(ID_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(191)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build(),
                CreateCollectionReq.FieldSchema.builder()
                    .name(DENSE_VECTOR_FIELD)
                    .dataType(DataType.FloatVector)
                    .dimension(vectorDimensions)
                    .build(),
                CreateCollectionReq.FieldSchema.builder()
                    .name(SEARCHABLE_TEXT_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(MAX_VARCHAR_LENGTH)
                    .build(),
                CreateCollectionReq.FieldSchema.builder()
                    .name(FULL_TEXT_FIELD)
                    .dataType(DataType.VarChar)
                    .maxLength(MAX_VARCHAR_LENGTH)
                    .enableAnalyzer(true)
                    .enableMatch(true)
                    .analyzerParams(Map.of("type", analyzerType))
                    .build(),
                CreateCollectionReq.FieldSchema.builder()
                    .name(SPARSE_VECTOR_FIELD)
                    .dataType(DataType.SparseFloatVector)
                    .build()
            ))
            .functionList(List.of(
                CreateCollectionReq.Function.builder()
                    .name("bm25_full_text")
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(FULL_TEXT_FIELD))
                    .outputFieldNames(List.of(SPARSE_VECTOR_FIELD))
                    .build()
            ))
            .build();
        return schema;
    }

    private List<IndexParam> indexParams() {
        return List.of(
            IndexParam.builder()
                .fieldName(DENSE_VECTOR_FIELD)
                .indexName("dense_autoindex")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build(),
            IndexParam.builder()
                .fieldName(SPARSE_VECTOR_FIELD)
                .indexName("sparse_bm25_index")
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build()
        );
    }

    private static String resolveAnalyzerType(CreateCollectionReq.FieldSchema field) {
        var params = field.getAnalyzerParams();
        if (params == null) {
            return null;
        }
        var type = params.get("type");
        return type == null ? null : type.toString();
    }

    private static CreateCollectionReq.Function buildRanker(HybridSearchRequest request) {
        return switch (request.rankerType()) {
            case WEIGHTED -> new WeightedRanker(request.weights());
            case RRF -> RRFRanker.builder().k(request.rrfK()).build();
        };
    }

    private static ConnectConfig connectConfig(MilvusVectorConfig config) {
        var builder = ConnectConfig.builder()
            .uri(config.uri())
            .dbName(config.databaseName())
            .enablePrecheck(true);
        if (config.token() != null) {
            builder.token(config.token());
        } else if (config.username() != null && config.password() != null) {
            builder.username(config.username());
            builder.password(config.password());
        }
        return builder.build();
    }

    private static JsonObject toJsonRow(StoredVectorRow row) {
        var json = new JsonObject();
        json.addProperty(ID_FIELD, row.id());
        json.add(DENSE_VECTOR_FIELD, toJsonArray(row.denseVector()));
        json.addProperty(SEARCHABLE_TEXT_FIELD, row.searchableText());
        json.addProperty(FULL_TEXT_FIELD, row.fullText());
        return json;
    }

    private static JsonArray toJsonArray(List<Double> values) {
        var array = new JsonArray();
        for (var value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<Float> toFloatList(List<Double> values) {
        var floats = new ArrayList<Float>(values.size());
        for (var value : values) {
            floats.add(value.floatValue());
        }
        return List.copyOf(floats);
    }

    private static List<Double> toDoubleList(Object value) {
        if (value instanceof List<?> list) {
            var doubles = new ArrayList<Double>(list.size());
            for (var element : list) {
                if (!(element instanceof Number number)) {
                    throw new StorageException("Milvus returned a non-numeric dense vector value");
                }
                doubles.add(number.doubleValue());
            }
            return List.copyOf(doubles);
        }
        throw new StorageException("Milvus returned an unexpected dense vector payload type: " + value);
    }

    private static List<VectorStore.VectorMatch> toMatches(io.milvus.v2.service.vector.response.SearchResp searchResp) {
        var groups = searchResp.getSearchResults();
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        var matches = new ArrayList<VectorStore.VectorMatch>();
        for (var result : groups.get(0)) {
            var id = result.getId() != null ? result.getId().toString() : result.getPrimaryKey();
            matches.add(new VectorStore.VectorMatch(id, result.getScore()));
        }
        return List.copyOf(matches);
    }
}
