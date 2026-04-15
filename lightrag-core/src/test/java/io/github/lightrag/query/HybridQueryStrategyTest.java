package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
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

    @Test
    void hybridUsesLlAndHlKeywordOverridesThroughChildStrategies() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(0.2d, 0.2d),
            "alice, focus", List.of(1.0d, 0.0d),
            "org, focus", List.of(0.0d, 1.0d)
        ));
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.HYBRID)
            .topK(1)
            .chunkTopK(3)
            .llKeywords(List.of("alice", "focus"))
            .hlKeywords(List.of("org", "focus"))
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob", "entity:carol");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(
                "relation:entity:alice|works_with|entity:bob",
                "relation:entity:bob|reports_to|entity:carol"
            );
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
    }

    @Test
    void hybridSkipsGlobalBranchWhenOnlyLlKeywordsAreProvided() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(0.0d, 1.0d),
            "alice, focus", List.of(1.0d, 0.0d)
        ));
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.HYBRID)
            .topK(1)
            .chunkTopK(3)
            .llKeywords(List.of("alice", "focus"))
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
    }

    @Test
    void hybridReappliesEntityBudgetAfterMergingChildResults() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(0.2d, 0.2d),
            "alice, focus", List.of(1.0d, 0.0d),
            "org, focus", List.of(0.0d, 1.0d)
        ));
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.HYBRID)
            .topK(1)
            .chunkTopK(3)
            .llKeywords(List.of("alice", "focus"))
            .hlKeywords(List.of("org", "focus"))
            .maxEntityTokens(12)
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob");
    }

    @Test
    void hybridAppliesFinalMetadataSafeguardToMergedChunks() {
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(scoredChunk("chunk-1", "Shanghai chunk", 0.95d, Map.of("region", "shanghai"))),
                ""
            ),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(
                    scoredChunk("chunk-1", "Shanghai chunk", 0.90d, Map.of("region", "shanghai")),
                    scoredChunk("chunk-2", "Beijing chunk", 0.99d, Map.of("region", "beijing"))
                ),
                ""
            ),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("hybrid question")
            .mode(QueryMode.HYBRID)
            .chunkTopK(5)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1");
    }

    @Test
    void hybridFiltersBeforeApplyingChunkTopKSoLowerRankedValidChunkCanBackfill() {
        var contextAssembler = new ContextAssembler();
        var strategy = new HybridQueryStrategy(
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(
                    scoredChunk("chunk-1", "Filtered high score", 0.99d, Map.of("region", "beijing")),
                    scoredChunk("chunk-2", "Allowed second score", 0.95d, Map.of("region", "shanghai"))
                ),
                ""
            ),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(scoredChunk("chunk-3", "Allowed lower score", 0.90d, Map.of("region", "shanghai"))),
                ""
            ),
            contextAssembler
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("hybrid backfill question")
            .mode(QueryMode.HYBRID)
            .chunkTopK(2)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-2", "chunk-3");
    }

    private static ScoredChunk scoredChunk(String chunkId, String text, double score, Map<String, String> metadata) {
        return new ScoredChunk(
            chunkId,
            new io.github.lightrag.types.Chunk(chunkId, "doc-1", text, 4, 0, metadata),
            score
        );
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
