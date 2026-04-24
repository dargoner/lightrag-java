package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

        var request = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .maxHop(3)
            .pathTopK(4)
            .multiHopEnabled(false)
            .build();

        var resolved = extractor.resolve(request, model);

        assertThat(resolved.hlKeywords()).containsExactly("organization");
        assertThat(resolved.llKeywords()).containsExactly("alice", "bob");
        assertThat(resolved.maxHop()).isEqualTo(3);
        assertThat(resolved.pathTopK()).isEqualTo(4);
        assertThat(resolved.multiHopEnabled()).isFalse();
        assertThat(model.keywordExtractionCallCount()).isEqualTo(1);
    }

    @Test
    void keywordExtractionPromptRequiresSameLanguageKeywords() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["住房公积金","租房提取"],"low_level_keywords":["办理流程","申请材料"]}
            """);
        var extractor = new QueryKeywordExtractor();

        extractor.resolve(QueryRequest.builder()
            .query("住房公积金租房提取怎么处理")
            .mode(QueryMode.MIX)
            .build(), model);

        assertThat(model.lastUserPrompt())
            .contains("same language as the user query")
            .contains("For Chinese queries, return Chinese keywords")
            .contains("住房公积金租房提取怎么处理")
            .contains("住房公积金", "租房提取", "办理流程", "申请材料");
    }

    @Test
    void chineseQueriesDropTranslatedKeywordsAndBackfillChineseDualPathKeywords() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["Housing provident fund","Rent withdrawal"],"low_level_keywords":["租房提取","申请手续"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var resolved = extractor.resolve(QueryRequest.builder()
            .query("住房公积金租房提取怎么处理")
            .mode(QueryMode.MIX)
            .build(), model);

        assertThat(resolved.hlKeywords())
            .allMatch(keyword -> keyword.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN));
        assertThat(resolved.hlKeywords()).contains("租房提取");
        assertThat(resolved.hlKeywords()).doesNotContain("Housing provident fund", "Rent withdrawal");
        assertThat(resolved.llKeywords()).containsExactly("租房提取", "申请手续");
    }

    @Test
    void usesDeterministicKeywordsForShortLiteralHybridQueriesWithoutCallingModel() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["ignored"],"low_level_keywords":["ignored"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var resolved = extractor.resolve(QueryRequest.builder()
            .query("执法")
            .mode(QueryMode.HYBRID)
            .build(), model);

        assertThat(resolved.hlKeywords()).containsExactly("执法");
        assertThat(resolved.llKeywords()).containsExactly("执法");
        assertThat(model.keywordExtractionCallCount()).isZero();
    }

    @Test
    void usesDeterministicKeywordsForShortLiteralQueriesByMode() {
        var model = new CountingKeywordChatModel("""
            {"high_level_keywords":["ignored"],"low_level_keywords":["ignored"]}
            """);
        var extractor = new QueryKeywordExtractor();

        var local = extractor.resolve(QueryRequest.builder()
            .query("执法")
            .mode(QueryMode.LOCAL)
            .build(), model);
        var global = extractor.resolve(QueryRequest.builder()
            .query("执法")
            .mode(QueryMode.GLOBAL)
            .build(), model);

        assertThat(local.hlKeywords()).isEmpty();
        assertThat(local.llKeywords()).containsExactly("执法");
        assertThat(global.hlKeywords()).containsExactly("执法");
        assertThat(global.llKeywords()).isEmpty();
        assertThat(model.keywordExtractionCallCount()).isZero();
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

        var localRequest = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.LOCAL)
            .maxHop(2)
            .pathTopK(6)
            .multiHopEnabled(false)
            .build();
        var local = extractor.resolve(localRequest, new CountingKeywordChatModel("{}"));
        var globalRequest = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.GLOBAL)
            .maxHop(4)
            .pathTopK(7)
            .build();
        var global = extractor.resolve(globalRequest, new CountingKeywordChatModel("{}"));
        var hybridRequest = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.HYBRID)
            .pathTopK(8)
            .build();
        var hybrid = extractor.resolve(hybridRequest, new CountingKeywordChatModel("{}"));

        assertThat(local.llKeywords()).containsExactly("Who works with Bob?");
        assertThat(local.hlKeywords()).isEmpty();
        assertThat(local.maxHop()).isEqualTo(2);
        assertThat(local.pathTopK()).isEqualTo(6);
        assertThat(local.multiHopEnabled()).isFalse();
        assertThat(global.hlKeywords()).containsExactly("Who works with Bob?");
        assertThat(global.llKeywords()).isEmpty();
        assertThat(global.maxHop()).isEqualTo(4);
        assertThat(global.pathTopK()).isEqualTo(7);
        assertThat(hybrid.llKeywords()).containsExactly("Who works with Bob?");
        assertThat(hybrid.hlKeywords()).isEmpty();
        assertThat(hybrid.pathTopK()).isEqualTo(8);
    }

    @Test
    void hybridChineseQuestionBackfillsLowLevelKeywordsWhenModelOnlyReturnsHighLevelKeywords() {
        var extractor = new QueryKeywordExtractor();

        var resolved = extractor.resolve(QueryRequest.builder()
            .query("提取条件是什么")
            .mode(QueryMode.HYBRID)
            .build(), new CountingKeywordChatModel("""
                {"high_level_keywords":["提取条件"],"low_level_keywords":[]}
                """));

        assertThat(resolved.hlKeywords()).containsExactly("提取条件");
        assertThat(resolved.llKeywords()).containsExactly("提取条件");
    }

    @Test
    void hybridChineseLongQuestionBuildsDualFallbackKeywordSetsWhenModelReturnsNothing() {
        var extractor = new QueryKeywordExtractor();
        var query = "缴存单位不办理住房公积金缴存登记后，如果当事人拒不整改，后续处理步骤、办理期限和听证权分别怎么规定？";

        var resolved = extractor.resolve(QueryRequest.builder()
            .query(query)
            .mode(QueryMode.HYBRID)
            .build(), new CountingKeywordChatModel("{}"));

        assertThat(resolved.hlKeywords())
            .contains("后续处理步骤", "办理期限", "听证权");
        assertThat(resolved.llKeywords()).contains("缴存单位不办理住房公积金缴存登记");
        assertThat(resolved.llKeywords()).anyMatch(keyword -> keyword.contains("拒不整改"));
        assertThat(resolved.llKeywords()).doesNotContain(query);
    }

    @Test
    void hybridChineseProcessQuestionUsesGenericStructureFallbackInsteadOfBusinessDictionary() {
        var extractor = new QueryKeywordExtractor();
        var query = "在跨境订单履约流程中，从下单、支付确认，到仓库出库，再到末端派送，这几步的触发条件、处理时限和处理方式分别是什么？";

        var resolved = extractor.resolve(QueryRequest.builder()
            .query(query)
            .mode(QueryMode.HYBRID)
            .build(), new CountingKeywordChatModel("{}"));

        assertThat(resolved.hlKeywords()).anyMatch(keyword -> keyword.contains("履约流程"));
        assertThat(resolved.hlKeywords()).anyMatch(keyword -> keyword.contains("触发条件"));
        assertThat(resolved.hlKeywords())
            .contains("处理时限", "处理方式");
        assertThat(resolved.llKeywords())
            .contains("跨境订单履约流程", "下单", "支付确认", "仓库出库", "末端派送");
        assertThat(resolved.llKeywords()).doesNotContain(query);
    }

    @Test
    void fallbackCopyPreservesMetadataFiltersAndConditions() {
        var extractor = new QueryKeywordExtractor();
        var metadataConditions = List.of(
            new io.github.lightrag.api.MetadataCondition("score", io.github.lightrag.api.MetadataOperator.GTE, "80")
        );
        var request = QueryRequest.builder()
            .query("Who works with Bob?")
            .mode(QueryMode.LOCAL)
            .metadataFilters(Map.of("source", List.of("doc-a")))
            .metadataConditions(metadataConditions)
            .build();

        var resolved = extractor.resolve(request, new CountingKeywordChatModel("{}"));

        assertThat(resolved.metadataFilters()).containsEntry("source", List.of("doc-a"));
        assertThat(resolved.metadataConditions()).containsExactlyElementsOf(metadataConditions);
    }

    private static final class CountingKeywordChatModel implements ChatModel {
        private final String keywordResponse;
        private int keywordExtractionCallCount;
        private String lastUserPrompt;

        private CountingKeywordChatModel(String keywordResponse) {
            this.keywordResponse = keywordResponse;
        }

        @Override
        public String generate(ChatRequest request) {
            keywordExtractionCallCount++;
            lastUserPrompt = request.userPrompt();
            return keywordResponse;
        }

        int keywordExtractionCallCount() {
            return keywordExtractionCallCount;
        }

        String lastUserPrompt() {
            return lastUserPrompt;
        }
    }
}
