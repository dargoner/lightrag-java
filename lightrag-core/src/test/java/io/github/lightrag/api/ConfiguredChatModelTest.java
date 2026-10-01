package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.model.CloseableIterator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = new ArrayList<>();
        private int streamCalls;

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return "ok";
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
