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
    private static final String REASONING_DRAFT_INSTRUCTIONS = """
        
        7. Reasoning Draft Instructions:
          - Produce a short reasoning draft that follows the provided reasoning path step by step.
          - Name the supporting entity or relation for each hop and keep every step grounded in the context.
          - Do not write the final answer.
          - Do not add a references section.
        """;
    private static final String FINAL_ANSWER_INSTRUCTIONS = """
        
        7. Final Answer Instructions:
          - Use the validated reasoning draft below to synthesize the final answer.
          - Keep the final answer consistent with the reasoning draft and the original context.
          - Do not expose chain-of-thought style scratch work; provide the final answer directly.
        
        ---Validated Reasoning Draft---
        
        %s
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

    public boolean shouldUseTwoStage(QueryRequest request, String systemPrompt) {
        Objects.requireNonNull(request, "request");
        var prompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        return !request.stream()
            && !request.onlyNeedContext()
            && !request.onlyNeedPrompt()
            && prompt.contains("Reasoning Path");
    }

    public String buildReasoningStagePrompt(String systemPrompt) {
        return appendBeforeContext(Objects.requireNonNull(systemPrompt, "systemPrompt"), REASONING_DRAFT_INSTRUCTIONS);
    }

    public String buildFinalStagePrompt(String systemPrompt, String reasoningDraft) {
        return appendBeforeContext(
            Objects.requireNonNull(systemPrompt, "systemPrompt"),
            FINAL_ANSWER_INSTRUCTIONS.formatted(Objects.requireNonNull(reasoningDraft, "reasoningDraft").strip())
        );
    }

    private static String appendBeforeContext(String prompt, String addition) {
        var marker = "\n---Context---\n";
        var insertionPoint = prompt.indexOf(marker);
        if (insertionPoint < 0) {
            return prompt + addition;
        }
        return prompt.substring(0, insertionPoint)
            + addition
            + prompt.substring(insertionPoint);
    }
}
