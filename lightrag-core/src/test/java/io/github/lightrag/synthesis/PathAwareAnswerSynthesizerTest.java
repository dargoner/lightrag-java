package io.github.lightrag.synthesis;

import io.github.lightrag.api.QueryRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathAwareAnswerSynthesizerTest {
    @Test
    void injectsReasoningContextIntoSystemPrompt() {
        var synthesizer = new PathAwareAnswerSynthesizer();

        var prompt = synthesizer.injectContext("""
            ---Context---

            %s
            """, QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .build(), """
            Reasoning Path 1
            Hop 1: Atlas --depends_on--> GraphStore
            Hop 2: GraphStore --owned_by--> KnowledgeGraphTeam
            """);

        assertThat(prompt)
            .contains("Reasoning Path 1")
            .contains("Hop 1")
            .contains("Hop 2")
            .contains("explain the answer hop by hop")
            .contains("do not have enough information");
    }

    @Test
    void leavesPlainPromptUntouchedWhenNoReasoningPathExists() {
        var synthesizer = new PathAwareAnswerSynthesizer();
        var prompt = synthesizer.injectContext("""
            ---Context---

            Alpha chunk
            """, QueryRequest.builder()
            .query("Who owns GraphStore?")
            .build(), """
            ---Context---

            Alpha chunk
            """);

        assertThat(prompt)
            .contains("Alpha chunk")
            .doesNotContain("hop by hop")
            .doesNotContain("insufficient");
    }

    @Test
    void enablesTwoStageOnlyForNonStreamingReasoningPathQueries() {
        var synthesizer = new PathAwareAnswerSynthesizer();
        var reasoningPrompt = """
            ---Context---

            Reasoning Path 1
            Hop 1: Atlas --depends_on--> GraphStore
            """;

        assertThat(synthesizer.shouldUseTwoStage(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .build(), reasoningPrompt)).isTrue();
        assertThat(synthesizer.shouldUseTwoStage(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .stream(true)
            .build(), reasoningPrompt)).isFalse();
        assertThat(synthesizer.shouldUseTwoStage(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .onlyNeedPrompt(true)
            .build(), reasoningPrompt)).isFalse();
        assertThat(synthesizer.shouldUseTwoStage(QueryRequest.builder()
            .query("Who owns GraphStore?")
            .build(), "Alpha chunk")).isFalse();
    }

    @Test
    void buildsReasoningAndFinalStagePrompts() {
        var synthesizer = new PathAwareAnswerSynthesizer();
        var basePrompt = """
            ---Context---

            Reasoning Path 1
            Hop 1: Atlas --depends_on--> GraphStore
            Hop 2: GraphStore --owned_by--> KnowledgeGraphTeam
            """;

        var reasoningPrompt = synthesizer.buildReasoningStagePrompt(basePrompt);
        var finalPrompt = synthesizer.buildFinalStagePrompt(basePrompt, "Atlas -> GraphStore -> KnowledgeGraphTeam");

        assertThat(reasoningPrompt)
            .contains("Reasoning Draft Instructions")
            .contains("Do not write the final answer");
        assertThat(finalPrompt)
            .contains("Validated Reasoning Draft")
            .contains("Atlas -> GraphStore -> KnowledgeGraphTeam")
            .contains("final answer");
    }
}
