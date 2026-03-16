package io.github.lightragjava.storage;

import io.github.lightragjava.storage.memory.InMemoryChunkStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryChunkStoreTest {
    @Test
    void storesAndLoadsChunksById() {
        var store = new InMemoryChunkStore();
        var firstChunk = new ChunkStore.ChunkRecord(
            "chunk-1",
            "doc-1",
            "First chunk",
            10,
            0,
            Map.of("kind", "intro")
        );
        var secondChunk = new ChunkStore.ChunkRecord(
            "chunk-2",
            "doc-1",
            "Second chunk",
            12,
            1,
            Map.of("kind", "body")
        );

        store.save(secondChunk);
        store.save(firstChunk);

        assertThat(store.load("chunk-1")).contains(firstChunk);
        assertThat(store.list()).containsExactlyInAnyOrder(firstChunk, secondChunk);
        assertThat(store.listByDocument("doc-1")).containsExactly(firstChunk, secondChunk);
    }
}
