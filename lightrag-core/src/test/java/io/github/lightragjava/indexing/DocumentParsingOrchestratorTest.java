package io.github.lightragjava.indexing;

import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParsingOrchestratorTest {
    @Test
    void parsesPlainTextSourcesAsPlainText() {
        var orchestrator = new DocumentParsingOrchestrator(new PlainTextParsingProvider());
        var source = RawDocumentSource.bytes("guide.md", "# Title\nBody".getBytes(StandardCharsets.UTF_8));

        var parsed = orchestrator.parse(source);

        assertThat(parsed.documentId()).isEqualTo(source.sourceId());
        assertThat(parsed.title()).isEqualTo("guide.md");
        assertThat(parsed.plainText()).isEqualTo("# Title\nBody");
        assertThat(parsed.blocks()).isEmpty();
    }

    @Test
    void rejectsNonTextMediaTypesForPlainTextPath() {
        var orchestrator = new DocumentParsingOrchestrator(new PlainTextParsingProvider());
        var source = RawDocumentSource.bytes(
            "guide.pdf",
            "%PDF".getBytes(StandardCharsets.UTF_8),
            "application/pdf",
            Map.of()
        );

        assertThatThrownBy(() -> orchestrator.parse(source, DocumentIngestOptions.defaults()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("application/pdf");
    }
}
