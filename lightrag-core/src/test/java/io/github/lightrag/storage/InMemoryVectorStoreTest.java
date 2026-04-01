package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {
    @Test
    void storesAndQueriesTopKChunkVectors() {
        var store = new InMemoryVectorStore();
        var namespace = "chunks";
        var first = new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d));
        var second = new VectorStore.VectorRecord("chunk-2", List.of(1.0d, 0.0d));
        var third = new VectorStore.VectorRecord("chunk-3", List.of(0.0d, 1.0d));

        store.saveAll(namespace, List.of(second, third, first));

        assertThat(store.list(namespace)).containsExactly(first, second, third);
        assertThat(store.search(namespace, List.of(1.0d, 0.0d), 2)).containsExactly(
            new VectorStore.VectorMatch("chunk-1", 1.0d),
            new VectorStore.VectorMatch("chunk-2", 1.0d)
        );
    }

    @Test
    void storesAndQueriesTopKEntityVectors() {
        var store = new InMemoryVectorStore();
        var namespace = "entities";
        var first = new VectorStore.VectorRecord("entity-1", List.of(1.0d, 0.0d));
        var second = new VectorStore.VectorRecord("entity-2", List.of(1.0d, 0.0d));
        var third = new VectorStore.VectorRecord("entity-3", List.of(0.0d, 1.0d));

        store.saveAll(namespace, List.of(third, second, first));

        assertThat(store.list(namespace)).containsExactly(first, second, third);
        assertThat(store.search(namespace, List.of(1.0d, 0.0d), 2)).containsExactly(
            new VectorStore.VectorMatch("entity-1", 1.0d),
            new VectorStore.VectorMatch("entity-2", 1.0d)
        );
    }

    @Test
    void storesAndQueriesTopKRelationVectors() {
        var store = new InMemoryVectorStore();
        var namespace = "relations";
        var first = new VectorStore.VectorRecord("relation-1", List.of(1.0d, 0.0d));
        var second = new VectorStore.VectorRecord("relation-2", List.of(1.0d, 0.0d));
        var third = new VectorStore.VectorRecord("relation-3", List.of(0.0d, 1.0d));

        store.saveAll(namespace, List.of(second, third, first));

        assertThat(store.list(namespace)).containsExactly(first, second, third);
        assertThat(store.search(namespace, List.of(1.0d, 0.0d), 2)).containsExactly(
            new VectorStore.VectorMatch("relation-1", 1.0d),
            new VectorStore.VectorMatch("relation-2", 1.0d)
        );
    }

    @Test
    void supportsKeywordSearchUsingSearchableTextAndKeywords() {
        var store = new InMemoryVectorStore();

        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "chunk-1",
                List.of(1.0d, 0.0d),
                "Milvus hybrid retrieval with BM25 sparse index",
                List.of("milvus", "hybrid", "bm25")
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "chunk-2",
                List.of(0.0d, 1.0d),
                "Graph traversal only",
                List.of("neo4j", "graph")
            )
        ));

        assertThat(store.search("chunks", new HybridVectorStore.SearchRequest(
            List.of(),
            "milvus bm25",
            List.of("milvus", "bm25"),
            HybridVectorStore.SearchMode.KEYWORD,
            2
        ))).containsExactly(
            new VectorStore.VectorMatch("chunk-1", 4.0d),
            new VectorStore.VectorMatch("chunk-2", 0.0d)
        );
    }

    @Test
    void supportsHybridSearchByCombiningSemanticAndKeywordSignals() {
        var store = new InMemoryVectorStore();

        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "chunk-1",
                List.of(0.2d, 1.0d),
                "Milvus hybrid retrieval with BM25 sparse index",
                List.of("milvus", "hybrid", "bm25")
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "chunk-2",
                List.of(1.0d, 0.0d),
                "Dense embedding semantics only",
                List.of("semantic", "dense")
            )
        ));

        assertThat(store.search("chunks", new HybridVectorStore.SearchRequest(
            List.of(1.0d, 0.0d),
            "milvus hybrid",
            List.of("milvus", "hybrid"),
            HybridVectorStore.SearchMode.HYBRID,
            2
        ))).containsExactly(
            new VectorStore.VectorMatch("chunk-1", 4.2d),
            new VectorStore.VectorMatch("chunk-2", 1.0d)
        );
    }
}
