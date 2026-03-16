package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalQueryStrategyTest {
    @Test
    void globalUsesRelationSimilarityAndEndpointEntities() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of("org question", List.of(0.0d, 1.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("org question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:bob", "entity:carol");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-3");
        assertThat(context.assembledContext())
            .contains("Relations:")
            .contains("reports_to")
            .contains("Entities:")
            .contains("Carol");
    }

    @Test
    void globalTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of("alice theme", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice theme")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    @Test
    void globalUsesHlKeywordsInsteadOfRawQueryWhenProvided() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(1.0d, 0.0d),
            "org, focus", List.of(0.0d, 1.0d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .hlKeywords(List.of("org", "focus"))
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:bob", "entity:carol");
    }

    @Test
    void globalTrimsRelationsToMaxRelationTokens() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "broad org question", List.of(0.8d, 0.6d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("broad org question")
            .mode(QueryMode.GLOBAL)
            .topK(2)
            .chunkTopK(2)
            .maxRelationTokens(6)
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
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
