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
    private static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private static final String WORKSPACE_STATUS = "ws-status";
    private static final String WORKSPACE_ISOLATED = "ws-isolated";
    private static final String WORKSPACE_B = "ws-b";
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
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS)
                .contentType(APPLICATION_JSON)
                .content(INGEST_PAYLOAD))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();

        var jobId = objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobSuccess(WORKSPACE_STATUS, jobId);

        mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/documents/status")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.documentId == 'doc-1')]").exists());

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.documentId").value("doc-1"));

        mockMvc.perform(delete("/documents/{documentId}", "doc-1")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNotFound());
    }

    @Test
    void missingJobAndDocumentReturn404() throws Exception {
        mockMvc.perform(get("/documents/jobs/{jobId}", "missing-job")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents/status/{documentId}", "missing-doc")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNotFound());
    }

    @Test
    void isolatesJobsAndStatusesAcrossWorkspaces() throws Exception {
        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .header(WORKSPACE_HEADER, WORKSPACE_ISOLATED)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "documents": [
                        {
                          "id": "doc-ws-a",
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
        awaitJobSuccess(WORKSPACE_ISOLATED, jobId);

        mockMvc.perform(get("/documents/status")
                .header(WORKSPACE_HEADER, WORKSPACE_ISOLATED))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.documentId == 'doc-ws-a')]").exists());

        mockMvc.perform(get("/documents/status")
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.documentId == 'doc-ws-a')]").isEmpty());

        mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isNotFound());
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
            Thread.sleep(50);
        }
        fail("job did not reach SUCCEEDED before timeout");
    }
}
