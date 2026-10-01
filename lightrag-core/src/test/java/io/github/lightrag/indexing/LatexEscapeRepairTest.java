package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link LatexEscapeRepair}, ported from the pinned corpus in upstream
 * {@code tests/llm/test_vlm_json_escape_repair.py} (contract:
 * {@code docs/design/LatexEscapeRepairContract.md}).
 *
 * <p>In this file a Java string escape {@code \t} / {@code \r} / {@code \n} / {@code \u000c} /
 * {@code \u0008} is a <em>damaged</em> control character (what JSON decoding left behind), and
 * {@code \\t} / {@code \\r} / {@code \\n} / {@code \\f} / {@code \\b} is an intact LaTeX command.
 * Form feed is written {@code \u000c} because {@code \f} reads too much like a LaTeX command.</p>
 */
class LatexEscapeRepairTest {

    private static void assertRepaired(String damaged, String expected) {
        assertThat(LatexEscapeRepair.repair(damaged)).isEqualTo(expected);
        // Repairing twice equals repairing once.
        assertThat(LatexEscapeRepair.repair(expected)).isEqualTo(expected);
    }

    private static void assertUnchanged(String text) {
        var repaired = LatexEscapeRepair.repair(text);
        assertThat(repaired).isEqualTo(text);
        assertThat(LatexEscapeRepair.repair(repaired)).isEqualTo(repaired);
    }

    @Test
    void repairsFormFeedAndBackspaceFollowedByALetter() {
        assertRepaired("$\u000crac{610}{C}$", "$\\frac{610}{C}$");
        assertRepaired("$\u0008eta + \u0008ar{x}$", "$\\beta + \\bar{x}$");
    }

    @Test
    void leavesIsolatedControlCharactersForSanitization() {
        assertUnchanged("before\u000c after\u0008.");
        assertUnchanged("a \u000c rac{b}{c} and \u0008 eta");
    }

    @Test
    void cleanTextIsUnchangedAndIdempotent() {
        assertUnchanged("$\\frac{a}{b}$ and \\beta with plain text");
        var once = LatexEscapeRepair.repair("$\u000crac{a}{b}$");
        assertThat(LatexEscapeRepair.repair(once)).isEqualTo(once);
    }

    @Test
    void repairsWhitespaceClassDamageInsideDollarMathOnly() {
        assertRepaired("domain is $\tau^2$", "domain is $\\tau^2$");
        assertRepaired("$a \times b$", "$a \\times b$");
        assertRepaired("$$\nabla f = 0$$", "$$\\nabla f = 0$$");
        assertRepaired("$\rho + \right)$", "$\\rho + \\right)$");
        // Outside math the residue stays ambiguous legitimate whitespace.
        assertUnchanged("label:\tau");
    }

    @Test
    void inMathRepairIsNotBlockedByAWordCharacter() {
        assertRepaired("$\tau_i$", "$\\tau_i$");
        assertRepaired("$\rho_{ij}$", "$\\rho_{ij}$");
        assertRepaired("$\theta_0$", "$\\theta_0$");
        assertRepaired("$\times2$", "$\\times2$");
        assertRepaired("$$\nabla_x f$$", "$$\\nabla_x f$$");
    }

    @Test
    void whitespaceClassDamageInProseIsDetectedButNotRewritten() {
        // CJK ideographs: the shape Chinese corpora actually produce. The prose detector's word
        // boundary reports nothing here (CJK is a word character), which is why the second
        // alternative exists; the text itself is never rewritten outside math.
        assertUnchanged("阈值\tau为0.5");
        assertUnchanged("数据\times倍");
        assertUnchanged("算子\nabla作用于 f");
        assertUnchanged("密度\rho的分布");
        assertUnchanged("Δ\theta角");
        assertUnchanged("the \tau value");
    }

    @Test
    void currencyAmountsDoNotConsumeALaterMathSpan() {
        assertRepaired("Cost is $5. The domain is $\tau^2$ end.", "Cost is $5. The domain is $\\tau^2$ end.");
        // Two amounts must not form a span of their own around prose.
        assertUnchanged("$5\rangle and $10");
        // A range of amounts followed by real math: only the math is repaired.
        assertRepaired("$5-$10 range $\tau$", "$5-$10 range $\\tau$");
    }

    @Test
    void intactMathDoesNotBlockAFollowingDamagedSpan() {
        assertRepaired("$10 \times 5$ then $\tau$", "$10 \\times 5$ then $\\tau$");
    }

    @Test
    void inlineSpanClosesAgainstTheFirstDollarOfADisplayPair() {
        assertRepaired("a $\tau$$ b", "a $\\tau$$ b");
    }

    @Test
    void proseBetweenTwoSpansIsNeverRewritten() {
        assertUnchanged("$ x $ then\text column $y$");
        assertUnchanged("成本为$a$，领域\tau为$b$");
    }

    @Test
    void currencyDollarIsRejectedAsAnOpener() {
        assertRepaired("Cost $5. Formula $$\tau^2$$", "Cost $5. Formula $$\\tau^2$$");
        // The span stays narrow: prose between the price and the real formula is not rewritten
        // along with it -- the tab in "and\text" stays a tab.
        assertRepaired("Cost $5 and\text is $\tau$", "Cost $5 and\text is $\\tau$");
    }

    @Test
    void markdownCodeIsNotScannedForMath() {
        assertUnchanged("```sh\necho \"$HOME\"\n\text=1\necho \"$PATH\"\n```");
        assertUnchanged("inline `echo \"$HOME\"; \text=1; echo \"$PATH\"` done");
        // An unclosed fence protects the rest of the text; an unclosed single backtick protects
        // nothing.
        assertUnchanged("text\n```sh\n$A \text $B\n");
        assertRepaired("stray ` tick then $\tau$ ok", "stray ` tick then $\\tau$ ok");
    }

    @Test
    void fenceLikeLineInsideABlockIsNotACloser() {
        var block = "```sh\n```not a closer\necho \"$HOME\"\n\text=1\necho \"$PATH\"\n```";
        assertUnchanged(block);
        assertRepaired(block + "\nafter $\tau$", block + "\nafter $\\tau$");
    }

    @Test
    void fenceIndentationIsSpacesOnly() {
        // A tab-indented fence-like line is code content, not a closer.
        assertUnchanged("```sh\n\t```\nA=$X; \text=1; B=$Y\n```");
        assertUnchanged("   ```sh\n   A=$X; \text=1; B=$Y\n   ```");
    }

    @Test
    void backtickFenceInfoStringMayNotCarryABacktick() {
        assertRepaired("```foo`bar\nafter $\tau$", "```foo`bar\nafter $\\tau$");
        assertUnchanged("```foo\nafter $\tau$");
        assertUnchanged("~~~foo`bar\nafter $\tau$");
    }

    @Test
    void fenceRegionsBehaveTheSameOnBothLineEndings() {
        for (var newline : List.of("\n", "\r\n")) {
            var block = String.join(newline, "```sh", "a", "```");
            assertRepaired(block + newline + "after $\tau$", block + newline + "after $\\tau$");
            var code = String.join(newline, "```sh", "echo \"$HOME\"", "\text=1", "echo \"$PATH\"", "```");
            assertUnchanged(code);
        }
    }

    @Test
    void closingFenceMayBeLongerThanTheOpener() {
        var fenced = "````sh\necho \"$A\"\n\text=1\necho \"$B\"\n`````";
        assertRepaired(fenced + "\nthen $\tau$", fenced + "\nthen $\\tau$");
    }

    @Test
    void inlineSpanCloserIsBoundedOnBothSides() {
        assertUnchanged("see `A ``B \"$HOME\" \text=1 \"$PATH\"` end");
    }

    @Test
    void mathAfterACodeRegionIsStillRepaired() {
        assertRepaired("```sh\necho \"$HOME\"\n```\nthen $\tau$ here", "```sh\necho \"$HOME\"\n```\nthen $\\tau$ here");
    }

    @Test
    void codeContentIsNotRepairedHoweverItIsReached() {
        // Pairing says WHERE a span is; the content gate says whether it is math.
        assertUnchanged("echo \"$HOME\"; \text=1; echo \"$PATH\"");
        assertUnchanged("stray ` tick\n```sh\nrun `cmd` now\necho \"$HOME\"\n\text=1\necho \"$PATH\"\n```");
        assertUnchanged("Example:\n\n    echo \"$HOME\"\n    \text=1\n    echo \"$PATH\"\n\ndone");
        assertUnchanged("A=$X\n\text=1\nB=$Y");
    }

    @Test
    void aStrayBacktickCannotStealAFenceOpener() {
        for (var stray : List.of("`", "\\`")) {
            assertUnchanged("A stray " + stray + " tick.\n\n```sh\necho `date` ; A=$X; \text=1; B=$Y\n```\n");
        }
    }

    @Test
    void aStrayBacktickCannotCrossABlankLine() {
        for (var blank : List.of("\n\n", "\r\n\r\n", "\n   \n")) {
            assertUnchanged("A stray ` tick." + blank + "Run `A=$X; \text=1; B=$Y` now.");
        }
    }

    @Test
    void anInlineSpanMayStillCrossASingleLineBreak() {
        assertUnchanged("Run `A=$X;\n\text=1; B=$Y` now.");
    }

    @Test
    void paragraphsNoLongerDisappearBetweenTwoStrayBackticks() {
        assertRepaired(
            "A stray ` tick.\n\nvalue $\tau$ here\n\nanother ` tick.",
            "A stray ` tick.\n\nvalue $\\tau$ here\n\nanother ` tick."
        );
    }

    @Test
    void backslashEscapesFollowParityOnTheOpenerOnly() {
        assertUnchanged("Type \\`x then `A=$X; \text=1; B=$Y`");
        assertUnchanged("Path C:\\\\ then `A=$X; \text=1; B=$Y`");
        // The closer deliberately does NOT honour the escape: CommonMark gives escapes no effect
        // inside a code span, so a backslash-backtick closes it.
        assertUnchanged("`A=$X; \text=1; B=$Y\\`");
        assertRepaired("Use \\`foo, then $\tau$ and \\`bar", "Use \\`foo, then $\\tau$ and \\`bar");
    }

    @Test
    void unquotedSingleLineShellIsStillRewritten() {
        // Pinned remaining exposure: one line, no quotes, no backticks -- nothing separates it
        // from "$x ... $y$".
        assertRepaired("A=$X; \text=1; B=$Y", "A=$X; \\text=1; B=$Y");
    }

    @Test
    void acceptedCostOfTheContentGate() {
        assertUnchanged("$\text{\"x\"} + \tau$");
        assertUnchanged("see $a +\n\tau$ end");
        // Display math is exempt from the line-break limit.
        assertRepaired("$$a +\n\tau$$", "$$a +\n\\tau$$");
    }

    @Test
    void lineBreakCommandsSurviveTheInlineVeto() {
        // Every "\r" / "\n" residue is the damage AND a line break at once, so the veto must
        // exempt the residue whitelist or it rejects the damage itself.
        assertRepaired("$\nabla f$", "$\\nabla f$");
        assertRepaired("$x \notin A$", "$x \\notin A$");
        assertRepaired("$\rho + 1$", "$\\rho + 1$");
        assertRepaired("$x \right)$", "$x \\right)$");
    }

    @Test
    void inlineVetoCoversEveryLineEnding() {
        for (var lineBreak : List.of("\n", "\r", "\r\n")) {
            assertUnchanged("A=$X" + lineBreak + "\text=1" + lineBreak + "B=$Y");
            assertUnchanged("see $a +" + lineBreak + "\tau$ end");
            assertRepaired("$$a +" + lineBreak + "\tau$$", "$$a +" + lineBreak + "\\tau$$");
        }
    }

    @Test
    void unmatchedBacktickRunDoesNotOpenASpan() {
        assertRepaired("x `` unmatched $\tau$ then ` end", "x `` unmatched $\\tau$ then ` end");
    }

    @Test
    void unclosedDisplayDelimiterIsSkippedWhole() {
        assertUnchanged("Unclosed $$x and\text$ suffix");
        // An inline opener is still reached after the failed display delimiter.
        assertRepaired("$$ broken and $\tau$ here", "$$ broken and $\\tau$ here");
    }

    @Test
    void displayMathWithNewlinesIsRepaired() {
        assertRepaired("$$\n\tau^2\n$$", "$$\n\\tau^2\n$$");
        assertRepaired("$$\n\nabla f$$", "$$\n\\nabla f$$");
    }

    @Test
    void paddedInlineSpanAndUnpairedOrEscapedDollarsAreNotMath() {
        assertUnchanged("$ \tau $");
        assertUnchanged("价格$5，公式$\tau$为");
        assertUnchanged("price $5 then\tau");
        assertUnchanged("escaped \\$\tau$");
    }

    @Test
    void mathWhitespaceRepairHandlesMultipleSpans() {
        var damaged = "first $\tau^2$, second $$a \times b$$";
        var expected = "first $\\tau^2$, second $$a \\times b$$";
        assertThat(LatexEscapeRepair.repair(damaged)).isEqualTo(expected);
        assertThat(LatexEscapeRepair.repair(expected)).isEqualTo(expected);
    }

    @Test
    void legitimateWhitespaceIsNotFlagged() {
        assertUnchanged("col1\tauthor list\nablation studies follow\nexists in the table");
    }

    @Test
    void mixedEscapingRealWorldResponse() {
        var decoded = "GraphRAG消耗$\u000crac{610 \\times 1,000}{C_{\\text{max}}}$次调用";
        var repaired = LatexEscapeRepair.repair(decoded, "doc-1:0");
        assertThat(repaired).contains("$\\frac{610 \\times 1,000}{C_{\\text{max}}}$");
        assertThat(repaired).doesNotContain("\u000c");
    }

    @Test
    void inMathPatternStaysASupersetOfTheProsePattern() {
        // The contract's reason a repaired span never re-triggers the prose warning: widening
        // the prose guard is only safe while every prose match is also a math match.
        var tails = Map.of(
            "\t", List.of("au", "heta", "imes", "ext", "ilde", "herefore", "riangle"),
            "\r", List.of("ho", "ight", "angle", "ceil"),
            "\n", List.of("abla", "otin")
        );
        var followers = List.of("", " ", ".", "_", "2", "x", "为", "。", "Δ");
        for (var entry : tails.entrySet()) {
            for (var tail : entry.getValue()) {
                for (var follower : followers) {
                    var text = "prefix" + entry.getKey() + tail + follower;
                    if (LatexEscapeRepair.WS_LATEX_SUSPECT_PATTERN.matcher(text).find()) {
                        assertThat(LatexEscapeRepair.WS_LATEX_MATH_PATTERN.matcher(text).find())
                            .as("math pattern must also match: %s", text)
                            .isTrue();
                    }
                }
            }
        }
    }

    @Test
    void sanitizerRestoresLatexBeforeDroppingControlCharacters() {
        assertThat(TextSanitizer.sanitizeForEncoding("cost $\u000crac{610}{C}$"))
            .isEqualTo("cost $\\frac{610}{C}$");
        // Isolated control characters the repair leaves alone are dropped as before.
        assertThat(TextSanitizer.sanitizeForEncoding("junk\u000c after\u0008.")).isEqualTo("junk after.");
    }
}
