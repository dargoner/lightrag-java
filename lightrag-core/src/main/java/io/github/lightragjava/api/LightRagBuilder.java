package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.VectorStore;

import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class LightRagBuilder {
    private ChatModel chatModel;
    private EmbeddingModel embeddingModel;
    private StorageProvider storageProvider;
    private Path snapshotPath;
    private RerankModel rerankModel;

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
        ));
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
