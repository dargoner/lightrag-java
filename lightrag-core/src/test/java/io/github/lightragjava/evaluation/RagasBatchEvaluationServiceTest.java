package io.github.lightragjava.evaluation;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagasBatchEvaluationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void evaluatesDatasetInBatchUsingSingleInMemoryIngest() throws Exception {
        Files.writeString(tempDir.resolve("01_notes.md"), """
            # Notes

            Alice works with Bob on retrieval systems.
            """);
        Files.writeString(tempDir.resolve("dataset.json"), """
            {
              "test_cases": [
                {"question": "Who works with Bob?", "ground_truth": "Alice works with Bob.", "project": "alpha"},
                {"question": "What does Alice work on?", "ground_truth": "Retrieval systems."}
              ]
            }
            """);

        var service = new RagasBatchEvaluationService();
        var results = service.evaluateBatch(
            new RagasBatchEvaluationService.BatchRequest(
                tempDir,
                tempDir.resolve("dataset.json"),
                QueryMode.NAIVE,
                10,
                10,
                RagasStorageProfile.IN_MEMORY
            ),
            new FakeChatModel(),
            new FakeEmbeddingModel()
        );

        assertThat(results).hasSize(2);
        assertThat(results)
            .extracting(RagasBatchEvaluationService.Result::answer)
            .containsExactly("Alice works with Bob.", "Retrieval systems.");
        assertThat(results.get(0).groundTruth()).isEqualTo("Alice works with Bob.");
        assertThat(results.get(0).caseMetadata()).containsEntry("project", "alpha");
        assertThat(results.get(0).contexts()).hasSize(1);
        assertThat(results.get(0).contexts().get(0).sourceId()).isNotBlank();
        assertThat(results.get(0).contexts().get(0).text()).contains("Alice works with Bob");
    }

    @Test
    void evaluatesDatasetUsingPostgresNeo4jTestcontainersProfile() throws Exception {
        Files.writeString(tempDir.resolve("01_notes.md"), """
            # Notes

            Alice works with Bob on retrieval systems.
            """);
        Files.writeString(tempDir.resolve("dataset.json"), """
            {
              "test_cases": [
                {"question": "Who works with Bob?", "ground_truth": "Alice works with Bob."}
              ]
            }
            """);

        var service = new RagasBatchEvaluationService();
        var results = service.evaluateBatch(
            new RagasBatchEvaluationService.BatchRequest(
                tempDir,
                tempDir.resolve("dataset.json"),
                QueryMode.NAIVE,
                10,
                10,
                RagasStorageProfile.POSTGRES_NEO4J_TESTCONTAINERS
            ),
            new FakeChatModel(),
            new FakeEmbeddingModel()
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).answer()).isEqualTo("Alice works with Bob.");
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("---Role---")
                || request.systemPrompt().contains("Document Chunks")) {
                if (request.userPrompt().contains("What does Alice work on?")) {
                    return "Retrieval systems.";
                }
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
                      "aliases": []
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
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> {
                    if (text.contains("Who works with Bob?") || text.contains("Alice works with Bob")) {
                        return List.of(1.0d, 0.0d);
                    }
                    if (text.contains("What does Alice work on?") || text.contains("retrieval systems")) {
                        return List.of(0.8d, 0.2d);
                    }
                    if (text.contains("Alice")) {
                        return List.of(1.0d, 0.0d);
                    }
                    if (text.contains("Bob")) {
                        return List.of(0.8d, 0.2d);
                    }
                    return List.of(0.0d, 1.0d);
                })
                .toList();
        }
    }
}
