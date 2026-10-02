package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Entity;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridVectorPayloadsTest {
    @Test
    void entityPayloadsCarryTheEntityFilePath() {
        var entity = new Entity(
            "alice", "Alice", "person", "Researcher", List.of("Al"), List.of("c1"), "/a.md<SEP>/b.md");

        var payloads = HybridVectorPayloads.entityPayloads(
            List.of(entity),
            List.of(new VectorStore.VectorRecord("alice", List.of(1.0d, 0.0d)))
        );

        assertThat(payloads).singleElement()
            .extracting(HybridVectorStore.EnrichedVectorRecord::filePath)
            .isEqualTo("/a.md<SEP>/b.md");
    }
}
