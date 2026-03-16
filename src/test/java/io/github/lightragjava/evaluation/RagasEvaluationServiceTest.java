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

class RagasEvaluationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void evaluatesMarkdownDirectoryIntoAnswerAndContexts() throws Exception {
        Files.writeString(tempDir.resolve("01_notes.md"), """
            # Notes

            Alice works with Bob on retrieval systems.
            """);

        var service = new RagasEvaluationService();
        var result = service.evaluate(
            new RagasEvaluationService.Request(
                tempDir,
                "Who works with Bob?",
                QueryMode.NAIVE,
                10,
                10
            ),
            new FakeChatModel(),
            new FakeEmbeddingModel()
        );

        assertThat(result.answer()).isEqualTo("Alice works with Bob.");
        assertThat(result.contexts()).hasSize(1);
        assertThat(result.contexts().get(0)).contains("Alice works with Bob");
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("---Role---")
                || request.systemPrompt().contains("Document Chunks")) {
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
