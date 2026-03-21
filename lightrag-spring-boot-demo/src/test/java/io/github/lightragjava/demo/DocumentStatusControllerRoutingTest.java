package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.LightRagProperties;
import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
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
    private WorkspaceLightRagFactory workspaceLightRagFactory;

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
        when(workspaceLightRagFactory.find("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/documents/status")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));

        verify(workspaceLightRagFactory).find("ghost");
        verify(workspaceLightRagFactory, never()).get(anyString());
    }

    @Test
    void getStatusReturnsNotFoundWithoutCreatingWorkspaceInstance() throws Exception {
        when(workspaceLightRagFactory.find("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/documents/status/{documentId}", "doc-1")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isNotFound());

        verify(workspaceLightRagFactory).find("ghost");
        verify(workspaceLightRagFactory, never()).get(anyString());
    }

    @Test
    void deleteDoesNotCreateWorkspaceInstance() throws Exception {
        when(workspaceLightRagFactory.find("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/documents/{documentId}", "doc-1")
                .header("X-Workspace-Id", "ghost"))
            .andExpect(status().isNoContent());

        verify(workspaceLightRagFactory).find("ghost");
        verify(workspaceLightRagFactory, never()).get(anyString());
    }
}
