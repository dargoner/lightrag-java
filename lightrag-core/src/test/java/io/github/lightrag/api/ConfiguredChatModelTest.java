package io.github.lightrag.api;

import io.github.lightrag.model.CachedChatModel;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.model.ChatResponse;
import io.github.lightrag.model.CloseableIterator;
import io.github.lightrag.storage.memory.InMemoryLlmCacheStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredChatModelTest {
    @Test
    void builderDefaultsMergeIntoRequestsAndRequestFieldsWin() {
        var delegate = new RecordingChatModel();
        var model = new ConfiguredChatModel(
            delegate,
            new ChatRequestOptions(0.2d, 512, null, "json_object")
        );

        model.generate(new ChatModel.ChatRequest("system", "user",
            new ChatRequestOptions(null, 64, null, null)));

        assertThat(delegate.requests()).hasSize(1);
        assertThat(delegate.requests().get(0).options())
            .isEqualTo(new ChatRequestOptions(0.2d, 64, null, "json_object"));
    }

    @Test
    void requestsAlreadyCarryingEveryDefaultPassThroughUnchanged() {
        var delegate = new RecordingChatModel();
        var model = new ConfiguredChatModel(delegate, new ChatRequestOptions(0.2d, null, null, null));
        var request = new ChatModel.ChatRequest("system", "user",
            new ChatRequestOptions(0.2d, null, null, null));

        model.generate(request);

        assertThat(delegate.requests().get(0)).isSameAs(request);
    }

    @Test
    void streamingDelegatesToTheWrappedModelWithMergedOptions() {
        var delegate = new RecordingChatModel();
        var model = new ConfiguredChatModel(delegate, new ChatRequestOptions(null, null, null, "json_object"));

        try (var stream = model.stream(new ChatModel.ChatRequest("system", "user"))) {
            assertThat(stream.hasNext()).isTrue();
            assertThat(stream.next()).isEqualTo("streamed");
        }

        assertThat(delegate.streamCalls()).isEqualTo(1);
        assertThat(delegate.requests().get(0).options())
            .isEqualTo(new ChatRequestOptions(null, null, null, "json_object"));
    }

    @Test
    void responseMetadataAndCacheIdentityFlowThroughTheWrapper() {
        var delegate = new RecordingChatModel();
        var model = new ConfiguredChatModel(delegate, new ChatRequestOptions(null, 512, null, null));

        var response = model.generateResponse(new ChatModel.ChatRequest("system", "user"));

        assertThat(response.content()).isEqualTo("partial");
        assertThat(response.truncated()).isTrue();
        assertThat(response.usage().completionTokens()).isEqualTo(3);
        assertThat(delegate.requests().get(0).options())
            .isEqualTo(new ChatRequestOptions(null, 512, null, null));
        assertThat(model.cacheIdentity())
            .isEqualTo("recording:metadata|defaults:t=n,max_tokens=v:512,top_p=n,format=n");
    }

    @Test
    void emptyDefaultsLeaveTheCacheIdentityUntouched() {
        var model = new ConfiguredChatModel(new RecordingChatModel(), ChatRequestOptions.NONE);

        assertThat(model.cacheIdentity()).isEqualTo("recording:metadata");
    }

    @Test
    void differentlyConfiguredDefaultsDoNotShareCachedAnswers() {
        var store = new InMemoryLlmCacheStore(new ReentrantReadWriteLock());
        var request = new ChatModel.ChatRequest("system", "user");
        var cold = new CachedChatModel("query",
            new ConfiguredChatModel(ignored -> "cold", new ChatRequestOptions(0.1d, null, null, null)), store);
        var warm = new CachedChatModel("query",
            new ConfiguredChatModel(ignored -> "warm", new ChatRequestOptions(0.9d, null, null, null)), store);

        assertThat(cold.generate(request)).isEqualTo("cold");
        assertThat(warm.generate(request)).isEqualTo("warm");
        assertThat(cold.generate(request)).isEqualTo("cold");
        assertThat(warm.generate(request)).isEqualTo("warm");
    }

    @Test
    void identicalDefaultsKeepSharingCachedAnswers() {
        var store = new InMemoryLlmCacheStore(new ReentrantReadWriteLock());
        var request = new ChatModel.ChatRequest("system", "user");
        var defaults = new ChatRequestOptions(null, 256, null, null);
        var secondCalls = new int[1];
        var first = new CachedChatModel("query",
            new ConfiguredChatModel(ignored -> "first", defaults), store);
        var second = new CachedChatModel("query", new ConfiguredChatModel(ignored -> {
            secondCalls[0]++;
            return "second";
        }, defaults), store);

        assertThat(first.generate(request)).isEqualTo("first");
        assertThat(second.generate(request)).isEqualTo("first");
        assertThat(secondCalls[0]).isZero();
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        private int streamCalls;

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return "ok";
        }

        @Override
        public ChatResponse generateResponse(ChatRequest request) {
            requests.add(request);
            return new ChatResponse("partial", "length", new ChatResponse.Usage(10, 3));
        }

        @Override
        public String cacheIdentity() {
            return "recording:metadata";
        }

        @Override
        public CloseableIterator<String> stream(ChatRequest request) {
            requests.add(request);
            streamCalls++;
            return CloseableIterator.of(List.of("streamed"));
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }

        int streamCalls() {
            return streamCalls;
        }
    }
}
