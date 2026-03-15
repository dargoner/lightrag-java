package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;
import io.github.lightragjava.indexing.IndexingPipeline;
import io.github.lightragjava.query.ContextAssembler;
import io.github.lightragjava.query.GlobalQueryStrategy;
import io.github.lightragjava.query.HybridQueryStrategy;
import io.github.lightragjava.query.LocalQueryStrategy;
import io.github.lightragjava.query.MixQueryStrategy;
import io.github.lightragjava.query.QueryEngine;
import io.github.lightragjava.types.Document;

import java.util.EnumMap;
import java.util.List;

public final class LightRag {
    private final LightRagConfig config;
    private final IndexingPipeline indexingPipeline;
    private final QueryEngine queryEngine;

    LightRag(LightRagConfig config) {
        this.config = config;
        this.indexingPipeline = new IndexingPipeline(
            config.chatModel(),
            config.embeddingModel(),
            config.storageProvider(),
            config.snapshotPath()
        );
        var contextAssembler = new ContextAssembler();
        var local = new LocalQueryStrategy(config.embeddingModel(), config.storageProvider(), contextAssembler);
        var global = new GlobalQueryStrategy(config.embeddingModel(), config.storageProvider(), contextAssembler);
        var hybrid = new HybridQueryStrategy(local, global, contextAssembler);
        var mix = new MixQueryStrategy(config.embeddingModel(), config.storageProvider(), hybrid, contextAssembler);
        var strategies = new EnumMap<QueryMode, io.github.lightragjava.query.QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.LOCAL, local);
        strategies.put(QueryMode.GLOBAL, global);
        strategies.put(QueryMode.HYBRID, hybrid);
        strategies.put(QueryMode.MIX, mix);
        this.queryEngine = new QueryEngine(config.chatModel(), contextAssembler, strategies);
    }

    public static LightRagBuilder builder() {
        return new LightRagBuilder();
    }

    public void ingest(List<Document> documents) {
        indexingPipeline.ingest(documents);
    }

    public QueryResult query(QueryRequest request) {
        return queryEngine.query(request);
    }

    LightRagConfig config() {
        return config;
    }
}
