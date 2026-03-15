package io.github.lightragjava;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class E2ELightRagTest {
    @Test
    void ingestBuildsChunkEntityRelationAndVectorIndexes() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "test"))));

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.chunkStore().listByDocument("doc-1")).hasSize(1);
        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.graphStore().allRelations())
            .extracting(relation -> relation.id())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
        assertThat(storage.vectorStore().list("chunks"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.vectorStore().list("entities"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("entity:alice", "entity:bob");
        assertThat(storage.vectorStore().list("relations"))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("relation:entity:alice|works_with|entity:bob");
    }

    @Test
    void ingestPersistsSnapshotWhenConfigured() {
        var storage = InMemoryStorageProvider.create();
        var snapshotPath = Path.of("snapshots", "doc-1.json");
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .loadFromSnapshot(snapshotPath)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        assertThat(storage.snapshotStore().list()).containsExactly(snapshotPath);
        var snapshot = storage.snapshotStore().load(snapshotPath);
        assertThat(snapshot.documents()).hasSize(1);
        assertThat(snapshot.chunks()).hasSize(1);
        assertThat(snapshot.entities()).hasSize(2);
        assertThat(snapshot.relations()).hasSize(1);
        assertThat(snapshot.vectors()).containsKeys("chunks", "entities", "relations");
    }

    @Test
    void queryUsesMixModeByDefaultAndCallsChatModelWithContext() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new FakeChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        QueryResult result = rag.query(QueryRequest.builder()
            .query("Who works with Bob?")
            .build());

        assertThat(result.answer()).isEqualTo("Alice works with Bob.");
        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .contains("doc-1:0");
        assertThat(chatModel.lastQueryRequest()).isNotNull();
        assertThat(chatModel.lastQueryRequest().userPrompt()).contains("Who works with Bob?");
        assertThat(chatModel.lastQueryRequest().userPrompt()).contains("Alice");
    }

    private static final class FakeChatModel implements ChatModel {
        private ChatRequest lastQueryRequest;

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains("Question:")) {
                lastQueryRequest = request;
                return "Alice works with Bob.";
            }
            return """
                {
                  "entities": [
                    {
                      "name": "Alice",
                      "type": "person",
                      "description": "Researcher",
                      "aliases": []
                    },
                    {
                      "name": "Bob",
                      "type": "person",
                      "description": "Engineer",
                      "aliases": ["Robert"]
                    }
                  ],
                  "relations": [
                    {
                      "sourceEntityName": "Alice",
                      "targetEntityName": "Bob",
                      "type": "works_with",
                      "description": "collaboration",
                      "weight": 0.8
                    }
                  ]
                }
                """;
        }

        ChatRequest lastQueryRequest() {
            return lastQueryRequest;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return java.util.stream.IntStream.range(0, texts.size())
                .mapToObj(index -> List.of((double) texts.get(index).length(), (double) index))
                .toList();
        }
    }
}
