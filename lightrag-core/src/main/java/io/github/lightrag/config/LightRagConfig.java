package io.github.lightrag.config;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;

import java.nio.file.Path;
import java.util.Objects;

public record LightRagConfig(
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    DocumentStatusStore documentStatusStore,
    Path snapshotPath,
    RerankModel rerankModel,
    WorkspaceStorageProvider workspaceStorageProvider
) {
    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        workspaceStorageProvider = Objects.requireNonNull(workspaceStorageProvider, "workspaceStorageProvider");
    }
}
