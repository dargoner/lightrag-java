package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;
import io.github.lightragjava.indexing.IndexingPipeline;
import io.github.lightragjava.types.Document;

import java.util.List;

public final class LightRag {
    private final LightRagConfig config;
    private final IndexingPipeline indexingPipeline;

    LightRag(LightRagConfig config) {
        this.config = config;
        this.indexingPipeline = new IndexingPipeline(
            config.chatModel(),
            config.embeddingModel(),
            config.storageProvider(),
            config.snapshotPath()
        );
    }

    public static LightRagBuilder builder() {
        return new LightRagBuilder();
    }

    public void ingest(List<Document> documents) {
        indexingPipeline.ingest(documents);
    }

    LightRagConfig config() {
        return config;
    }
}
