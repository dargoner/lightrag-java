package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.types.QueryContext;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class QueryEngine {
    private static final String SYSTEM_PROMPT = "Answer the user's question using the provided context.";

    private final ChatModel chatModel;
    private final ContextAssembler contextAssembler;
    private final Map<QueryMode, QueryStrategy> strategies;
    private final RerankModel rerankModel;

    public QueryEngine(
        ChatModel chatModel,
        ContextAssembler contextAssembler,
        Map<QueryMode, QueryStrategy> strategies,
        RerankModel rerankModel
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.strategies = Map.copyOf(new EnumMap<>(Objects.requireNonNull(strategies, "strategies")));
        this.rerankModel = rerankModel;
    }

    public QueryResult query(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var strategy = strategies.get(query.mode());
        if (strategy == null) {
            throw new IllegalStateException("No query strategy configured for mode: " + query.mode());
        }

        QueryContext context = strategy.retrieve(query);
        var answer = chatModel.generate(new ChatModel.ChatRequest(
            SYSTEM_PROMPT,
            """
            Context:
            %s

            Question:
            %s
            """.formatted(context.assembledContext(), query.query())
        ));
        return new QueryResult(answer, contextAssembler.toContexts(context));
    }
}
