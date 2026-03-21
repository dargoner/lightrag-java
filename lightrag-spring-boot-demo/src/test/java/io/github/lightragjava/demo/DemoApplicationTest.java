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

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private static final String WORKSPACE_A = "ws-demo-a";
    private static final String WORKSPACE_B = "ws-demo-b";

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void ingestsDocumentsAndAnswersQuery() throws Exception {
        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .header(WORKSPACE_HEADER, WORKSPACE_A)
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
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();

        var jobId = objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobSuccess(WORKSPACE_A, jobId);

        mockMvc.perform(post("/query")
                .header(WORKSPACE_HEADER, WORKSPACE_A)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "mode": "MIX"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.answer").value("Alice works with Bob."));

        mockMvc.perform(post("/query")
                .header(WORKSPACE_HEADER, WORKSPACE_B)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "query": "Who works with Bob?",
                      "mode": "MIX"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contexts").isEmpty())
            .andExpect(jsonPath("$.references").isEmpty());
    }

    private void awaitJobSuccess(String workspaceId, String jobId) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var jobResult = mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                    .header(WORKSPACE_HEADER, workspaceId))
                .andExpect(status().isOk())
                .andReturn();
            var statusValue = objectMapper.readTree(jobResult.getResponse().getContentAsString()).get("status").asText();
            if ("SUCCEEDED".equals(statusValue)) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("job did not reach SUCCEEDED before timeout");
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
