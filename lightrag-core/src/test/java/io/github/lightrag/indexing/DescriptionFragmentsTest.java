package io.github.lightrag.indexing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptionFragmentsTest {
    @Test
    void combinesStoredFragmentsFirstAndDropsExactDuplicates() {
        var combined = DescriptionFragments.combine(
            List.of("stored one", "stored two"),
            List.of("stored two", "new three")
        );

        assertThat(combined).containsExactly("stored one", "stored two", "new three");
    }

    @Test
    void deduplicatesFragmentsThatDifferOnlyByIllegalCharacters() {
        var combined = DescriptionFragments.combine(
            List.of("badtext"),
            List.of("bad\u0000text", "kept")
        );

        assertThat(combined).containsExactly("badtext", "kept");
    }

    @Test
    void dropsFragmentsThatSanitizeToEmpty() {
        var combined = DescriptionFragments.combine(
            List.of(),
            List.of("", "   ", "\u0000")
        );

        assertThat(combined).isEmpty();
    }

    @Test
    void splitsJoinedDescriptionsAndDropsBlanks() {
        assertThat(DescriptionFragments.split("first<SEP>second")).containsExactly("first", "second");
        assertThat(DescriptionFragments.split("first<SEP><SEP>  <SEP>second")).containsExactly("first", "second");
        assertThat(DescriptionFragments.split("")).isEmpty();
        assertThat(DescriptionFragments.split(null)).isEmpty();
    }

    @Test
    void roundTripsCombinedFragmentsThroughJoinAndSplit() {
        var combined = DescriptionFragments.combine(
            List.of("stored"),
            List.of("new", "stored")
        );

        var joined = String.join(DescriptionFragments.SEPARATOR, combined);

        assertThat(DescriptionFragments.split(joined)).isEqualTo(combined);
    }
}
