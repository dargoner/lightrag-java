package io.github.lightrag.api;

import io.github.lightrag.config.LightRagConfig;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.FixedWindowChunker;
import io.github.lightrag.indexing.SmartChunker;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.model.RerankFailureMode;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.model.openai.ModelRetrySupport;
import io.github.lightrag.model.openai.OpenAiCompatibleChatModel;
import io.github.lightrag.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightrag.query.QueryEngine;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.FixedWorkspaceStorageProvider;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.NoopVectorStorageProvider;
import io.github.lightrag.storage.NoopVectorWorkspaceStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageAssembly;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Supplier;

public final class LightRagBuilder {
    private static final int DEFAULT_CHUNK_WINDOW = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    static final boolean DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED = false;
    static final double DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD = 0.80d;

    private ChatModel chatModel;
    private ChatModel queryModel;
    private ChatModel keywordModel;
    private ChatModel extractionModel;
    private ChatModel summaryModel;
    private io.github.lightrag.model.ChatRequestOptions chatRequestOptions;
    private Integer modelMaxAttempts;
    private EmbeddingModel embeddingModel;
    private StorageProvider storageProvider;
    private WorkspaceStorageProvider workspaceStorageProvider;
    private Path snapshotPath;
    private RerankModel rerankModel;
    private Chunker chunker = new FixedWindowChunker(DEFAULT_CHUNK_WINDOW, DEFAULT_CHUNK_OVERLAP);
    private boolean automaticQueryKeywordExtraction = true;
    private int rerankCandidateMultiplier = 2;
    private double minRerankScore = 0.0d;
    private RerankFailureMode rerankFailureMode = RerankFailureMode.FAIL_FAST;
    private TokenCounter tokenCounter = new HeuristicTokenCounter();
    private String failResponse = QueryEngine.DEFAULT_FAIL_RESPONSE;
    private String userPromptPrefix = "";
    private int forceLlmSummaryOnMerge = io.github.lightrag.indexing.DescriptionSummarizer.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE;
    private int summaryMaxTokens = io.github.lightrag.indexing.DescriptionSummarizer.DEFAULT_SUMMARY_MAX_TOKENS;
    private int summaryContextSize = io.github.lightrag.indexing.DescriptionSummarizer.DEFAULT_SUMMARY_CONTEXT_SIZE;
    private int summaryLengthRecommended = io.github.lightrag.indexing.DescriptionSummarizer.DEFAULT_SUMMARY_LENGTH_RECOMMENDED;
    private int maxSourceIdsPerEntity = io.github.lightrag.indexing.SourceIdLimits.DEFAULT_MAX_SOURCE_IDS;
    private int maxSourceIdsPerRelation = io.github.lightrag.indexing.SourceIdLimits.DEFAULT_MAX_SOURCE_IDS;
    private io.github.lightrag.indexing.SourceIdLimits.Method sourceIdsLimitMethod =
        io.github.lightrag.indexing.SourceIdLimits.Method.KEEP;
    private int maxFilePaths = io.github.lightrag.indexing.FilePathLimits.DEFAULT_MAX_FILE_PATHS;
    private int embeddingBatchSize = Integer.MAX_VALUE;
    private int maxParallelInsert = 3;
    private int maxAsyncLlm = LlmConcurrencyBudget.DEFAULT_MAX_ASYNC_LLM;
    private int embeddingMaxAsync = LlmConcurrencyBudget.DEFAULT_EMBEDDING_MAX_ASYNC;
    private int maxGraphNodes = io.github.lightrag.config.LightRagConfig.DEFAULT_MAX_GRAPH_NODES;
    private int chunkExtractParallelism = 2;
    private int maxConcurrentDocumentTasks = 1;
    private int entityExtractMaxGleaning = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING;
    private int maxExtractInputTokens = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS;
    private int entityExtractMaxRecords = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS;
    private int entityExtractMaxEntities = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES;
    private KgExtractionValidator kgExtractionValidator;
    private boolean sectionContextEnabled = true;
    private String entityExtractionLanguage = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE;
    private List<String> entityTypes = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES;
    private boolean graphExtractionEnabled = true;
    private List<String> relationTypes = List.of();
    private List<GraphExtractionExample> graphExtractionExamples = List.of();
    private boolean embeddingSemanticMergeEnabled = DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED;
    private double embeddingSemanticMergeThreshold = DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD;
    private boolean contextualExtractionRefinementEnabled;
    private boolean allowDeterministicAttributionFallback;
    private boolean noopVectorStore;
    private DocumentParsingOrchestrator documentParsingOrchestrator;
    private GraphExtractionOptionsProvider graphExtractionOptionsProvider = GraphExtractionOptionsProvider.none();
    private final List<TaskEventListener> taskEventListeners = new ArrayList<>();

    public LightRagBuilder chatModel(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        return this;
    }

    public LightRagBuilder embeddingModel(EmbeddingModel embeddingModel) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        return this;
    }

    public LightRagBuilder queryModel(ChatModel queryModel) {
        this.queryModel = Objects.requireNonNull(queryModel, "queryModel");
        return this;
    }

    public LightRagBuilder keywordModel(ChatModel keywordModel) {
        this.keywordModel = Objects.requireNonNull(keywordModel, "keywordModel");
        return this;
    }

    public LightRagBuilder extractionModel(ChatModel extractionModel) {
        this.extractionModel = Objects.requireNonNull(extractionModel, "extractionModel");
        return this;
    }

    public LightRagBuilder summaryModel(ChatModel summaryModel) {
        this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
        return this;
    }

    /**
     * Defaults for every chat request (temperature, max tokens, top-p, response format). Applied
     * per role model as merge defaults: a request's own non-null options win.
     */
    public LightRagBuilder chatRequestOptions(io.github.lightrag.model.ChatRequestOptions chatRequestOptions) {
        this.chatRequestOptions = Objects.requireNonNull(chatRequestOptions, "chatRequestOptions");
        return this;
    }

    /**
     * Retries injected chat and embedding models on transient provider failures (timeouts,
     * connection errors, 408/409/5xx, the "could not parse" 400s) with exponential backoff
     * capped at 1 s. Rate limits (429) and other 4xx fail fast. {@code 1} disables retry.
     * Models that already own a retry policy (the built-in OpenAI-compatible ones) keep their
     * constructor-provided one.
     */
    public LightRagBuilder modelMaxAttempts(int modelMaxAttempts) {
        if (modelMaxAttempts < 1) {
            throw new IllegalArgumentException("modelMaxAttempts must be positive");
        }
        this.modelMaxAttempts = modelMaxAttempts;
        return this;
    }

    /**
     * Source-id cap per entity (upstream {@code max_source_ids_per_entity}, default 200).
     * {@link Integer#MAX_VALUE} is an explicit opt-out that disables capping for both kinds.
     */
    public LightRagBuilder maxSourceIdsPerEntity(int maxSourceIdsPerEntity) {
        if (maxSourceIdsPerEntity <= 0) {
            throw new IllegalArgumentException("maxSourceIdsPerEntity must be positive");
        }
        this.maxSourceIdsPerEntity = maxSourceIdsPerEntity;
        return this;
    }

    /** Source-id cap per relation (upstream {@code max_source_ids_per_relation}, default 200). */
    public LightRagBuilder maxSourceIdsPerRelation(int maxSourceIdsPerRelation) {
        if (maxSourceIdsPerRelation <= 0) {
            throw new IllegalArgumentException("maxSourceIdsPerRelation must be positive");
        }
        this.maxSourceIdsPerRelation = maxSourceIdsPerRelation;
        return this;
    }

    /** How a cap keeps ids: {@code KEEP} (upstream default, head) or {@code FIFO} (tail). */
    public LightRagBuilder sourceIdsLimitMethod(io.github.lightrag.indexing.SourceIdLimits.Method sourceIdsLimitMethod) {
        this.sourceIdsLimitMethod = Objects.requireNonNull(sourceIdsLimitMethod, "sourceIdsLimitMethod");
        return this;
    }

    /**
     * Entity and relation {@code file_path} cap (upstream {@code max_file_paths}, default 75).
     * Display-only: over the limit the list keeps its head plus the
     * {@code ...truncated...(KEEP Old)} marker under the {@link #sourceIdsLimitMethod} in force.
     */
    public LightRagBuilder maxFilePaths(int maxFilePaths) {
        if (maxFilePaths <= 0) {
            throw new IllegalArgumentException("maxFilePaths must be positive");
        }
        this.maxFilePaths = maxFilePaths;
        return this;
    }

    public LightRagBuilder storage(StorageProvider storageProvider) {
        if (workspaceStorageProvider != null) {
            throw new IllegalStateException("workspaceStorageProvider is already configured");
        }
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        return this;
    }

    public LightRagBuilder storageAssembly(StorageAssembly storageAssembly) {
        return storage(Objects.requireNonNull(storageAssembly, "storageAssembly").toStorageProvider());
    }

    public LightRagBuilder workspaceStorage(WorkspaceStorageProvider workspaceStorageProvider) {
        if (storageProvider != null) {
            throw new IllegalStateException("storageProvider is already configured");
        }
        if (snapshotPath != null) {
            throw new IllegalStateException("workspaceStorageProvider does not support snapshots");
        }
        this.workspaceStorageProvider = Objects.requireNonNull(workspaceStorageProvider, "workspaceStorageProvider");
        return this;
    }

    /**
     * Configures the second-stage chunk reranker; {@code OpenAiCompatibleRerankModel} is the bundled
     * Cohere/Jina-compatible HTTP binding over {@code POST {baseUrl}rerank}. Provider results are
     * authoritative: chunks missing from the response are not re-appended, and entries with unknown
     * ids or non-finite scores are ignored. A failing reranker is propagated to the caller by
     * default; configure {@link #rerankFailureMode} to fall back to the retrieval order instead.
     */
    public LightRagBuilder rerankModel(RerankModel rerankModel) {
        this.rerankModel = Objects.requireNonNull(rerankModel, "rerankModel");
        return this;
    }

    public LightRagBuilder chunker(Chunker chunker) {
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        return this;
    }

    public LightRagBuilder documentParsingOrchestrator(DocumentParsingOrchestrator documentParsingOrchestrator) {
        this.documentParsingOrchestrator = Objects.requireNonNull(documentParsingOrchestrator, "documentParsingOrchestrator");
        return this;
    }

    public LightRagBuilder taskEventListener(TaskEventListener listener) {
        taskEventListeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public LightRagBuilder enableEmbeddingSemanticMerge(boolean enabled) {
        this.embeddingSemanticMergeEnabled = enabled;
        return this;
    }

    public LightRagBuilder embeddingSemanticMergeThreshold(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0.0d || threshold > 1.0d) {
            throw new IllegalArgumentException("embeddingSemanticMergeThreshold must be between 0.0 and 1.0");
        }
        this.embeddingSemanticMergeThreshold = threshold;
        return this;
    }

    public LightRagBuilder automaticQueryKeywordExtraction(boolean automaticQueryKeywordExtraction) {
        this.automaticQueryKeywordExtraction = automaticQueryKeywordExtraction;
        return this;
    }

    public LightRagBuilder rerankCandidateMultiplier(int rerankCandidateMultiplier) {
        if (rerankCandidateMultiplier <= 0) {
            throw new IllegalArgumentException("rerankCandidateMultiplier must be positive");
        }
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        return this;
    }

    public LightRagBuilder minRerankScore(double minRerankScore) {
        if (!Double.isFinite(minRerankScore) || minRerankScore < 0.0d) {
            throw new IllegalArgumentException("minRerankScore must be non-negative");
        }
        this.minRerankScore = minRerankScore;
        return this;
    }

    /**
     * How a query reacts when the configured reranker throws: {@link RerankFailureMode#FAIL_FAST}
     * (default) propagates the error; {@link RerankFailureMode#FALLBACK_TO_ORIGINAL} logs a warning
     * and keeps the original retrieval order (upstream {@code utils.py:7013-7021}).
     */
    public LightRagBuilder rerankFailureMode(RerankFailureMode rerankFailureMode) {
        this.rerankFailureMode = Objects.requireNonNull(rerankFailureMode, "rerankFailureMode");
        return this;
    }

    /**
     * Token counter shared by every query budget check. Defaults to {@link HeuristicTokenCounter}
     * (each CJK/Kana/Hangul code point counts as one token, other text at ~4 characters per token);
     * supply a provider-accurate counter to line budgets up with the real tokenizer, as upstream
     * does with {@code Tokenizer.encode}.
     */
    public LightRagBuilder tokenCounter(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        return this;
    }

    /**
     * Canned answer returned without a model call when retrieval finds no context, mirroring the
     * upstream {@code fail_response}. Defaults to {@link QueryEngine#DEFAULT_FAIL_RESPONSE}.
     */
    public LightRagBuilder failResponse(String failResponse) {
        this.failResponse = Objects.requireNonNull(failResponse, "failResponse");
        return this;
    }

    /**
     * Server-side instructions prepended to every request's {@code user_prompt} (upstream
     * {@code user_prompt_prefix}). Empty means no prefix; a request can opt out per call with
     * {@code disableUserPromptPrefix}.
     */
    public LightRagBuilder userPromptPrefix(String userPromptPrefix) {
        this.userPromptPrefix = Objects.requireNonNull(userPromptPrefix, "userPromptPrefix");
        return this;
    }

    /**
     * Fragments at or above this count are summarized with the summary model on merge
     * ({@code force_llm_summary_on_merge}, upstream default 8). Below it the deduplicated fragments are stored
     * as a {@code <SEP>} join without a model call.
     */
    public LightRagBuilder forceLlmSummaryOnMerge(int forceLlmSummaryOnMerge) {
        if (forceLlmSummaryOnMerge <= 0) {
            throw new IllegalArgumentException("forceLlmSummaryOnMerge must be positive");
        }
        this.forceLlmSummaryOnMerge = forceLlmSummaryOnMerge;
        return this;
    }

    public LightRagBuilder summaryMaxTokens(int summaryMaxTokens) {
        if (summaryMaxTokens <= 0) {
            throw new IllegalArgumentException("summaryMaxTokens must be positive");
        }
        this.summaryMaxTokens = summaryMaxTokens;
        return this;
    }

    public LightRagBuilder summaryContextSize(int summaryContextSize) {
        if (summaryContextSize <= 0) {
            throw new IllegalArgumentException("summaryContextSize must be positive");
        }
        this.summaryContextSize = summaryContextSize;
        return this;
    }

    public LightRagBuilder summaryLengthRecommended(int summaryLengthRecommended) {
        if (summaryLengthRecommended <= 0) {
            throw new IllegalArgumentException("summaryLengthRecommended must be positive");
        }
        this.summaryLengthRecommended = summaryLengthRecommended;
        return this;
    }

    public LightRagBuilder embeddingBatchSize(int embeddingBatchSize) {
        if (embeddingBatchSize <= 0) {
            throw new IllegalArgumentException("embeddingBatchSize must be positive");
        }
        this.embeddingBatchSize = embeddingBatchSize;
        return this;
    }

    public LightRagBuilder maxParallelInsert(int maxParallelInsert) {
        if (maxParallelInsert <= 0) {
            throw new IllegalArgumentException("maxParallelInsert must be positive");
        }
        this.maxParallelInsert = maxParallelInsert;
        return this;
    }

    /**
     * Budget for concurrent model calls per LLM role (extract, summary, query, keyword) and,
     * separately, for embeddings. Mirrors the upstream per-role limiters; size it above the
     * widest upstream stage that must run at once, never below it.
     */
    public LightRagBuilder maxAsyncLlm(int maxAsyncLlm) {
        if (maxAsyncLlm <= 0) {
            throw new IllegalArgumentException("maxAsyncLlm must be positive");
        }
        this.maxAsyncLlm = maxAsyncLlm;
        return this;
    }

    /**
     * Upper bound for the node budget of {@link LightRag#getKnowledgeGraph}; a caller may ask for
     * fewer nodes but never more. Mirrors the upstream {@code max_graph_nodes} setting.
     */
    public LightRagBuilder maxGraphNodes(int maxGraphNodes) {
        if (maxGraphNodes <= 0) {
            throw new IllegalArgumentException("maxGraphNodes must be positive");
        }
        this.maxGraphNodes = maxGraphNodes;
        return this;
    }

    public LightRagBuilder embeddingMaxAsync(int embeddingMaxAsync) {
        if (embeddingMaxAsync <= 0) {
            throw new IllegalArgumentException("embeddingMaxAsync must be positive");
        }
        this.embeddingMaxAsync = embeddingMaxAsync;
        return this;
    }

    public LightRagBuilder chunkExtractParallelism(int chunkExtractParallelism) {
        if (chunkExtractParallelism <= 0) {
            throw new IllegalArgumentException("chunkExtractParallelism must be positive");
        }
        this.chunkExtractParallelism = chunkExtractParallelism;
        return this;
    }

    /**
     * Bounds how many document-scoped tasks run concurrently inside one workspace. Unlike
     * {@code maxParallelInsert}, which parallelizes the documents of a single ingest call, this is the
     * knob that matters when the caller submits one task per document. Workspace-exclusive work
     * (delete, rebuild, snapshots) always runs alone regardless of this value.
     */
    public LightRagBuilder maxConcurrentDocumentTasks(int maxConcurrentDocumentTasks) {
        if (maxConcurrentDocumentTasks <= 0) {
            throw new IllegalArgumentException("maxConcurrentDocumentTasks must be positive");
        }
        this.maxConcurrentDocumentTasks = maxConcurrentDocumentTasks;
        return this;
    }

    public LightRagBuilder entityExtractMaxGleaning(int entityExtractMaxGleaning) {
        if (entityExtractMaxGleaning < 0) {
            throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
        }
        this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        return this;
    }

    public LightRagBuilder maxExtractInputTokens(int maxExtractInputTokens) {
        if (maxExtractInputTokens <= 0) {
            throw new IllegalArgumentException("maxExtractInputTokens must be positive");
        }
        this.maxExtractInputTokens = maxExtractInputTokens;
        return this;
    }

    public LightRagBuilder entityExtractMaxRecords(int entityExtractMaxRecords) {
        if (entityExtractMaxRecords < 1) {
            throw new IllegalArgumentException("entityExtractMaxRecords must be positive");
        }
        this.entityExtractMaxRecords = entityExtractMaxRecords;
        return this;
    }

    public LightRagBuilder entityExtractMaxEntities(int entityExtractMaxEntities) {
        if (entityExtractMaxEntities < 1) {
            throw new IllegalArgumentException("entityExtractMaxEntities must be positive");
        }
        this.entityExtractMaxEntities = entityExtractMaxEntities;
        return this;
    }

    public LightRagBuilder kgExtractionValidator(KgExtractionValidator kgExtractionValidator) {
        this.kgExtractionValidator = Objects.requireNonNull(kgExtractionValidator, "kgExtractionValidator");
        return this;
    }

    public LightRagBuilder enableSectionContext(boolean sectionContextEnabled) {
        this.sectionContextEnabled = sectionContextEnabled;
        return this;
    }

    public LightRagBuilder entityExtractionLanguage(String entityExtractionLanguage) {
        Objects.requireNonNull(entityExtractionLanguage, "entityExtractionLanguage");
        if (entityExtractionLanguage.isBlank()) {
            throw new IllegalArgumentException("entityExtractionLanguage must not be blank");
        }
        this.entityExtractionLanguage = entityExtractionLanguage.strip();
        return this;
    }

    public LightRagBuilder entityTypes(List<String> entityTypes) {
        var normalizedEntityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes")).stream()
            .map(type -> {
                Objects.requireNonNull(type, "entityTypes entry");
                if (type.isBlank()) {
                    throw new IllegalArgumentException("entityTypes entries must not be blank");
                }
                return type.strip();
            })
            .toList();
        if (normalizedEntityTypes.isEmpty()) {
            throw new IllegalArgumentException("entityTypes must not be empty");
        }
        this.entityTypes = normalizedEntityTypes;
        return this;
    }

    public LightRagBuilder graphExtractionEnabled(boolean graphExtractionEnabled) {
        this.graphExtractionEnabled = graphExtractionEnabled;
        return this;
    }

    public LightRagBuilder relationTypes(List<String> relationTypes) {
        this.relationTypes = normalizeOptionalList(relationTypes, "relationTypes entry");
        return this;
    }

    public LightRagBuilder graphExtractionExamples(List<GraphExtractionExample> graphExtractionExamples) {
        this.graphExtractionExamples = List.copyOf(Objects.requireNonNull(graphExtractionExamples, "graphExtractionExamples"));
        return this;
    }

    public LightRagBuilder graphExtractionOptionsProvider(GraphExtractionOptionsProvider graphExtractionOptionsProvider) {
        this.graphExtractionOptionsProvider = Objects.requireNonNull(
            graphExtractionOptionsProvider,
            "graphExtractionOptionsProvider"
        );
        return this;
    }

    public LightRagBuilder contextualExtractionRefinement(boolean enabled) {
        this.contextualExtractionRefinementEnabled = enabled;
        return this;
    }

    public LightRagBuilder allowDeterministicAttributionFallback(boolean enabled) {
        this.allowDeterministicAttributionFallback = enabled;
        return this;
    }

    /**
     * Routes every vector operation to a no-op store: ingestion builds only the graph, chunks, and KV state and
     * never calls the embedding model, while query-time vector search returns no hits. Pair it with
     * {@link io.github.lightrag.model.NoopEmbeddingModel} and rebuild the vector index offline before running
     * vector-backed retrieval modes.
     */
    public LightRagBuilder noopVectorStore(boolean enabled) {
        this.noopVectorStore = enabled;
        return this;
    }

    public LightRagBuilder loadFromSnapshot(Path path) {
        if (workspaceStorageProvider != null) {
            throw new IllegalStateException("workspaceStorageProvider does not support snapshots");
        }
        this.snapshotPath = Objects.requireNonNull(path, "path");
        return this;
    }

    public LightRag build() {
        if (chatModel == null) {
            throw new IllegalStateException("chatModel is required");
        }
        if (embeddingModel == null) {
            throw new IllegalStateException("embeddingModel is required");
        }
        if (storageProvider == null && workspaceStorageProvider == null) {
            throw new IllegalStateException("storageProvider is required");
        }
        if (embeddingSemanticMergeEnabled && !(chunker instanceof SmartChunker)) {
            throw new IllegalStateException("embedding semantic merge requires SmartChunker");
        }

        AtomicStorageProvider atomicStorageProvider = null;
        DocumentStatusStore documentStatusStore = null;
        WorkspaceStorageProvider resolvedWorkspaceStorageProvider;

        if (storageProvider != null) {
            requireStore("documentStore", storageProvider.documentStore(), DocumentStore.class);
            requireStore("chunkStore", storageProvider.chunkStore(), ChunkStore.class);
            requireStore("graphStore", storageProvider.graphStore(), GraphStore.class);
            requireStore("vectorStore", storageProvider.vectorStore(), VectorStore.class);
            requireStore("documentStatusStore", storageProvider.documentStatusStore(), DocumentStatusStore.class);
            requireStore("snapshotStore", storageProvider.snapshotStore(), SnapshotStore.class);
            requireSupportedStore("documentGraphSnapshotStore", storageProvider::documentGraphSnapshotStore,
                DocumentGraphSnapshotStore.class, StorageProvider.DOCUMENT_GRAPH_SNAPSHOT_STORE_UNSUPPORTED_MESSAGE);
            requireSupportedStore("documentGraphJournalStore", storageProvider::documentGraphJournalStore,
                DocumentGraphJournalStore.class, StorageProvider.DOCUMENT_GRAPH_JOURNAL_STORE_UNSUPPORTED_MESSAGE);
            if (!(storageProvider instanceof AtomicStorageProvider configuredAtomicStorageProvider)) {
                throw new IllegalStateException("storageProvider must implement AtomicStorageProvider");
            }
            atomicStorageProvider = configuredAtomicStorageProvider;
            documentStatusStore = configuredAtomicStorageProvider.documentStatusStore();
            if (noopVectorStore) {
                atomicStorageProvider = new NoopVectorStorageProvider(atomicStorageProvider);
            }
            resolvedWorkspaceStorageProvider = new FixedWorkspaceStorageProvider(atomicStorageProvider);
            restoreSnapshotIfPresent(atomicStorageProvider, snapshotPath);
        } else {
            try {
                var validatedStorageProvider = Objects.requireNonNull(
                    workspaceStorageProvider.forWorkspace(new WorkspaceScope("default")),
                    "workspaceStorageProvider.forWorkspace"
                );
                requireStore("documentStore", validatedStorageProvider.documentStore(), DocumentStore.class);
                requireStore("chunkStore", validatedStorageProvider.chunkStore(), ChunkStore.class);
                requireStore("graphStore", validatedStorageProvider.graphStore(), GraphStore.class);
                requireStore("vectorStore", validatedStorageProvider.vectorStore(), VectorStore.class);
                requireStore("documentStatusStore", validatedStorageProvider.documentStatusStore(), DocumentStatusStore.class);
                requireStore("snapshotStore", validatedStorageProvider.snapshotStore(), SnapshotStore.class);
                requireSupportedStore("documentGraphSnapshotStore", validatedStorageProvider::documentGraphSnapshotStore,
                    DocumentGraphSnapshotStore.class, StorageProvider.DOCUMENT_GRAPH_SNAPSHOT_STORE_UNSUPPORTED_MESSAGE);
                requireSupportedStore("documentGraphJournalStore", validatedStorageProvider::documentGraphJournalStore,
                    DocumentGraphJournalStore.class, StorageProvider.DOCUMENT_GRAPH_JOURNAL_STORE_UNSUPPORTED_MESSAGE);
                resolvedWorkspaceStorageProvider = noopVectorStore
                    ? new NoopVectorWorkspaceStorageProvider(workspaceStorageProvider)
                    : workspaceStorageProvider;
            } catch (RuntimeException exception) {
                try {
                    workspaceStorageProvider.close();
                } catch (RuntimeException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }

        var extractionRefinementOptions = new ExtractionRefinementOptions(
            contextualExtractionRefinementEnabled,
            allowDeterministicAttributionFallback,
            3,
            1_200,
            1,
            1,
            1
        );

        return new LightRag(new LightRagConfig(
            withRequestOptions(withRetryPolicy(chatModel)),
            withRequestOptions(withRetryPolicy(queryModel)),
            withRequestOptions(withRetryPolicy(keywordModel)),
            withRequestOptions(withRetryPolicy(extractionModel)),
            withRequestOptions(withRetryPolicy(summaryModel)),
            withRetryPolicy(embeddingModel),
            atomicStorageProvider,
            documentStatusStore,
            snapshotPath,
            rerankModel,
            resolvedWorkspaceStorageProvider,
            forceLlmSummaryOnMerge,
            summaryMaxTokens,
            summaryContextSize,
            summaryLengthRecommended,
            maxSourceIdsPerEntity,
            maxSourceIdsPerRelation,
            sourceIdsLimitMethod,
            maxFilePaths,
            entityExtractMaxRecords,
            entityExtractMaxEntities,
            kgExtractionValidator,
            sectionContextEnabled,
            maxAsyncLlm,
            embeddingMaxAsync,
            maxGraphNodes
        ), chunker, documentParsingOrchestrator, automaticQueryKeywordExtraction, rerankCandidateMultiplier, minRerankScore,
            rerankFailureMode, tokenCounter, embeddingBatchSize, maxParallelInsert,
            chunkExtractParallelism,
            maxConcurrentDocumentTasks,
            entityExtractMaxGleaning, maxExtractInputTokens, entityExtractionLanguage, entityTypes,
            graphExtractionEnabled, relationTypes, graphExtractionExamples,
            embeddingSemanticMergeEnabled, embeddingSemanticMergeThreshold, extractionRefinementOptions,
            graphExtractionOptionsProvider, taskEventListeners, failResponse, userPromptPrefix);
    }

    private ChatModel withRequestOptions(ChatModel model) {
        if (model == null || chatRequestOptions == null) {
            return model;
        }
        return new ConfiguredChatModel(model, chatRequestOptions);
    }

    private ChatModel withRetryPolicy(ChatModel model) {
        if (model == null || modelMaxAttempts == null || modelMaxAttempts <= 1) {
            return model;
        }
        if (model instanceof OpenAiCompatibleChatModel) {
            // The built-in model owns its own retry policy; an outer wrapper would multiply attempts.
            return model;
        }
        return new RetryingChatModel(model, modelMaxAttempts, ModelRetrySupport.DEFAULT_INITIAL_BACKOFF);
    }

    private EmbeddingModel withRetryPolicy(EmbeddingModel model) {
        if (model == null || modelMaxAttempts == null || modelMaxAttempts <= 1) {
            return model;
        }
        if (model instanceof OpenAiCompatibleEmbeddingModel) {
            return model;
        }
        return new RetryingEmbeddingModel(model, modelMaxAttempts, ModelRetrySupport.DEFAULT_INITIAL_BACKOFF);
    }

    private static List<String> normalizeOptionalList(List<String> values, String entryName) {
        return List.copyOf(Objects.requireNonNull(values, entryName)).stream()
            .map(value -> {
                Objects.requireNonNull(value, entryName);
                if (value.isBlank()) {
                    throw new IllegalArgumentException(entryName + " must not be blank");
                }
                return value.strip();
            })
            .toList();
    }

    private static <T> T requireStore(String componentName, T store, Class<T> storeType) {
        if (store == null) {
            throw new IllegalStateException(componentName + " is required");
        }
        return storeType.cast(store);
    }

    private static <T> T requireSupportedStore(
        String componentName,
        Supplier<? extends T> storeSupplier,
        Class<T> storeType,
        String unsupportedMessage
    ) {
        Objects.requireNonNull(storeSupplier, "storeSupplier");
        try {
            return requireStore(componentName, storeSupplier.get(), storeType);
        } catch (UnsupportedOperationException exception) {
            if (Objects.equals(exception.getMessage(), unsupportedMessage)) {
                throw new IllegalStateException(componentName + " is required", exception);
            }
            throw exception;
        }
    }

    private void restoreSnapshotIfPresent(AtomicStorageProvider storageProvider, Path path) {
        if (path == null) {
            return;
        }
        try {
            storageProvider.restore(storageProvider.snapshotStore().load(path));
        } catch (NoSuchElementException ignored) {
            // Missing snapshots are allowed so the same path can be used for first-time autosave.
        }
    }
}
