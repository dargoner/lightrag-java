package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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
}
