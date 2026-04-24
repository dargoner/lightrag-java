package io.github.lightrag.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class QueryKeywordExtractor {
    private static final Logger log = LoggerFactory.getLogger(QueryKeywordExtractor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SHORT_LITERAL_QUERY_MAX_CODEPOINTS = 8;
    private static final Pattern HIGH_LEVEL_CHINESE_PATTERN = Pattern.compile(
        "([\\p{IsHan}A-Za-z0-9]{0,12}(?:条件|流程|步骤|期限|时限|权利|权|告知|规定|要求|材料|依据|标准|范围|方式|时间|责任|结果|影响|政策|情形|程序|路径|原因|目标|区别|联系|作用|问题|规则|办法|节点|状态))"
    );
    private static final Pattern SUBJECT_PREFIX_PATTERN = Pattern.compile("^(?:在)?(.+?(?:流程|过程|场景|问题|案件)?)(?:中|里|后|前|时)");
    private static final Pattern PROCESS_ITEM_PATTERN = Pattern.compile("(?:从|再到|到)([^，。！？；;]+?)(?=(?:从|再到|到|这几步之间|$))");

    private static final String KEYWORD_EXTRACTION_PROMPT_TEMPLATE = """
        ---Role---
        You are an expert keyword extractor for a Retrieval-Augmented Generation system.

        ---Goal---
        Extract:
        1. high_level_keywords: broader themes or intents
        2. low_level_keywords: concrete entities, names, or specific details

        ---Instructions---
        - Return valid JSON only.
        - Use the keys "high_level_keywords" and "low_level_keywords".
        - Use concise words or meaningful phrases.
        - Keep keywords in the same language as the user query. For Chinese queries, return Chinese keywords and do not translate them to English.
        - If the query is too vague, return empty arrays for both keys.

        ---Examples---
        %s

        ---Real Data---
        User Query: %s

        ---Output---
        """;

    private static final String KEYWORD_EXTRACTION_EXAMPLES = """
        Query: "How does international trade influence global economic stability?"
        Output: {"high_level_keywords":["International trade","Global economic stability"],"low_level_keywords":["Trade agreements","Tariffs","Imports","Exports"]}

        Query: "What are the environmental consequences of deforestation on biodiversity?"
        Output: {"high_level_keywords":["Deforestation","Biodiversity loss"],"low_level_keywords":["Species extinction","Habitat destruction","Rainforest"]}

        Query: "What is the role of education in reducing poverty?"
        Output: {"high_level_keywords":["Education","Poverty reduction"],"low_level_keywords":["School access","Literacy rates","Job training"]}

        Query: "住房公积金租房提取怎么处理？"
        Output: {"high_level_keywords":["住房公积金","租房提取"],"low_level_keywords":["住房公积金","租房提取","办理流程","申请材料"]}
        """;

    private final boolean automaticKeywordExtractionEnabled;

    QueryKeywordExtractor() {
        this(true);
    }

    QueryKeywordExtractor(boolean automaticKeywordExtractionEnabled) {
        this.automaticKeywordExtractionEnabled = automaticKeywordExtractionEnabled;
    }

    QueryRequest resolve(QueryRequest request, ChatModel chatModel) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(chatModel, "chatModel");
        if (!automaticKeywordExtractionEnabled) {
            return request;
        }
        if (!supportsAutomaticKeywords(request.mode())) {
            return request;
        }
        if (!request.hlKeywords().isEmpty() || !request.llKeywords().isEmpty()) {
            return request;
        }
        var deterministic = resolveDeterministicKeywords(request);
        if (deterministic != null) {
            log.info(
                "LightRAG keyword extraction bypassed for short literal query: mode={}, query={}, highLevelCount={}, lowLevelCount={}",
                request.mode(),
                request.query(),
                deterministic.hlKeywords().size(),
                deterministic.llKeywords().size()
            );
            return deterministic;
        }

        var startedAt = System.nanoTime();
        var prompt = KEYWORD_EXTRACTION_PROMPT_TEMPLATE.formatted(KEYWORD_EXTRACTION_EXAMPLES, request.query());
        var response = chatModel.generate(new ChatModel.ChatRequest("", prompt));
        var extracted = normalizeKeywordsForQueryLanguage(request, parseKeywords(response));
        var resolved = completeKeywordsForMode(request, extracted);
        var elapsedMs = elapsedMillis(startedAt);
        var fallbackApplied = resolved.highLevel().isEmpty() && resolved.lowLevel().isEmpty();
        var dualPathBackfillApplied = !resolved.equals(extracted);
        log.info(
            "LightRAG keyword extraction completed: mode={}, query={}, elapsedMs={}, rawHighLevelCount={}, rawLowLevelCount={}, resolvedHighLevelCount={}, resolvedLowLevelCount={}, fallbackApplied={}, dualPathBackfillApplied={}",
            request.mode(),
            request.query(),
            elapsedMs,
            extracted.highLevel().size(),
            extracted.lowLevel().size(),
            resolved.highLevel().size(),
            resolved.lowLevel().size(),
            fallbackApplied,
            dualPathBackfillApplied
        );
        if (!resolved.highLevel().isEmpty() || !resolved.lowLevel().isEmpty()) {
            return copyWithKeywords(request, resolved.highLevel(), resolved.lowLevel());
        }
        return applyFallback(request);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static boolean supportsAutomaticKeywords(QueryMode mode) {
        return mode == QueryMode.LOCAL
            || mode == QueryMode.GLOBAL
            || mode == QueryMode.HYBRID
            || mode == QueryMode.MIX;
    }

    private static ExtractedKeywords parseKeywords(String response) {
        try {
            var root = OBJECT_MAPPER.readTree(response);
            return new ExtractedKeywords(
                normalizeKeywords(root.path("high_level_keywords")),
                normalizeKeywords(root.path("low_level_keywords"))
            );
        } catch (JsonProcessingException exception) {
            return new ExtractedKeywords(List.of(), List.of());
        }
    }

    private static List<String> normalizeKeywords(com.fasterxml.jackson.databind.JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
            .filter(com.fasterxml.jackson.databind.JsonNode::isTextual)
            .map(com.fasterxml.jackson.databind.JsonNode::asText)
            .map(String::trim)
            .filter(keyword -> !keyword.isEmpty())
            .toList();
    }

    private static ExtractedKeywords normalizeKeywordsForQueryLanguage(QueryRequest request, ExtractedKeywords keywords) {
        if (!containsChinese(request.query())) {
            return keywords;
        }
        return new ExtractedKeywords(
            keepChineseKeywords(keywords.highLevel()),
            keepChineseKeywords(keywords.lowLevel())
        );
    }

    private static List<String> keepChineseKeywords(List<String> keywords) {
        return keywords.stream()
            .filter(QueryKeywordExtractor::containsChinese)
            .toList();
    }

    private static QueryRequest resolveDeterministicKeywords(QueryRequest request) {
        var normalizedQuery = normalizeQuery(request.query());
        if (normalizedQuery == null || !isShortLiteralQuery(normalizedQuery)) {
            return null;
        }
        return switch (request.mode()) {
            case GLOBAL -> copyWithKeywords(request, List.of(normalizedQuery), List.of());
            case LOCAL -> copyWithKeywords(request, List.of(), List.of(normalizedQuery));
            case HYBRID, MIX -> copyWithKeywords(request, List.of(normalizedQuery), List.of(normalizedQuery));
            default -> null;
        };
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        var normalized = query.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean isShortLiteralQuery(String query) {
        if (query.codePointCount(0, query.length()) > SHORT_LITERAL_QUERY_MAX_CODEPOINTS) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.isWhitespace(ch) || isSentenceDelimiter(ch)) {
                return false;
            }
        }
        return !containsQuestionCue(query);
    }

    private static boolean isSentenceDelimiter(char ch) {
        return switch (ch) {
            case ',', '，', '.', '。', '?', '？', '!', '！', ';', '；', ':', '：',
                '/', '\\', '"', '\'', '(', ')', '[', ']', '{', '}', '<', '>', '、' -> true;
            default -> false;
        };
    }

    private static boolean containsQuestionCue(String query) {
        return query.contains("什么")
            || query.contains("怎么")
            || query.contains("如何")
            || query.contains("为什么")
            || query.contains("是否")
            || query.contains("多少")
            || query.contains("哪些")
            || query.contains("谁")
            || query.contains("哪")
            || query.endsWith("吗")
            || query.endsWith("呢");
    }

    private static QueryRequest applyFallback(QueryRequest request) {
        return switch (request.mode()) {
            case GLOBAL -> copyWithKeywords(request, List.of(request.query()), List.of());
            case LOCAL, HYBRID, MIX -> copyWithKeywords(request, List.of(), List.of(request.query()));
            default -> request;
        };
    }

    private static ExtractedKeywords completeKeywordsForMode(QueryRequest request, ExtractedKeywords extracted) {
        if (request.mode() != QueryMode.HYBRID && request.mode() != QueryMode.MIX) {
            return extracted;
        }
        if (!extracted.highLevel().isEmpty() && !extracted.lowLevel().isEmpty()) {
            return extracted;
        }
        var derived = containsChinese(request.query())
            ? deriveChineseDualPathKeywords(request.query())
            : new ExtractedKeywords(List.of(), List.of());
        var highLevel = extracted.highLevel().isEmpty() ? derived.highLevel() : extracted.highLevel();
        var lowLevel = extracted.lowLevel().isEmpty() ? derived.lowLevel() : extracted.lowLevel();
        if (highLevel.isEmpty() && !lowLevel.isEmpty()) {
            highLevel = List.of(lowLevel.get(0));
        }
        if (lowLevel.isEmpty() && !highLevel.isEmpty()) {
            lowLevel = List.of(highLevel.get(0));
        }
        return new ExtractedKeywords(highLevel, lowLevel);
    }

    private static ExtractedKeywords deriveChineseDualPathKeywords(String query) {
        var normalized = normalizeQuery(query);
        if (normalized == null) {
            return new ExtractedKeywords(List.of(), List.of());
        }
        var highLevel = new java.util.LinkedHashSet<String>();
        collectHighLevelCandidates(normalized, highLevel);

        var lowLevel = new java.util.LinkedHashSet<String>();
        extractSubjectPhrase(normalized).ifPresent(lowLevel::add);
        collectProcessItems(normalized, lowLevel);
        collectClauseFragments(normalized, highLevel, lowLevel);
        return new ExtractedKeywords(List.copyOf(highLevel), List.copyOf(lowLevel));
    }

    private static void collectHighLevelCandidates(String query, Set<String> output) {
        var matcher = HIGH_LEVEL_CHINESE_PATTERN.matcher(query);
        while (matcher.find()) {
            for (var token : matcher.group(1).split("(?:、|，|,|和)")) {
                normalizeCandidate(token).ifPresent(output::add);
            }
        }
    }

    private static Optional<String> extractSubjectPhrase(String query) {
        var matcher = SUBJECT_PREFIX_PATTERN.matcher(query);
        if (!matcher.find()) {
            return Optional.empty();
        }
        var candidate = matcher.group(1);
        if (candidate.endsWith("案件")) {
            candidate = candidate.substring(0, candidate.length() - 2);
        }
        return normalizeCandidate(candidate);
    }

    private static void collectProcessItems(String query, Set<String> output) {
        var matcher = PROCESS_ITEM_PATTERN.matcher(query);
        while (matcher.find()) {
            for (var token : matcher.group(1).split("(?:、|，|,|或者|以及|和|及)")) {
                normalizeCandidate(token).ifPresent(output::add);
            }
        }
    }

    private static void collectClauseFragments(String query, Set<String> highLevel, Set<String> lowLevel) {
        for (var clause : query.split("[，,。！？；;]")) {
            for (var token : clause.split("(?:、|以及|或者|并且|并|和|与|及)")) {
                var candidate = normalizeCandidate(token);
                if (candidate.isEmpty()) {
                    continue;
                }
                var value = candidate.get();
                if (value.length() > 24 || containsQuestionCue(value)) {
                    continue;
                }
                var overlapsHighLevel = highLevel.stream().anyMatch(keyword -> keyword.equals(value) || keyword.contains(value));
                if (!overlapsHighLevel) {
                    lowLevel.add(value);
                }
            }
        }
    }

    private static Optional<String> normalizeCandidate(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var candidate = raw.trim()
            .replaceAll("^[，,。！？；;：:、】【、\\s]+", "")
            .replaceAll("[，,。！？；;：:、】【、\\s]+$", "")
            .replaceAll("^(?:请问|关于|其中|如果|对于|对|在|从|到|再到)+", "")
            .replaceAll("(?:分别怎么规定|分别是什么|怎么规定|如何规定|怎么处理|如何处理|这几步之间|是什么|是什么时候|什么|哪些|哪几步|哪一步|怎么|如何|吗|呢|后|前|时|中|里)+$", "")
            .trim();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        if (candidate.length() > 32) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static boolean containsChinese(String query) {
        if (query == null) {
            return false;
        }
        return query.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static QueryRequest copyWithKeywords(QueryRequest request, List<String> hlKeywords, List<String> llKeywords) {
        return new QueryRequest(
            request.query(),
            request.mode(),
            request.topK(),
            request.chunkTopK(),
            request.maxEntityTokens(),
            request.maxRelationTokens(),
            request.maxTotalTokens(),
            request.maxHop(),
            request.pathTopK(),
            request.multiHopEnabled(),
            request.responseType(),
            request.enableRerank(),
            request.onlyNeedContext(),
            request.onlyNeedPrompt(),
            request.includeReferences(),
            request.stream(),
            request.modelFunc(),
            request.userPrompt(),
            hlKeywords,
            llKeywords,
            request.conversationHistory(),
            request.metadataFilters(),
            request.metadataConditions()
        );
    }

    private record ExtractedKeywords(List<String> highLevel, List<String> lowLevel) {
    }
}
