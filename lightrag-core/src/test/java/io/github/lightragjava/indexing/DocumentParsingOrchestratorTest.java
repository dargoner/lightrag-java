package io.github.lightragjava.indexing;

import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
    void rejectsUnsupportedBinaryMediaTypes() {
        var orchestrator = new DocumentParsingOrchestrator(new PlainTextParsingProvider());
        var source = RawDocumentSource.bytes(
            "guide.bin",
            new byte[] {1, 2, 3},
            "application/octet-stream",
            Map.of()
        );

        assertThatThrownBy(() -> orchestrator.parse(source, DocumentIngestOptions.defaults()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("application/octet-stream");
    }

    @Test
    void fallsBackToTikaWhenMineruIsUnavailableForPdf() {
        var orchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new FailingMineruClient("mineru_api", "mineru offline"),
                new MineruDocumentAdapter()
            ),
            new TikaFallbackParsingProvider(source -> "Recovered from tika")
        );
        var source = RawDocumentSource.bytes(
            "guide.pdf",
            "%PDF".getBytes(StandardCharsets.UTF_8),
            "application/pdf",
            Map.of("source", "upload")
        );

        var parsed = orchestrator.parse(source, DocumentIngestOptions.defaults());

        assertThat(parsed.plainText()).isEqualTo("Recovered from tika");
        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "tika")
            .containsEntry("parse_backend", "tika")
            .containsEntry("parse_error_reason", "mineru offline")
            .containsEntry("source", "upload");
    }

    @Test
    void failsInsteadOfUsingTikaWhenImageMineruParsingFails() {
        var orchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new FailingMineruClient("mineru_self_hosted", "mineru image parse failed"),
                new MineruDocumentAdapter()
            ),
            new TikaFallbackParsingProvider(source -> {
                throw new AssertionError("tika fallback must not be used for images");
            })
        );
        var source = RawDocumentSource.bytes(
            "scan.png",
            new byte[] {1, 2, 3},
            "image/png",
            Map.of()
        );

        assertThatThrownBy(() -> orchestrator.parse(source, DocumentIngestOptions.defaults()))
            .isInstanceOf(MineruUnavailableException.class)
            .hasMessageContaining("mineru image parse failed");
    }

    @Test
    void recordsParseModeBackendAndErrorReasonOnDowngrade() {
        var orchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new FailingMineruClient("mineru_api", "timeout contacting mineru"),
                new MineruDocumentAdapter()
            ),
            new TikaFallbackParsingProvider(source -> "Recovered html")
        );
        var source = RawDocumentSource.bytes(
            "page.html",
            "<html><body>Recovered html</body></html>".getBytes(StandardCharsets.UTF_8),
            "text/html",
            Map.of("workspace", "alpha")
        );

        var parsed = orchestrator.parse(source, DocumentIngestOptions.defaults());

        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "tika")
            .containsEntry("parse_backend", "tika")
            .containsEntry("parse_error_reason", "timeout contacting mineru")
            .containsEntry("workspace", "alpha");
    }

    private static final class FailingMineruClient implements MineruClient {
        private final String backend;
        private final String message;

        private FailingMineruClient(String backend, String message) {
            this.backend = backend;
            this.message = message;
        }

        @Override
        public String backend() {
            return backend;
        }

        @Override
        public ParseResult parse(RawDocumentSource source) {
            throw new MineruUnavailableException(message);
        }
    }
}
