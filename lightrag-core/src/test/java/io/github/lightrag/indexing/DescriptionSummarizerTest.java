package io.github.lightrag.indexing;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.HeuristicTokenCounter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptionSummarizerTest {
    @Test
    void joinsFragmentsWithoutLlmBelowBothThresholds() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 8, 1_200, 12_000);

        var result = summarizer.summarize(
            "Entity",
            "trade tariff",
            List.of("first fragment", "second fragment", "first fragment")
        );

        assertThat(result.description()).isEqualTo("first fragment<SEP>second fragment");
        assertThat(result.llmUsed()).isFalse();
        assertThat(model.generateCalls()).isZero();
    }

    @Test
    void summarizesWithLlmAtTheForceThreshold() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 8, 1_200, 12_000);
        var fragments = IntStream.rangeClosed(1, 8).mapToObj(index -> "fragment " + index).toList();

        var result = summarizer.summarize("Entity", "trade tariff", fragments);

        assertThat(result.description()).isEqualTo("SUMMARY");
        assertThat(result.llmUsed()).isTrue();
        assertThat(model.generateCalls()).isEqualTo(1);
        assertThat(model.requests().get(0).userPrompt()).contains("{\"Description\": \"fragment 8\"}");
    }

    @Test
    void mapReducesWhenTheFragmentListExceedsTheSummaryContext() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 8, 1_200, 12_000);
        var fragments = IntStream.rangeClosed(1, 6)
            .mapToObj(index -> String.valueOf((char) ('a' + index - 1)).repeat(20_000))
            .toList();

        var result = summarizer.summarize("Entity", "trade tariff", fragments);

        assertThat(result.description()).isEqualTo("SUMMARY<SEP>SUMMARY<SEP>SUMMARY");
        assertThat(result.llmUsed()).isTrue();
        assertThat(model.generateCalls()).isEqualTo(3);
    }

    @Test
    void singleFragmentIsSanitizedAndReturnedWithoutLlm() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 8, 1_200, 12_000);

        var result = summarizer.summarize("Entity", "trade tariff", List.of("bad\u0000text"));

        assertThat(result.description()).isEqualTo("badtext");
        assertThat(result.llmUsed()).isFalse();
        assertThat(model.generateCalls()).isZero();
    }

    @Test
    void cacheKeyChangesWhenTheFragmentSetChanges() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 8, 1_200, 12_000);
        var baseline = IntStream.rangeClosed(1, 8).mapToObj(index -> "fragment " + index).toList();
        var changed = new ArrayList<>(baseline);
        changed.set(2, "changed fragment");

        summarizer.summarize("Entity", "trade tariff", baseline);
        summarizer.summarize("Entity", "trade tariff", changed);

        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(0).userPrompt())
            .isNotEqualTo(model.requests().get(1).userPrompt());
    }

    @Test
    void cacheKeyIsStableWhenFragmentsDifferOnlyBeyondTheTruncationWindow() {
        var model = new RecordingChatModel("SUMMARY");
        var summarizer = summarizer(model, 6, 1_200, 100);
        var visible = List.of(
            "a".repeat(60),
            "b".repeat(60),
            "c".repeat(60),
            "d".repeat(60),
            "e".repeat(60)
        );
        var first = new ArrayList<>(visible);
        first.add("z".repeat(60));
        var second = new ArrayList<>(visible);
        second.add("q".repeat(60));

        summarizer.summarize("Entity", "trade tariff", first);
        summarizer.summarize("Entity", "trade tariff", second);

        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().get(0).userPrompt()).isEqualTo(model.requests().get(1).userPrompt());
    }

    @Test
    void propagatesSummaryModelFailuresWithoutFallingBackToTheJoin() {
        var summarizer = summarizer(new ThrowingChatModel(), 8, 1_200, 12_000);
        var fragments = IntStream.rangeClosed(1, 8).mapToObj(index -> "fragment " + index).toList();

        assertThatThrownBy(() -> summarizer.summarize("Entity", "trade tariff", fragments))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("summary failed");
    }

    private static DescriptionSummarizer summarizer(ChatModel model, int force, int maxTokens, int context) {
        return new DescriptionSummarizer(
            model,
            new HeuristicTokenCounter(),
            force,
            maxTokens,
            context,
            DescriptionSummarizer.DEFAULT_SUMMARY_LENGTH_RECOMMENDED,
            KnowledgeExtractor.DEFAULT_LANGUAGE
        );
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String response;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return response;
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }

        int generateCalls() {
            return requests.size();
        }
    }

    private static final class ThrowingChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            throw new IllegalStateException("summary failed");
        }
    }
}
