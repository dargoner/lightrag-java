package io.github.lightrag.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

final class QueryBudgeting {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenCounter tokenCounter;

    QueryBudgeting(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    int approximateTokenCount(String text) {
        if (text == null) {
            return 0;
        }
        var normalized = text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return tokenCounter.countTokens(normalized);
    }

    static String formatEntity(ScoredEntity entity) {
        var row = new LinkedHashMap<String, String>();
        row.put("entity", entity.entity().name());
        row.put("type", entity.entity().type());
        row.put("description", entity.entity().description());
        return writeRow(row);
    }

    static String formatRelation(ScoredRelation relation) {
        var row = new LinkedHashMap<String, String>();
        row.put("entity1", relation.relation().srcId());
        row.put("entity2", relation.relation().tgtId());
        row.put("description", relation.relation().description());
        return writeRow(row);
    }

    private static String writeRow(Map<String, String> row) {
        try {
            return JSON.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize the context row", exception);
        }
    }

    static String formatChunk(ScoredChunk chunk, String referenceId, Optional<String> headings) {
        var identifier = referenceId == null || referenceId.isBlank() ? "" : "[%s] ".formatted(referenceId);
        var headingsSegment = headings == null || headings.isEmpty()
            ? ""
            : "headings: %s | ".formatted(headings.get());
        return "- %s%s | %.3f | %s%s".formatted(
            identifier,
            chunk.chunkId(),
            chunk.score(),
            headingsSegment,
            chunk.chunk().text()
        );
    }

    List<ScoredEntity> limitEntities(List<ScoredEntity> entities, int maxTokens) {
        return limitByTextTokens(entities, maxTokens, QueryBudgeting::formatEntity);
    }

    List<ScoredRelation> limitRelations(List<ScoredRelation> relations, int maxTokens) {
        return limitByTextTokens(relations, maxTokens, QueryBudgeting::formatRelation);
    }

    private <T> List<T> limitByTextTokens(List<T> items, int maxTokens, Function<T, String> formatter) {
        if (maxTokens <= 0 || items.isEmpty()) {
            return List.of();
        }
        var rendered = new ArrayList<String>(items.size());
        for (var item : items) {
            rendered.add(formatter.apply(item));
        }
        // Mirror upstream truncate_list_by_token_size: the budget covers the exact text the
        // caller renders later (every row joined by the "\n" separator), so the separator's own
        // tokens are part of it, and the kept prefix is re-verified against its own join before
        // returning. Never keeps a partial row.
        var kept = rendered.size();
        while (kept > 0 && approximateTokenCount(String.join("\n", rendered.subList(0, kept))) > maxTokens) {
            kept--;
        }
        return List.copyOf(items.subList(0, kept));
    }
}
