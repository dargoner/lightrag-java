package io.github.lightragjava.config;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.AtomicStorageProvider;

import java.nio.file.Path;
import java.util.Objects;

public record LightRagConfig(
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    Path snapshotPath
) {
    public LightRagConfig {
        chatModel = Objects.requireNonNull(chatModel, "chatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
    }
}
