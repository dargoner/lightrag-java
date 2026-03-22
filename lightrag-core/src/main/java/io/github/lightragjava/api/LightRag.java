package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.indexing.DeletionPipeline;
import io.github.lightragjava.indexing.GraphManagementPipeline;
import io.github.lightragjava.indexing.IndexingPipeline;
import io.github.lightragjava.query.ContextAssembler;
import io.github.lightragjava.query.GlobalQueryStrategy;
import io.github.lightragjava.query.HybridQueryStrategy;
import io.github.lightragjava.query.LocalQueryStrategy;
import io.github.lightragjava.query.MixQueryStrategy;
import io.github.lightragjava.query.NaiveQueryStrategy;
import io.github.lightragjava.query.QueryEngine;
import io.github.lightragjava.types.Document;

import java.util.EnumMap;
import java.util.List;
import java.util.NoSuchElementException;

public final class LightRag {
    private final LightRagConfig config;
    private final Chunker chunker;
    private final boolean automaticQueryKeywordExtraction;
    private final int rerankCandidateMultiplier;
    private final int embeddingBatchSize;
    private final int maxParallelInsert;
    private final IndexingPipeline indexingPipeline;
    private final DeletionPipeline deletionPipeline;
    private final GraphManagementPipeline graphManagementPipeline;
    private final QueryEngine queryEngine;

    LightRag(LightRagConfig config) {
        this(config, null, true, 2, Integer.MAX_VALUE, 1);
    }

    LightRag(LightRagConfig config, Chunker chunker) {
        this(config, chunker, true, 2, Integer.MAX_VALUE, 1);
    }

    LightRag(
        LightRagConfig config,
        Chunker chunker,
        boolean automaticQueryKeywordExtraction,
        int rerankCandidateMultiplier,
        int embeddingBatchSize,
        int maxParallelInsert
    ) {
        this.config = config;
        this.chunker = chunker;
        this.automaticQueryKeywordExtraction = automaticQueryKeywordExtraction;
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxParallelInsert = maxParallelInsert;
        this.indexingPipeline = new IndexingPipeline(
            config.chatModel(),
            config.embeddingModel(),
            config.storageProvider(),
            config.snapshotPath(),
            chunker,
            embeddingBatchSize,
            maxParallelInsert
        );
        this.deletionPipeline = new DeletionPipeline(
            config.storageProvider(),
            indexingPipeline,
            config.snapshotPath()
        );
        this.graphManagementPipeline = new GraphManagementPipeline(
            config.storageProvider(),
            indexingPipeline,
            config.snapshotPath()
        );
        var contextAssembler = new ContextAssembler();
        var naive = new NaiveQueryStrategy(config.embeddingModel(), config.storageProvider(), contextAssembler);
        var local = new LocalQueryStrategy(config.embeddingModel(), config.storageProvider(), contextAssembler);
        var global = new GlobalQueryStrategy(config.embeddingModel(), config.storageProvider(), contextAssembler);
        var hybrid = new HybridQueryStrategy(local, global, contextAssembler);
        var mix = new MixQueryStrategy(config.embeddingModel(), config.storageProvider(), hybrid, contextAssembler);
        var strategies = new EnumMap<QueryMode, io.github.lightragjava.query.QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.NAIVE, naive);
        strategies.put(QueryMode.LOCAL, local);
        strategies.put(QueryMode.GLOBAL, global);
        strategies.put(QueryMode.HYBRID, hybrid);
        strategies.put(QueryMode.MIX, mix);
        this.queryEngine = new QueryEngine(
            config.chatModel(),
            contextAssembler,
            strategies,
            config.rerankModel(),
            automaticQueryKeywordExtraction,
            rerankCandidateMultiplier
        );
    }

    public static LightRagBuilder builder() {
        return new LightRagBuilder();
    }

    public void ingest(List<Document> documents) {
        indexingPipeline.ingest(documents);
    }

    public GraphEntity createEntity(CreateEntityRequest request) {
        return graphManagementPipeline.createEntity(request);
    }

    public GraphRelation createRelation(CreateRelationRequest request) {
        return graphManagementPipeline.createRelation(request);
    }

    public GraphEntity editEntity(EditEntityRequest request) {
        return graphManagementPipeline.editEntity(request);
    }

    public GraphRelation editRelation(EditRelationRequest request) {
        return graphManagementPipeline.editRelation(request);
    }

    public GraphEntity mergeEntities(MergeEntitiesRequest request) {
        return graphManagementPipeline.mergeEntities(request);
    }

    /**
     * Deletes the resolved entity from graph and vector storage while preserving source documents and chunks.
     * Use {@link #deleteByDocumentId(String)} to remove the originating text itself.
     */
    public void deleteByEntity(String entityName) {
        deletionPipeline.deleteByEntity(entityName);
    }

    /**
     * Deletes all relations between the resolved endpoint entities from graph and relation-vector storage.
     * Source documents and chunks remain available until removed by document deletion.
     */
    public void deleteByRelation(String sourceEntityName, String targetEntityName) {
        deletionPipeline.deleteByRelation(sourceEntityName, targetEntityName);
    }

    /**
     * Deletes a document by clearing storage and rebuilding all remaining documents through the current
     * LightRag indexing pipeline.
     */
    public void deleteByDocumentId(String documentId) {
        deletionPipeline.deleteByDocumentId(documentId);
    }

    public QueryResult query(QueryRequest request) {
        return queryEngine.query(request);
    }

    public DocumentProcessingStatus getDocumentStatus(String documentId) {
        return config.documentStatusStore()
            .load(documentId)
            .map(LightRag::toDocumentProcessingStatus)
            .orElseThrow(() -> new NoSuchElementException("document status does not exist: " + documentId));
    }

    public List<DocumentProcessingStatus> listDocumentStatuses() {
        return config.documentStatusStore().list().stream()
            .map(LightRag::toDocumentProcessingStatus)
            .toList();
    }

    LightRagConfig config() {
        return config;
    }

    Chunker chunker() {
        return chunker;
    }

    boolean automaticQueryKeywordExtraction() {
        return automaticQueryKeywordExtraction;
    }

    int rerankCandidateMultiplier() {
        return rerankCandidateMultiplier;
    }

    int embeddingBatchSize() {
        return embeddingBatchSize;
    }

    int maxParallelInsert() {
        return maxParallelInsert;
    }

    private static DocumentProcessingStatus toDocumentProcessingStatus(
        io.github.lightragjava.storage.DocumentStatusStore.StatusRecord statusRecord
    ) {
        return new DocumentProcessingStatus(
            statusRecord.documentId(),
            statusRecord.status(),
            statusRecord.summary(),
            statusRecord.errorMessage()
        );
    }
}
