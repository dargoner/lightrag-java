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

    private record FakeEmbeddingModel(Map<String, List<Double>> vectorsByText) implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> vectorsByText.getOrDefault(text, List.of(0.0d, 0.0d)))
                .toList();
        }
    }
}
