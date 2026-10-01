package io.github.lightrag.model;

import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.storage.LlmCacheStore;
import io.github.lightrag.storage.memory.InMemoryLlmCacheStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

class CachedChatModelTest {
    private final LlmCacheStore store = new InMemoryLlmCacheStore(new ReentrantReadWriteLock());

    @Test
    void cachesCompleteResponsesAndReplaysThemWithoutCallingTheDelegateAgain() {
        var delegate = new StubChatModel(new ChatResponse("answer", "stop", null));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("system", "user");

        assertThat(cached.generate(request)).isEqualTo("answer");
        assertThat(cached.generate(request)).isEqualTo("answer");

        assertThat(delegate.calls()).isEqualTo(1);
        assertThat(store.contains(CachedChatModel.cacheId("extract", request))).isTrue();
    }

    @Test
    void doesNotCacheTruncatedResponses() {
        var delegate = new StubChatModel(new ChatResponse("partial", "length", new ChatResponse.Usage(12, 3)));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("s", "u");

        var response = cached.generateResponse(request);

        assertThat(response.truncated()).isTrue();
        assertThat(response.content()).isEqualTo("partial");
        assertThat(response.usage().completionTokens()).isEqualTo(3);
        assertThat(store.load(CachedChatModel.cacheId("extract", request))).isEmpty();

        cached.generateResponse(request);
        assertThat(delegate.calls()).isEqualTo(2);
    }

    @Test
    void generateResponseReplaysCompleteResponsesFromTheCache() {
        var delegate = new StubChatModel(new ChatResponse("answer", "stop", null));
        var cached = new CachedChatModel("extract", delegate, store);
        var request = new ChatRequest("s", "u");

        cached.generateResponse(request);
        var replayed = cached.generateResponse(request);

        assertThat(replayed.content()).isEqualTo("answer");
        assertThat(replayed.truncated()).isFalse();
        assertThat(delegate.calls()).isEqualTo(1);
    }

    private static final class StubChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private StubChatModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            return generateResponse(request).content();
        }

        @Override
        public ChatResponse generateResponse(ChatRequest request) {
            requests.add(request);
            return responses.get(Math.min(requests.size() - 1, responses.size() - 1));
        }

        int calls() {
            return requests.size();
        }
    }
}
