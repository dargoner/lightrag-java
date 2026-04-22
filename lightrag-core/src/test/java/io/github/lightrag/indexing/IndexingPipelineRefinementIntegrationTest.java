package io.github.lightrag.indexing;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingPipelineRefinementIntegrationTest {
    @Test
    void addsRelationVectorsAfterRefinementFillsBrokenEdge() {
        var extractionModel = new SequentialChatModel(
            """
            {"entities":[
              {"name":"订单系统","type":"system","description":"","aliases":[]},
              {"name":"PostgreSQL","type":"database","description":"","aliases":[]}
            ],"relations":[]}
            """,
            """
            {"entities":[
              {"name":"PostgreSQL","type":"database","description":"","aliases":[]}
            ],"relations":[]}
            """,
            """
            {
              "entities": [],
              "relations": [
                {
                  "source_entity": "订单系统",
                  "target_entity": "PostgreSQL",
                  "relationship_keywords": "依赖",
                  "relationship_description": "订单系统依赖 PostgreSQL 进行事务存储",
                  "weight": 1.0,
                  "supportingChunkIndexes": [0, 1]
                }
              ],
              "warnings": []
            }
            """
        );
        var storageProvider = InMemoryStorageProvider.create();
        try (var rag = LightRag.builder()
            .chatModel(extractionModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storageProvider)
            .chunker(new TwoChunkChunker())
            .entityExtractMaxGleaning(0)
            .contextualExtractionRefinement(true)
            .build()) {

            rag.ingest("default", List.of(new Document("doc-1", "title", "订单系统依赖 PostgreSQL 进行事务存储", Map.of())));
        }

        assertThat(storageProvider.graphStore().allRelations())
            .extracting(GraphStore.RelationRecord::type)
            .contains("依赖");
    }

    private static final class SequentialChatModel implements ChatModel {
        private final List<String> responses;
        private int index;

        private SequentialChatModel(String... responses) {
            this.responses = new ArrayList<>(List.of(responses));
        }

        @Override
        public String generate(ChatRequest request) {
            var current = Math.min(index, responses.size() - 1);
            index++;
            return responses.get(current);
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> List.of((double) text.length(), 1.0d))
                .toList();
        }
    }

    private static final class TwoChunkChunker implements Chunker {
        @Override
        public List<Chunk> chunk(Document document) {
            return List.of(
                new Chunk(document.id() + ":0", document.id(), "订单系统依赖", 6, 0, Map.of()),
                new Chunk(document.id() + ":1", document.id(), "PostgreSQL 进行事务存储", 18, 1, Map.of())
            );
        }
    }
}
