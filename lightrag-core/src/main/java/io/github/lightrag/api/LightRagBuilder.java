package io.github.lightrag.api;

import io.github.lightrag.config.LightRagConfig;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.FixedWindowChunker;
import io.github.lightrag.indexing.SmartChunker;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.FixedWorkspaceStorageProvider;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageAssembly;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class LightRagBuilder {
    private static final int DEFAULT_CHUNK_WINDOW = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    static final boolean DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED = false;
    static final double DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD = 0.80d;

    private ChatModel chatModel;
    private ChatModel queryModel;
    private ChatModel extractionModel;
    private ChatModel summaryModel;
    private EmbeddingModel embeddingModel;
    private StorageProvider storageProvider;
    private WorkspaceStorageProvider workspaceStorageProvider;
    private Path snapshotPath;
    private RerankModel rerankModel;
    private Chunker chunker = new FixedWindowChunker(DEFAULT_CHUNK_WINDOW, DEFAULT_CHUNK_OVERLAP);
    private boolean automaticQueryKeywordExtraction = true;
    private int rerankCandidateMultiplier = 2;
    private int embeddingBatchSize = Integer.MAX_VALUE;
    private int maxParallelInsert = 1;
    private int entityExtractMaxGleaning = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING;
    private int maxExtractInputTokens = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS;
    private String entityExtractionLanguage = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE;
    private List<String> entityTypes = io.github.lightrag.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES;
    private boolean embeddingSemanticMergeEnabled = DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED;
    private double embeddingSemanticMergeThreshold = DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD;
    private boolean contextualExtractionRefinementEnabled;
    private boolean allowDeterministicAttributionFallback;
    private DocumentParsingOrchestrator documentParsingOrchestrator;

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

    public LightRagBuilder extractionModel(ChatModel extractionModel) {
        this.extractionModel = Objects.requireNonNull(extractionModel, "extractionModel");
        return this;
    }

    public LightRagBuilder summaryModel(ChatModel summaryModel) {
        this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
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
     * Configures an optional second-stage chunk reranker. If queries keep rerank enabled but no model is configured,
     * Java treats that as a deterministic no-op and does not emit upstream-style warnings in this phase.
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

    public LightRagBuilder contextualExtractionRefinement(boolean enabled) {
        this.contextualExtractionRefinementEnabled = enabled;
        return this;
    }

    public LightRagBuilder allowDeterministicAttributionFallback(boolean enabled) {
        this.allowDeterministicAttributionFallback = enabled;
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
            requireStore("documentGraphSnapshotStore", storageProvider.documentGraphSnapshotStore(), DocumentGraphSnapshotStore.class);
            requireStore("documentGraphJournalStore", storageProvider.documentGraphJournalStore(), DocumentGraphJournalStore.class);
            if (!(storageProvider instanceof AtomicStorageProvider configuredAtomicStorageProvider)) {
                throw new IllegalStateException("storageProvider must implement AtomicStorageProvider");
            }
            atomicStorageProvider = configuredAtomicStorageProvider;
            documentStatusStore = configuredAtomicStorageProvider.documentStatusStore();
            resolvedWorkspaceStorageProvider = new FixedWorkspaceStorageProvider(configuredAtomicStorageProvider);
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
                requireStore("documentGraphSnapshotStore", validatedStorageProvider.documentGraphSnapshotStore(), DocumentGraphSnapshotStore.class);
                requireStore("documentGraphJournalStore", validatedStorageProvider.documentGraphJournalStore(), DocumentGraphJournalStore.class);
                resolvedWorkspaceStorageProvider = workspaceStorageProvider;
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
            chatModel,
            queryModel,
            extractionModel,
            summaryModel,
            embeddingModel,
            atomicStorageProvider,
            documentStatusStore,
            snapshotPath,
            rerankModel,
            resolvedWorkspaceStorageProvider
        ), chunker, documentParsingOrchestrator, automaticQueryKeywordExtraction, rerankCandidateMultiplier, embeddingBatchSize, maxParallelInsert,
            entityExtractMaxGleaning, maxExtractInputTokens, entityExtractionLanguage, entityTypes,
            embeddingSemanticMergeEnabled, embeddingSemanticMergeThreshold, extractionRefinementOptions);
    }

    private static <T> T requireStore(String componentName, T store, Class<T> storeType) {
        if (store == null) {
            throw new IllegalStateException(componentName + " is required");
        }
        return storeType.cast(store);
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
