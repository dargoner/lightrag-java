package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalQueryStrategyTest {
    @Test
    void localUsesEntitySimilarityAndOneHopNeighbors() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
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
        assertThat(context.assembledContext())
            .contains("Entities:")
            .contains("Alice")
            .contains("Relations:")
            .contains("works_with")
            .contains("Chunks:")
            .contains("chunk-1");
    }

    @Test
    void localTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    static void seedGraph(InMemoryStorageProvider storage) {
        storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-1", "doc-1", "Alice works with Bob", 4, 0, Map.of()));
        storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-2", "doc-1", "Bob supports Alice", 4, 1, Map.of()));
        storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-3", "doc-2", "Bob reports to Carol", 4, 0, Map.of()));

        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:alice",
            "Alice",
            "person",
            "Researcher",
            List.of(),
            List.of("chunk-1")
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:bob",
            "Bob",
            "person",
            "Engineer",
            List.of("Robert"),
            List.of("chunk-2", "chunk-3")
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "entity:carol",
            "Carol",
            "person",
            "Manager",
            List.of(),
            List.of("chunk-3")
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            "relation:entity:alice|works_with|entity:bob",
            "entity:alice",
            "entity:bob",
            "works_with",
            "Alice collaborates with Bob",
            0.8d,
            List.of("chunk-1", "chunk-2")
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            "relation:entity:bob|reports_to|entity:carol",
            "entity:bob",
            "entity:carol",
            "reports_to",
            "Bob reports to Carol",
            0.6d,
            List.of("chunk-3")
        ));
    }

    static void seedVectors(InMemoryStorageProvider storage) {
        storage.vectorStore().saveAll("chunks", List.of(
            new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord("chunk-2", List.of(0.7d, 0.3d)),
            new VectorStore.VectorRecord("chunk-3", List.of(0.0d, 1.0d))
        ));
        storage.vectorStore().saveAll("entities", List.of(
            new VectorStore.VectorRecord("entity:alice", List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord("entity:bob", List.of(0.6d, 0.4d)),
            new VectorStore.VectorRecord("entity:carol", List.of(0.0d, 1.0d))
        ));
        storage.vectorStore().saveAll("relations", List.of(
            new VectorStore.VectorRecord("relation:entity:alice|works_with|entity:bob", List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord("relation:entity:bob|reports_to|entity:carol", List.of(0.0d, 1.0d))
        ));
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
