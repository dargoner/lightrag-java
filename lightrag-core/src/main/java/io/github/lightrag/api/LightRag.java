package io.github.lightrag.api;

import io.github.lightrag.config.LightRagConfig;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DeletionPipeline;
import io.github.lightrag.indexing.DescriptionSummarizer;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.GraphMaterializationPipeline;
import io.github.lightrag.indexing.GraphManagementPipeline;
import io.github.lightrag.indexing.GraphVectorIndexer;
import io.github.lightrag.indexing.IndexingProgressListener;
import io.github.lightrag.indexing.IndexingPipeline;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.CachedChatModel;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.model.RerankFailureMode;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyConformanceValidator;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyJsonCodec;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.OntologyTaskContext;
import io.github.lightrag.persistence.ContentExport;
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
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.EmbeddingSpaceStore;
import io.github.lightrag.storage.NoopVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.task.TaskExecutionService;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.task.WorkspaceConcurrencyMode;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.PreChunkedChunk;
import io.github.lightrag.types.RawDocumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public final class LightRag implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(LightRag.class);

    private final LightRagConfig config;
    private final Chunker chunker;
    private final boolean automaticQueryKeywordExtraction;
    private final int rerankCandidateMultiplier;
    private final double minRerankScore;
    private final RerankFailureMode rerankFailureMode;
    private final TokenCounter tokenCounter;
    private final int embeddingBatchSize;
    private final int maxParallelInsert;
    private final int chunkExtractParallelism;
    private final int maxConcurrentDocumentTasks;
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
    private final String failResponse;
    private final String userPromptPrefix;
    private final TaskExecutionService taskExecutionService;
    private final LlmConcurrencyBudget llmConcurrencyBudget;
    private final AtomicBoolean closed = new AtomicBoolean();

    LightRag(LightRagConfig config) {
        this(config, null, null, true, 2, 0.0d, RerankFailureMode.FAIL_FAST, new HeuristicTokenCounter(), Integer.MAX_VALUE, 1,
            1,
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
            List.of(),
            QueryEngine.DEFAULT_FAIL_RESPONSE,
            "");
    }

    LightRag(LightRagConfig config, Chunker chunker) {
        this(config, chunker, null, true, 2, 0.0d, RerankFailureMode.FAIL_FAST, new HeuristicTokenCounter(), Integer.MAX_VALUE, 1,
            1,
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
            List.of(),
            QueryEngine.DEFAULT_FAIL_RESPONSE,
            "");
    }

    LightRag(
        LightRagConfig config,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        boolean automaticQueryKeywordExtraction,
        int rerankCandidateMultiplier,
        double minRerankScore,
        RerankFailureMode rerankFailureMode,
        TokenCounter tokenCounter,
        int embeddingBatchSize,
        int maxParallelInsert,
        int chunkExtractParallelism,
        int maxConcurrentDocumentTasks,
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
        List<TaskEventListener> taskEventListeners,
        String failResponse,
        String userPromptPrefix
    ) {
        this.config = config;
        this.llmConcurrencyBudget = new LlmConcurrencyBudget(config.maxAsyncLlm(), config.embeddingMaxAsync());
        this.chunker = chunker;
        this.automaticQueryKeywordExtraction = automaticQueryKeywordExtraction;
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        this.minRerankScore = minRerankScore;
        this.rerankFailureMode = Objects.requireNonNull(rerankFailureMode, "rerankFailureMode");
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.embeddingBatchSize = embeddingBatchSize;
        this.maxParallelInsert = maxParallelInsert;
        this.chunkExtractParallelism = chunkExtractParallelism;
        this.maxConcurrentDocumentTasks = maxConcurrentDocumentTasks;
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
            graphExtractionExamples,
            config.entityExtractMaxRecords(),
            config.entityExtractMaxEntities()
        );
        this.graphExtractionOptionsProvider = Objects.requireNonNull(
            graphExtractionOptionsProvider,
            "graphExtractionOptionsProvider"
        );
        this.documentParsingOrchestrator = documentParsingOrchestrator;
        this.taskEventListeners = List.copyOf(Objects.requireNonNull(taskEventListeners, "taskEventListeners"));
        this.failResponse = Objects.requireNonNull(failResponse, "failResponse");
        this.userPromptPrefix = Objects.requireNonNull(userPromptPrefix, "userPromptPrefix");
        this.taskExecutionService = new TaskExecutionService(
            workspaceId -> resolveProvider(resolveScope(workspaceId)),
            this.taskEventListeners,
            maxConcurrentDocumentTasks
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
        runInWorkspace(scope, modeForDocumentCount(normalizedRequest.documents().size()), provider -> {
            newIndexingPipeline(scope, provider).ingest(normalizedRequest.documents());
            return null;
        });
    }

    public void ingestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, modeForDocumentCount(sources.size()), provider -> {
            newIndexingPipeline(scope, provider).ingestSources(sources, options);
            return null;
        });
    }

    public void ingest(String workspaceId, PreChunkedIngestRequest request) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, modeForDocumentCount(countDocuments(normalizedRequest.chunks())), provider -> {
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
        var documentCount = normalizedRequest.documents().size();
        return submitIngestTask(
            workspaceId,
            options,
            modeForDocumentCount(documentCount),
            Map.of("documentCount", Integer.toString(documentCount)),
            (scope, progressListener, ontologyContext) -> newIndexingPipeline(
                scope, resolveProvider(scope), progressListener, ontologyContext
            ).ingest(normalizedRequest.documents())
        );
    }

    public String submitIngest(String workspaceId, PreChunkedIngestRequest request) {
        return submitIngest(workspaceId, request, TaskSubmitOptions.defaults());
    }

    public String submitIngest(String workspaceId, PreChunkedIngestRequest request, TaskSubmitOptions options) {
        var normalizedRequest = Objects.requireNonNull(request, "request");
        var documentCount = countDocuments(normalizedRequest.chunks());
        return submitIngestTask(
            workspaceId,
            options,
            modeForDocumentCount(documentCount),
            Map.of(
                "documentCount", Integer.toString(documentCount),
                "chunkCount", Integer.toString(normalizedRequest.chunks().size())
            ),
            (scope, progressListener, ontologyContext) -> newIndexingPipeline(
                scope, resolveProvider(scope), progressListener, ontologyContext
            ).ingestPreChunkedChunks(normalizedRequest.chunks())
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
        WorkspaceConcurrencyMode mode,
        Map<String, String> metadata,
        IngestTaskWork work
    ) {
        var submitOptions = Objects.requireNonNull(options, "options");
        var taskWork = Objects.requireNonNull(work, "work");
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, submitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.INGEST_DOCUMENTS,
            pipelineMetadata(scope, metadata, ontologyContext),
            mode,
            submitOptions.listeners(),
            progressListener -> taskWork.run(scope, progressListener, ontologyContext)
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
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, taskSubmitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.INGEST_SOURCES,
            pipelineMetadata(scope, Map.of("sourceCount", Integer.toString(normalizedSources.size())), ontologyContext),
            modeForDocumentCount(normalizedSources.size()),
            taskSubmitOptions.listeners(),
            progressListener -> {
                newIndexingPipeline(scope, resolveProvider(scope), progressListener, ontologyContext)
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
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, submitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.REBUILD_GRAPH,
            pipelineMetadata(scope, Map.of(), ontologyContext),
            submitOptions.listeners(),
            progressListener -> {
                newDeletionPipeline(scope, resolveProvider(scope), progressListener, ontologyContext)
                    .rebuildAllDocuments();
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
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, resolvedSubmitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.DELETE_DOCUMENT,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "deleteLlmCache", Boolean.toString(resolvedDeleteOptions.deleteLlmCache())
            ), ontologyContext),
            resolvedSubmitOptions.listeners(),
            progressListener -> {
                newDeletionPipeline(scope, resolveProvider(scope), progressListener, ontologyContext)
                    .deleteByDocumentId(normalizedDocumentId, resolvedDeleteOptions);
                graphExtractionOptionsProvider.markOntologyDirty(scope);
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
        var result = runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).createEntity(request));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    public GraphRelation createRelation(String workspaceId, CreateRelationRequest request) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).createRelation(request));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    public GraphEntity editEntity(String workspaceId, EditEntityRequest request) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).editEntity(request));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    public GraphRelation updateRelation(String workspaceId, UpdateRelationRequest request) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).updateRelation(request));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    public void deleteRelation(String workspaceId, DeleteRelationRequest request) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, provider -> {
            newGraphManagementPipeline(scope, provider).deleteRelation(request);
            return null;
        });
        graphExtractionOptionsProvider.markOntologyDirty(scope);
    }

    public GraphEntity mergeEntities(String workspaceId, MergeEntitiesRequest request) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(scope, provider -> newGraphManagementPipeline(scope, provider).mergeEntities(request));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    /** All entity ids in the workspace graph, sorted by code point. */
    public List<String> getGraphLabels(String workspaceId) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> provider.graphStore().labels());
    }

    /**
     * Entity ids whose id or name contains {@code query}, case-insensitive, cut to {@code limit};
     * case-insensitive exact matches come first.
     */
    public List<String> searchGraphLabels(String workspaceId, String query, int limit) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, provider -> provider.graphStore().searchLabels(query, limit));
    }

    /**
     * Bounded subgraph around {@code nodeLabel} ({@code "*"} ranks the whole graph by degree), with the
     * node budget clamped to the configured {@code maxGraphNodes} so a caller can ask for fewer nodes
     * but never more.
     */
    public KnowledgeGraphView getKnowledgeGraph(String workspaceId, String nodeLabel, int maxDepth, int maxNodes) {
        var scope = resolveScope(workspaceId);
        var budget = maxNodes <= 0
            ? config.maxGraphNodes()
            : Math.min(maxNodes, config.maxGraphNodes());
        return runInWorkspace(
            scope,
            provider -> provider.graphStore().getKnowledgeGraph(nodeLabel, maxDepth, budget)
        );
    }

    /**
     * Deletes the resolved entity from graph and vector storage while preserving source documents and chunks.
     * Use {@link #deleteByDocumentId(String, String)} to remove the originating text itself.
     */
    public DeletionResult deleteByEntity(String workspaceId, String entityName) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(scope, provider -> newDeletionPipeline(scope, provider).deleteByEntity(entityName));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    /**
     * Deletes all relations between the resolved endpoint entities from graph and relation-vector storage.
     * Source documents and chunks remain available until removed by document deletion.
     */
    public DeletionResult deleteByRelation(String workspaceId, String sourceEntityName, String targetEntityName) {
        var scope = resolveScope(workspaceId);
        var result = runInWorkspace(
            scope,
            provider -> newDeletionPipeline(scope, provider).deleteByRelation(sourceEntityName, targetEntityName)
        );
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
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
        var result = runInWorkspace(scope, provider -> newDeletionPipeline(scope, provider).deleteByDocumentId(documentId, options));
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        return result;
    }

    public DocumentIngestResumeResult resumeDocumentIngest(String workspaceId, String documentId) {
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveContextFromTaskHistory(scope, documentId));
        return runInWorkspace(
            scope,
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            provider -> resumeDocumentIngest(
                scope,
                provider,
                documentId,
                IndexingProgressListener.noop(),
                TaskMetadataReporter.noop(),
                ontologyContext
            )
        );
    }

    public String submitResumeDocumentIngest(String workspaceId, String documentId) {
        return submitResumeDocumentIngest(workspaceId, documentId, TaskSubmitOptions.defaults());
    }

    public String submitResumeDocumentIngest(String workspaceId, String documentId, TaskSubmitOptions options) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var submitOptions = Objects.requireNonNull(options, "options");
        var scope = resolveScope(workspaceId);
        var requestedContext = submitOptions.ontologyContext();
        var ontologyContext = bindTaskContext(
            scope,
            requestedContext.isEmpty()
                ? resolveContextFromTaskHistory(scope, normalizedDocumentId)
                : requestedContext
        );
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.RESUME_DOCUMENT_INGEST,
            pipelineMetadata(scope, Map.of("documentId", normalizedDocumentId), ontologyContext),
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            submitOptions.listeners(),
            progressListener -> resumeDocumentIngest(
                scope,
                resolveProvider(scope),
                normalizedDocumentId,
                progressListener,
                progressListener instanceof TaskMetadataReporter metadataReporter
                    ? metadataReporter
                    : TaskMetadataReporter.noop(),
                ontologyContext
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

    /**
     * Answers one retrieval over a set of workspaces as a single pipeline: keywords, query
     * embeddings, store reads and reranking run once, with each store read IN-batched across the
     * workspace set. A single-element set behaves exactly like {@link #query(String, QueryRequest)}.
     * Workspace-set reads require a workspace storage provider that supports
     * {@link io.github.lightrag.storage.WorkspaceStorageProvider#forWorkspaces(java.util.List)};
     * providers without batch support throw {@link UnsupportedOperationException} and callers fall
     * back to per-workspace queries.
     */
    public QueryResult queryForWorkspaces(List<String> workspaceIds, QueryRequest request) {
        // Validate the caller's list directly: List.copyOf would reject a null element with a bare
        // NullPointerException before the blank-id contract below can report it as an argument error.
        var ids = Objects.requireNonNull(workspaceIds, "workspaceIds");
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("workspaceIds must not be empty");
        }
        var normalized = new LinkedHashSet<String>();
        for (var id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("workspaceIds must not contain blank ids");
            }
            normalized.add(id.strip());
        }
        var scopes = normalized.stream().map(WorkspaceScope::new).toList();
        var provider = Objects.requireNonNull(
            config.workspaceStorageProvider().forWorkspaces(scopes),
            "workspaceStorageProvider.forWorkspaces"
        );
        return newQueryEngine(provider).query(request);
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

    /**
     * Returns document statuses filtered by {@code statuses} with offset/limit paging and a deterministic
     * order by document id; {@code total} counts all matches before paging. A null or empty filter means
     * "all statuses". Filtering happens in memory over {@code documentStatusStore().list()}; store-side
     * pushdown is a follow-up.
     */
    public DocumentStatusPage queryDocumentStatuses(
        String workspaceId,
        Set<DocumentStatus> statuses,
        int offset,
        int limit
    ) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        var scope = resolveScope(workspaceId);
        var filter = statuses == null ? Set.<DocumentStatus>of() : Set.copyOf(statuses);
        var matches = resolveProvider(scope).documentStatusStore().list().stream()
            .filter(record -> filter.isEmpty() || filter.contains(record.status()))
            .sorted(Comparator.comparing(DocumentStatusStore.StatusRecord::documentId))
            .toList();
        var items = matches.stream()
            .skip(offset)
            .limit(limit)
            .map(LightRag::toDocumentProcessingStatus)
            .toList();
        return new DocumentStatusPage(items, matches.size(), offset, limit);
    }

    /** Looks up several document statuses by id; unknown ids are omitted from the result. */
    public List<DocumentProcessingStatus> getDocumentStatuses(String workspaceId, List<String> documentIds) {
        var scope = resolveScope(workspaceId);
        var ids = List.copyOf(Objects.requireNonNull(documentIds, "documentIds"));
        var statusStore = resolveProvider(scope).documentStatusStore();
        return ids.stream()
            .map(statusStore::load)
            .flatMap(Optional::stream)
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
        return materializeDocumentGraph(workspaceId, documentId, mode, TaskSubmitOptions.defaults());
    }

    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode,
        TaskSubmitOptions options
    ) {
        return materializeDocumentGraph(workspaceId, documentId, mode, null, options);
    }

    /**
     * Materializes the document graph, polling {@code cancellationCheckpoint} while the work runs.
     *
     * @param cancellationCheckpoint polled between extraction steps and before every atomic commit; {@code null}
     *     behaves as {@link CancellationCheckpoint#NONE}. The boundary semantics (a commit already entered is not
     *     interruptible) are documented on {@link io.github.lightrag.indexing.GraphMaterializationPipeline}.
     */
    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        return materializeDocumentGraph(workspaceId, documentId, mode, cancellationCheckpoint, TaskSubmitOptions.defaults());
    }

    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode,
        CancellationCheckpoint cancellationCheckpoint,
        TaskSubmitOptions options
    ) {
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, options));
        return runInWorkspace(
            scope,
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            provider -> newGraphMaterializationPipeline(
                scope,
                provider,
                IndexingProgressListener.noop(),
                TaskMetadataReporter.noop(),
                cancellationCheckpoint,
                ontologyContext
            ).materialize(documentId, mode)
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
        return resumeChunkGraph(workspaceId, documentId, chunkId, TaskSubmitOptions.defaults());
    }

    public ChunkGraphMaterializationResult resumeChunkGraph(
        String workspaceId,
        String documentId,
        String chunkId,
        TaskSubmitOptions options
    ) {
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, options));
        return runInWorkspace(
            scope,
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            provider -> newGraphMaterializationPipeline(
                scope,
                provider,
                IndexingProgressListener.noop(),
                TaskMetadataReporter.noop(),
                null,
                ontologyContext
            ).resumeChunk(documentId, chunkId)
        );
    }

    public ChunkGraphMaterializationResult repairChunkGraph(String workspaceId, String documentId, String chunkId) {
        return repairChunkGraph(workspaceId, documentId, chunkId, TaskSubmitOptions.defaults());
    }

    public ChunkGraphMaterializationResult repairChunkGraph(
        String workspaceId,
        String documentId,
        String chunkId,
        TaskSubmitOptions options
    ) {
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, options));
        return runInWorkspace(
            scope,
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            provider -> newGraphMaterializationPipeline(
                scope,
                provider,
                IndexingProgressListener.noop(),
                TaskMetadataReporter.noop(),
                null,
                ontologyContext
            ).repairChunk(documentId, chunkId)
        );
    }

    public String submitDocumentGraphMaterialization(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        return submitDocumentGraphMaterialization(workspaceId, documentId, mode, TaskSubmitOptions.defaults());
    }

    public String submitDocumentGraphMaterialization(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode,
        TaskSubmitOptions options
    ) {
        var scope = resolveScope(workspaceId);
        var normalizedDocumentId = Objects.requireNonNull(documentId, "documentId");
        var requestedMode = Objects.requireNonNull(mode, "mode");
        var submitOptions = Objects.requireNonNull(options, "options");
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, submitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.MATERIALIZE_DOCUMENT_GRAPH,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "requestedMode", requestedMode.name()
            ), ontologyContext),
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            submitOptions.listeners(),
            progressListener -> {
                newGraphMaterializationPipeline(
                    scope,
                    resolveProvider(scope),
                    progressListener,
                    progressListener instanceof TaskMetadataReporter metadataReporter
                        ? metadataReporter
                        : TaskMetadataReporter.noop(),
                    null,
                    ontologyContext
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
        return submitChunkGraphMaterialization(workspaceId, documentId, chunkId, action, TaskSubmitOptions.defaults());
    }

    public String submitChunkGraphMaterialization(
        String workspaceId,
        String documentId,
        String chunkId,
        GraphChunkAction action,
        TaskSubmitOptions options
    ) {
        var scope = resolveScope(workspaceId);
        var normalizedDocumentId = Objects.requireNonNull(documentId, "documentId");
        var normalizedChunkId = Objects.requireNonNull(chunkId, "chunkId");
        var requestedAction = Objects.requireNonNull(action, "action");
        var submitOptions = Objects.requireNonNull(options, "options");
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, submitOptions));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.MATERIALIZE_CHUNK_GRAPH,
            pipelineMetadata(scope, Map.of(
                "documentId", normalizedDocumentId,
                "chunkId", normalizedChunkId,
                "requestedAction", requestedAction.name()
            ), ontologyContext),
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            submitOptions.listeners(),
            progressListener -> {
                var pipeline = newGraphMaterializationPipeline(
                    scope,
                    resolveProvider(scope),
                    progressListener,
                    progressListener instanceof TaskMetadataReporter metadataReporter
                        ? metadataReporter
                        : TaskMetadataReporter.noop(),
                    null,
                    ontologyContext
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
            graphExtractionOptionsProvider.markOntologyDirty(scope);
            return null;
        });
    }

    /**
     * Exports the workspace content (documents, chunks, entities, relations, document statuses and the derived
     * graph bookkeeping) to a self-contained single-file JSON artifact without vectors: importing rebuilds them
     * with the embedding model configured on the importing instance, so the artifact carries no dependency on
     * the exporting side's embedding space and can travel over any byte channel. The same artifact is available
     * as {@link #exportData(String)} bytes and on a caller-owned stream via {@link #exportData(String, OutputStream)}.
     */
    public void exportData(String workspaceId, Path path) {
        ContentExport.write(Objects.requireNonNull(path, "path"), captureContentSnapshot(workspaceId));
    }

    /** Exports the workspace content as the bytes of the artifact described by {@link #exportData(String, Path)}. */
    public byte[] exportData(String workspaceId) {
        return ContentExport.writeBytes(captureContentSnapshot(workspaceId));
    }

    /** Streams the artifact described by {@link #exportData(String, Path)} to a caller-owned stream. */
    public void exportData(String workspaceId, OutputStream outputStream) {
        ContentExport.write(Objects.requireNonNull(outputStream, "outputStream"), captureContentSnapshot(workspaceId));
    }

    /**
     * Imports an artifact produced by {@link #exportData(String, Path)}: the workspace content is replaced, then
     * chunk, entity and relation vectors are rebuilt from that content with the configured embedding model and
     * the embedding space marker is refreshed to the space of the rebuilt vectors.
     */
    public void importData(String workspaceId, Path path) {
        importData(workspaceId, path, IndexingProgressListener.noop());
    }

    /**
     * Imports the artifact described by {@link #importData(String, Path)} while reporting progress to the given
     * listener: callbacks run synchronously on the calling thread while the import holds the workspace gate and are
     * subject to the {@link IndexingProgressListener} reentrancy contract. The stages cover the content restore
     * ({@link TaskStage#SNAPSHOT_LOADING}), the vector rebuilds ({@link TaskStage#VECTOR_INDEXING}, one started and
     * succeeded pair per namespace; skipped when the vector store is disabled) and the final marker refresh
     * ({@link TaskStage#FINALIZING}).
     */
    public void importData(String workspaceId, Path path, IndexingProgressListener progressListener) {
        importSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(path, "path")),
            Objects.requireNonNull(progressListener, "progressListener")
        );
    }

    /** Imports the artifact carried by the given bytes; see {@link #importData(String, Path)}. */
    public void importData(String workspaceId, byte[] data) {
        importData(workspaceId, data, IndexingProgressListener.noop());
    }

    /** Imports the artifact carried by the given bytes and reports progress; see {@link #importData(String, Path, IndexingProgressListener)}. */
    public void importData(String workspaceId, byte[] data, IndexingProgressListener progressListener) {
        importSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(data, "data")),
            Objects.requireNonNull(progressListener, "progressListener")
        );
    }

    /** Imports the artifact read from a caller-owned stream; see {@link #importData(String, Path)}. */
    public void importData(String workspaceId, InputStream inputStream) {
        importData(workspaceId, inputStream, IndexingProgressListener.noop());
    }

    /** Imports the artifact read from a caller-owned stream and reports progress; see {@link #importData(String, Path, IndexingProgressListener)}. */
    public void importData(String workspaceId, InputStream inputStream, IndexingProgressListener progressListener) {
        importSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(inputStream, "inputStream")),
            Objects.requireNonNull(progressListener, "progressListener")
        );
    }

    /** Submits the import described by {@link #importData(String, Path)} as a background task. */
    public String submitImport(String workspaceId, Path path) {
        return submitImport(workspaceId, path, TaskSubmitOptions.defaults());
    }

    /**
     * Submits the import as a background task and returns its task id: the artifact is read and validated on the
     * calling thread, so an unreadable or unsupported artifact fails immediately without creating a task, then the
     * import runs like {@link #importData(String, Path)} on a task thread, replacing the workspace content and
     * rebuilding its vectors. Task records and {@link TaskEventListener} events carry the same stages as the
     * listener overloads plus the submitted/completed task lifecycle.
     */
    public String submitImport(String workspaceId, Path path, TaskSubmitOptions options) {
        return submitImportSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(path, "path")),
            Objects.requireNonNull(options, "options")
        );
    }

    /** Submits the import of the artifact carried by the given bytes as a background task; see {@link #submitImport(String, Path, TaskSubmitOptions)}. */
    public String submitImport(String workspaceId, byte[] data) {
        return submitImport(workspaceId, data, TaskSubmitOptions.defaults());
    }

    /** Submits the import of the artifact carried by the given bytes as a background task; see {@link #submitImport(String, Path, TaskSubmitOptions)}. */
    public String submitImport(String workspaceId, byte[] data, TaskSubmitOptions options) {
        return submitImportSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(data, "data")),
            Objects.requireNonNull(options, "options")
        );
    }

    /** Submits the import of the artifact read from a caller-owned stream as a background task; see {@link #submitImport(String, Path, TaskSubmitOptions)}. */
    public String submitImport(String workspaceId, InputStream inputStream) {
        return submitImport(workspaceId, inputStream, TaskSubmitOptions.defaults());
    }

    /** Submits the import of the artifact read from a caller-owned stream as a background task; see {@link #submitImport(String, Path, TaskSubmitOptions)}. */
    public String submitImport(String workspaceId, InputStream inputStream, TaskSubmitOptions options) {
        return submitImportSnapshot(
            workspaceId,
            ContentExport.read(Objects.requireNonNull(inputStream, "inputStream")),
            Objects.requireNonNull(options, "options")
        );
    }

    private SnapshotStore.Snapshot captureContentSnapshot(String workspaceId) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(scope, storageProvider -> withoutVectors(StorageSnapshots.capture(storageProvider)));
    }

    private void importSnapshot(String workspaceId, SnapshotStore.Snapshot snapshot, IndexingProgressListener progressListener) {
        var scope = resolveScope(workspaceId);
        runInWorkspace(scope, storageProvider -> {
            importContent(storageProvider, snapshot, progressListener, scope);
            return null;
        });
    }

    private String submitImportSnapshot(String workspaceId, SnapshotStore.Snapshot snapshot, TaskSubmitOptions options) {
        var scope = resolveScope(workspaceId);
        var ontologyContext = bindTaskContext(scope, resolveOntologyTaskContext(scope, options));
        return taskExecutionService.submit(
            scope.workspaceId(),
            TaskType.IMPORT_CONTENT,
            pipelineMetadata(scope, Map.of(), ontologyContext),
            options.listeners(),
            progressListener -> importContent(resolveProvider(scope), snapshot, progressListener, scope)
        );
    }

    private void importContent(
        AtomicStorageProvider storageProvider,
        SnapshotStore.Snapshot snapshot,
        IndexingProgressListener progressListener,
        WorkspaceScope scope
    ) {
        progressListener.onStageStarted(TaskStage.SNAPSHOT_LOADING, "restoring workspace content from import");
        storageProvider.restore(snapshot);
        graphExtractionOptionsProvider.markOntologyDirty(scope);
        progressListener.onStageSucceeded(
            TaskStage.SNAPSHOT_LOADING,
            "restored %d documents, %d chunks, %d entities, %d relations".formatted(
                snapshot.documents().size(),
                snapshot.chunks().size(),
                snapshot.entities().size(),
                snapshot.relations().size()
            )
        );
        rebuildVectorsFromContent(storageProvider, progressListener);
    }

    private static SnapshotStore.Snapshot withoutVectors(SnapshotStore.Snapshot snapshot) {
        return new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities(),
            snapshot.relations(),
            Map.of(),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        );
    }

    private void rebuildVectorsFromContent(
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        if (storageProvider.vectorStore() instanceof NoopVectorStore) {
            progressListener.onStageSkipped(
                TaskStage.VECTOR_INDEXING,
                "vector store is disabled; imported content is kept without vectors"
            );
            return;
        }
        var chunks = storageProvider.chunkStore().list().stream().map(GraphVectorIndexer::toChunk).toList();
        var entities = storageProvider.graphStore().allEntities().stream().map(GraphVectorIndexer::toEntity).toList();
        var relations = storageProvider.graphStore().allRelations().stream().map(GraphVectorIndexer::toRelation).toList();
        var embeddingModel = limitedEmbeddingModel(LlmConcurrencyBudget.EmbeddingPriority.LOW);
        var vectorStore = storageProvider.vectorStore();

        progressListener.onStageStarted(TaskStage.VECTOR_INDEXING, "embedding %d chunk vectors".formatted(chunks.size()));
        var chunkVectors = GraphVectorIndexer.chunkVectors(embeddingModel, embeddingBatchSize, chunks);
        GraphVectorIndexer.saveChunkVectors(chunks, chunkVectors, vectorStore);
        progressListener.onStageSucceeded(TaskStage.VECTOR_INDEXING, "embedded %d chunk vectors".formatted(chunkVectors.size()));

        progressListener.onStageStarted(TaskStage.VECTOR_INDEXING, "embedding %d entity vectors".formatted(entities.size()));
        var entityVectors = GraphVectorIndexer.entityVectors(embeddingModel, embeddingBatchSize, entities);
        GraphVectorIndexer.saveEntityVectors(entities, entityVectors, vectorStore);
        progressListener.onStageSucceeded(TaskStage.VECTOR_INDEXING, "embedded %d entity vectors".formatted(entityVectors.size()));

        progressListener.onStageStarted(TaskStage.VECTOR_INDEXING, "embedding %d relation vectors".formatted(relations.size()));
        var relationVectors = GraphVectorIndexer.relationVectors(embeddingModel, embeddingBatchSize, relations);
        GraphVectorIndexer.saveRelationVectors(relations, relationVectors, vectorStore);
        progressListener.onStageSucceeded(TaskStage.VECTOR_INDEXING, "embedded %d relation vectors".formatted(relationVectors.size()));

        progressListener.onStageStarted(TaskStage.FINALIZING, "finalizing import");
        refreshEmbeddingSpaceMarker(storageProvider, embeddingModel, chunkVectors, entityVectors, relationVectors);
        progressListener.onStageSucceeded(TaskStage.FINALIZING, "import finalized");
    }

    @SafeVarargs
    private static void refreshEmbeddingSpaceMarker(
        AtomicStorageProvider storageProvider,
        EmbeddingModel embeddingModel,
        List<VectorStore.VectorRecord>... batches
    ) {
        EmbeddingSpaceStore spaceStore;
        try {
            spaceStore = storageProvider.embeddingSpaceStore();
        } catch (UnsupportedOperationException exception) {
            if (!Objects.equals(exception.getMessage(), StorageProvider.EMBEDDING_SPACE_STORE_UNSUPPORTED_MESSAGE)) {
                throw exception;
            }
            return;
        }
        var sample = java.util.Arrays.stream(batches).filter(batch -> !batch.isEmpty()).findFirst().orElse(List.of());
        if (sample.isEmpty()) {
            // The imported content produced no vectors, so a stale marker from the replaced workspace must not
            // survive and refuse the next ingest of a different model.
            spaceStore.delete();
            return;
        }
        var identity = embeddingModel.cacheIdentity();
        spaceStore.save(new EmbeddingSpaceStore.Marker(
            identity == null || identity.isBlank() ? "unknown" : identity,
            sample.get(0).vector().size(),
            Instant.now().toString()
        ));
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

    RerankFailureMode rerankFailureMode() {
        return rerankFailureMode;
    }

    TokenCounter tokenCounter() {
        return tokenCounter;
    }

    String failResponse() {
        return failResponse;
    }

    String userPromptPrefix() {
        return userPromptPrefix;
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

    int maxConcurrentDocumentTasks() {
        return maxConcurrentDocumentTasks;
    }

    int entityExtractMaxGleaning() {
        return entityExtractMaxGleaning;
    }

    int maxExtractInputTokens() {
        return maxExtractInputTokens;
    }

    int entityExtractMaxRecords() {
        return globalGraphExtractionOptions.resolvedEntityExtractMaxRecords();
    }

    int entityExtractMaxEntities() {
        return globalGraphExtractionOptions.resolvedEntityExtractMaxEntities();
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

    private <T> T runInWorkspace(
        WorkspaceScope scope,
        WorkspaceConcurrencyMode mode,
        Function<AtomicStorageProvider, T> work
    ) {
        var normalizedScope = Objects.requireNonNull(scope, "scope");
        var normalizedMode = Objects.requireNonNull(mode, "mode");
        return taskExecutionService.runInWorkspace(
            normalizedScope.workspaceId(),
            normalizedMode,
            provider -> Objects.requireNonNull(work, "work").apply(provider)
        );
    }

    /**
     * Classifies an ingest request by the number of documents it carries: a request that provably touches exactly
     * one document only rewrites that document's derived objects, while a request carrying several documents (batch
     * documents, sources, pre-chunked chunks across document ids) keeps the whole workspace. A request that is
     * neither of these stays exclusive without passing through here.
     */
    private static WorkspaceConcurrencyMode modeForDocumentCount(long documentCount) {
        return documentCount == 1
            ? WorkspaceConcurrencyMode.DOCUMENT_SCOPED
            : WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE;
    }

    private IndexingPipeline newIndexingPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newIndexingPipeline(
            scope,
            storageProvider,
            IndexingProgressListener.noop(),
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private IndexingPipeline newIndexingPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        return newIndexingPipeline(
            scope,
            storageProvider,
            progressListener,
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private IndexingPipeline newIndexingPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        OntologyTaskContext ontologyContext
    ) {
        var llmCacheStore = storageProvider.llmCacheStore();
        var graphExtractionOptions = ontologyContext.graphExtractionOptions();
        var ontologyExtractionOverlay = ontologyContext.overlay();
        var kgExtractionValidator = ontologyContext.validator() != null
            ? ontologyContext.validator()
            : config.kgExtractionValidator();
        if (graphExtractionOptions.resolvedEnabled()
            && ontologyExtractionOverlay.mode() == ExtractionMode.STRICT
            && kgExtractionValidator == null) {
            throw new IllegalStateException(
                "ontology extraction is configured in STRICT mode but no ontology is bound for workspace "
                    + scope.workspaceId() + "; submit the task with an ontology task context"
            );
        }
        return new IndexingPipeline(
            cachedModel("extract", config.extractionModel(), llmCacheStore),
            cachedModel("summary", config.summaryModel(), llmCacheStore),
            limitedEmbeddingModel(LlmConcurrencyBudget.EmbeddingPriority.LOW),
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
            progressListener,
            descriptionSummarizer(llmCacheStore, graphExtractionOptions.resolvedLanguage()),
            config.maxSourceIdsPerEntity(),
            config.maxSourceIdsPerRelation(),
            config.sourceIdsLimitMethod(),
            config.maxFilePaths(),
            graphExtractionOptions.resolvedEntityExtractMaxRecords(),
            graphExtractionOptions.resolvedEntityExtractMaxEntities(),
            kgExtractionValidator,
            config.sectionContextEnabled(),
            ontologyExtractionOverlay
        );
    }

    private DeletionPipeline newDeletionPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newDeletionPipeline(
            scope,
            storageProvider,
            IndexingProgressListener.noop(),
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private DeletionPipeline newDeletionPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener
    ) {
        return newDeletionPipeline(scope, storageProvider, progressListener, bindTaskContext(scope, OntologyTaskContext.empty()));
    }

    private DeletionPipeline newDeletionPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        OntologyTaskContext ontologyContext
    ) {
        return new DeletionPipeline(
            storageProvider,
            newIndexingPipeline(scope, storageProvider, progressListener, ontologyContext),
            config.snapshotPath()
        );
    }

    private GraphManagementPipeline newGraphManagementPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return new GraphManagementPipeline(
            storageProvider,
            newIndexingPipeline(
                scope,
                storageProvider,
                IndexingProgressListener.noop(),
                bindTaskContext(scope, OntologyTaskContext.empty())
            ),
            config.snapshotPath()
        );
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(WorkspaceScope scope, AtomicStorageProvider storageProvider) {
        return newGraphMaterializationPipeline(
            scope,
            storageProvider,
            IndexingProgressListener.noop(),
            TaskMetadataReporter.noop(),
            null,
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        return newGraphMaterializationPipeline(
            scope,
            storageProvider,
            IndexingProgressListener.noop(),
            TaskMetadataReporter.noop(),
            cancellationCheckpoint,
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter
    ) {
        return newGraphMaterializationPipeline(
            scope,
            storageProvider,
            progressListener,
            metadataReporter,
            null,
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        return newGraphMaterializationPipeline(
            scope,
            storageProvider,
            progressListener,
            metadataReporter,
            cancellationCheckpoint,
            bindTaskContext(scope, OntologyTaskContext.empty())
        );
    }

    private GraphMaterializationPipeline newGraphMaterializationPipeline(
        WorkspaceScope scope,
        AtomicStorageProvider storageProvider,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter,
        CancellationCheckpoint cancellationCheckpoint,
        OntologyTaskContext ontologyContext
    ) {
        var llmCacheStore = storageProvider.llmCacheStore();
        var graphExtractionOptions = ontologyContext.graphExtractionOptions();
        if (!graphExtractionOptions.resolvedEnabled()) {
            throw new IllegalStateException("knowledge graph extraction is disabled for workspace " + scope.workspaceId());
        }
        var ontologyExtractionOverlay = ontologyContext.overlay();
        var kgExtractionValidator = ontologyContext.validator() != null
            ? ontologyContext.validator()
            : config.kgExtractionValidator();
        if (ontologyExtractionOverlay.mode() == ExtractionMode.STRICT && kgExtractionValidator == null) {
            throw new IllegalStateException(
                "ontology extraction is configured in STRICT mode but no ontology is bound for workspace "
                    + scope.workspaceId() + "; submit the task with an ontology task context"
            );
        }
        return new GraphMaterializationPipeline(
            cachedModel("extract", config.extractionModel(), llmCacheStore),
            limitedEmbeddingModel(LlmConcurrencyBudget.EmbeddingPriority.LOW),
            storageProvider,
            extractionRefinementOptions,
            config.snapshotPath(),
            metadataReporter,
            progressListener,
            graphExtractionOptions.resolvedChunkExtractParallelism(),
            graphExtractionOptions.resolvedEntityExtractMaxGleaning(),
            graphExtractionOptions.resolvedMaxExtractInputTokens(),
            graphExtractionOptions.resolvedLanguage(),
            graphExtractionOptions.resolvedEntityTypes(),
            graphExtractionOptions.resolvedRelationTypes(),
            graphExtractionOptions.resolvedExamples(),
            cancellationCheckpoint,
            descriptionSummarizer(llmCacheStore, graphExtractionOptions.resolvedLanguage()),
            config.maxSourceIdsPerEntity(),
            config.maxSourceIdsPerRelation(),
            config.sourceIdsLimitMethod(),
            config.maxFilePaths(),
            graphExtractionOptions.resolvedEntityExtractMaxRecords(),
            graphExtractionOptions.resolvedEntityExtractMaxEntities(),
            kgExtractionValidator,
            config.sectionContextEnabled(),
            ontologyExtractionOverlay
        );
    }

    private DocumentIngestResumeResult resumeDocumentIngest(
        WorkspaceScope scope,
        AtomicStorageProvider provider,
        String documentId,
        IndexingProgressListener progressListener,
        TaskMetadataReporter metadataReporter,
        OntologyTaskContext ontologyContext
    ) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var graphOptions = ontologyContext.graphExtractionOptions();
        var graphEnabled = graphOptions.resolvedEnabled()
            && !documentSkipsKnowledgeGraph(provider, normalizedDocumentId);
        var graphStatus = GraphMaterializationStatus.MISSING;

        if (graphEnabled) {
            var graphPipeline = newGraphMaterializationPipeline(
                scope, provider, progressListener, metadataReporter, null, ontologyContext
            );
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
        newIndexingPipeline(scope, provider, progressListener, ontologyContext).ingest(List.of(source));
        var finalStatus = provider.documentStatusStore().load(normalizedDocumentId)
            .map(io.github.lightrag.storage.DocumentStatusStore.StatusRecord::status)
            .orElse(DocumentStatus.FAILED);
        var finalGraphStatus = graphEnabled
            ? newGraphMaterializationPipeline(
                scope, provider, IndexingProgressListener.noop(), TaskMetadataReporter.noop(), null, ontologyContext
            )
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

    private OntologyExtractionOverlay resolveOntologyExtractionOverlay(WorkspaceScope scope) {
        var normalizedScope = Objects.requireNonNull(scope, "scope");
        return Objects.requireNonNull(
                graphExtractionOptionsProvider.ontologyExtractionOverlay(normalizedScope),
                "graphExtractionOptionsProvider.ontologyExtractionOverlay"
            )
            .orElse(OntologyExtractionOverlay.empty());
    }

    private OntologyTaskContext resolveOntologyTaskContext(WorkspaceScope scope, TaskSubmitOptions options) {
        var normalizedScope = Objects.requireNonNull(scope, "scope");
        var normalizedOptions = Objects.requireNonNull(options, "options");
        if (!normalizedOptions.ontologyContext().isEmpty()) {
            return normalizedOptions.ontologyContext();
        }
        return OntologyTaskContext.empty();
    }

    private OntologyTaskContext bindTaskContext(WorkspaceScope scope, OntologyTaskContext context) {
        if (!context.isEmpty()) {
            return context;
        }
        return new OntologyTaskContext(
            resolveGraphExtractionOptions(scope),
            new Ontology(null, null, null, null),
            resolveOntologyExtractionOverlay(scope),
            false
        );
    }

    private static final String ONTOLOGY_CONTEXT_METADATA_KEY = "lightrag.ontology.resolutionContext";

    private OntologyTaskContext resolveContextFromTaskHistory(WorkspaceScope scope, String documentId) {
        var provider = resolveProvider(scope);
        var selectedTask = taskExecutionService.listTasks(scope.workspaceId()).stream()
            .filter(task -> isAssociatedWithDocument(provider, task, documentId))
            .max(Comparator
                .comparing(TaskSnapshot::requestedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TaskSnapshot::taskId))
            .orElse(null);
        if (selectedTask == null) {
            return OntologyTaskContext.empty();
        }
        var metadata = selectedTask.metadata();
        if (!metadata.containsKey(ONTOLOGY_CONTEXT_METADATA_KEY)) {
            // Tasks submitted before the context feature carry no snapshot; fall back to legacy binding.
            return OntologyTaskContext.empty();
        }
        String contextJson = metadata.get(ONTOLOGY_CONTEXT_METADATA_KEY);
        if (contextJson == null || contextJson.isBlank()) {
            throw new IllegalStateException(
                "Stored ontology resolution context for document " + documentId
                    + " in workspace " + scope.workspaceId() + " is corrupt: context payload is blank"
            );
        }
        try {
            return OntologyJsonCodec.readContext(contextJson);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Stored ontology resolution context for document " + documentId
                    + " in workspace " + scope.workspaceId() + " is corrupt: " + exception.getMessage(),
                exception
            );
        }
    }

    private static boolean isAssociatedWithDocument(
        AtomicStorageProvider provider,
        TaskSnapshot task,
        String documentId
    ) {
        if (documentId.equals(task.metadata().get("documentId"))) {
            return true;
        }
        try {
            return provider.taskDocumentStore().load(task.taskId(), documentId).isPresent();
        } catch (UnsupportedOperationException exception) {
            if (StorageProvider.TASK_DOCUMENT_STORE_UNSUPPORTED_MESSAGE.equals(exception.getMessage())
                || "taskDocumentStore is not available in this relational adapter".equals(exception.getMessage())) {
                return false;
            }
            throw exception;
        }
    }

    private Map<String, String> pipelineMetadata(
        WorkspaceScope scope,
        Map<String, String> baseMetadata,
        OntologyTaskContext ontologyContext
    ) {
        var graphOptions = ontologyContext.graphExtractionOptions();
        var metadata = new LinkedHashMap<String, String>(Objects.requireNonNull(baseMetadata, "baseMetadata"));
        metadata.put("maxParallelInsert", Integer.toString(maxParallelInsert));
        metadata.put("maxConcurrentDocumentTasks", Integer.toString(maxConcurrentDocumentTasks));
        metadata.put("embeddingBatchSize", Integer.toString(embeddingBatchSize));
        metadata.put("chunkExtractParallelism", Integer.toString(graphOptions.resolvedChunkExtractParallelism()));
        metadata.put("entityExtractMaxGleaning", Integer.toString(graphOptions.resolvedEntityExtractMaxGleaning()));
        metadata.put("maxExtractInputTokens", Integer.toString(graphOptions.resolvedMaxExtractInputTokens()));
        metadata.put("entityExtractMaxRecords", Integer.toString(graphOptions.resolvedEntityExtractMaxRecords()));
        metadata.put("entityExtractMaxEntities", Integer.toString(graphOptions.resolvedEntityExtractMaxEntities()));
        metadata.put("graphExtractionEnabled", Boolean.toString(graphOptions.resolvedEnabled()));
        metadata.put("entityTypeCount", Integer.toString(graphOptions.resolvedEntityTypes().size()));
        metadata.put("relationTypeCount", Integer.toString(graphOptions.resolvedRelationTypes().size()));
        metadata.put("graphExtractionExampleCount", Integer.toString(graphOptions.resolvedExamples().size()));
        metadata.put("embeddingSemanticMergeEnabled", Boolean.toString(embeddingSemanticMergeEnabled));
        metadata.put("ontologyContextFingerprint", ontologyContext.fingerprint());
        metadata.put("lightrag.ontology.resolutionContext", OntologyJsonCodec.writeContext(ontologyContext));
        return Map.copyOf(metadata);
    }

    private QueryEngine newQueryEngine(AtomicStorageProvider storageProvider) {
        var llmCacheStore = storageProvider.llmCacheStore();
        var contextAssembler = new ContextAssembler(tokenCounter);
        var queryPriority = LlmConcurrencyBudget.EmbeddingPriority.HIGH;
        var naive = new NaiveQueryStrategy(limitedEmbeddingModel(queryPriority), storageProvider, contextAssembler, tokenCounter);
        var local = new LocalQueryStrategy(limitedEmbeddingModel(queryPriority), storageProvider, contextAssembler, tokenCounter);
        var global = new GlobalQueryStrategy(limitedEmbeddingModel(queryPriority), storageProvider, contextAssembler, tokenCounter);
        var hybrid = new HybridQueryStrategy(local, global, contextAssembler, tokenCounter);
        var mix = new MixQueryStrategy(limitedEmbeddingModel(queryPriority), storageProvider, hybrid, contextAssembler, tokenCounter);
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
            new PathAwareAnswerSynthesizer(),
            failResponse,
            userPromptPrefix,
            rerankFailureMode,
            tokenCounter
        );
    }

    private DescriptionSummarizer descriptionSummarizer(io.github.lightrag.storage.LlmCacheStore llmCacheStore, String language) {
        return new DescriptionSummarizer(
            cachedModel("summary", config.summaryModel(), llmCacheStore),
            new io.github.lightrag.model.HeuristicTokenCounter(),
            config.forceLlmSummaryOnMerge(),
            config.summaryMaxTokens(),
            config.summaryContextSize(),
            config.summaryLengthRecommended(),
            language
        );
    }

    private ChatModel cachedModel(String role, ChatModel delegate, io.github.lightrag.storage.LlmCacheStore cacheStore) {
        // The cache sits outside the concurrency limiter so cache hits never consume a slot,
        // matching upstream (use_llm_func_with_cache checks the cache before the limited role func).
        return new CachedChatModel(role, llmConcurrencyBudget.limitChat(role, delegate), cacheStore);
    }

    private EmbeddingModel limitedEmbeddingModel(LlmConcurrencyBudget.EmbeddingPriority priority) {
        return llmConcurrencyBudget.limitEmbedding(priority, config.embeddingModel());
    }

    @FunctionalInterface
    private interface IngestTaskWork {
        void run(WorkspaceScope scope, IndexingProgressListener progressListener, OntologyTaskContext ontologyContext);
    }

    private static DocumentProcessingStatus toDocumentProcessingStatus(
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord
    ) {
        return new DocumentProcessingStatus(
            statusRecord.documentId(),
            statusRecord.status(),
            statusRecord.summary(),
            statusRecord.errorMessage(),
            statusRecord.metadata()
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
