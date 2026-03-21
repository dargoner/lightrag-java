package io.github.lightragjava.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.DocumentProcessingStatus;
import io.github.lightragjava.api.DocumentStatus;
import io.github.lightragjava.api.LightRag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
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
    void resetInMemoryJobs() {
        ((ConcurrentMap<?, ?>) ReflectionTestUtils.getField(ingestJobService, "jobs")).clear();
        ((AtomicLong) ReflectionTestUtils.getField(ingestJobService, "sequence")).set(0L);
    }

    @Test
    void jobLifecycleAndStatusEndpoints() throws Exception {
        var deleted = new AtomicBoolean(false);
        doNothing().when(lightRag).ingest(argThat(documents ->
            documents.size() == 1 && "doc-1".equals(documents.get(0).id())
        ));
        when(lightRag.listDocumentStatuses()).thenReturn(List.of(
            new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunk", null)
        ));
        when(lightRag.getDocumentStatus("doc-1")).thenAnswer(invocation -> {
            if (deleted.get()) {
                throw new NoSuchElementException("missing doc-1");
            }
            return new DocumentProcessingStatus("doc-1", DocumentStatus.PROCESSED, "processed 1 chunk", null);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            deleted.set(true);
            return null;
        }).when(lightRag).deleteByDocumentId("doc-1");

        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .contentType(APPLICATION_JSON)
                .content(INGEST_PAYLOAD))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();

        var jobId = objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobStatus(jobId, "SUCCEEDED");

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
        org.mockito.Mockito.doThrow(new NoSuchElementException("missing-doc"))
            .when(lightRag).getDocumentStatus("missing-doc");

        mockMvc.perform(get("/documents/jobs/{jobId}", "missing-job"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/documents/status/{documentId}", "missing-doc"))
            .andExpect(status().isNotFound());
    }

    @Test
    void jobsEndpointReturnsPaginatedNewestFirstWithTimelineFields() throws Exception {
        doNothing().when(lightRag).ingest(argThat(documents -> documents.size() == 1));

        var firstJobId = submitJob(INGEST_PAYLOAD);
        awaitJobStatus(firstJobId, "SUCCEEDED");
        Thread.sleep(5L);

        var secondJobId = submitJob(SECOND_INGEST_PAYLOAD);
        awaitJobStatus(secondJobId, "SUCCEEDED");
        Thread.sleep(5L);

        var thirdJobId = submitJob(THIRD_INGEST_PAYLOAD);
        awaitJobStatus(thirdJobId, "SUCCEEDED");

        mockMvc.perform(get("/documents/jobs")
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
            .when(lightRag).ingest(argThat(documents ->
                documents.size() == 1 && "doc-fail".equals(documents.get(0).id())
            ));

        var failedJobId = submitJob(FAILING_INGEST_PAYLOAD);
        awaitJobStatus(failedJobId, "FAILED");

        mockMvc.perform(get("/documents/jobs/{jobId}", failedJobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.errorMessage").value("simulated ingest failure"))
            .andExpect(jsonPath("$.createdAt").isNotEmpty())
            .andExpect(jsonPath("$.startedAt").isNotEmpty())
            .andExpect(jsonPath("$.finishedAt").isNotEmpty());

        mockMvc.perform(get("/documents/jobs")
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
            var documents = invocation.getArgument(0, List.class);
            var firstDocument = (io.github.lightragjava.types.Document) documents.get(0);
            if ("doc-block".equals(firstDocument.id())) {
                firstJobStarted.countDown();
                if (!releaseFirstJob.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release blocking job");
                }
            }
            return null;
        }).when(lightRag).ingest(anyList());

        var blockingJobId = submitJob(BLOCKING_INGEST_PAYLOAD);
        awaitJobStatus(blockingJobId, "RUNNING");
        if (!firstJobStarted.await(1, TimeUnit.SECONDS)) {
            fail("blocking job did not start");
        }

        var pendingJobId = submitJob(SECOND_INGEST_PAYLOAD);

        mockMvc.perform(post("/documents/jobs/{jobId}/cancel", pendingJobId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value(pendingJobId))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancellable").value(false))
            .andExpect(jsonPath("$.retriable").value(true))
            .andExpect(jsonPath("$.attempt").value(1));

        mockMvc.perform(get("/documents/jobs/{jobId}", pendingJobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.errorMessage").value("job cancelled"));

        var retryResult = mockMvc.perform(post("/documents/jobs/{jobId}/retry", pendingJobId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retriedFromJobId").value(pendingJobId))
            .andExpect(jsonPath("$.attempt").value(2))
            .andReturn();
        var retriedJobId = objectMapper.readTree(retryResult.getResponse().getContentAsString()).get("jobId").asText();

        releaseFirstJob.countDown();
        awaitJobStatus(blockingJobId, "SUCCEEDED");
        awaitJobStatus(retriedJobId, "SUCCEEDED");
    }

    @Test
    void retryFailedJobCreatesNewAttemptAndRejectsSucceededTransitions() throws Exception {
        var attempts = new AtomicInteger();
        doAnswer(invocation -> {
            var documents = invocation.getArgument(0, List.class);
            var firstDocument = (io.github.lightragjava.types.Document) documents.get(0);
            if ("doc-fail".equals(firstDocument.id()) && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated ingest failure");
            }
            return null;
        }).when(lightRag).ingest(anyList());

        var failedJobId = submitJob(FAILING_INGEST_PAYLOAD);
        awaitJobStatus(failedJobId, "FAILED");

        var retriedResult = mockMvc.perform(post("/documents/jobs/{jobId}/retry", failedJobId))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.retriedFromJobId").value(failedJobId))
            .andExpect(jsonPath("$.attempt").value(2))
            .andReturn();
        var retriedJobId = objectMapper.readTree(retriedResult.getResponse().getContentAsString()).get("jobId").asText();
        awaitJobStatus(retriedJobId, "SUCCEEDED");

        mockMvc.perform(post("/documents/jobs/{jobId}/cancel", retriedJobId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("job is not cancellable: " + retriedJobId));

        mockMvc.perform(post("/documents/jobs/{jobId}/retry", retriedJobId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("job is not retriable: " + retriedJobId));
    }

    @Test
    void rejectsInvalidJobPaginationParams() throws Exception {
        mockMvc.perform(get("/documents/jobs")
                .queryParam("page", "-1")
                .queryParam("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("page must be greater than or equal to 0"));
    }

    private String submitJob(String payload) throws Exception {
        var ingestResult = mockMvc.perform(post("/documents/ingest")
                .contentType(APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn();
        return objectMapper.readTree(ingestResult.getResponse().getContentAsString()).get("jobId").asText();
    }

    private void awaitJobStatus(String jobId, String expectedStatus) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            var jobResult = mockMvc.perform(get("/documents/jobs/{jobId}", jobId))
                .andExpect(status().isOk())
                .andReturn();
            var statusValue = objectMapper.readTree(jobResult.getResponse().getContentAsString()).get("status").asText();
            if (expectedStatus.equals(statusValue)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("job did not reach " + expectedStatus + " before timeout");
    }
}
