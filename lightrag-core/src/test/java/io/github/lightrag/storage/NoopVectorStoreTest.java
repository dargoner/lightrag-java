package io.github.lightrag.storage;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.NoopEmbeddingModel;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoopVectorStoreTest {
    private static final String WORKSPACE = "default";

    @Test
    void noopStoreDiscardsWritesAndReturnsEmptyReads() {
        var store = new NoopVectorStore();
        store.saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d))));

        assertThat(store.list("chunks")).isEmpty();
        assertThat(store.search("chunks", List.of(1.0d, 0.0d), 10)).isEmpty();
    }

    @Test
    void noopVectorRouteSkipsEmbeddingAndStillPersistsTheGraph() {
        var storage = InMemoryStorageProvider.create();
        var embeddingModel = new CountingEmbeddingModel("counted", 2);

        try (var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(embeddingModel)
            .storage(storage)
            .noopVectorStore(true)
            .build()) {
            rag.ingest(WORKSPACE, List.of(document("doc-1")));
        }

        assertThat(embeddingModel.embeddingCalls()).isZero();
        assertThat(storage.graphStore().allEntities()).isNotEmpty();
        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE)).isEmpty();
    }

    @Test
    void noopVectorRouteStillAnswersQueriesWithNoopEmbeddings() {
        var storage = InMemoryStorageProvider.create();

        try (var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new NoopEmbeddingModel())
            .storage(storage)
            .noopVectorStore(true)
            .build()) {
            rag.ingest(WORKSPACE, List.of(document("doc-1")));

            var result = rag.query(WORKSPACE, QueryRequest.builder()
                .query("Who works with Bob?")
                .mode(QueryMode.LOCAL)
                .build());

            assertThat(result.answer()).isNotBlank();
        }
    }

    private static Document document(String id) {
        return new Document(id, "Title", "Alice works with Bob", Map.of());
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (isRetrievalPrompt(request)) {
                return "Alice works with Bob.";
            }
            if (isKeywordExtractionPrompt(request)) {
                return """
                    {
                      "high_level_keywords": ["collaboration"],
                      "low_level_keywords": ["Alice", "Bob"]
                    }
                    """;
            }
            var prompt = request.userPrompt() == null ? "" : request.userPrompt().toLowerCase(Locale.ROOT);
            if (prompt.contains("should_continue")) {
                return "no";
            }
            return "{\"entities\":[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Alice\",\"aliases\":[]},"
                + "{\"name\":\"Bob\",\"type\":\"person\",\"description\":\"Bob\",\"aliases\":[]}],"
                + "\"relations\":[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\","
                + "\"type\":\"works_with\",\"description\":\"works with\",\"weight\":1.0}]}";
        }

        private static boolean isRetrievalPrompt(ChatRequest request) {
            return request.systemPrompt().contains("---Role---")
                && request.systemPrompt().contains("---Instructions---")
                && request.systemPrompt().contains("---Context---")
                && request.systemPrompt().contains("The response should be presented in");
        }

        private static boolean isKeywordExtractionPrompt(ChatRequest request) {
            return request.systemPrompt().isEmpty()
                && request.userPrompt().contains("high_level_keywords")
                && request.userPrompt().contains("low_level_keywords");
        }
    }

    private static final class CountingEmbeddingModel implements EmbeddingModel {
        private final String identity;
        private final int dimensions;
        private int embeddingCalls;

        private CountingEmbeddingModel(String identity, int dimensions) {
            this.identity = identity;
            this.dimensions = dimensions;
        }

        @Override
        public String cacheIdentity() {
            return identity;
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            embeddingCalls++;
            var vector = new java.util.ArrayList<Double>(dimensions);
            for (int i = 0; i < dimensions; i++) {
                vector.add(i == 0 ? 1.0d : 0.0d);
            }
            return texts.stream().map(text -> List.copyOf(vector)).toList();
        }

        private int embeddingCalls() {
            return embeddingCalls;
        }
    }
}
