package io.github.lightragjava.synthesis;

import io.github.lightragjava.api.QueryRequest;

import java.util.Objects;

public final class PathAwareAnswerSynthesizer {
    private static final String MULTI_HOP_INSTRUCTIONS = """
        
        6. Multi-Hop Reasoning Instructions:
          - When the context includes `Reasoning Path` sections, explain the answer hop by hop.
          - Name the intermediate entity or relation for each hop instead of collapsing multiple hops into one unsupported statement.
          - If any hop is missing evidence or the path is incomplete, say you do not have enough information to confirm the full multi-hop chain.
          - Only state a multi-hop conclusion when each hop is grounded in the provided context.
        """;

    public String injectContext(String template, QueryRequest request, String reasoningContext) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(request, "request");
        var prompt = Objects.requireNonNull(reasoningContext, "reasoningContext");
        if (!prompt.contains("Reasoning Path")) {
            return template.formatted(prompt);
        }
        var marker = "\n---Context---\n";
        var insertionPoint = prompt.indexOf(marker);
        if (insertionPoint < 0) {
            return template.formatted(prompt + MULTI_HOP_INSTRUCTIONS);
        }
        var rewritten = prompt.substring(0, insertionPoint)
            + MULTI_HOP_INSTRUCTIONS
            + prompt.substring(insertionPoint);
        return template.formatted(rewritten);
    }
}
