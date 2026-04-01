package io.github.lightrag.indexing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTextSanitizerTest {
    @Test
    void repairsConservativeOcrArtifactsInsideBlockText() {
        var sanitizer = new ChunkTextSanitizer();

        var repaired = sanitizer.sanitizeBlockText("数值达到 3.0 \\%\ndigi-\ntal economy");

        assertThat(repaired).isEqualTo("数值达到 3.0%\ndigital economy");
    }
}
