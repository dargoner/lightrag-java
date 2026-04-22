package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.VectorStore;

import java.util.List;

public interface MilvusClientAdapter extends AutoCloseable {
    void ensureCollection(CollectionDefinition collectionDefinition);

    void upsert(String collectionName, List<StoredVectorRow> rows);

    List<VectorStore.VectorRecord> list(String collectionName);

    default List<VectorStore.VectorRecord> list(ListRequest request) {
        throw new UnsupportedOperationException("list with filter is not implemented");
    }

    List<VectorStore.VectorMatch> semanticSearch(SemanticSearchRequest request);

    List<VectorStore.VectorMatch> keywordSearch(KeywordSearchRequest request);

    List<VectorStore.VectorMatch> hybridSearch(HybridSearchRequest request);

    default void deleteAll(String collectionName) {
        throw new UnsupportedOperationException("deleteAll is not implemented");
    }

    default void deleteAll(DeleteRequest request) {
        throw new UnsupportedOperationException("deleteAll with filter is not implemented");
    }

    default void flush(List<String> collectionNames) {
    }

    @Override
    void close();

    record CollectionDefinition(String collectionName, String namespace, int vectorDimensions, String analyzerType) {
    }

    record StoredVectorRow(
        String pkId,
        String vectorId,
        String workspaceId,
        String recordType,
        String id,
        List<Double> denseVector,
        String searchableText,
        List<String> keywords,
        String fullText,
        String srcId,
        String tgtId,
        String filePath
    ) {
        public StoredVectorRow(
            String pkId,
            String vectorId,
            String workspaceId,
            String recordType,
            String id,
            List<Double> denseVector,
            String searchableText,
            List<String> keywords,
            String fullText
        ) {
            this(pkId, vectorId, workspaceId, recordType, id, denseVector, searchableText, keywords, fullText, "", "", "");
        }
    }

    record SemanticSearchRequest(String collectionName, List<Double> queryVector, int topK, String filter) {
    }

    record KeywordSearchRequest(String collectionName, String queryText, int topK, String filter) {
    }

    record HybridSearchRequest(
        String collectionName,
        List<Double> queryVector,
        String queryText,
        int topK,
        String filter,
        HybridRankerType rankerType,
        List<Float> weights,
        int rrfK
    ) {
    }

    record ListRequest(String collectionName, String filter) {
    }

    record DeleteRequest(String collectionName, String filter) {
    }

    enum HybridRankerType {
        RRF,
        WEIGHTED
    }
}
