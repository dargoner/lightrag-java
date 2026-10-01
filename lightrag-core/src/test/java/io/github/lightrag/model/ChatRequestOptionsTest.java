package io.github.lightrag.model;

import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.model.openai.OpenAiCompatibleChatModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestOptionsTest {
    @Test
    void requestOptionsAreSerializedIntoTheProviderPayload() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"Answer"}}]}"""));
            server.start();
            var model = new OpenAiCompatibleChatModel(
                server.url("/v1/").toString(),
                "gpt-4o-mini",
                "secret",
                Duration.ofSeconds(5),
                new ChatRequestOptions(0.2d, 512, 0.9d, "json_object")
            );

            model.generate(new ChatRequest("system", "user"));

            assertThat(server.takeRequest().getBody().readUtf8())
                .contains("\"temperature\":0.2")
                .contains("\"max_tokens\":512")
                .contains("\"top_p\":0.9")
                .contains("\"response_format\":{\"type\":\"json_object\"}");
        }
    }

    @Test
    void perRequestOverridesWinOverModelDefaults() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"Answer"}}]}"""));
            server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"Answer"}}]}"""));
            server.start();
            var baseUrl = server.url("/v1/").toString();
            var timeout = Duration.ofSeconds(5);
            var model = new OpenAiCompatibleChatModel(baseUrl, "gpt-4o-mini", "secret", timeout,
                new ChatRequestOptions(null, 512, null, null));

            model.generate(new ChatRequest("system", "user",
                new ChatRequestOptions(null, 64, null, null)));

            var overrideBody = server.takeRequest().getBody().readUtf8();
            assertThat(overrideBody).contains("\"max_tokens\":64").doesNotContain("\"max_tokens\":512");

            var fallback = new OpenAiCompatibleChatModel(baseUrl, "gpt-4o-mini", "secret", timeout,
                new ChatRequestOptions(null, null, null, "json_object"));
            fallback.generate(new ChatRequest("system", "user",
                new ChatRequestOptions(0.2d, null, null, null)));

            var fallbackBody = server.takeRequest().getBody().readUtf8();
            assertThat(fallbackBody)
                .contains("\"temperature\":0.2")
                .contains("\"response_format\":{\"type\":\"json_object\"}");
        }
    }

    @Test
    void payloadOmitsUnsetOptions() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"Answer"}}]}"""));
            server.start();
            var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-4o-mini", "secret");

            model.generate(new ChatRequest("system", "user"));

            assertThat(server.takeRequest().getBody().readUtf8())
                .doesNotContain("\"temperature\"")
                .doesNotContain("\"max_tokens\"")
                .doesNotContain("\"top_p\"")
                .doesNotContain("\"response_format\"");
        }
    }

    @Test
    void mergeKeepsBaseFieldsAndPrefersOverrideFields() {
        var base = new ChatRequestOptions(0.2d, 512, 0.9d, "json_object");

        assertThat(base.merge(new ChatRequestOptions(0.7d, null, null, null)))
            .isEqualTo(new ChatRequestOptions(0.7d, 512, 0.9d, "json_object"));
        assertThat(base.merge(null)).isEqualTo(base);
        assertThat(ChatRequestOptions.NONE.merge(ChatRequestOptions.NONE)).isEqualTo(ChatRequestOptions.NONE);
    }

    @Test
    void requestsDefaultToNoOptions() {
        assertThat(new ChatRequest("system", "user").options()).isEqualTo(ChatRequestOptions.NONE);
        assertThat(new ChatRequest("system", "user", ChatRequestOptions.NONE).options())
            .isEqualTo(ChatRequestOptions.NONE);
    }

    @Test
    void optionsRejectOutOfRangeValues() {
        assertThatThrownBy(() -> new ChatRequestOptions(2.5d, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temperature");
        assertThatThrownBy(() -> new ChatRequestOptions(-0.1d, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temperature");
        assertThatThrownBy(() -> new ChatRequestOptions(null, 0, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTokens");
        assertThatThrownBy(() -> new ChatRequestOptions(null, null, 1.5d, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("topP");
        assertThatThrownBy(() -> new ChatRequestOptions(null, null, null, " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("responseFormat");
    }
}
