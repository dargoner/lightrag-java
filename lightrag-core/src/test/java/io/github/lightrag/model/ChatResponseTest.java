package io.github.lightrag.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatResponseTest {
    @Test
    void lengthFinishReasonMarksTheResponseAsTruncated() {
        assertThat(new ChatResponse("partial", "length", null).truncated()).isTrue();
        assertThat(new ChatResponse("partial", "LENGTH", null).truncated()).isTrue();
        assertThat(new ChatResponse("full", "stop", null).truncated()).isFalse();
        assertThat(ChatResponse.of("full").truncated()).isFalse();
    }

    @Test
    void usageCarriesPromptAndCompletionTokens() {
        var response = new ChatResponse("answer", "stop", new ChatResponse.Usage(12, 3));

        assertThat(response.usage().promptTokens()).isEqualTo(12);
        assertThat(response.usage().completionTokens()).isEqualTo(3);
    }

    @Test
    void contentIsRequired() {
        assertThatThrownBy(() -> new ChatResponse(null, "stop", null))
            .isInstanceOf(NullPointerException.class);
    }
}
