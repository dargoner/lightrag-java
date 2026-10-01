package io.github.lightrag.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryValidationTest {
    @Test
    void rejectsEmptyAndWhitespaceOnlyQueries() {
        assertThatThrownBy(() -> QueryValidation.validateRagQuery("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> QueryValidation.validateRagQuery(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> QueryValidation.validateRagQuery(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void weightsEastAsianCharactersTwice() {
        assertThat(QueryValidation.meetsMinWeight("住房公积金")).isTrue();   // 5 x 2 = 10
        assertThat(QueryValidation.meetsMinWeight("你好")).isTrue();       // 2 x 2 = 4 >= 3
        assertThat(QueryValidation.meetsMinWeight("好")).isFalse();        // 2 < 3
        assertThat(QueryValidation.meetsMinWeight("日本語")).isTrue();     // Kana/Kanji block
        assertThat(QueryValidation.meetsMinWeight("한국")).isTrue();       // Hangul block
        assertThat(QueryValidation.meetsMinWeight("ab")).isFalse();
        assertThat(QueryValidation.meetsMinWeight("abc")).isTrue();
        assertThat(QueryValidation.meetsMinWeight("  tariffs  ")).isTrue();
    }

    @Test
    void rejectsQueriesBelowMinimumWeightWithUpstreamMessage() {
        assertThatThrownBy(() -> QueryValidation.validateRagQuery("ab"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("RAG query is too short. Enter at least 3 English characters or an equivalent "
                + "combination where each Chinese, Japanese or Korean character counts as 2.");
        assertThatThrownBy(() -> QueryValidation.validateRagQuery("好"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("RAG query is too short");
    }

    @Test
    void bypassPathOnlyRejectsEmpty() {
        QueryValidation.validateNotEmpty("好");
        assertThatThrownBy(() -> QueryValidation.validateNotEmpty(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryValidation.validateNotEmpty("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
