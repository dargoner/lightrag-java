package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryKeywordExtractorTest {
    @Test
    void returnsManualKeywordOverridesWithoutCallingModel() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["ignored"],"low_level_keywords":["ignored"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var resolved = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .hlKeywords(List.of("organization"))
            .llKeywords(List.of("alice"))
            .build(), model);

        assertThat(resolved.hlKeywords()).containsExactly("organization");
        assertThat(resolved.llKeywords()).containsExactly("alice");
        assertThat(model.keywordExtractionCallCount()).isZero();
    }

    @Test
    void extractsKeywordsForGraphAwareModesWhenOverridesAreMissing() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["organization"],"low_level_keywords":["alice","bob"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var resolved = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .build(), model);

        assertThat(resolved.hlKeywords()).containsExactly("organization");
        assertThat(resolved.llKeywords()).containsExactly("alice", "bob");
        assertThat(model.keywordExtractionCallCount()).isEqualTo(1);
    }

    @Test
    void skipsKeywordExtractionForNaiveAndBypassModes() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["ignored"],"low_level_keywords":["ignored"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var naive = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.NAIVE)
            .build(), model);
        var bypass = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.BYPASS)
            .build(), model);

        assertThat(naive.hlKeywords()).isEmpty();
        assertThat(naive.llKeywords()).isEmpty();
        assertThat(bypass.hlKeywords()).isEmpty();
        assertThat(bypass.llKeywords()).isEmpty();
        assertThat(model.keywordExtractionCallCount()).isZero();
    }

    @Test
    void returnsOriginalRequestWhenAutomaticKeywordExtractionIsDisabled() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["organization"],"low_level_keywords":["alice","bob"]}
            """);
        var extractor = new QueryKeywordExtractor(false);
        var request = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .build();

        var resolved = extractor.resolve(request, model);

        assertThat(resolved).isEqualTo(request);
        assertThat(model.keywordExtractionCallCount()).isZero();
    }

    @Test
    void fallsBackByModeWhenExtractionReturnsNoKeywords() {
        var extractor = new QueryKeywordExtractor();

        var local = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.LOCAL)
            .build(), new CountingKeywordChatModel("{}"));
        var global = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.GLOBAL)
            .build(), new CountingKeywordChatModel("{}"));
        var hybrid = extractor.resolve(QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .build(), new CountingKeywordChatModel("{}"));

        assertThat(local.llKeywords()).containsExactly("Who works with Bob?");
        assertThat(local.hlKeywords()).isEmpty();
        assertThat(global.hlKeywords()).containsExactly("Who works with Bob?");
        assertThat(global.llKeywords()).isEmpty();
        assertThat(hybrid.llKeywords()).containsExactly("Who works with Bob?");
        assertThat(hybrid.hlKeywords()).isEmpty();
    }

    private static final class CountingKeywordChatModel implements ChatModel {
        private final String keywordResponse;
        private int keywordExtractionCallCount;

        private CountingKeywordChatModel(String keywordResponse) {
            this.keywordResponse = keywordResponse;
        }

        @Override
        public String generate(ChatRequest request) {
            keywordExtractionCallCount++;
            return keywordResponse;
        }

        int keywordExtractionCallCount() {
            return keywordExtractionCallCount;
        }
    }
}
