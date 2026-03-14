package io.github.lightragjava.config;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.StorageProvider;

import java.nio.file.Path;
import java.util.Objects;

public record LightRagConfig(
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    StorageProvider storageProvider,
    Path snapshotPath
) {
    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
    }
}
