package io.github.lightrag.api;

import io.github.lightrag.config.LightRagConfig;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DeletionPipeline;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.GraphManagementPipeline;
import io.github.lightrag.indexing.IndexingProgressListener;
import io.github.lightrag.indexing.IndexingPipeline;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.query.ContextAssembler;
import io.github.lightrag.query.DefaultPathRetriever;
import io.github.lightrag.query.DefaultPathScorer;
import io.github.lightrag.query.GlobalQueryStrategy;
import io.github.lightrag.query.HybridQueryStrategy;
import io.github.lightrag.query.LocalQueryStrategy;
import io.github.lightrag.query.MixQueryStrategy;
import io.github.lightrag.query.MultiHopQueryStrategy;
import io.github.lightrag.query.NaiveQueryStrategy;
import io.github.lightrag.query.QueryEngine;
import io.github.lightrag.query.ReasoningContextAssembler;
import io.github.lightrag.query.RuleBasedQueryIntentClassifier;
import io.github.lightrag.synthesis.PathAwareAnswerSynthesizer;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.task.TaskExecutionService;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.RawDocumentSource;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LightRag implements AutoCloseable {
    private final LightRagConfig config;
    private final Chunker chunker;
    private final boolean automaticQueryKeywordExtraction;
    private final int rerankCandidateMultiplier;
    private final int embeddingBatchSize;
    private final int maxParallelInsert;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final String entityExtractionLanguage;
    private final List<String> entityTypes;
    private final boolean embeddingSemanticMergeEnabled;
    private final double embeddingSemanticMergeThreshold;
    private final ExtractionRefinementOptions extractionRefinementOptions;
    private final DocumentParsingOrchestrator documentParsingOrchestrator;
    private final TaskExecutionService taskExecutionService;
    private final AtomicBoolean closed = new AtomicBoolean();

    LightRag(LightRagConfig config) {
        this(config, null, null, true, 2, Integer.MAX_VALUE, 1,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD,
            ExtractionRefinementOptions.disabled());
    }

    LightRag(LightRagConfig config, Chunker chunker) {
        this(config, chunker, null, true, 2, Integer.MAX_VALUE, 1,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD,
            ExtractionRefinementOptions.disabled());
    }

    LightRag(
        LightRagConfig config,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        boolean automaticQueryKeywordExtraction,
        int rerankCandidateMultiplier,
        int embeddingBatchSize,
        int maxParallelInsert,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold,
        ExtractionRefinementOptions extractionRefinementOptions
    ) {
        this.config = config;
        this.chunker = chunker;
        this.automaticQueryKeywordExtraction = automaticQueryKeywordExtraction;
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxParallelInsert = maxParallelInsert;
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        this.maxExtractInputTokens = maxExtractInputTokens;
        this.entityExtractionLanguage = Objects.requireNonNull(entityExtractionLanguage, "entityExtractionLanguage");
        this.entityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
        this.embeddingSemanticMergeEnabled = embeddingSemanticMergeEnabled;
        this.embeddingSemanticMergeThreshold = embeddingSemanticMergeThreshold;
        this.extractionRefinementOptions = Objects.requireNonNull(extractionRefinementOptions, "extractionRefinementOptions");
        this.documentParsingOrchestrator = documentParsingOrchestrator;
        this.taskExecutionService = new TaskExecutionService(workspaceId -> resolveProvider(resolveScope(workspaceId)));
    }

    public static LightRagBuilder builder() {
        return new LightRagBuilder();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        taskExecutionService.close();
        config.workspaceStorageProvider().close();
    }

    public void ingest(String workspaceId, List<Document> documents) {
        var scope = resolveScope(workspaceId);
        newIndexingPipeline(resolveProvider(scope)).ingest(documents);
    }

    public void ingestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options) {
        var scope = resolveScope(workspaceId);
        newIndexingPipeline(resolveProvider(scope)).ingestSources(sources, options);
    }

    public String submitIngest(String workspaceId, List<Document> documents) {
        var normalizedDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        return taskExecutionService.submit(
            workspaceId,
            TaskType.INGEST_DOCUMENTS,
            Map.of("documentCount", Integer.toString(normalizedDocuments.size())),
            progressListener -> newIndexingPipeline(resolveProvider(resolveScope(workspaceId)), progressListener).ingest(normalizedDocuments)
        );
    }

    public String submitIngestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options) {
        var normalizedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        var resolvedOptions = Objects.requireNonNull(options, "options");
        return taskExecutionService.submit(
            workspaceId,
            TaskType.INGEST_SOURCES,
            Map.of("sourceCount", Integer.toString(normalizedSources.size())),
            progressListener -> newIndexingPipeline(resolveProvider(resolveScope(workspaceId)), progressListener)
                .ingestSources(normalizedSources, resolvedOptions)
        );
    }

    public String submitRebuild(String workspaceId) {
        return taskExecutionService.submit(
            workspaceId,
            TaskType.REBUILD_GRAPH,
            Map.of(),
            progressListener -> newDeletionPipeline(resolveProvider(resolveScope(workspaceId)), progressListener).rebuildAllDocuments()
        );
    }

    public TaskSnapshot getTask(String workspaceId, String taskId) {
        return taskExecutionService.getTask(workspaceId, taskId);
    }

    public List<TaskSnapshot> listTasks(String workspaceId) {
        return taskExecutionService.listTasks(workspaceId);
    }

    public TaskSnapshot cancelTask(String workspaceId, String taskId) {
        return taskExecutionService.cancel(workspaceId, taskId);
    }

    public GraphEntity createEntity(String workspaceId, CreateEntityRequest request) {
        var scope = resolveScope(workspaceId);
        return newGraphManagementPipeline(resolveProvider(scope)).createEntity(request);
    }

    public GraphRelation createRelation(String workspaceId, CreateRelationRequest request) {
        var scope = resolveScope(workspaceId);
        return newGraphManagementPipeline(resolveProvider(scope)).createRelation(request);
    }

    public GraphEntity editEntity(String workspaceId, EditEntityRequest request) {
        var scope = resolveScope(workspaceId);
        return newGraphManagementPipeline(resolveProvider(scope)).editEntity(request);
    }

    public GraphRelation editRelation(String workspaceId, EditRelationRequest request) {
        var scope = resolveScope(workspaceId);
        return newGraphManagementPipeline(resolveProvider(scope)).editRelation(request);
    }

    public GraphEntity mergeEntities(String workspaceId, MergeEntitiesRequest request) {
        var scope = resolveScope(workspaceId);
        return newGraphManagementPipeline(resolveProvider(scope)).mergeEntities(request);
    }

    /**
     * Deletes the resolved entity from graph and vector storage while preserving source documents and chunks.
     * Use {@link #deleteByDocumentId(String, String)} to remove the originating text itself.
     */
    public void deleteByEntity(String workspaceId, String entityName) {
        var scope = resolveScope(workspaceId);
        newDeletionPipeline(resolveProvider(scope)).deleteByEntity(entityName);
    }

    /**
     * Deletes all relations between the resolved endpoint entities from graph and relation-vector storage.
     * Source documents and chunks remain available until removed by document deletion.
     */
    public void deleteByRelation(String workspaceId, String sourceEntityName, String targetEntityName) {
        var scope = resolveScope(workspaceId);
        newDeletionPipeline(resolveProvider(scope)).deleteByRelation(sourceEntityName, targetEntityName);
    }

    /**
     * Deletes a document by clearing storage and rebuilding all remaining documents through the current
     * LightRag indexing pipeline.
     */
    public void deleteByDocumentId(String workspaceId, String documentId) {
        var scope = resolveScope(workspaceId);
        newDeletionPipeline(resolveProvider(scope)).deleteByDocumentId(documentId);
    }

    public QueryResult query(String workspaceId, QueryRequest request) {
        var scope = resolveScope(workspaceId);
        return newQueryEngine(resolveProvider(scope)).query(request);
    }

    public DocumentProcessingStatus getDocumentStatus(String workspaceId, String documentId) {
        var scope = resolveScope(workspaceId);
        return resolveProvider(scope).documentStatusStore()
            .load(documentId)
            .map(LightRag::toDocumentProcessingStatus)
            .orElseThrow(() -> new NoSuchElementException("document status does not exist: " + documentId));
    }

    public List<DocumentProcessingStatus> listDocumentStatuses(String workspaceId) {
        var scope = resolveScope(workspaceId);
        return resolveProvider(scope).documentStatusStore().list().stream()
            .map(LightRag::toDocumentProcessingStatus)
            .toList();
    }

    public DocumentGraphInspection inspectDocumentGraph(String workspaceId, String documentId) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        throw unsupportedGraphMaterializationApi();
    }

    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(mode, "mode");
        throw unsupportedGraphMaterializationApi();
    }

    public DocumentChunkGraphStatus getDocumentChunkGraphStatus(String workspaceId, String documentId, String chunkId) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(chunkId, "chunkId");
        throw unsupportedGraphMaterializationApi();
    }

    public List<DocumentChunkGraphStatus> listDocumentChunkGraphStatuses(String workspaceId, String documentId) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        throw unsupportedGraphMaterializationApi();
    }

    public ChunkGraphMaterializationResult resumeChunkGraph(String workspaceId, String documentId, String chunkId) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(chunkId, "chunkId");
        throw unsupportedGraphMaterializationApi();
    }

    public ChunkGraphMaterializationResult repairChunkGraph(String workspaceId, String documentId, String chunkId) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(chunkId, "chunkId");
        throw unsupportedGraphMaterializationApi();
    }

    public String submitDocumentGraphMaterialization(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(mode, "mode");
        throw unsupportedGraphMaterializationApi();
    }

    public String submitChunkGraphMaterialization(
        String workspaceId,
        String documentId,
        String chunkId,
        GraphChunkAction action
    ) {
        resolveScope(workspaceId);
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(action, "action");
        throw unsupportedGraphMaterializationApi();
    }

    public void saveSnapshot(String workspaceId, Path path) {
        var scope = resolveScope(workspaceId);
        var snapshotPath = Objects.requireNonNull(path, "path");
        var storageProvider = resolveProvider(scope);
        storageProvider.snapshotStore().save(snapshotPath, StorageSnapshots.capture(storageProvider));
    }

    public void restoreSnapshot(String workspaceId, Path path) {
        var scope = resolveScope(workspaceId);
        var snapshotPath = Objects.requireNonNull(path, "path");
        var storageProvider = resolveProvider(scope);
        storageProvider.restore(storageProvider.snapshotStore().load(snapshotPath));
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

    int entityExtractMaxGleaning() {
        return entityExtractMaxGleaning;
    }

    int maxExtractInputTokens() {
        return maxExtractInputTokens;
    }

    String entityExtractionLanguage() {
        return entityExtractionLanguage;
    }

    List<String> entityTypes() {
        return entityTypes;
    }

    boolean embeddingSemanticMergeEnabled() {
        return embeddingSemanticMergeEnabled;
    }

    double embeddingSemanticMergeThreshold() {
        return embeddingSemanticMergeThreshold;
    }

    boolean contextualExtractionRefinementEnabled() {
        return extractionRefinementOptions.enabled();
    }

    boolean allowDeterministicAttributionFallback() {
        return extractionRefinementOptions.allowDeterministicAttributionFallback();
    }

    ExtractionRefinementOptions extractionRefinementOptions() {
        return extractionRefinementOptions;
    }

    private WorkspaceScope resolveScope(String workspaceId) {
        return new WorkspaceScope(workspaceId);
    }

    private AtomicStorageProvider resolveProvider(WorkspaceScope scope) {
        return Objects.requireNonNull(
            config.workspaceStorageProvider().forWorkspace(scope),
            "workspaceStorageProvider.forWorkspace"
        );
    }

    private IndexingPipeline newIndexingPipeline(AtomicStorageProvider storageProvider) {
        return newIndexingPipeline(storageProvider, IndexingProgressListener.noop());
    }

    private IndexingPipeline newIndexingPipeline(
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        return new IndexingPipeline(
            config.extractionModel(),
            config.summaryModel(),
            config.embeddingModel(),
            storageProvider,
            config.snapshotPath(),
            chunker,
            documentParsingOrchestrator,
            embeddingBatchSize,
            maxParallelInsert,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            entityExtractionLanguage,
            entityTypes,
            embeddingSemanticMergeEnabled,
            embeddingSemanticMergeThreshold,
            extractionRefinementOptions,
            progressListener
        );
    }

    private DeletionPipeline newDeletionPipeline(AtomicStorageProvider storageProvider) {
        return newDeletionPipeline(storageProvider, IndexingProgressListener.noop());
    }

    private DeletionPipeline newDeletionPipeline(
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        return new DeletionPipeline(storageProvider, newIndexingPipeline(storageProvider, progressListener), config.snapshotPath());
    }

    private GraphManagementPipeline newGraphManagementPipeline(AtomicStorageProvider storageProvider) {
        return new GraphManagementPipeline(storageProvider, newIndexingPipeline(storageProvider), config.snapshotPath());
    }

    private QueryEngine newQueryEngine(AtomicStorageProvider storageProvider) {
        var contextAssembler = new ContextAssembler();
        var naive = new NaiveQueryStrategy(config.embeddingModel(), storageProvider, contextAssembler);
        var local = new LocalQueryStrategy(config.embeddingModel(), storageProvider, contextAssembler);
        var global = new GlobalQueryStrategy(config.embeddingModel(), storageProvider, contextAssembler);
        var hybrid = new HybridQueryStrategy(local, global, contextAssembler);
        var mix = new MixQueryStrategy(config.embeddingModel(), storageProvider, hybrid, contextAssembler);
        var multiHop = new MultiHopQueryStrategy(
            mix::retrieve,
            new DefaultPathRetriever(storageProvider.graphStore(), 5),
            new DefaultPathScorer(),
            new ReasoningContextAssembler(storageProvider.graphStore(), storageProvider.chunkStore())
        );
        var strategies = new EnumMap<QueryMode, io.github.lightrag.query.QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.NAIVE, naive);
        strategies.put(QueryMode.LOCAL, local);
        strategies.put(QueryMode.GLOBAL, global);
        strategies.put(QueryMode.HYBRID, hybrid);
        strategies.put(QueryMode.MIX, mix);
        return new QueryEngine(
            config.queryModel(),
            contextAssembler,
            strategies,
            config.rerankModel(),
            automaticQueryKeywordExtraction,
            rerankCandidateMultiplier,
            new RuleBasedQueryIntentClassifier(),
            multiHop,
            new PathAwareAnswerSynthesizer()
        );
    }

    private static DocumentProcessingStatus toDocumentProcessingStatus(
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord
    ) {
        return new DocumentProcessingStatus(
            statusRecord.documentId(),
            statusRecord.status(),
            statusRecord.summary(),
            statusRecord.errorMessage()
        );
    }

    private static UnsupportedOperationException unsupportedGraphMaterializationApi() {
        return new UnsupportedOperationException("graph materialization behavior is not implemented yet");
    }
}
