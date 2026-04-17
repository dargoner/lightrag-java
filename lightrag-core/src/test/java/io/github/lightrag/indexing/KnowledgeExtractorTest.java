package io.github.lightrag.indexing;

import io.github.lightrag.indexing.refinement.RefinedRelationPatch;
import io.github.lightrag.indexing.refinement.RefinementScope;
import io.github.lightrag.indexing.refinement.RefinementWindow;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
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
    void includesConfiguredLanguageAndEntityTypesInPrompt() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var extractor = new KnowledgeExtractor(chatModel, 0, 10_000, "Chinese", List.of("Person", "Organization"));

        extractor.extract(chunk("Alice works at OpenAI"));

        assertThat(chatModel.requests()).hasSize(1);
        assertThat(chatModel.requests().get(0).systemPrompt()).contains("Chinese");
        assertThat(chatModel.requests().get(0).systemPrompt()).contains("Person, Organization");
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

    @Test
    void acceptsJsonObjectEmbeddedInSurroundingText() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            以下是提取结果，请直接使用：
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
            输出结束。
            """));

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of())
        );
    }

    @Test
    void acceptsJsonCodeFenceEmbeddedInSurroundingText() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            以下是提取结果，请直接使用：
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
            输出结束。
            """));

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of())
        );
    }

    @Test
    void clampsRelationWeightAndFallsBackToConfidence() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            {
              "entities": [
                {"name": "Alice", "type": "person", "description": "Researcher", "aliases": []},
                {"name": "Bob", "type": "person", "description": "Engineer", "aliases": []},
                {"name": "Charlie", "type": "person", "description": "Reviewer", "aliases": []}
              ],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "works_with",
                  "description": "collaboration",
                  "weight": 1.7
                },
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Charlie",
                  "type": "reviews",
                  "description": "review chain",
                  "confidence": 0.4
                }
              ]
            }
            """));

        var result = extractor.extract(chunk("Alice works with Bob and Charlie"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "works_with", "collaboration", 1.0d),
            new ExtractedRelation("Alice", "Charlie", "reviews", "review chain", 0.4d)
        );
    }

    @Test
    void mergesRelationTypeVariantsAcrossGleaning() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {"name": "Alice", "type": "person", "description": "Researcher", "aliases": []},
                {"name": "Bob", "type": "person", "description": "Engineer", "aliases": []}
              ],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "works_with",
                  "description": "short",
                  "weight": 0.7
                }
              ]
            }
            """,
            """
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "works-with",
                  "description": "longer collaboration description",
                  "weight": 0.9
                }
              ]
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000);

        var result = extractor.extract(chunk("Alice works with Bob"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "works_with", "longer collaboration description", 0.9d)
        );
    }

    @Test
    void mergesUndirectedRelationsAcrossGleaning() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {"name": "Alice", "type": "person", "description": "Researcher", "aliases": []},
                {"name": "Bob", "type": "person", "description": "Engineer", "aliases": []}
              ],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "collaboration, research",
                  "description": "short",
                  "weight": 0.6
                }
              ]
            }
            """,
            """
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "Bob",
                  "targetEntityName": "Alice",
                  "type": "research, collaboration",
                  "description": "longer collaboration description",
                  "weight": 0.9
                }
              ]
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000);

        var result = extractor.extract(chunk("Alice works with Bob on retrieval systems"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "collaboration, research", "longer collaboration description", 0.9d)
        );
    }

    @Test
    void mergesKeywordOrderVariantsAcrossGleaning() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {"name": "Alice", "type": "person", "description": "Researcher", "aliases": []},
                {"name": "Bob", "type": "person", "description": "Engineer", "aliases": []}
              ],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "architecture, dependency",
                  "description": "short",
                  "weight": 0.5
                }
              ]
            }
            """,
            """
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "dependency, architecture",
                  "description": "longer dependency description",
                  "weight": 0.8
                }
              ]
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000);

        var result = extractor.extract(chunk("Alice depends on Bob's architecture guidance"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "architecture, dependency", "longer dependency description", 0.8d)
        );
    }

    @Test
    void promptIncludesUndirectedAndIncrementalExtractionRules() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [],
              "relations": []
            }
            """,
            """
            {
              "entities": [],
              "relations": []
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000, "Chinese", List.of("Person", "Organization"));

        extractor.extract(chunk("Alice works at OpenAI with Bob"));

        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(0).systemPrompt())
            .contains("Treat relationships as undirected unless direction is explicitly stated.")
            .contains("Do not output duplicate relationships.")
            .contains("If none of the provided entity types apply, use \"Other\".");
        assertThat(chatModel.requests().get(1).userPrompt())
            .contains("Do not repeat entities or relationships that were already extracted correctly.")
            .contains("Return only incremental JSON using the same schema as before.");
    }

    @Test
    void stabilizesRelationEndpointsAndKeywordsAfterUndirectedMerge() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {"name": "Alice", "type": "person", "description": "Researcher", "aliases": []},
                {"name": "Bob", "type": "person", "description": "Engineer", "aliases": []}
              ],
              "relations": [
                {
                  "sourceEntityName": "Bob",
                  "targetEntityName": "Alice",
                  "type": "research, collaboration",
                  "description": "short",
                  "weight": 0.6
                }
              ]
            }
            """,
            """
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "collaboration, research",
                  "description": "longer collaboration description",
                  "weight": 0.9
                }
              ]
            }
            """
        );
        var extractor = new KnowledgeExtractor(chatModel, 1, 10_000);

        var result = extractor.extract(chunk("Alice works with Bob on retrieval systems"));

        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Bob", "collaboration, research", "longer collaboration description", 0.9d)
        );
    }

    @Test
    void extractsWindowRelationsWithSupportingChunkIndexes() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "订单系统",
                  "targetEntityName": "PostgreSQL",
                  "type": "依赖",
                  "description": "订单系统依赖 PostgreSQL 进行事务存储",
                  "weight": 1.0,
                  "supportingChunkIndexes": [0, 1, 1]
                }
              ],
              "warnings": []
            }
            """));
        var window = new RefinementWindow(
            "doc-1",
            List.of(
                chunk("chunk-1", "订单系统依赖"),
                chunk("chunk-2", "PostgreSQL 进行事务存储")
            ),
            0,
            RefinementScope.ADJACENT,
            16
        );

        var result = extractor.extractWindow(window);

        assertThat(result.relationPatches()).containsExactly(
            new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of("chunk-1", "chunk-2")
            )
        );
    }

    @Test
    void dropsWindowRelationsWhenSupportingChunkIndexesAreOutOfRange() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "订单系统",
                  "targetEntityName": "PostgreSQL",
                  "type": "依赖",
                  "description": "bad indexes",
                  "weight": 1.0,
                  "supportingChunkIndexes": [3]
                }
              ],
              "warnings": []
            }
            """));
        var window = new RefinementWindow(
            "doc-1",
            List.of(
                chunk("chunk-1", "订单系统依赖"),
                chunk("chunk-2", "PostgreSQL 进行事务存储")
            ),
            0,
            RefinementScope.ADJACENT,
            16
        );

        var result = extractor.extractWindow(window);

        assertThat(result.relationPatches()).isEmpty();
        assertThat(result.warnings()).contains("dropped relation candidate because supportingChunkIndexes were invalid");
    }

    @Test
    void preservesWindowRelationsForFallbackWhenDeterministicAttributionIsEnabled() {
        var chatModel = new RecordingChatModel("""
                {
                  "entities": [],
                  "relations": [
                    {
                      "sourceEntityName": "订单系统",
                      "targetEntityName": "PostgreSQL",
                      "type": "依赖",
                      "description": "订单系统依赖 PostgreSQL 进行事务存储",
                      "weight": 1.0,
                      "supportingChunkIndexes": []
                    }
                  ],
                  "warnings": []
                }
                """);
        var extractor = new KnowledgeExtractor(
            chatModel,
            0,
            10_000,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            true
        );
        var window = new RefinementWindow(
            "doc-1",
            List.of(chunk("chunk-1", "订单系统依赖 PostgreSQL 进行事务存储")),
            0,
            RefinementScope.ADJACENT,
            16
        );

        var result = extractor.extractWindow(window);

        assertThat(result.relationPatches()).containsExactly(
            new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of()
            )
        );
        assertThat(chatModel.requests()).hasSize(1);
        assertThat(chatModel.requests().get(0).systemPrompt())
            .contains("you may return the candidate with an empty supportingChunkIndexes array");
    }

    @Test
    void normalizesWindowRelationKeywords() {
        var extractor = new KnowledgeExtractor(new StubChatModel("""
            {
              "entities": [],
              "relations": [
                {
                  "sourceEntityName": "Alice",
                  "targetEntityName": "Bob",
                  "type": "research, collaboration",
                  "description": "Alice and Bob collaborate on retrieval systems",
                  "weight": 0.9,
                  "supportingChunkIndexes": [0]
                }
              ],
              "warnings": []
            }
            """));
        var window = new RefinementWindow(
            "doc-1",
            List.of(chunk("chunk-1", "Alice and Bob collaborate on retrieval systems")),
            0,
            RefinementScope.ADJACENT,
            16
        );

        var result = extractor.extractWindow(window);

        assertThat(result.relationPatches()).containsExactly(
            new RefinedRelationPatch(
                new ExtractedRelation(
                    "Alice",
                    "Bob",
                    "collaboration, research",
                    "Alice and Bob collaborate on retrieval systems",
                    0.9d
                ),
                List.of("chunk-1")
            )
        );
    }

    private static Chunk chunk(String text) {
        return new Chunk("doc-1:0", "doc-1", text, text.length(), 0, Map.of());
    }

    private static Chunk chunk(String chunkId, String text) {
        return new Chunk(chunkId, "doc-1", text, text.length(), 0, Map.of());
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
