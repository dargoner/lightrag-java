package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FilePathLimitsTest {
    @Test
    void keepsTheHeadAndAppendsTheTruncationMarkerWhenOverTheLimit() {
        var paths = IntStream.rangeClosed(1, 80).mapToObj(i -> "/docs/" + i + ".md").toList();

        var limited = FilePathLimits.apply(paths, 75, SourceIdLimits.Method.KEEP);

        assertThat(limited).hasSize(76).endsWith("...truncated...(KEEP Old)");
        assertThat(limited.subList(0, 75)).containsExactlyElementsOf(paths.subList(0, 75));
    }

    @Test
    void skipsExistingPlaceholdersSoTheyNeverAccumulate() {
        var paths = new ArrayList<String>();
        IntStream.rangeClosed(1, 80).mapToObj(i -> "/docs/" + i + ".md").forEach(paths::add);
        paths.add("...truncated...(KEEP Old)");

        var limited = FilePathLimits.apply(paths, 75, SourceIdLimits.Method.KEEP);

        assertThat(limited).hasSize(76);
        assertThat(limited.stream().filter(path -> path.startsWith("...truncated"))).hasSize(1);
        assertThat(limited.get(75)).isEqualTo("...truncated...(KEEP Old)");
    }

    @Test
    void fifoKeepsTheTailAndDedupesInOrder() {
        var paths = IntStream.rangeClosed(1, 80).mapToObj(i -> "/docs/" + i + ".md")
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        paths.add("/docs/40.md");

        var limited = FilePathLimits.apply(paths, 75, SourceIdLimits.Method.FIFO);

        assertThat(limited).hasSize(76).endsWith("...truncated...(FIFO)");
        assertThat(limited.subList(0, 75)).containsExactlyElementsOf(paths.subList(5, 80));
    }
}
