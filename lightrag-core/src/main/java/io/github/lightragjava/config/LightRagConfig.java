package io.github.lightragjava.config;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.DocumentStatusStore;

import java.nio.file.Path;
import java.util.Objects;

public record LightRagConfig(
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    DocumentStatusStore documentStatusStore,
    Path snapshotPath,
    RerankModel rerankModel,
    Chunker chunker,
    boolean automaticQueryKeywordExtraction,
    int rerankCandidateMultiplier
) {
    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        documentStatusStore = Objects.requireNonNull(documentStatusStore, "documentStatusStore");
        chunker = Objects.requireNonNull(chunker, "chunker");
        if (rerankCandidateMultiplier <= 0) {
            throw new IllegalArgumentException("rerankCandidateMultiplier must be positive");
        }
    }
}
