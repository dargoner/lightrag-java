package io.github.lightrag.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleEmbeddingModelTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void embeddingAdapterSendsOpenAiCompatibleRequestPayload() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "data": [
                    {
                      "embedding": [1.0, 0.0]
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleEmbeddingModel(server.url("/v1/").toString(), "text-embedding-test", "secret");

            model.embedAll(List.of("hello"));

            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/v1/embeddings");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret");
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("model").asText()).isEqualTo("text-embedding-test");
            assertThat(payload.path("input")).hasSize(1);
            assertThat(payload.path("input").get(0).asText()).isEqualTo("hello");
        }
    }

    @Test
    void embeddingAdapterParsesEmbeddingVectors() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "data": [
                    {
                      "embedding": [1.0, 0.0]
                    },
                    {
                      "embedding": [0.5, 0.5]
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleEmbeddingModel(server.url("/v1/").toString(), "text-embedding-test", "secret");

            assertThat(model.embedAll(List.of("hello", "world")))
                .containsExactly(List.of(1.0d, 0.0d), List.of(0.5d, 0.5d));
        }
    }

    @Test
    void non2xxResponsesRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("x-request-id", "req-embed-500")
                .setBody("{\"error\":\"server\"}"));
            server.start();
            var model = singleAttemptEmbeddingModel(server, Duration.ofSeconds(30));

            assertThatThrownBy(() -> model.embedAll(List.of("hello")))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("server")
                .hasMessageContaining("/v1/embeddings")
                .hasMessageContaining("req-embed-500");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void malformedJsonOrMissingRequiredFieldsRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{not json"));
            server.enqueue(new MockResponse().setBody("""
                {
                  "data": [
                    {}
                  ]
                }
                """));
            server.start();
            var model = singleAttemptEmbeddingModel(server, Duration.ofSeconds(30));

            assertThatThrownBy(() -> model.embedAll(List.of("hello")))
                .isInstanceOf(ModelException.class);
            assertThatThrownBy(() -> model.embedAll(List.of("hello")))
                .isInstanceOf(ModelException.class);
        }
    }

    @Test
    void embeddingAdapterSupportsCustomRequestTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setBody("""
                    {
                      "data": [
                        {
                          "embedding": [1.0, 0.0]
                        }
                      ]
                    }
                    """)
                .setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
            server.start();
            var model = new OpenAiCompatibleEmbeddingModel(
                server.url("/v1/").toString(),
                "text-embedding-test",
                "secret",
                Duration.ofSeconds(5)
            );

            assertThat(model.embedAll(List.of("hello")))
                .containsExactly(List.of(1.0d, 0.0d));
        }
    }

    @Test
    void embeddingAdapterRaisesTimeoutExceptionWhenRequestExceedsTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setBody("""
                    {
                      "data": [
                        {
                          "embedding": [1.0, 0.0]
                        }
                      ]
                    }
                    """)
                .setBodyDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS));
            server.start();
            var model = singleAttemptEmbeddingModel(server, Duration.ofMillis(200));

            assertThatThrownBy(() -> model.embedAll(List.of("hello")))
                .isInstanceOf(ModelTimeoutException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("/v1/embeddings");
        }
    }

    @Test
    void retriesTransientServerErrorsUntilSuccess() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"server\"}"));
            server.enqueue(new MockResponse().setBody("""
                {
                  "data": [
                    {
                      "embedding": [1.0, 0.0]
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleEmbeddingModel(
                server.url("/v1/").toString(),
                "text-embedding-test",
                "secret",
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(1)
            );

            assertThat(model.embedAll(List.of("hello"))).containsExactly(List.of(1.0d, 0.0d));
            assertThat(server.getRequestCount()).isEqualTo(2);
        }
    }

    @Test
    void doesNotRetryPermanentRateLimitFailures() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"type\":\"budget_exceeded\"}}"));
            server.start();
            var model = new OpenAiCompatibleEmbeddingModel(
                server.url("/v1/").toString(),
                "text-embedding-test",
                "secret",
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(1)
            );

            assertThatThrownBy(() -> model.embedAll(List.of("hello")))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("budget_exceeded");
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    private static OpenAiCompatibleEmbeddingModel singleAttemptEmbeddingModel(
        MockWebServer server,
        Duration timeout
    ) {
        return new OpenAiCompatibleEmbeddingModel(
            server.url("/v1/").toString(),
            "text-embedding-test",
            "secret",
            timeout,
            1,
            Duration.ofMillis(1)
        );
    }
}
