package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryingChatModelTest {
    @Test
    void forwardsTheDelegateCacheIdentity() {
        var model = new RetryingChatModel(new IdentityChatModel(), 3, Duration.ofMillis(1));

        assertThat(model.cacheIdentity()).isEqualTo("recording:identity");
    }

    private static final class IdentityChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return "ok";
        }

        @Override
        public String cacheIdentity() {
            return "recording:identity";
        }
    }
}
