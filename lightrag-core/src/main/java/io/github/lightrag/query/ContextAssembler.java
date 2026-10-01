package io.github.lightrag.query;

import io.github.lightrag.api.QueryResult;
import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ContextAssembler {
    private final TokenCounter tokenCounter;

    public ContextAssembler(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    public ContextAssembler() {
        this(new HeuristicTokenCounter());
    }

    public String assemble(QueryContext context) {
        var source = Objects.requireNonNull(context, "context");
        var referenceIds = QueryReferences.assignReferenceIds(source.matchedChunks());
        return """
            Entities:
            %s

            Relations:
            %s

            Chunks:
            %s

            Reference Document List:
            %s
            """.formatted(
            formatEntities(source),
            formatRelations(source),
            formatChunks(source, referenceIds),
            formatReferences(referenceIds)
        );
    }

    public List<QueryResult.Context> toContexts(QueryContext context) {
        return Objects.requireNonNull(context, "context").matchedChunks().stream()
            .map(chunk -> new QueryResult.Context(chunk.chunkId(), chunk.chunk().text()))
            .toList();
    }

    static String approxChunkProjection(ScoredChunk chunk, Optional<String> headings) {
        return QueryBudgeting.formatChunk(chunk, "", headings);
    }

    private static String formatEntities(QueryContext context) {
        if (context.matchedEntities().isEmpty()) {
            return "(none)";
        }
        return context.matchedEntities().stream()
            .map(QueryBudgeting::formatEntity)
            .collect(Collectors.joining("\n"));
    }

    private static String formatRelations(QueryContext context) {
        if (context.matchedRelations().isEmpty()) {
            return "(none)";
        }
        return context.matchedRelations().stream()
            .map(QueryBudgeting::formatRelation)
            .collect(Collectors.joining("\n"));
    }

    private String formatChunks(QueryContext context, Map<String, String> referenceIds) {
        if (context.matchedChunks().isEmpty()) {
            return "(none)";
        }
        return context.matchedChunks().stream()
            .map(chunk -> QueryBudgeting.formatChunk(
                chunk,
                referenceIds.getOrDefault(QueryReferences.sourceOf(chunk), ""),
                ChunkHeadings.resolve(chunk, tokenCounter)
            ))
            .collect(Collectors.joining("\n"));
    }

    private static String formatReferences(Map<String, String> referenceIds) {
        if (referenceIds.isEmpty()) {
            return "(none)";
        }
        return referenceIds.entrySet().stream()
            .map(entry -> "- [%s] %s".formatted(entry.getValue(), entry.getKey()))
            .collect(Collectors.joining("\n"));
    }
}
