package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HybridQueryStrategyTest {
    @Test
    void hybridMergesLocalAndGlobalByIdWithMaxScoreRetention() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of("hybrid question", List.of(1.0d, 0.0d)));
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("hybrid question")
            .mode(QueryMode.HYBRID)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
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
