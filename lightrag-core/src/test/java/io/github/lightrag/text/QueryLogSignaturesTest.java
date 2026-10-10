package io.github.lightrag.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryLogSignaturesTest {

    @Test
    void ofReportsStrippedLengthAndHashWithoutEchoingText() {
        assertThat(QueryLogSignatures.of(null)).isEqualTo("len=0");
        assertThat(QueryLogSignatures.of("   ")).isEqualTo("len=0");

        var signature = QueryLogSignatures.of("  secret user query  ");
        assertThat(signature).startsWith("len=17,hash=");
        assertThat(signature).doesNotContain("secret", "query");
    }

    @Test
    void countReportsEntryCountOnlyForKeywordCollections() {
        assertThat(QueryLogSignatures.count(null)).isZero();
        assertThat(QueryLogSignatures.count(List.of())).isZero();
        assertThat(QueryLogSignatures.count(List.of("alpha", "", "beta"))).isEqualTo(3);
    }
}
