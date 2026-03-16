package io.github.lightragjava.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ModelException;
import io.github.lightragjava.model.ChatModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleChatModelTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void chatAdapterSendsOpenAiCompatibleRequestPayload() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Answer"
                      }
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            model.generate(new ChatModel.ChatRequest("System prompt", "User prompt"));

            var request = server.takeRequest();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer secret");
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("model").asText()).isEqualTo("gpt-test");
            assertThat(payload.path("messages")).hasSize(2);
            assertThat(payload.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("System prompt");
            assertThat(payload.path("messages").get(1).path("role").asText()).isEqualTo("user");
            assertThat(payload.path("messages").get(1).path("content").asText()).isEqualTo("User prompt");
        }
    }

    @Test
    void chatAdapterSendsConversationHistoryBeforeCurrentUserMessage() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Answer"
                      }
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            model.generate(new ChatModel.ChatRequest(
                "System prompt",
                "Current user prompt",
                List.of(
                    new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                    new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
                )
            ));

            var request = server.takeRequest();
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("messages")).hasSize(4);
            assertThat(payload.path("messages").get(0).path("role").asText()).isEqualTo("system");
            assertThat(payload.path("messages").get(1).path("role").asText()).isEqualTo("user");
            assertThat(payload.path("messages").get(1).path("content").asText()).isEqualTo("Earlier question");
            assertThat(payload.path("messages").get(2).path("role").asText()).isEqualTo("assistant");
            assertThat(payload.path("messages").get(2).path("content").asText()).isEqualTo("Earlier answer");
            assertThat(payload.path("messages").get(3).path("role").asText()).isEqualTo("user");
            assertThat(payload.path("messages").get(3).path("content").asText()).isEqualTo("Current user prompt");
        }
    }

    @Test
    void chatAdapterOmitsBlankSystemMessage() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Answer"
                      }
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            model.generate(new ChatModel.ChatRequest(
                "",
                "Current user prompt",
                List.of(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"))
            ));

            var request = server.takeRequest();
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("messages")).hasSize(2);
            assertThat(payload.path("messages").get(0).path("role").asText()).isEqualTo("user");
            assertThat(payload.path("messages").get(0).path("content").asText()).isEqualTo("Earlier question");
            assertThat(payload.path("messages").get(1).path("role").asText()).isEqualTo("user");
            assertThat(payload.path("messages").get(1).path("content").asText()).isEqualTo("Current user prompt");
        }
    }

    @Test
    void chatAdapterParsesResponseContent() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {
                  "choices": [
                    {
                      "message": {
                        "content": "Parsed answer"
                      }
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            assertThat(model.generate(new ChatModel.ChatRequest("System prompt", "User prompt")))
                .isEqualTo("Parsed answer");
        }
    }

    @Test
    void chatAdapterStreamsSseFragmentsAndSetsStreamFlag() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                    data: {"choices":[{"delta":{"content":"Hello "}}]}

                    data: {"choices":[{"delta":{"content":"world"}}]}

                    data: [DONE]

                    """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            try (var stream = model.stream(new ChatModel.ChatRequest("System prompt", "User prompt"))) {
                assertThat(readAll(stream)).containsExactly("Hello ", "world");
            }

            var request = server.takeRequest();
            var payload = OBJECT_MAPPER.readTree(request.getBody().readUtf8());
            assertThat(payload.path("stream").asBoolean()).isTrue();
        }
    }

    @Test
    void chatAdapterAllowsEarlyCloseOfStreamingResponses() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                    data: {"choices":[{"delta":{"content":"Hello "}}]}

                    data: {"choices":[{"delta":{"content":"world"}}]}

                    data: [DONE]

                    """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            try (var stream = model.stream(new ChatModel.ChatRequest("System prompt", "User prompt"))) {
                assertThat(stream.hasNext()).isTrue();
                assertThat(stream.next()).isEqualTo("Hello ");
                stream.close();
                assertThat(stream.hasNext()).isFalse();
            }
        }
    }

    @Test
    void chatAdapterSupportsMultilineSseDataEvents() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                    data: {"choices":[{"delta":{
                    data: "content":"Hello from multiline"
                    data: }}]}

                    data: [DONE]

                    """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            try (var stream = model.stream(new ChatModel.ChatRequest("System prompt", "User prompt"))) {
                assertThat(readAll(stream)).containsExactly("Hello from multiline");
            }
        }
    }

    @Test
    void non2xxResponsesRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            assertThatThrownBy(() -> model.generate(new ChatModel.ChatRequest("System prompt", "User prompt")))
                .isInstanceOf(ModelException.class)
                .hasMessageContaining("401");
        }
    }

    @Test
    void malformedJsonOrMissingRequiredFieldsRaiseModelException() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{not json"));
            server.enqueue(new MockResponse().setBody("""
                {
                  "choices": [
                    {
                      "message": {}
                    }
                  ]
                }
                """));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-test", "secret");

            assertThatThrownBy(() -> model.generate(new ChatModel.ChatRequest("System prompt", "User prompt")))
                .isInstanceOf(ModelException.class);
            assertThatThrownBy(() -> model.generate(new ChatModel.ChatRequest("System prompt", "User prompt")))
                .isInstanceOf(ModelException.class);
        }
    }

    @Test
    void chatAdapterSupportsCustomRequestTimeout() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setBody("""
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "Delayed answer"
                          }
                        }
                      ]
                    }
                    """)
                .setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
            server.start();
            var model = new OpenAiCompatibleChatModel(
                server.url("/v1/").toString(),
                "gpt-test",
                "secret",
                Duration.ofSeconds(5)
            );

            assertThat(model.generate(new ChatModel.ChatRequest("System prompt", "User prompt")))
                .isEqualTo("Delayed answer");
        }
    }

    private static List<String> readAll(java.util.Iterator<String> iterator) {
        var values = new java.util.ArrayList<String>();
        while (iterator.hasNext()) {
            values.add(iterator.next());
        }
        return values;
    }
}
