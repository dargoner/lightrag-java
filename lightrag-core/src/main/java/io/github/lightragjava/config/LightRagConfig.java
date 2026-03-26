package io.github.lightragjava.config;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.WorkspaceStorageProvider;

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
