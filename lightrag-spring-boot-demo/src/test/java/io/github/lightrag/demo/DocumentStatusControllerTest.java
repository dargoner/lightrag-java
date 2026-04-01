package io.github.lightrag.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.DocumentProcessingStatus;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.LightRag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
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
    private static final String SECOND_INGEST_PAYLOAD = """
        {
          "documents": [
            {
              "id": "doc-2",
              "title": "Second Title",
              "content": "Bob works with Carol",
              "metadata": {"source": "demo"}
            }
          ]
        }
        """;
    private static final String THIRD_INGEST_PAYLOAD = """
        {
          "documents": [
            {
              "id": "doc-3",
              "title": "Third Title",
              "content": "Carol works with Dave",
              "metadata": {"source": "demo"}
            }
          ]
        }
        """;
    private static final String BLOCKING_INGEST_PAYLOAD = """
        {
          "documents": [
            {
              "id": "doc-block",
              "title": "Blocking Title",
              "content": "Block the worker thread",
              "metadata": {"source": "demo"}
            }
          ]
        }
        """;
    private static final String FAILING_INGEST_PAYLOAD = """
        {
          "documents": [
            {
              "id": "doc-fail",
              "title": "Broken Title",
              "content": "This document should fail",
              "metadata": {"source": "demo"}
            }
          ]
        }
        """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestJobService ingestJobService;

    @MockBean
    private LightRag lightRag;

    @BeforeEach
    void setUp() {
        ((ConcurrentMap<?, ?>) ReflectionTestUtils.getField(ingestJobService, "jobs")).clear();
        ((AtomicLong) ReflectionTestUtils.getField(ingestJobService, "sequence")).set(0L);
    }

    @Test
    void jobLifecycleAndStatusEndpoints() throws Exception {
        var deleted = new AtomicBoolean(false);
        doNothing().when(lightRag).ingest(eq(WORKSPACE_STATUS), argThat(documents ->
            documents.size() == 1 && "doc-1".equals(documents.get(0).id())
        ));
        when(lightRag.listDocumentStatuses(WORKSPACE_STATUS)).thenReturn(List.of(
            new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunk", null)
        ));
        when(lightRag.getDocumentStatus(WORKSPACE_STATUS, "doc-1")).thenAnswer(invocation -> {
            if (deleted.get()) {
                throw new NoSuchElementException("missing doc-1");
            }
            return new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunk", null);
        });
        doAnswer(invocation -> {
            deleted.set(true);
            return null;
        }).when(lightRag).deleteByDocumentId(WORKSPACE_STATUS, "doc-1");

        var jobId = submitJob(WORKSPACE_STATUS, INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, jobId, "SUCCEEDED");

        mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.documentCount").value(1))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.finishedAt").isNotEmpty())
            .andExpect(jsonPath("$.cancellable").value(false))
            .andExpect(jsonPath("$.retriable").value(false))
            .andExpect(jsonPath("$.attempt").value(1));

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
        when(lightRag.getDocumentStatus(WORKSPACE_STATUS, "missing-doc"))
            .thenThrow(new NoSuchElementException("missing-doc"));

        mockMvc.perform(get("/documents/jobs/{jobId}", "missing-job")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/documents/status/{documentId}", "missing-doc")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isNotFound());
    }

    @Test
    void jobsEndpointReturnsPaginatedNewestFirstWithTimelineFields() throws Exception {
        doNothing().when(lightRag).ingest(eq(WORKSPACE_STATUS), argThat(documents -> documents.size() == 1));

        var firstJobId = submitJob(WORKSPACE_STATUS, INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, firstJobId, "SUCCEEDED");

        var secondJobId = submitJob(WORKSPACE_STATUS, SECOND_INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, secondJobId, "SUCCEEDED");

        var thirdJobId = submitJob(WORKSPACE_STATUS, THIRD_INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, thirdJobId, "SUCCEEDED");

        mockMvc.perform(get("/documents/jobs")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS)
                .queryParam("page", "0")
                .queryParam("size", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items[0].jobId").value(thirdJobId))
            .andExpect(jsonPath("$.items[1].jobId").value(secondJobId))
            .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
            .andExpect(jsonPath("$.items[0].startedAt").isNotEmpty())
            .andExpect(jsonPath("$.items[0].finishedAt").isNotEmpty())
            .andExpect(jsonPath("$.items[0].documentCount").value(1));
    }

    @Test
    void failedJobExposesErrorMessageInDetailAndListResponses() throws Exception {
        doThrow(new IllegalStateException("simulated ingest failure"))
            .when(lightRag).ingest(eq(WORKSPACE_STATUS), argThat(documents ->
                documents.size() == 1 && "doc-fail".equals(documents.get(0).id())
            ));

        var failedJobId = submitJob(WORKSPACE_STATUS, FAILING_INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, failedJobId, "FAILED");

        mockMvc.perform(get("/documents/jobs/{jobId}", failedJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorMessage").value("simulated ingest failure"))
            .andExpect(jsonPath("$.retriable").value(true))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        mockMvc.perform(get("/documents/jobs")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS)
                .queryParam("page", "0")
                .queryParam("size", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].jobId").value(failedJobId))
            .andExpect(jsonPath("$.items[0].status").value("FAILED"))
            .andExpect(jsonPath("$.items[0].errorMessage").value("simulated ingest failure"));
    }

    @Test
    void cancelPendingJobAndRetryCreatesNewAttempt() throws Exception {
        var firstJobStarted = new CountDownLatch(1);
        var releaseFirstJob = new CountDownLatch(1);
        doAnswer(invocation -> {
            var documents = invocation.getArgument(1, List.class);
            var firstDocument = (io.github.lightrag.types.Document) documents.get(0);
            if ("doc-block".equals(firstDocument.id())) {
                firstJobStarted.countDown();
                if (!releaseFirstJob.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release blocking job");
                }
            }
            return null;
        }).when(lightRag).ingest(eq(WORKSPACE_STATUS), anyList());

        var blockingJobId = submitJob(WORKSPACE_STATUS, BLOCKING_INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, blockingJobId, "RUNNING");
        if (!firstJobStarted.await(1, TimeUnit.SECONDS)) {
            fail("blocking job did not start");
        }

        var pendingJobId = submitJob(WORKSPACE_STATUS, SECOND_INGEST_PAYLOAD);

        mockMvc.perform(post("/documents/jobs/{jobId}/cancel", pendingJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(pendingJobId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellable").value(false))
            .andExpect(jsonPath("$.retriable").value(true))
            .andExpect(jsonPath("$.attempt").value(1));

        mockMvc.perform(get("/documents/jobs/{jobId}", pendingJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.errorMessage").value("job cancelled"));

        var retryResult = mockMvc.perform(post("/documents/jobs/{jobId}/retry", pendingJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retriedFromJobId").value(pendingJobId))
            .andExpect(jsonPath("$.attempt").value(2))
            .andReturn();
        var retriedJobId = objectMapper.readTree(retryResult.getResponse().getContentAsString()).get("jobId").asText();

        releaseFirstJob.countDown();
        awaitJobStatus(WORKSPACE_STATUS, blockingJobId, "SUCCEEDED");
        awaitJobStatus(WORKSPACE_STATUS, retriedJobId, "SUCCEEDED");
    }

    @Test
    void retryFailedJobCreatesNewAttemptAndRejectsSucceededTransitions() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            var documents = invocation.getArgument(1, List.class);
            var firstDocument = (io.github.lightrag.types.Document) documents.get(0);
            if ("doc-fail".equals(firstDocument.id()) && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated ingest failure");
            }
            return null;
        }).when(lightRag).ingest(eq(WORKSPACE_STATUS), anyList());

        var failedJobId = submitJob(WORKSPACE_STATUS, FAILING_INGEST_PAYLOAD);
        awaitJobStatus(WORKSPACE_STATUS, failedJobId, "FAILED");

        var retriedResult = mockMvc.perform(post("/documents/jobs/{jobId}/retry", failedJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retriedFromJobId").value(failedJobId))
            .andExpect(jsonPath("$.attempt").value(2))
            .andReturn();
        var retriedJobId = objectMapper.readTree(retriedResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobStatus(WORKSPACE_STATUS, retriedJobId, "SUCCEEDED");

        mockMvc.perform(post("/documents/jobs/{jobId}/cancel", retriedJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("job is not cancellable: " + retriedJobId));

        mockMvc.perform(post("/documents/jobs/{jobId}/retry", retriedJobId)
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("job is not retriable: " + retriedJobId));
    }

    @Test
    void rejectsInvalidJobPaginationParams() throws Exception {
        mockMvc.perform(get("/documents/jobs")
                .header(WORKSPACE_HEADER, WORKSPACE_STATUS)
                .queryParam("page", "-1")
                .queryParam("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("page must be greater than or equal to 0"));
    }

    @Test
    void isolatesJobsStatusesAndControlsAcrossWorkspaces() throws Exception {
        doNothing().when(lightRag).ingest(eq(WORKSPACE_ISOLATED), argThat(documents ->
            documents.size() == 1 && "doc-ws-a".equals(documents.get(0).id())
        ));
        when(lightRag.listDocumentStatuses(WORKSPACE_ISOLATED)).thenReturn(List.of(
            new DocumentProcessingStatus("doc-ws-a", DocumentStatus.PROCESSED, "processed 1 chunk", null)
        ));
        when(lightRag.listDocumentStatuses(WORKSPACE_B)).thenReturn(List.of());

        var jobId = submitJob(WORKSPACE_ISOLATED, """
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
            """);
        awaitJobStatus(WORKSPACE_ISOLATED, jobId, "SUCCEEDED");

        mockMvc.perform(get("/documents/status")
                .header(WORKSPACE_HEADER, WORKSPACE_ISOLATED))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.documentId == 'doc-ws-a')]").exists());

        mockMvc.perform(get("/documents/status")
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/documents/jobs/{jobId}/cancel", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/documents/jobs/{jobId}/retry", jobId)
                .header(WORKSPACE_HEADER, WORKSPACE_B))
            .andExpect(status().isNotFound());
    }

    private String submitJob(String workspaceId, String payload) throws Exception {
        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .header(WORKSPACE_HEADER, workspaceId)
                .contentType(APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();
        return objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
    }

    private void awaitJobStatus(String workspaceId, String jobId, String expectedStatus) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            var jobResult = mockMvc.perform(get("/documents/jobs/{jobId}", jobId)
                    .header(WORKSPACE_HEADER, workspaceId))
                .andExpect(status().isOk())
                .andReturn();
            var statusValue = objectMapper.readTree(jobResult.getResponse().getContentAsString()).get("status").asText();
            if (expectedStatus.equals(statusValue)) {
                return;
            }
            Thread.sleep(50L);
        }
        fail("job did not reach " + expectedStatus + " before timeout");
    }
}
