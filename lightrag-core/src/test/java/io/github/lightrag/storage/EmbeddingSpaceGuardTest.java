package io.github.lightrag.storage;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.exception.VectorSpaceMismatchException;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingSpaceGuardTest {
    private static final String WORKSPACE = "default";
    private static final String FIRST_IDENTITY = "openai-compatible:text-embedding-3-small@https://example.test/v1";

    @Test
    void recordsTheMarkerOnTheFirstVectorWrite() {
        var storage = InMemoryStorageProvider.create();

        try (var rag = lightRag(storage, new FakeEmbeddingModel("fake-a", 2))) {
            rag.ingest(WORKSPACE, List.of(document("doc-1")));
        }

        var marker = storage.embeddingSpaceStore().load();
        assertThat(marker).isPresent();
        assertThat(marker.orElseThrow().modelIdentity()).isEqualTo("fake-a");
        assertThat(marker.orElseThrow().dimensions()).isEqualTo(2);
        assertThat(marker.orElseThrow().recordedAt()).isNotBlank();
    }

    @Test
    void refusesWhenTheStoredMarkerDescribesADifferentEmbeddingSpace() {
        var storage = InMemoryStorageProvider.create();
        try (var rag = lightRag(storage, new FakeEmbeddingModel(FIRST_IDENTITY, 2))) {
            rag.ingest(WORKSPACE, List.of(document("doc-1")));
        }

        try (var rag = lightRag(storage, new FakeEmbeddingModel("other-model", 2))) {
            assertThatThrownBy(() -> rag.ingest(WORKSPACE, List.of(document("doc-2"))))
                .isInstanceOf(VectorSpaceMismatchException.class)
                .hasMessageContaining("other-model")
                .hasMessageContaining(FIRST_IDENTITY)
                .hasMessageContaining("rebuild-vdb");
        }
    }

    @Test
    void acceptsAMatchingSpaceAcrossSessions() {
        var storage = InMemoryStorageProvider.create();
        try (var rag = lightRag(storage, new FakeEmbeddingModel("fake-a", 2))) {
            rag.ingest(WORKSPACE, List.of(document("doc-1")));
        }

        try (var rag = lightRag(storage, new FakeEmbeddingModel("fake-a", 2))) {
            rag.ingest(WORKSPACE, List.of(document("doc-2")));
        }

        assertThat(storage.embeddingSpaceStore().load().orElseThrow().modelIdentity()).isEqualTo("fake-a");
        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
            .anyMatch(vector -> vector.id().startsWith("doc-2"));
    }

    @Test
    void refusesWhenTheStoredMarkerHasADifferentDimensionCount() {
        var storage = InMemoryStorageProvider.create();
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("fake-a", 1536, "2026-01-01T00:00:00Z"));

        try (var rag = lightRag(storage, new FakeEmbeddingModel("fake-a", 2))) {
            assertThatThrownBy(() -> rag.ingest(WORKSPACE, List.of(document("doc-1"))))
                .isInstanceOf(VectorSpaceMismatchException.class)
                .hasMessageContaining("1536")
                .hasMessageContaining("2 dimensions");
        }
    }

    private static LightRag lightRag(StorageProvider storage, EmbeddingModel embeddingModel) {
        return LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(embeddingModel)
            .storage(storage)
            .build();
    }

    private static Document document(String id) {
        return new Document(id, "Title", "Alice works with Bob", Map.of());
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            var prompt = request.userPrompt() == null ? "" : request.userPrompt().toLowerCase(Locale.ROOT);
            if (prompt.contains("should_continue")) {
                return "no";
            }
            return "{\"entities\":[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Alice\",\"aliases\":[]},"
                + "{\"name\":\"Bob\",\"type\":\"person\",\"description\":\"Bob\",\"aliases\":[]}],"
                + "\"relations\":[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\","
                + "\"type\":\"works_with\",\"description\":\"works with\",\"weight\":1.0}]}";
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        private final String identity;
        private final int dimensions;

        private FakeEmbeddingModel(String identity, int dimensions) {
            this.identity = identity;
            this.dimensions = dimensions;
        }

        @Override
        public String cacheIdentity() {
            return identity;
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> vector()).toList();
        }

        private List<Double> vector() {
            var values = new java.util.ArrayList<Double>(dimensions);
            for (int i = 0; i < dimensions; i++) {
                values.add(i == 0 ? 1.0d : 0.0d);
            }
            return List.copyOf(values);
        }
    }
}
