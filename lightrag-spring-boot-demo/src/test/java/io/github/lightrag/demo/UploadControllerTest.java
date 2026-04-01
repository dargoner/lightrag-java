package io.github.lightrag.demo;

import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.spring.boot.IngestPreset;
import io.github.lightrag.spring.boot.LightRagProperties;
import io.github.lightrag.types.RawDocumentSource;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    @Autowired
    private LightRagProperties lightRagProperties;

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
        when(ingestJobService.submitSources(eq("alpha"), any(), any(), eq(true))).thenReturn("job-1");

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

        var sourcesCaptor = ArgumentCaptor.forClass(List.class);
        var optionsCaptor = ArgumentCaptor.forClass(DocumentIngestOptions.class);
        verify(ingestJobService).submitSources(eq("alpha"), sourcesCaptor.capture(), optionsCaptor.capture(), eq(true));

        var sources = (List<RawDocumentSource>) sourcesCaptor.getValue();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).sourceId()).isNotBlank();
        assertThat(sources.get(0).fileName()).isEqualTo("Alice Notes.txt");
        assertThat(new String(sources.get(0).bytes(), StandardCharsets.UTF_8)).isEqualTo("Alice works with Bob");
        assertThat(sources.get(0).metadata())
            .containsEntry("source", "upload")
            .containsEntry("fileName", "Alice Notes.txt")
            .containsEntry("contentType", MediaType.TEXT_PLAIN_VALUE);
        assertThat(optionsCaptor.getValue()).isNotNull();
        assertThat(mvcResult.getResponse().getContentAsString()).contains(sources.get(0).sourceId());
    }

    @Test
    void uploadsMultipleFilesAndAllowsAsyncOverride() throws Exception {
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false))).thenReturn("job-2");

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

        var sourcesCaptor = ArgumentCaptor.forClass(List.class);
        verify(ingestJobService).submitSources(eq("default"), sourcesCaptor.capture(), any(), eq(false));

        var sources = (List<RawDocumentSource>) sourcesCaptor.getValue();
        assertThat(sources).hasSize(2);
        assertThat(sources)
            .extracting(RawDocumentSource::fileName)
            .containsExactly("alice.txt", "notes.md");
    }

    @Test
    void uploadsDocxAsRawSourceAndKeepsBusinessDefaults() throws Exception {
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false))).thenReturn("job-docx");

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "contract.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "fake-docx".getBytes(StandardCharsets.UTF_8)
                ))
                .param("async", "false"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-docx"))
            .andExpect(jsonPath("$.documentIds[0]").isNotEmpty());

        var sourcesCaptor = ArgumentCaptor.forClass(List.class);
        var optionsCaptor = ArgumentCaptor.forClass(DocumentIngestOptions.class);
        verify(ingestJobService).submitSources(eq("default"), sourcesCaptor.capture(), optionsCaptor.capture(), eq(false));

        var sources = (List<RawDocumentSource>) sourcesCaptor.getValue();
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).fileName()).isEqualTo("contract.docx");
        assertThat(sources.get(0).mediaType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(optionsCaptor.getValue()).isNotNull();
    }

    @Test
    void allowsPresetOverridePerUploadRequest() throws Exception {
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false))).thenReturn("job-law");

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "law.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    new byte[] {1, 2, 3}
                ))
                .param("preset", "LAW")
                .param("async", "false"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-law"));

        var optionsCaptor = ArgumentCaptor.forClass(DocumentIngestOptions.class);
        verify(ingestJobService).submitSources(eq("default"), any(), optionsCaptor.capture(), eq(false));

        assertThat(optionsCaptor.getValue().documentTypeHint()).isEqualTo(io.github.lightrag.indexing.DocumentTypeHint.LAW);
        assertThat(optionsCaptor.getValue().chunkGranularity()).isEqualTo(io.github.lightrag.api.ChunkGranularity.MEDIUM);
        assertThat(optionsCaptor.getValue().parentChildProfile().enabled()).isTrue();
    }

    @Test
    void usesConfiguredDefaultPresetWhenNoOverrideIsProvided() throws Exception {
        lightRagProperties.getIndexing().getIngest().setPreset(IngestPreset.QA);
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false))).thenReturn("job-qa");

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "faq.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    new byte[] {1, 2, 3}
                ))
                .param("async", "false"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-qa"));

        var optionsCaptor = ArgumentCaptor.forClass(DocumentIngestOptions.class);
        verify(ingestJobService).submitSources(eq("default"), any(), optionsCaptor.capture(), eq(false));

        assertThat(optionsCaptor.getValue().documentTypeHint()).isEqualTo(io.github.lightrag.indexing.DocumentTypeHint.QA);
        assertThat(optionsCaptor.getValue().chunkGranularity()).isEqualTo(io.github.lightrag.api.ChunkGranularity.MEDIUM);
        assertThat(optionsCaptor.getValue().parentChildProfile().enabled()).isTrue();
    }

    @Test
    void keepsLegacyIngestPropertyOverridesCompatible() throws Exception {
        lightRagProperties.getIndexing().getIngest().setDocumentType("LAW");
        lightRagProperties.getIndexing().getIngest().setChunkGranularity("MEDIUM");
        lightRagProperties.getIndexing().getIngest().setParentChildEnabled(true);
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false))).thenReturn("job-legacy");

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "legacy.pdf",
                    MediaType.APPLICATION_PDF_VALUE,
                    new byte[] {1, 2, 3}
                ))
                .param("async", "false"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-legacy"));

        var optionsCaptor = ArgumentCaptor.forClass(DocumentIngestOptions.class);
        verify(ingestJobService).submitSources(eq("default"), any(), optionsCaptor.capture(), eq(false));

        assertThat(optionsCaptor.getValue().documentTypeHint()).isEqualTo(io.github.lightrag.indexing.DocumentTypeHint.LAW);
        assertThat(optionsCaptor.getValue().chunkGranularity()).isEqualTo(io.github.lightrag.api.ChunkGranularity.MEDIUM);
        assertThat(optionsCaptor.getValue().parentChildProfile().enabled()).isTrue();
    }

    @Test
    void surfacesParserFailureWhenSyncUploadThrows() throws Exception {
        when(ingestJobService.submitSources(eq("default"), any(), any(), eq(false)))
            .thenThrow(new IllegalArgumentException("MinerU provider is not configured"));

        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "scan.png",
                    MediaType.IMAGE_PNG_VALUE,
                    new byte[] {1, 2, 3}
                ))
                .param("async", "false"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("MinerU provider is not configured"));
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsUnsupportedFileExtension() throws Exception {
        mockMvc.perform(multipart("/documents/upload")
                .file(new MockMultipartFile(
                    "files",
                    "paper.exe",
                    MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    "oops".getBytes(StandardCharsets.UTF_8)
                )))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("unsupported file type: paper.exe"));

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
    }

    @Test
    void rejectsMissingFilesPart() throws Exception {
        mockMvc.perform(multipart("/documents/upload"))
            .andExpect(status().isBadRequest());

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
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

        verify(ingestJobService, never()).submitSources(anyString(), any(), any(), anyBoolean());
    }
}
