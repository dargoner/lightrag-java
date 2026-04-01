package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDocumentStoreTest {
    @Test
    void storesAndLoadsDocumentsById() {
        var store = new InMemoryDocumentStore();
        var document = new DocumentStore.DocumentRecord(
            "doc-1",
            "Title",
            "Body",
            Map.of("source", "unit-test")
        );

        store.save(document);

        assertThat(store.contains("doc-1")).isTrue();
        assertThat(store.load("doc-1")).contains(document);
        assertThat(store.list()).containsExactly(document);
    }
}
