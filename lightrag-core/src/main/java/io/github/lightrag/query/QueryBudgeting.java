package io.github.lightrag.query;

import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class QueryBudgeting {
    private QueryBudgeting() {
    }

    static int approximateTokenCount(String text) {
        if (text == null) {
            return 0;
        }
        var normalized = text.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }

    static String formatEntity(ScoredEntity entity) {
        return "- %s | %s | %.3f".formatted(entity.entityId(), entity.entity().name(), entity.score());
    }

    static String formatRelation(ScoredRelation relation) {
        return "- %s | %s | %.3f".formatted(
            relation.relationId(),
            relation.relation().type(),
            relation.score()
        );
    }

    static String formatChunk(ScoredChunk chunk) {
        return "- %s | %.3f | %s".formatted(chunk.chunkId(), chunk.score(), chunk.chunk().text());
    }

    static List<ScoredEntity> limitEntities(List<ScoredEntity> entities, int maxTokens) {
        return limitByTextTokens(entities, maxTokens, QueryBudgeting::formatEntity);
    }

    static List<ScoredRelation> limitRelations(List<ScoredRelation> relations, int maxTokens) {
        return limitByTextTokens(relations, maxTokens, QueryBudgeting::formatRelation);
    }

    static List<ScoredChunk> limitChunks(List<ScoredChunk> chunks, int maxTokens) {
        if (maxTokens <= 0 || chunks.isEmpty()) {
            return List.of();
        }
        var limited = new ArrayList<ScoredChunk>(chunks.size());
        var remaining = maxTokens;
        for (var chunk : chunks) {
            var tokenCost = chunk.chunk().tokenCount() > 0
                ? chunk.chunk().tokenCount()
                : approximateTokenCount(chunk.chunk().text());
            if (tokenCost > remaining) {
                break;
            }
            limited.add(chunk);
            remaining -= tokenCost;
        }
        return List.copyOf(limited);
    }

    private static <T> List<T> limitByTextTokens(List<T> items, int maxTokens, Function<T, String> formatter) {
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
