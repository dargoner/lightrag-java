package io.github.lightrag.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import io.github.lightrag.model.RerankModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleRerankModelTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void mapsCohereStyleResultsBackToCandidateIds() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": 1,
                      "relevance_score": 0.91
                    },
                    {
                      "index": 0,
                      "relevance_score": 0.23
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleRerankModel(
                server.url("/v1/").toString(),
                "bge-reranker-v2",
                "secret",
                Duration.ofSeconds(30)
            );

            var results = model.rerank(new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one"),
                new RerankModel.RerankCandidate("c2", "text two")
            )));

            assertThat(results)
                .extracting(RerankModel.RerankResult::id)
                .containsExactly("c2", "c1");
            assertThat(results)
                .extracting(RerankModel.RerankResult::score)
                .containsExactly(0.91d, 0.23d);
            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/v1/rerank");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret");
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("model").asText()).isEqualTo("bge-reranker-v2");
            assertThat(payload.path("query").asText()).isEqualTo("query");
            assertThat(payload.path("top_n").asInt()).isEqualTo(2);
            assertThat(payload.path("documents")).hasSize(2);
            assertThat(payload.path("documents").get(0).asText()).isEqualTo("text one");
            assertThat(payload.path("documents").get(1).asText()).isEqualTo("text two");
        }
    }

    @Test
    void rejectsResponsesWithUnknownIndexesOrNonFiniteScores() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": 5,
                      "relevance_score": 0.9
                    }
                  ]
                }
                """));
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": 0,
                      "relevance_score": "high"
                    }
                  ]
                }
                """));
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": 0
                    }
                  ]
                }
                """));
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": true,
                      "relevance_score": 0.9
                    }
                  ]
                }
                """));
            server.start();
            var model = singleAttemptRerankModel(server, Duration.ofSeconds(30));
            var request = new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one")
            ));

            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("out of range");
            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("finite");
            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("finite");
            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("index");
        }
    }

    @Test
    void malformedJsonOrMissingResultsRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{not json"));
            server.enqueue(new MockResponse().setBody("""
                {
                  "meta": {}
                }
                """));
            server.start();
            var model = singleAttemptRerankModel(server, Duration.ofSeconds(30));
            var request = new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one")
            ));

            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class);
            assertThatThrownBy(() -> model.rerank(request))
                .isInstanceOf(ModelException.class);
        }
    }

    @Test
    void non2xxResponsesRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("x-request-id", "req-rerank-500")
                .setBody("{\"error\":\"server\"}"));
            server.start();
            var model = singleAttemptRerankModel(server, Duration.ofSeconds(30));

            assertThatThrownBy(() -> model.rerank(new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one")
            ))))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("server")
                .hasMessageContaining("/v1/rerank")
                .hasMessageContaining("req-rerank-500");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void rerankAdapterRaisesTimeoutExceptionWhenRequestExceedsTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setBody("""
                    {
                      "results": []
                    }
                    """)
                .setBodyDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS));
            server.start();
            var model = singleAttemptRerankModel(server, Duration.ofMillis(200));

            assertThatThrownBy(() -> model.rerank(new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one")
            ))))
                .isInstanceOf(ModelTimeoutException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("/v1/rerank");
        }
    }

    @Test
    void retriesTransientServerErrorsUntilSuccess() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"server\"}"));
            server.enqueue(new MockResponse().setBody("""
                {
                  "results": [
                    {
                      "index": 0,
                      "relevance_score": 0.5
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleRerankModel(
                server.url("/v1/").toString(),
                "bge-reranker-v2",
                "secret",
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(10)
            );

            assertThat(model.rerank(new RerankModel.RerankRequest("query", List.of(
                new RerankModel.RerankCandidate("c1", "text one")
            ))))
                .containsExactly(new RerankModel.RerankResult("c1", 0.5d));
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void skipsHttpCallsWhenThereAreNoCandidates() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var model = new OpenAiCompatibleRerankModel(
                server.url("/v1/").toString(),
                "bge-reranker-v2",
                "secret"
            );

            assertThat(model.rerank(new RerankModel.RerankRequest("query", List.of()))).isEmpty();
            assertThat(server.getRequestCount()).isZero();
        }
    }

    private static OpenAiCompatibleRerankModel singleAttemptRerankModel(MockWebServer server, Duration timeout) {
        return new OpenAiCompatibleRerankModel(
            server.url("/v1/").toString(),
            "bge-reranker-v2",
            "secret",
            timeout,
            1,
            Duration.ZERO
        );
    }
}
