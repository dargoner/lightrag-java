package io.github.lightrag.demo;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.spring.boot.LightRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentStatusController.class)
@Import({ApiExceptionHandler.class, WorkspaceResolver.class, DocumentStatusControllerRoutingTest.TestConfig.class})
class DocumentStatusControllerRoutingTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LightRag lightRag;

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
    void listStatusDoesNotCreateWorkspaceInstance() throws Exception {
        when(lightRag.listDocumentStatuses("ghost")).thenReturn(List.of());

        mockMvc.perform(get("/documents/status")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));

        verify(lightRag).listDocumentStatuses("ghost");
        verify(lightRag, never()).getDocumentStatus(any(), any());
    }

    @Test
    void getStatusReturnsNotFoundWithoutCreatingWorkspaceInstance() throws Exception {
        when(lightRag.getDocumentStatus("ghost", "doc-1"))
            .thenThrow(new NoSuchElementException("document not found: doc-1"));

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isNotFound());

        verify(lightRag).getDocumentStatus("ghost", "doc-1");
        verify(lightRag, never()).deleteByDocumentId(any(), any());
    }

    @Test
    void deleteDoesNotCreateWorkspaceInstance() throws Exception {
        org.mockito.Mockito.doThrow(new NoSuchElementException("document not found: doc-1"))
            .when(lightRag).deleteByDocumentId("ghost", "doc-1");

        mockMvc.perform(delete("/documents/{documentId}", "doc-1")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isNoContent());

        verify(lightRag).deleteByDocumentId(eq("ghost"), eq("doc-1"));
    }
}
