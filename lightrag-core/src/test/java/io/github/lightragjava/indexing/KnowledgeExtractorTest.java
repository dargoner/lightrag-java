package io.github.lightragjava.indexing;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.ExtractedEntity;
import io.github.lightragjava.types.ExtractedRelation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExtractorTest {
    @Test
    void gleansAdditionalEntitiesWhenConfigured() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "short",
                  "aliases": []
                }
              ],
              "relations": []
            }
            """,
            """
            {
              "entities": [
                {
                  "name": "Bob",
                  "type": "person",
                  "description": "Engineer",
                  "aliases": []
                },
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "Research lead",
                  "aliases": ["Al"]
                }
              ],
              "relations": []
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000);

        var result = extractor.extract(chunk("Alice works with Bob on retrieval systems"));

        assertThat(result.entities()).containsExactlyInAnyOrder(
            new ExtractedEntity("Alice", "person", "Research lead", List.of("Al")),
            new ExtractedEntity("Bob", "person", "Engineer", List.of())
        );
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(1).conversationHistory()).hasSize(2);
    }

    @Test
    void skipsGleaningWhenContextBudgetIsTooSmall() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "Researcher",
                  "aliases": []
                }
              ],
              "relations": []
            }
            """,
            """
            {
              "entities": [
                {
                  "name": "Bob",
                  "type": "person",
                  "description": "Engineer",
                  "aliases": []
                }
              ],
              "relations": []
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 5);

        var result = extractor.extract(chunk("Alice works with Bob on retrieval systems"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of())
        );
        assertThat(chatModel.requests()).hasSize(1);
        assertThat(result.warnings()).containsExactly("skipped gleaning because extraction context exceeded maxExtractInputTokens");
    }

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

    @Test
    void acceptsJsonWrappedInMarkdownCodeFences() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            ```json
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "Researcher",
                  "aliases": []
                }
              ],
              "relations": []
            }
            ```
            """));

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of())
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

    private static final class RecordingChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return responses.get(Math.min(requests.size() - 1, responses.size() - 1));
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
