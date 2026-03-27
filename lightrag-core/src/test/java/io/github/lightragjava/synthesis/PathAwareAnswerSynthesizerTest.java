package io.github.lightragjava.synthesis;

import io.github.lightragjava.api.QueryRequest;
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
}
