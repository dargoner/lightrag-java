package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.VectorStore;

import java.util.List;

interface MilvusClientAdapter extends AutoCloseable {
    void ensureCollection(CollectionDefinition collectionDefinition);

    void upsert(String collectionName, List<StoredVectorRow> rows);

    List<VectorStore.VectorRecord> list(String collectionName);

    List<VectorStore.VectorMatch> semanticSearch(SemanticSearchRequest request);

    List<VectorStore.VectorMatch> keywordSearch(KeywordSearchRequest request);

    List<VectorStore.VectorMatch> hybridSearch(HybridSearchRequest request);

    default void deleteAll(String collectionName) {
        throw new UnsupportedOperationException("deleteAll is not implemented");
    }

    default void flush(List<String> collectionNames) {
    }

    @Override
    void close();

    record CollectionDefinition(String collectionName, int vectorDimensions, String analyzerType) {
    }

    record StoredVectorRow(
        String id,
        List<Double> denseVector,
        String searchableText,
        List<String> keywords,
        String fullText
    ) {
    }

    record SemanticSearchRequest(String collectionName, List<Double> queryVector, int topK) {
    }

    record KeywordSearchRequest(String collectionName, String queryText, int topK) {
    }

    record HybridSearchRequest(
        String collectionName,
        List<Double> queryVector,
        String queryText,
        int topK,
        HybridRankerType rankerType,
        List<Float> weights,
        int rrfK
    ) {
    }

    enum HybridRankerType {
        RRF,
        WEIGHTED
    }
}
