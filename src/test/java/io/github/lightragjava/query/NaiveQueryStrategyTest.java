package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NaiveQueryStrategyTest {
    @Test
    void naiveUsesDirectChunkSimilarityWithoutGraphMatches() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedEntities()).isEmpty();
        assertThat(context.matchedRelations()).isEmpty();
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
        assertThat(context.assembledContext())
            .contains("Chunks:")
            .contains("chunk-1")
            .contains("Alice works with Bob");
    }

    @Test
    void naiveTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedEntities()).isEmpty();
        assertThat(context.matchedRelations()).isEmpty();
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    @Test
    void naiveIgnoresTopKAndUsesChunkTopKForDirectChunkRetrieval() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void naiveBreaksScoreTiesByChunkId() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightragjava.storage.ChunkStore.ChunkRecord(
            "chunk-a",
            "doc-1",
            "Alpha",
            1,
            0,
            Map.of()
        ));
        storage.chunkStore().save(new io.github.lightragjava.storage.ChunkStore.ChunkRecord(
            "chunk-b",
            "doc-1",
            "Beta",
            1,
            1,
            Map.of()
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightragjava.storage.VectorStore.VectorRecord("chunk-b", List.of(1.0d, 0.0d)),
            new io.github.lightragjava.storage.VectorStore.VectorRecord("chunk-a", List.of(1.0d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("tie question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("tie question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-a", "chunk-b");
    }

    private record FakeEmbeddingModel(Map<String, List<Double>> vectorsByText) implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> vectorsByText.getOrDefault(text, List.of(0.0d, 0.0d)))
                .toList();
        }
    }
}
