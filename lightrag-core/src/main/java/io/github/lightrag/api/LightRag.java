package io.github.lightrag.api;

import io.github.lightrag.config.LightRagConfig;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DeletionPipeline;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.GraphMaterializationPipeline;
import io.github.lightrag.indexing.GraphManagementPipeline;
import io.github.lightrag.indexing.IndexingProgressListener;
import io.github.lightrag.indexing.IndexingPipeline;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.CachedChatModel;
import io.github.lightrag.model.ChatModel;
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
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.task.TaskExecutionService;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.PreChunkedChunk;
import io.github.lightrag.types.RawDocumentSource;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class LightRag implements AutoCloseable {
    private final LightRagConfig config;
    private final Chunker chunker;
    private final boolean automaticQueryKeywordExtraction;
    private final int rerankCandidateMultiplier;
    private final double minRerankScore;
    private final int embeddingBatchSize;
    private final int maxParallelInsert;
    private final int chunkExtractParallelism;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final String entityExtractionLanguage;
    private final List<String> entityTypes;
    private final boolean graphExtractionEnabled;
    private final List<String> relationTypes;
    private final List<GraphExtractionExample> graphExtractionExamples;
    private final boolean embeddingSemanticMergeEnabled;
    private final double embeddingSemanticMergeThreshold;
    private final ExtractionRefinementOptions extractionRefinementOptions;
    private final GraphExtractionOptions globalGraphExtractionOptions;
    private final GraphExtractionOptionsProvider graphExtractionOptionsProvider;
    private final DocumentParsingOrchestrator documentParsingOrchestrator;
    private final List<TaskEventListener> taskEventListeners;
    private final TaskExecutionService taskExecutionService;
    private final AtomicBoolean closed = new AtomicBoolean();

    LightRag(LightRagConfig config) {
        this(config, null, null, true, 2, 0.0d, Integer.MAX_VALUE, 1,
            1,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            true,
            List.of(),
            List.of(),
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD,
            ExtractionRefinementOptions.disabled(),
            GraphExtractionOptionsProvider.none(),
            List.of());
    }

    LightRag(LightRagConfig config, Chunker chunker) {
        this(config, chunker, null, true, 2, 0.0d, Integer.MAX_VALUE, 1,
            1,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE,
            io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            true,
            List.of(),
            List.of(),
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED,
            LightRagBuilder.DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD,
            ExtractionRefinementOptions.disabled(),
            GraphExtractionOptionsProvider.none(),
            List.of());
    }

    LightRag(
        LightRagConfig config,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        boolean automaticQueryKeywordExtraction,
        int rerankCandidateMultiplier,
        double minRerankScore,
        int embeddingBatchSize,
        int maxParallelInsert,
        int chunkExtractParallelism,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        boolean graphExtractionEnabled,
        List<String> relationTypes,
        List<GraphExtractionExample> graphExtractionExamples,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold,
        ExtractionRefinementOptions extractionRefinementOptions,
        GraphExtractionOptionsProvider graphExtractionOptionsProvider,
        List<TaskEventListener> taskEventListeners
    ) {
        this.config = config;
        this.chunker = chunker;
        this.automaticQueryKeywordExtraction = automaticQueryKeywordExtraction;
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        this.minRerankScore = minRerankScore;
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxParallelInsert = maxParallelInsert;
        this.chunkExtractParallelism = chunkExtractParallelism;
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        this.maxExtractInputTokens = maxExtractInputTokens;
        this.entityExtractionLanguage = Objects.requireNonNull(entityExtractionLanguage, "entityExtractionLanguage");
        this.entityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes"));
        this.graphExtractionEnabled = graphExtractionEnabled;
        this.relationTypes = List.copyOf(Objects.requireNonNull(relationTypes, "relationTypes"));
        this.graphExtractionExamples = List.copyOf(Objects.requireNonNull(graphExtractionExamples, "graphExtractionExamples"));
        this.embeddingSemanticMergeEnabled = embeddingSemanticMergeEnabled;
        this.embeddingSemanticMergeThreshold = embeddingSemanticMergeThreshold;
        this.extractionRefinementOptions = Objects.requireNonNull(extractionRefinementOptions, "extractionRefinementOptions");
        this.globalGraphExtractionOptions = new GraphExtractionOptions(
            graphExtractionEnabled,
            chunkExtractParallelism,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            entityExtractionLanguage,
            entityTypes,
            relationTypes,
            graphExtractionExamples
        );
        this.graphExtractionOptionsProvider = Objects.requireNonNull(
            graphExtractionOptionsProvider,
            "graphExtractionOptionsProvider"
        );
        this.documentParsingOrchestrator = documentParsingOrchestrator;
        this.taskEventListeners = List.copyOf(Objects.requireNonNull(taskEventListeners, "taskEventListeners"));
        this.taskExecutionService = new TaskExecutionService(
            workspaceId -> resolveProvider(resolveScope(workspaceId)),
            this.taskEventListeners
        );
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
        ingest(workspaceId, DocumentIngestRequest.of(documents));
    }

    public void ingest(String workspaceId, DocumentIngestRequest request) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            newIndexingPipeline(scope, provider).ingest(normalizedRequest.documents());
            return null;
        });
    }

    public void ingestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            newIndexingPipeline(scope, provider).ingestSources(sources, options);
            return null;
        });
    }

    public void ingest(String workspaceId, PreChunkedIngestRequest request) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            newIndexingPipeline(scope, provider).ingestPreChunkedChunks(normalizedRequest.chunks());
            return null;
        });
    }

    public void ingestChunks(String workspaceId, List<PreChunkedChunk> chunks) {
        ingest(workspaceId, PreChunkedIngestRequest.ofChunks(chunks));
    }

    public String submitIngest(String workspaceId, List<Document> documents) {
        return submitIngest(workspaceId, DocumentIngestRequest.of(documents), TaskSubmitOptions.defaults());
    }

    public String submitIngest(String workspaceId, List<Document> documents, TaskSubmitOptions options) {
        return submitIngest(workspaceId, DocumentIngestRequest.of(documents), options);
    }

    public String submitIngest(String workspaceId, DocumentIngestRequest request) {
        return submitIngest(workspaceId, request, TaskSubmitOptions.defaults());
    }

    public String submitIngest(String workspaceId, DocumentIngestRequest request, TaskSubmitOptions options) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        return submitIngestTask(
            workspaceId,
            options,
            Map.of("documentCount", Integer.toString(normalizedRequest.documents().size())),
            (scope, progressListener) -> newIndexingPipeline(scope, resolveProvider(scope), progressListener)
                .ingest(normalizedRequest.documents())
        );
    }

    public String submitIngest(String workspaceId, PreChunkedIngestRequest request) {
        return submitIngest(workspaceId, request, TaskSubmitOptions.defaults());
    }

    public String submitIngest(String workspaceId, PreChunkedIngestRequest request, TaskSubmitOptions options) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        return submitIngestTask(
            workspaceId,
            options,
            Map.of(
                "documentCount", Integer.toString(countDocuments(normalizedRequest.chunks())),
                "chunkCount", Integer.toString(normalizedRequest.chunks().size())
            ),
            (scope, progressListener) -> newIndexingPipeline(scope, resolveProvider(scope), progressListener)
                .ingestPreChunkedChunks(normalizedRequest.chunks())
        );
    }

    public String submitIngestChunks(String workspaceId, List<PreChunkedChunk> chunks) {
        return submitIngestChunks(workspaceId, chunks, TaskSubmitOptions.defaults());
    }

    public String submitIngestChunks(String workspaceId, List<PreChunkedChunk> chunks, TaskSubmitOptions options) {
        return submitIngest(workspaceId, PreChunkedIngestRequest.ofChunks(chunks), options);
    }

    private String submitIngestTask(
        String workspaceId,
        TaskSubmitOptions options,
        Map<String, String> metadata,
        IngestTaskWork work
    ) {
        var submitOptions = Objects.requireNonNull(options, "options");
        var taskWork = Objects.requireNonNull(work, "work");
        var scope = resolveScope(workspaceId);
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.INGEST_DOCUMENTS,
            pipelineMetadata(scope, metadata),
            submitOptions.listeners(),
            progressListener -> taskWork.run(scope, progressListener)
        );
    }

    private int countDocuments(List<PreChunkedChunk> chunks) {
        return (int) chunks.stream()
            .map(PreChunkedChunk::documentId)
            .distinct()
            .count();
    }

    public String submitIngestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options) {
        return submitIngestSources(workspaceId, sources, options, TaskSubmitOptions.defaults());
    }

    public String submitIngestSources(
        String workspaceId,
        List<RawDocumentSource> sources,
        DocumentIngestOptions options,
        TaskSubmitOptions submitOptions
    ) {
        var normalizedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        var resolvedOptions = Objects.requireNonNull(options, "options");
        var taskSubmitOptions = Objects.requireNonNull(submitOptions, "submitOptions");
        var scope = resolveScope(workspaceId);
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.INGEST_SOURCES,
            pipelineMetadata(scope, Map.of("sourceCount", Integer.toString(normalizedSources.size()))),
            taskSubmitOptions.listeners(),
            progressListener -> {
                newIndexingPipeline(scope, resolveProvider(scope), progressListener)
                    .ingestSources(normalizedSources, resolvedOptions);
            }
        );
    }

    public String submitRebuild(String workspaceId) {
        return submitRebuild(workspaceId, TaskSubmitOptions.defaults());
    }

    public String submitRebuild(String workspaceId, TaskSubmitOptions options) {
        var submitOptions = Objects.requireNonNull(options, "options");
        var scope = resolveScope(workspaceId);
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.REBUILD_GRAPH,
            pipelineMetadata(scope, Map.of()),
            submitOptions.listeners(),
            progressListener -> {
                newDeletionPipeline(scope, resolveProvider(scope), progressListener).rebuildAllDocuments();
            }
        );
    }

    public String submitDeleteByDocumentId(String workspaceId, String documentId) {
        return submitDeleteByDocumentId(workspaceId, documentId, DeleteDocumentOptions.defaults(), TaskSubmitOptions.defaults());
    }

    public String submitDeleteByDocumentId(
        String workspaceId,
        String documentId,
        DeleteDocumentOptions deleteOptions,
        TaskSubmitOptions submitOptions
    ) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var resolvedDeleteOptions = Objects.requireNonNull(deleteOptions, "deleteOptions");
        var resolvedSubmitOptions = Objects.requireNonNull(submitOptions, "submitOptions");
        var scope = resolveScope(workspaceId);
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.DELETE_DOCUMENT,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "deleteLlmCache", Boolean.toString(resolvedDeleteOptions.deleteLlmCache())
            )),
            resolvedSubmitOptions.listeners(),
            progressListener -> {
                newDeletionPipeline(scope, resolveProvider(scope), progressListener)
                    .deleteByDocumentId(normalizedDocumentId, resolvedDeleteOptions);
            }
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

    public List<TaskDocumentSnapshot> listTaskDocuments(String workspaceId, String taskId) {
        var scope = resolveScope(workspaceId);
        return resolveProvider(scope).taskDocumentStore()
            .listByTask(requireNonBlank(taskId, "taskId")).stream()
            .map(TaskDocumentStore.TaskDocumentRecord::toSnapshot)
            .toList();
    }

    public TaskDocumentSnapshot getTaskDocument(String workspaceId, String taskId, String documentId) {
        var scope = resolveScope(workspaceId);
        return resolveProvider(scope).taskDocumentStore()
            .load(requireNonBlank(taskId, "taskId"), requireNonBlank(documentId, "documentId"))
            .map(TaskDocumentStore.TaskDocumentRecord::toSnapshot)
            .orElseThrow(() -> new NoSuchElementException("task document does not exist: " + documentId));
    }

    public GraphEntity createEntity(String workspaceId, CreateEntityRequest request) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).createEntity(request));
    }

    public GraphRelation createRelation(String workspaceId, CreateRelationRequest request) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).createRelation(request));
    }

    public GraphEntity editEntity(String workspaceId, EditEntityRequest request) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).editEntity(request));
    }

    public GraphRelation updateRelation(String workspaceId, UpdateRelationRequest request) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).updateRelation(request));
    }

    public void deleteRelation(String workspaceId, DeleteRelationRequest request) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            newGraphManagementPipeline(scope, provider).deleteRelation(request);
            return null;
        });
    }

    public GraphEntity mergeEntities(String workspaceId, MergeEntitiesRequest request) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).mergeEntities(request));
    }

    /**
     * Deletes the resolved entity from graph and vector storage while preserving source documents and chunks.
     * Use {@link #deleteByDocumentId(String, String)} to remove the originating text itself.
     */
    public DeletionResult deleteByEntity(String workspaceId, String entityName) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newDeletionPipeline(scope, provider).deleteByEntity(entityName));
    }

    /**
     * Deletes all relations between the resolved endpoint entities from graph and relation-vector storage.
     * Source documents and chunks remain available until removed by document deletion.
     */
    public DeletionResult deleteByRelation(String workspaceId, String sourceEntityName, String targetEntityName) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> newDeletionPipeline(scope, provider).deleteByRelation(sourceEntityName, targetEntityName)
        );
    }

    /**
     * Deletes a document by clearing storage and rebuilding all remaining documents through the current
     * LightRag indexing pipeline.
     */
    public DeletionResult deleteByDocumentId(String workspaceId, String documentId) {
        return deleteByDocumentId(workspaceId, documentId, DeleteDocumentOptions.defaults());
    }

    public DeletionResult deleteByDocumentId(String workspaceId, String documentId, DeleteDocumentOptions options) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> newDeletionPipeline(scope, provider).deleteByDocumentId(documentId, options));
    }

    public DocumentIngestResumeResult resumeDocumentIngest(String workspaceId, String documentId) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> resumeDocumentIngest(scope, provider, documentId, IndexingProgressListener.noop(), TaskMetadataReporter.noop())
        );
    }

    public String submitResumeDocumentIngest(String workspaceId, String documentId) {
        return submitResumeDocumentIngest(workspaceId, documentId, TaskSubmitOptions.defaults());
    }

    public String submitResumeDocumentIngest(String workspaceId, String documentId, TaskSubmitOptions options) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var submitOptions = Objects.requireNonNull(options, "options");
        var scope = resolveScope(workspaceId);
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.RESUME_DOCUMENT_INGEST,
            pipelineMetadata(scope, Map.of("documentId", normalizedDocumentId)),
            submitOptions.listeners(),
            progressListener -> resumeDocumentIngest(
                scope,
                resolveProvider(scope),
                normalizedDocumentId,
                progressListener,
                progressListener instanceof TaskMetadataReporter metadataReporter
                    ? metadataReporter
                    : TaskMetadataReporter.noop()
            )
        );
    }

    public void clearCache(String workspaceId) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            provider.llmCacheStore().drop();
            return null;
        });
    }

    public QueryResult query(String workspaceId, QueryRequest request) {
        var scope = resolveScope(workspaceId);
        return newQueryEngine(resolveProvider(scope)).query(request);
    }

    public StructuredQueryResult queryStructured(String workspaceId, QueryRequest request) {
        var scope = resolveScope(workspaceId);
        return newQueryEngine(resolveProvider(scope)).queryStructured(request);
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
        var scope = resolveScope(workspaceId);
        return newGraphMaterializationPipeline(scope, resolveProvider(scope)).inspect(documentId);
    }

    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> newGraphMaterializationPipeline(scope, provider).materialize(documentId, mode)
        );
    }

    public DocumentChunkGraphStatus getDocumentChunkGraphStatus(String workspaceId, String documentId, String chunkId) {
        var scope = resolveScope(workspaceId);
        return newGraphMaterializationPipeline(scope, resolveProvider(scope)).getChunkStatus(documentId, chunkId);
    }

    public List<DocumentChunkGraphStatus> listDocumentChunkGraphStatuses(String workspaceId, String documentId) {
        var scope = resolveScope(workspaceId);
        return newGraphMaterializationPipeline(scope, resolveProvider(scope)).listChunkStatuses(documentId);
    }

    public ChunkGraphMaterializationResult resumeChunkGraph(String workspaceId, String documentId, String chunkId) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> newGraphMaterializationPipeline(scope, provider).resumeChunk(documentId, chunkId)
        );
    }

    public ChunkGraphMaterializationResult repairChunkGraph(String workspaceId, String documentId, String chunkId) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> newGraphMaterializationPipeline(scope, provider).repairChunk(documentId, chunkId)
        );
    }

    public String submitDocumentGraphMaterialization(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        var scope = resolveScope(workspaceId);
        var normalizedDocumentId = Objects.requireNonNull(documentId, "documentId");
        var requestedMode = Objects.requireNonNull(mode, "mode");
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.MATERIALIZE_DOCUMENT_GRAPH,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "requestedMode", requestedMode.name()
            )),
            progressListener -> {
                newGraphMaterializationPipeline(
                    scope,
                    resolveProvider(scope),
                    progressListener,
                    progressListener instanceof TaskMetadataReporter metadataReporter
                        ? metadataReporter
                        : TaskMetadataReporter.noop()
                ).materialize(normalizedDocumentId, requestedMode);
            }
        );
    }

    public String submitChunkGraphMaterialization(
        String workspaceId,
        String documentId,
        String chunkId,
        GraphChunkAction action
    ) {
        var scope = resolveScope(workspaceId);
        var normalizedDocumentId = Objects.requireNonNull(documentId, "documentId");
        var normalizedChunkId = Objects.requireNonNull(chunkId, "chunkId");
        var requestedAction = Objects.requireNonNull(action, "action");
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.MATERIALIZE_CHUNK_GRAPH,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "chunkId", normalizedChunkId,
                "requestedAction", requestedAction.name()
            )),
            progressListener -> {
                var pipeline = newGraphMaterializationPipeline(
                    scope,
                    resolveProvider(scope),
                    progressListener,
                    progressListener instanceof TaskMetadataReporter metadataReporter
                        ? metadataReporter
                        : TaskMetadataReporter.noop()
                );
                if (requestedAction == GraphChunkAction.REPAIR) {
                    pipeline.repairChunk(normalizedDocumentId, normalizedChunkId);
                } else if (requestedAction == GraphChunkAction.RESUME) {
                    pipeline.resumeChunk(normalizedDocumentId, normalizedChunkId);
                } else {
                    throw new IllegalArgumentException("GraphChunkAction.NONE cannot be submitted");
                }
            }
        );
    }

    public void saveSnapshot(String workspaceId, Path path) {
        var scope = resolveScope(workspaceId);
        var snapshotPath = Objects.requireNonNull(path, "path");
        runInWorkspace(scope, storageProvider -> {
            storageProvider.snapshotStore().save(snapshotPath, StorageSnapshots.capture(storageProvider));
            return null;
        });
    }

    public void restoreSnapshot(String workspaceId, Path path) {
        var scope = resolveScope(workspaceId);
        var snapshotPath = Objects.requireNonNull(path, "path");
        runInWorkspace(scope, storageProvider -> {
            storageProvider.restore(storageProvider.snapshotStore().load(snapshotPath));
            return null;
        });
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

    double minRerankScore() {
        return minRerankScore;
    }

    int embeddingBatchSize() {
        return embeddingBatchSize;
    }

    int maxParallelInsert() {
        return maxParallelInsert;
    }

    int chunkExtractParallelism() {
        return chunkExtractParallelism;
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

    boolean graphExtractionEnabled() {
        return graphExtractionEnabled;
    }

    List<String> relationTypes() {
        return relationTypes;
    }

    List<GraphExtractionExample> graphExtractionExamples() {
        return graphExtractionExamples;
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

    List<TaskEventListener> taskEventListeners() {
        return taskEventListeners;
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

    private <T> T runInWorkspace(WorkspaceScope scope, Function<AtomicStorageProvider, T> work) {
        var normalizedScope = Objects.requireNonNull(scope, "scope");
        return taskExecutionService.runInWorkspace(
            normalizedScope.workspaceId(),
            provider -> Objects.requireNonNull(work, "work").apply(provider)
        );
    }

    private IndexingPipeline newIndexingPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newIndexingPipeline(scope, storageProvider, IndexingProgressListener.noop());
    }

    private IndexingPipeline newIndexingPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        var llmCacheStore = storageProvider.llmCacheStore();
        var graphExtractionOptions = resolveGraphExtractionOptions(scope);
        return new IndexingPipeline(
            cachedModel("extract", config.extractionModel(), llmCacheStore),
            cachedModel("summary", config.summaryModel(), llmCacheStore),
            config.embeddingModel(),
            storageProvider,
            config.snapshotPath(),
            chunker,
            documentParsingOrchestrator,
            embeddingBatchSize,
            maxParallelInsert,
            graphExtractionOptions.resolvedChunkExtractParallelism(),
            graphExtractionOptions.resolvedEntityExtractMaxGleaning(),
            graphExtractionOptions.resolvedMaxExtractInputTokens(),
            graphExtractionOptions.resolvedLanguage(),
            graphExtractionOptions.resolvedEntityTypes(),
            graphExtractionOptions.resolvedEnabled(),
            graphExtractionOptions.resolvedRelationTypes(),
            graphExtractionOptions.resolvedExamples(),
            embeddingSemanticMergeEnabled,
            embeddingSemanticMergeThreshold,
            extractionRefinementOptions,
            progressListener
        );
    }

    private DeletionPipeline newDeletionPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newDeletionPipeline(scope, storageProvider, IndexingProgressListener.noop());
    }

    private DeletionPipeline newDeletionPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        return new DeletionPipeline(storageProvider, newIndexingPipeline(scope, storageProvider, progressListener), config.snapshotPath());
    }

    private GraphManagementPipeline newGraphManagementPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return new GraphManagementPipeline(storageProvider, newIndexingPipeline(scope, storageProvider), config.snapshotPath());
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newGraphMaterializationPipeline(scope, storageProvider, IndexingProgressListener.noop(), TaskMetadataReporter.noop());
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter
    ) {
        var llmCacheStore = storageProvider.llmCacheStore();
        var graphExtractionOptions = resolveGraphExtractionOptions(scope);
        if (!graphExtractionOptions.resolvedEnabled()) {
            throw new IllegalStateException("knowledge graph extraction is disabled for workspace " + scope.workspaceId());
        }
        return new GraphMaterializationPipeline(
            cachedModel("extract", config.extractionModel(), llmCacheStore),
            config.embeddingModel(),
            storageProvider,
            extractionRefinementOptions,
            config.snapshotPath(),
            metadataReporter,
            progressListener,
            graphExtractionOptions.resolvedEntityExtractMaxGleaning(),
            graphExtractionOptions.resolvedMaxExtractInputTokens(),
            graphExtractionOptions.resolvedLanguage(),
            graphExtractionOptions.resolvedEntityTypes(),
            graphExtractionOptions.resolvedRelationTypes(),
            graphExtractionOptions.resolvedExamples()
        );
    }

    private DocumentIngestResumeResult resumeDocumentIngest(
        WorkspaceScope scope,
        AtomicStorageProvider provider,
        String documentId,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter
    ) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var graphEnabled = resolveGraphExtractionOptions(scope).resolvedEnabled()
            && !documentSkipsKnowledgeGraph(provider, normalizedDocumentId);
        var graphStatus = GraphMaterializationStatus.MISSING;

        if (graphEnabled) {
            var graphPipeline = newGraphMaterializationPipeline(scope, provider, progressListener, metadataReporter);
            var inspection = graphPipeline.inspect(normalizedDocumentId);
            graphStatus = inspection.graphStatus();
            if (inspection.graphStatus() != GraphMaterializationStatus.MERGED && inspection.repairable()) {
                var materialized = graphPipeline.materialize(normalizedDocumentId, GraphMaterializationMode.AUTO);
                var finalStatus = provider.documentStatusStore().load(normalizedDocumentId)
                    .map(io.github.lightrag.storage.DocumentStatusStore.StatusRecord::status)
                    .orElse(DocumentStatus.FAILED);
                return new DocumentIngestResumeResult(
                    normalizedDocumentId,
                    DocumentIngestResumeAction.GRAPH_MATERIALIZATION,
                    finalStatus,
                    materialized.finalStatus(),
                    materialized.summary(),
                    materialized.errorMessage()
                );
            }
        }

        var currentStatus = provider.documentStatusStore().load(normalizedDocumentId).orElse(null);
        if (currentStatus != null && currentStatus.status() == DocumentStatus.PROCESSED) {
            return new DocumentIngestResumeResult(
                normalizedDocumentId,
                DocumentIngestResumeAction.NONE,
                DocumentStatus.PROCESSED,
                graphStatus,
                "document ingest already complete",
                null
            );
        }

        var source = provider.documentStore().load(normalizedDocumentId)
            .map(LightRag::toDocument)
            .orElseThrow(() -> new NoSuchElementException(
                "cannot resume document ingest because stored document does not exist: " + normalizedDocumentId
            ));
        newIndexingPipeline(scope, provider, progressListener).ingest(List.of(source));
        var finalStatus = provider.documentStatusStore().load(normalizedDocumentId)
            .map(io.github.lightrag.storage.DocumentStatusStore.StatusRecord::status)
            .orElse(DocumentStatus.FAILED);
        var finalGraphStatus = graphEnabled
            ? newGraphMaterializationPipeline(scope, provider, IndexingProgressListener.noop(), TaskMetadataReporter.noop())
                .inspect(normalizedDocumentId)
                .graphStatus()
            : GraphMaterializationStatus.MISSING;
        return new DocumentIngestResumeResult(
            normalizedDocumentId,
            DocumentIngestResumeAction.REINGEST,
            finalStatus,
            finalGraphStatus,
            "document reingested from stored full document",
            null
        );
    }

    private static Document toDocument(io.github.lightrag.storage.DocumentStore.DocumentRecord record) {
        return new Document(record.id(), record.title(), record.content(), record.metadata());
    }

    private static boolean documentSkipsKnowledgeGraph(AtomicStorageProvider provider, String documentId) {
        return provider.documentStore().load(documentId)
            .map(io.github.lightrag.storage.DocumentStore.DocumentRecord::metadata)
            .map(metadata -> metadata.get(DocumentIngestOptions.METADATA_PROCESS_OPTIONS))
            .map(value -> value.indexOf('!') >= 0)
            .orElse(false);
    }

    private GraphExtractionOptions resolveGraphExtractionOptions(WorkspaceScope scope) {
        var normalizedScope = Objects.requireNonNull(scope, "scope");
        return Objects.requireNonNull(
                graphExtractionOptionsProvider.resolve(normalizedScope),
                "graphExtractionOptionsProvider.resolve"
            )
            .map(options -> options.mergeOver(globalGraphExtractionOptions))
            .orElse(globalGraphExtractionOptions)
            .mergeOver(GraphExtractionOptions.defaults());
    }

    private Map<String, String> pipelineMetadata(WorkspaceScope scope, Map<String, String> baseMetadata) {
        var graphOptions = resolveGraphExtractionOptions(scope);
        var metadata = new LinkedHashMap<String, String>(Objects.requireNonNull(baseMetadata, "baseMetadata"));
        metadata.put("maxParallelInsert", Integer.toString(maxParallelInsert));
        metadata.put("embeddingBatchSize", Integer.toString(embeddingBatchSize));
        metadata.put("chunkExtractParallelism", Integer.toString(graphOptions.resolvedChunkExtractParallelism()));
        metadata.put("entityExtractMaxGleaning", Integer.toString(graphOptions.resolvedEntityExtractMaxGleaning()));
        metadata.put("maxExtractInputTokens", Integer.toString(graphOptions.resolvedMaxExtractInputTokens()));
        metadata.put("graphExtractionEnabled", Boolean.toString(graphOptions.resolvedEnabled()));
        metadata.put("entityTypeCount", Integer.toString(graphOptions.resolvedEntityTypes().size()));
        metadata.put("relationTypeCount", Integer.toString(graphOptions.resolvedRelationTypes().size()));
        metadata.put("graphExtractionExampleCount", Integer.toString(graphOptions.resolvedExamples().size()));
        metadata.put("embeddingSemanticMergeEnabled", Boolean.toString(embeddingSemanticMergeEnabled));
        return Map.copyOf(metadata);
    }

    private QueryEngine newQueryEngine(AtomicStorageProvider storageProvider) {
        var llmCacheStore = storageProvider.llmCacheStore();
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
            cachedModel("query", config.queryModel(), llmCacheStore),
            cachedModel("keyword", config.keywordModel(), llmCacheStore),
            contextAssembler,
            strategies,
            config.rerankModel(),
            automaticQueryKeywordExtraction,
            rerankCandidateMultiplier,
            minRerankScore,
            new RuleBasedQueryIntentClassifier(),
            multiHop,
            new PathAwareAnswerSynthesizer()
        );
    }

    private static ChatModel cachedModel(String role, ChatModel delegate, io.github.lightrag.storage.LlmCacheStore cacheStore) {
        return new CachedChatModel(role, delegate, cacheStore);
    }

    @FunctionalInterface
    private interface IngestTaskWork {
        void run(WorkspaceScope scope, IndexingProgressListener progressListener);
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

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
