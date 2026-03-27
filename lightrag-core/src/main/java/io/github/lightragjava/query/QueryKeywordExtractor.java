package io.github.lightragjava.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.ChatModel;

import java.util.List;
import java.util.Objects;

final class QueryKeywordExtractor {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

        var prompt = KEYWORD_EXTRACTION_PROMPT_TEMPLATE.formatted(KEYWORD_EXTRACTION_EXAMPLES, request.query());
        var response = chatModel.generate(new ChatModel.ChatRequest("", prompt));
        var extracted = parseKeywords(response);
        if (!extracted.highLevel().isEmpty() || !extracted.lowLevel().isEmpty()) {
            return copyWithKeywords(request, extracted.highLevel(), extracted.lowLevel());
        }
        return applyFallback(request);
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

    private static QueryRequest applyFallback(QueryRequest request) {
        return switch (request.mode()) {
            case GLOBAL -> copyWithKeywords(request, List.of(request.query()), List.of());
            case LOCAL, HYBRID, MIX -> copyWithKeywords(request, List.of(), List.of(request.query()));
            default -> request;
        };
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
            request.conversationHistory()
        );
    }

    private record ExtractedKeywords(List<String> highLevel, List<String> lowLevel) {
    }
}
