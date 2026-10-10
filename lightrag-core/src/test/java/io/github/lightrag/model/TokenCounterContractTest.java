package io.github.lightrag.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the {@link TokenCounter} contract that {@code QueryBudgeting} relies on when it finds the
 * longest fitting prefix by binary search. A counter that breaks monotonicity could make budgeting
 * select a different row than the upstream incremental scan would.
 */
class TokenCounterContractTest {

    private static final List<String> FRAGMENTS = List.of(
        "alpha", "bravo", "中文实体", "コンニチハ", "한국어", "résumé", "x9",
        "long-word-abcdefghijklmnop", "混合 mixed 文本", " ", "\n", "1234567890",
        "a", "中", "🀄"
    );

    @Test
    void defaultCounterIsMonotoneUnderAppending() {
        var counter = new HeuristicTokenCounter();
        var random = new Random(20261010L);
        for (var round = 0; round < 1000; round++) {
            var prefix = randomText(random);
            var suffix = randomText(random);
            assertThat(counter.countTokens(prefix + suffix))
                .as("round %s: appending %s to %s must not decrease the count", round, suffix, prefix)
                .isGreaterThanOrEqualTo(counter.countTokens(prefix));
        }
    }

    @Test
    void defaultCounterCountsEmptyAndNullTextAsZero() {
        var counter = new HeuristicTokenCounter();
        assertThat(counter.countTokens("")).isZero();
        assertThat(counter.countTokens(null)).isZero();
    }

    private static String randomText(Random random) {
        var builder = new StringBuilder();
        var parts = random.nextInt(8);
        for (var index = 0; index < parts; index++) {
            builder.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
        }
        return builder.toString();
    }
}
