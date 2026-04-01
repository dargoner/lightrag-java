package io.github.lightrag.query;

import io.github.lightrag.api.QueryResult;
import io.github.lightrag.types.QueryContext;

import java.util.List;
import java.util.Objects;

public final class ContextAssembler {
    public String assemble(QueryContext context) {
        var source = Objects.requireNonNull(context, "context");
        return """
            Entities:
            %s

            Relations:
            %s

            Chunks:
            %s
            """.formatted(
            formatEntities(source),
            formatRelations(source),
            formatChunks(source)
        );
    }

    public List<QueryResult.Context> toContexts(QueryContext context) {
        return Objects.requireNonNull(context, "context").matchedChunks().stream()
            .map(chunk -> new QueryResult.Context(chunk.chunkId(), chunk.chunk().text()))
            .toList();
    }

    private static String formatEntities(QueryContext context) {
        if (context.matchedEntities().isEmpty()) {
            return "(none)";
        }
        return context.matchedEntities().stream()
            .map(QueryBudgeting::formatEntity)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String formatRelations(QueryContext context) {
        if (context.matchedRelations().isEmpty()) {
            return "(none)";
        }
        return context.matchedRelations().stream()
            .map(QueryBudgeting::formatRelation)
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String formatChunks(QueryContext context) {
        if (context.matchedChunks().isEmpty()) {
            return "(none)";
        }
        return context.matchedChunks().stream()
            .map(QueryBudgeting::formatChunk)
            .collect(java.util.stream.Collectors.joining("\n"));
    }
}
