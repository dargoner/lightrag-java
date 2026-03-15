package io.github.lightragjava.indexing;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.ExtractedEntity;
import io.github.lightragjava.types.ExtractedRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExtractorTest {
    @Test
    void dropsBlankExtractedEntityNames() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            {
              "entities": [
                {
                  "name": "   ",
                  "type": "person",
                  "description": "ignored",
                  "aliases": ["Ghost"]
                },
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "Researcher",
                  "aliases": ["Al"]
                }
              ],
              "relations": []
            }
            """));

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of("Al"))
        );
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void dropsRelationsWithMissingEndpointsAndDefaultsWeight() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
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
                  "aliases": []
                }
              ],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "",
                  "type": "works_with",
                  "description": "ignored"
                },
                {
                  "sourceEntityName": " Alice ",
                  "targetEntityName": " Bob ",
                  "type": "works_with",
                  "description": "collaboration"
                }
              ]
            }
            """));

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 1.0d)
        );
    }

    private static Chunk chunk(String text) {
        return new Chunk("doc-1:0", "doc-1", text, text.length(), 0, Map.of());
    }

    private record StubChatModel(String response) implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return response;
        }
    }
}
