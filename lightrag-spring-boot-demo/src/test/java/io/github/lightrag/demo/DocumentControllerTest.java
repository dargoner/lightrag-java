package io.github.lightrag.demo;

import io.github.lightrag.spring.boot.LightRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
@Import({ApiExceptionHandler.class, WorkspaceResolver.class, DocumentControllerTest.TestConfig.class})
class DocumentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IngestJobService ingestJobService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        LightRagProperties lightRagProperties() {
            return new LightRagProperties();
        }
    }

    @Test
    void submitsIngestJobForResolvedWorkspace() throws Exception {
        when(ingestJobService.submit(eq("alpha"), anyList(), eq(true))).thenReturn("job-1");

        mockMvc.perform(post("/documents/ingest")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "documents": [
                        {
                          "id": "doc-1",
                          "title": "Title",
                          "content": "Alice works with Bob"
                        }
                      ]
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-1"));

        verify(ingestJobService).submit(eq("alpha"), anyList(), eq(true));
    }

    @Test
    void fallsBackToDefaultWorkspaceWhenHeaderMissing() throws Exception {
        when(ingestJobService.submit(eq("default"), anyList(), eq(true))).thenReturn("job-2");

        mockMvc.perform(post("/documents/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "documents": [
                        {
                          "id": "doc-1",
                          "title": "Title",
                          "content": "Alice works with Bob"
                        }
                      ]
                    }
                    """))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-2"));

        verify(ingestJobService).submit(eq("default"), anyList(), eq(true));
    }

    @Test
    void rejectsEmptyDocumentsPayload() throws Exception {
        mockMvc.perform(post("/documents/ingest")
                .header("X-Workspace-Id", "alpha")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "documents": []
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("documents must not be empty"));

        verify(ingestJobService, never()).submit(eq("alpha"), anyList(), eq(true));
    }
}
