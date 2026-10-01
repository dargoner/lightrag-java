package io.github.lightrag.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicTokenCounterTest {
    private final TokenCounter counter = new HeuristicTokenCounter();

    @Test
    void countsEachWideCodePointAsOneToken() {
        assertThat(counter.countTokens("住房公积金")).isEqualTo(5);
    }

    @Test
    void countsNonWideRunsAtFourCharactersPerToken() {
        assertThat(counter.countTokens("abcd")).isEqualTo(1);
        assertThat(counter.countTokens("abcde")).isEqualTo(2);
        assertThat(counter.countTokens("hello world")).isEqualTo(3); // 11 non-wide chars -> 3
    }

    @Test
    void handlesMixedTextAndEmptyInput() {
        assertThat(counter.countTokens("")).isZero();
        assertThat(counter.countTokens(null)).isZero();
        assertThat(counter.countTokens("租金 rent")).isEqualTo(4); // 2 wide + 5 non-wide chars -> 2 + 2
    }
}
