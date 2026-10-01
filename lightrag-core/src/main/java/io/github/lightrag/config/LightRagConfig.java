package io.github.lightrag.config;

import io.github.lightrag.api.KgExtractionValidator;
import io.github.lightrag.indexing.DescriptionSummarizer;
import io.github.lightrag.indexing.FilePathLimits;
import io.github.lightrag.indexing.KnowledgeExtractor;
import io.github.lightrag.indexing.SourceIdLimits;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;

import java.nio.file.Path;
import java.util.Objects;

public record LightRagConfig(
    ChatModel chatModel,
    ChatModel queryModel,
    ChatModel keywordModel,
    ChatModel extractionModel,
    ChatModel summaryModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    DocumentStatusStore documentStatusStore,
    Path snapshotPath,
    RerankModel rerankModel,
    WorkspaceStorageProvider workspaceStorageProvider,
    int forceLlmSummaryOnMerge,
    int summaryMaxTokens,
    int summaryContextSize,
    int summaryLengthRecommended,
    int maxSourceIdsPerEntity,
    int maxSourceIdsPerRelation,
    SourceIdLimits.Method sourceIdsLimitMethod,
    int maxFilePaths,
    int entityExtractMaxRecords,
    int entityExtractMaxEntities,
    KgExtractionValidator kgExtractionValidator,
    boolean sectionContextEnabled,
    int maxAsyncLlm,
    int embeddingMaxAsync,
    int maxGraphNodes
) {
    /** Upstream {@code max_graph_nodes} default ({@code constants.py:13}). */
    public static final int DEFAULT_MAX_GRAPH_NODES = 1000;

    public LightRagConfig(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        DocumentStatusStore documentStatusStore,
        Path snapshotPath,
        RerankModel rerankModel,
        WorkspaceStorageProvider workspaceStorageProvider
    ) {
        this(
            chatModel,
            null,
            null,
            null,
            null,
            embeddingModel,
            storageProvider,
            documentStatusStore,
            snapshotPath,
            rerankModel,
            workspaceStorageProvider,
            0,
            0,
            0,
            0,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.DEFAULT_MAX_SOURCE_IDS,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES,
            null,
            true,
            LlmConcurrencyBudget.DEFAULT_MAX_ASYNC_LLM,
            LlmConcurrencyBudget.DEFAULT_EMBEDDING_MAX_ASYNC,
            DEFAULT_MAX_GRAPH_NODES
        );
    }

    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        workspaceStorageProvider = Objects.requireNonNull(workspaceStorageProvider, "workspaceStorageProvider");
        sourceIdsLimitMethod = sourceIdsLimitMethod == null ? SourceIdLimits.Method.KEEP : sourceIdsLimitMethod;
        maxFilePaths = maxFilePaths > 0 ? maxFilePaths : FilePathLimits.DEFAULT_MAX_FILE_PATHS;
        entityExtractMaxRecords = entityExtractMaxRecords >= 1
            ? entityExtractMaxRecords
            : KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS;
        entityExtractMaxEntities = entityExtractMaxEntities >= 1
            ? entityExtractMaxEntities
            : KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES;
        maxAsyncLlm = maxAsyncLlm > 0 ? maxAsyncLlm : LlmConcurrencyBudget.DEFAULT_MAX_ASYNC_LLM;
        embeddingMaxAsync = embeddingMaxAsync > 0
            ? embeddingMaxAsync
            : LlmConcurrencyBudget.DEFAULT_EMBEDDING_MAX_ASYNC;
        maxGraphNodes = maxGraphNodes > 0 ? maxGraphNodes : DEFAULT_MAX_GRAPH_NODES;
    }

    public ChatModel defaultChatModel() {
        return chatModel;
    }

    public ChatModel queryModel() {
        return queryModel != null ? queryModel : chatModel;
    }

    public ChatModel keywordModel() {
        return keywordModel != null ? keywordModel : chatModel;
    }

    public ChatModel extractionModel() {
        return extractionModel != null ? extractionModel : chatModel;
    }

    public ChatModel summaryModel() {
        return summaryModel != null ? summaryModel : chatModel;
    }

    public int forceLlmSummaryOnMerge() {
        return forceLlmSummaryOnMerge > 0
            ? forceLlmSummaryOnMerge
            : DescriptionSummarizer.DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE;
    }

    public int summaryMaxTokens() {
        return summaryMaxTokens > 0 ? summaryMaxTokens : DescriptionSummarizer.DEFAULT_SUMMARY_MAX_TOKENS;
    }

    public int summaryContextSize() {
        return summaryContextSize > 0 ? summaryContextSize : DescriptionSummarizer.DEFAULT_SUMMARY_CONTEXT_SIZE;
    }

    public int summaryLengthRecommended() {
        return summaryLengthRecommended > 0
            ? summaryLengthRecommended
            : DescriptionSummarizer.DEFAULT_SUMMARY_LENGTH_RECOMMENDED;
    }
}
