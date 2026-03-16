package io.github.lightragjava.demo;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {DemoApplication.class, DemoApplicationTest.TestConfig.class},
    properties = {
        "lightrag.chat.base-url=http://localhost:11434/v1/",
        "lightrag.chat.model=qwen2.5:7b",
        "lightrag.chat.api-key=dummy",
        "lightrag.embedding.base-url=http://localhost:11434/v1/",
        "lightrag.embedding.model=nomic-embed-text",
        "lightrag.embedding.api-key=dummy",
        "lightrag.storage.type=in-memory"
    }
)
@AutoConfigureMockMvc
class DemoApplicationTest {
    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @Test
    void ingestsDocumentsAndAnswersQuery() throws Exception {
        mockMvc.perform(post("/documents/ingest")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "documents": [
                        {
                          "id": "doc-1",
                          "title": "Title",
                          "content": "Alice works with Bob",
                          "metadata": {"source": "demo"}
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(post("/query")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "mode": "MIX"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("Alice works with Bob."));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        ChatModel chatModel() {
            return request -> {
                if (request.systemPrompt().contains("---Role---")) {
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
            };
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return texts -> texts.stream()
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
