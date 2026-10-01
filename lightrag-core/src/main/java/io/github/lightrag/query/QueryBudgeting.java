package io.github.lightrag.query;

import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

final class QueryBudgeting {
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
        return "- %s | %s | %.3f".formatted(entity.entityId(), entity.entity().name(), entity.score());
    }

    static String formatRelation(ScoredRelation relation) {
        return "- %s -> %s | %s | %.3f".formatted(
            relation.relation().srcId(),
            relation.relation().tgtId(),
            relation.relation().keywords(),
            relation.score()
        );
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
        var limited = new ArrayList<T>(items.size());
        var remaining = maxTokens;
        for (var item : items) {
            var tokenCost = approximateTokenCount(formatter.apply(item));
            if (tokenCost > remaining) {
                break;
            }
            limited.add(item);
            remaining -= tokenCost;
        }
        return List.copyOf(limited);
    }
}
