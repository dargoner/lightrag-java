package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.spring.boot.LightRagProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class QueryController {
    private final LightRag lightRag;
    private final LightRagProperties properties;

    QueryController(LightRag lightRag, LightRagProperties properties) {
        this.lightRag = lightRag;
        this.properties = properties;
    }

    @PostMapping("/query")
    QueryResponse query(@RequestBody QueryPayload payload) {
        validate(payload);
        var result = lightRag.query(QueryRequest.builder()
            .query(payload.query().strip())
            .mode(payload.mode() == null ? defaultMode() : payload.mode())
            .topK(payload.topK() == null ? properties.getQuery().getDefaultTopK() : payload.topK())
            .chunkTopK(payload.chunkTopK() == null ? properties.getQuery().getDefaultChunkTopK() : payload.chunkTopK())
            .maxEntityTokens(payload.maxEntityTokens() == null ? QueryRequest.DEFAULT_MAX_ENTITY_TOKENS : payload.maxEntityTokens())
            .maxRelationTokens(payload.maxRelationTokens() == null ? QueryRequest.DEFAULT_MAX_RELATION_TOKENS : payload.maxRelationTokens())
            .maxTotalTokens(payload.maxTotalTokens() == null ? QueryRequest.DEFAULT_MAX_TOTAL_TOKENS : payload.maxTotalTokens())
            .responseType(payload.responseType() == null ? properties.getQuery().getDefaultResponseType() : payload.responseType())
            .enableRerank(payload.enableRerank() == null || payload.enableRerank())
            .onlyNeedContext(payload.onlyNeedContext() != null && payload.onlyNeedContext())
            .onlyNeedPrompt(payload.onlyNeedPrompt() != null && payload.onlyNeedPrompt())
            .includeReferences(payload.includeReferences() != null && payload.includeReferences())
            .stream(false)
            .userPrompt(payload.userPrompt() == null ? "" : payload.userPrompt().strip())
            .hlKeywords(payload.hlKeywords() == null ? List.of() : payload.hlKeywords())
            .llKeywords(payload.llKeywords() == null ? List.of() : payload.llKeywords())
            .conversationHistory(toConversationHistory(payload.conversationHistory()))
            .build());
        return new QueryResponse(result.answer(), result.contexts(), result.references());
    }

    private void validate(QueryPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("request body is required");
        }
        requireNonBlank(payload.query(), "query");
        requirePositive(payload.topK(), "topK");
        requirePositive(payload.chunkTopK(), "chunkTopK");
        requirePositive(payload.maxEntityTokens(), "maxEntityTokens");
        requirePositive(payload.maxRelationTokens(), "maxRelationTokens");
        requirePositive(payload.maxTotalTokens(), "maxTotalTokens");
        if (Boolean.TRUE.equals(payload.stream())) {
            throw new IllegalArgumentException("stream=true is not supported on /query; use buffered requests only");
        }
        if (payload.responseType() != null) {
            requireNonBlank(payload.responseType(), "responseType");
        }
        if (payload.userPrompt() != null && payload.userPrompt().isBlank()) {
            throw new IllegalArgumentException("userPrompt must not be blank");
        }
        if (payload.conversationHistory() == null) {
            return;
        }
        for (var message : payload.conversationHistory()) {
            if (message == null) {
                throw new IllegalArgumentException("conversationHistory item must not be null");
            }
            requireNonBlank(message.role(), "conversationHistory.role");
            if (message.content() == null || message.content().isBlank()) {
                throw new IllegalArgumentException("conversationHistory.content must not be blank");
            }
        }
    }

    private QueryMode defaultMode() {
        return QueryMode.valueOf(properties.getQuery().getDefaultMode().strip().toUpperCase(java.util.Locale.ROOT));
    }

    private static List<ChatModel.ChatRequest.ConversationMessage> toConversationHistory(
        List<ConversationMessagePayload> conversationHistory
    ) {
        if (conversationHistory == null) {
            return List.of();
        }
        return conversationHistory.stream()
            .map(message -> new ChatModel.ChatRequest.ConversationMessage(message.role().strip(), message.content().strip()))
            .toList();
    }

    private static void requirePositive(Integer value, String fieldName) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    record QueryPayload(
        String query,
        QueryMode mode,
        Integer topK,
        Integer chunkTopK,
        Integer maxEntityTokens,
        Integer maxRelationTokens,
        Integer maxTotalTokens,
        String responseType,
        Boolean enableRerank,
        Boolean onlyNeedContext,
        Boolean onlyNeedPrompt,
        Boolean includeReferences,
        Boolean stream,
        String userPrompt,
        List<String> hlKeywords,
        List<String> llKeywords,
        List<ConversationMessagePayload> conversationHistory
    ) {
    }

    record ConversationMessagePayload(String role, String content) {
    }

    record QueryResponse(String answer, java.util.List<QueryResult.Context> contexts, java.util.List<QueryResult.Reference> references) {
    }
}
