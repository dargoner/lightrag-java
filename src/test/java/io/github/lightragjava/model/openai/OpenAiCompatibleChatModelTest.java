package io.github.lightragjava.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ModelException;
import io.github.lightragjava.model.ChatModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

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
}
