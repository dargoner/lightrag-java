package io.github.lightragjava.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class DocumentStatusControllerTest {
    private static final String INGEST_PAYLOAD = """
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
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void jobLifecycleAndStatusEndpoints() throws Exception {
        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .contentType(APPLICATION_JSON)
                .content(INGEST_PAYLOAD))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();

        var jobId = objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobSuccess(jobId);

        mockMvc.perform(get("/documents/jobs/{jobId}", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/documents/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].documentId").value("doc-1"));

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value("doc-1"));

        mockMvc.perform(delete("/documents/{documentId}", "doc-1"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1"))
            .andExpect(status().isNotFound());
    }

    @Test
    void missingJobAndDocumentReturn404() throws Exception {
        mockMvc.perform(get("/documents/jobs/{jobId}", "missing-job"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents/status/{documentId}", "missing-doc"))
            .andExpect(status().isNotFound());
    }

    private void awaitJobSuccess(String jobId) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var jobResult = mockMvc.perform(get("/documents/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andReturn();
            var statusValue = objectMapper.readTree(jobResult.getResponse().getContentAsString()).get("status").asText();
            if ("SUCCEEDED".equals(statusValue)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("job did not reach SUCCEEDED before timeout");
    }
}
