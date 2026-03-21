package io.github.lightragjava.demo;

import io.github.lightragjava.types.Document;
import io.github.lightragjava.spring.boot.LightRagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UploadController.class)
@Import({UploadControllerTest.TestConfig.class, ApiExceptionHandler.class, WorkspaceResolver.class})
@SuppressWarnings("unchecked")
class UploadControllerTest {
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

        @Bean
        UploadedDocumentMapper uploadedDocumentMapper() {
            return new UploadedDocumentMapper();
        }
    }

    @Test
    void uploadsSingleFileAndUsesConfiguredAsyncDefault() throws Exception {
        when(ingestJobService.submit(eq("alpha"), any(), eq(true))).thenReturn("job-1");

        var mvcResult = mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "nested/path/Alice Notes.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Alice works with Bob".getBytes(StandardCharsets.UTF_8)
                ))
                .header("X-Workspace-Id", "alpha"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-1"))
            .andExpect(jsonPath("$.documentIds[0]").isNotEmpty())
            .andReturn();

        var documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ingestJobService).submit(eq("alpha"), documentsCaptor.capture(), eq(true));

        @SuppressWarnings("unchecked")
        var documents = (List<Document>) documentsCaptor.getValue();
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).id()).startsWith("alice-notes-");
        assertThat(documents.get(0).title()).isEqualTo("Alice Notes.txt");
        assertThat(documents.get(0).content()).isEqualTo("Alice works with Bob");
        assertThat(documents.get(0).metadata())
            .containsEntry("source", "upload")
            .containsEntry("filename", "Alice Notes.txt")
            .containsEntry("contentType", MediaType.TEXT_PLAIN_VALUE);
        assertThat(mvcResult.getResponse().getContentAsString()).contains(documents.get(0).id());
    }

    @Test
    void uploadsMultipleFilesAndAllowsAsyncOverride() throws Exception {
        when(ingestJobService.submit(eq("default"), any(), eq(false))).thenReturn("job-2");

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "alice.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Alice works with Bob".getBytes(StandardCharsets.UTF_8)
                ))
                .file(new MockMultipartFile(
                    "files",
                    "notes.md",
                    "text/markdown",
                    "# Notes\nBob works with Alice".getBytes(StandardCharsets.UTF_8)
                ))
                .param("async", "false"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-2"))
            .andExpect(jsonPath("$.documentIds[0]").isNotEmpty())
            .andExpect(jsonPath("$.documentIds[1]").isNotEmpty());

        var documentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(ingestJobService).submit(eq("default"), documentsCaptor.capture(), eq(false));

        @SuppressWarnings("unchecked")
        var documents = (List<Document>) documentsCaptor.getValue();
        assertThat(documents).hasSize(2);
        assertThat(documents)
            .extracting(Document::title)
            .containsExactly("alice.txt", "notes.md");
    }

    @Test
    void rejectsWhitespaceOnlyFileContent() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "blank.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "   ".getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("file content must not be blank: blank.txt"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsUnsupportedFileExtension() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "paper.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    "%PDF".getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("unsupported file type: paper.pdf"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsMissingFilesPart() throws Exception {
        mockMvc.perform(multipart("/documents/upload"))
            .andExpect(status().isBadRequest());

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsBlankFileName() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "   ",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Alice works with Bob".getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("file name must not be blank"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "large.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "a".repeat(1_048_577).getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("file too large: large.txt"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsInvalidUtf8Content() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "broken.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    new byte[] {(byte) 0xC3, (byte) 0x28}
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("file content must be valid UTF-8: broken.txt"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsTooManyFilesInSingleRequest() throws Exception {
        var requestBuilder = multipart("/documents/upload");
        for (int index = 0; index < 21; index++) {
            requestBuilder.file(new MockMultipartFile(
                "files",
                "file-" + index + ".txt",
                MediaType.TEXT_PLAIN_VALUE,
                "hello".getBytes(StandardCharsets.UTF_8)
            ));
        }

        mockMvc.perform(requestBuilder)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("too many files in a single upload"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsUploadWhenTotalSizeExceedsLimit() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "large-a.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "a".repeat(2_200_000).getBytes(StandardCharsets.UTF_8)
                ))
                .file(new MockMultipartFile(
                    "files",
                    "large-b.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "b".repeat(2_200_000).getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("total upload too large"));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }

    @Test
    void rejectsDuplicateGeneratedDocumentIdsInSingleRequest() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "alice.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Alice works with Bob".getBytes(StandardCharsets.UTF_8)
                ))
                .file(new MockMultipartFile(
                    "files",
                    "alice.txt",
                    MediaType.TEXT_PLAIN_VALUE,
                    "Alice works with Bob".getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("duplicate uploaded document id: alice-")));

        verify(ingestJobService, never()).submit(anyString(), any(), any(Boolean.class));
    }
}
