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
    ChatModel queryModel,
    ChatModel extractionModel,
    ChatModel summaryModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    DocumentStatusStore documentStatusStore,
    Path snapshotPath,
    RerankModel rerankModel,
    WorkspaceStorageProvider workspaceStorageProvider
) {
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
            embeddingModel,
            storageProvider,
            documentStatusStore,
            snapshotPath,
            rerankModel,
            workspaceStorageProvider
        );
    }

    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        workspaceStorageProvider = Objects.requireNonNull(workspaceStorageProvider, "workspaceStorageProvider");
    }

    public ChatModel defaultChatModel() {
        return chatModel;
    }

    public ChatModel queryModel() {
        return queryModel != null ? queryModel : chatModel;
    }

    public ChatModel extractionModel() {
        return extractionModel != null ? extractionModel : chatModel;
    }

    public ChatModel summaryModel() {
        return summaryModel != null ? summaryModel : chatModel;
    }
}
