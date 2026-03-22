package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.indexing.FixedWindowChunker;
import io.github.lightragjava.indexing.SmartChunker;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.VectorStore;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class LightRagBuilder {
    private static final int DEFAULT_CHUNK_WINDOW = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;
    static final boolean DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED = false;
    static final double DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD = 0.80d;

    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private StorageProvider storageProvider;
    private Path snapshotPath;
    private RerankModel rerankModel;
    private Chunker chunker = new FixedWindowChunker(DEFAULT_CHUNK_WINDOW, DEFAULT_CHUNK_OVERLAP);
    private boolean automaticQueryKeywordExtraction = true;
    private int rerankCandidateMultiplier = 2;
    private int embeddingBatchSize = Integer.MAX_VALUE;
    private int maxParallelInsert = 1;
    private int entityExtractMaxGleaning = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING;
    private int maxExtractInputTokens = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS;
    private boolean embeddingSemanticMergeEnabled = DEFAULT_EMBEDDING_SEMANTIC_MERGE_ENABLED;
    private double embeddingSemanticMergeThreshold = DEFAULT_EMBEDDING_SEMANTIC_MERGE_THRESHOLD;

    public LightRagBuilder chatModel(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        return this;
    }

    public LightRagBuilder embeddingModel(EmbeddingModel embeddingModel) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        return this;
    }

    public LightRagBuilder storage(StorageProvider storageProvider) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
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

    public LightRagBuilder loadFromSnapshot(Path path) {
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
        if (storageProvider == null) {
            throw new IllegalStateException("storageProvider is required");
        }
        if (embeddingSemanticMergeEnabled && !(chunker instanceof SmartChunker)) {
            throw new IllegalStateException("embedding semantic merge requires SmartChunker");
        }
        requireStore("documentStore", storageProvider.documentStore(), DocumentStore.class);
        requireStore("chunkStore", storageProvider.chunkStore(), ChunkStore.class);
        requireStore("graphStore", storageProvider.graphStore(), GraphStore.class);
        requireStore("vectorStore", storageProvider.vectorStore(), VectorStore.class);
        requireStore("documentStatusStore", storageProvider.documentStatusStore(), DocumentStatusStore.class);
        requireStore("snapshotStore", storageProvider.snapshotStore(), SnapshotStore.class);
        if (!(storageProvider instanceof AtomicStorageProvider atomicStorageProvider)) {
            throw new IllegalStateException("storageProvider must implement AtomicStorageProvider");
        }
        restoreSnapshotIfPresent(atomicStorageProvider, snapshotPath);

        return new LightRag(new LightRagConfig(
            chatModel,
            embeddingModel,
            atomicStorageProvider,
            storageProvider.documentStatusStore(),
            snapshotPath,
            rerankModel
        ), chunker, automaticQueryKeywordExtraction, rerankCandidateMultiplier, embeddingBatchSize, maxParallelInsert,
            entityExtractMaxGleaning, maxExtractInputTokens, embeddingSemanticMergeEnabled, embeddingSemanticMergeThreshold);
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
