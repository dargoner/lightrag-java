package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MineruParsingProviderTest {
    @Test
    void adaptsStructuredBlocksForApiBackend() {
        var provider = new MineruParsingProvider(
            new FakeMineruClient(
                "mineru_api",
                new MineruClient.ParseResult(
                    List.of(new MineruClient.Block(
                        "block-1",
                        "paragraph",
                        "Structured paragraph",
                        "Chapter 1",
                        List.of("Chapter 1"),
                        1,
                        null,
                        1,
                        Map.of("kind", "body")
                    )),
                    "# ignored markdown"
                )
            ),
            new MineruDocumentAdapter()
        );
        var source = RawDocumentSource.bytes(
            "guide.pdf",
            "%PDF".getBytes(StandardCharsets.UTF_8),
            "application/pdf",
            Map.of("source", "upload")
        );

        var parsed = provider.parse(source);

        assertThat(parsed.plainText()).isEqualTo("Structured paragraph");
        assertThat(parsed.blocks()).hasSize(1);
        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "mineru")
            .containsEntry("parse_backend", "mineru_api")
            .containsEntry("source", "upload");
    }

    @Test
    void usesMarkdownFallbackWhenStructuredBlocksMissingForSelfHostedBackend() {
        var provider = new MineruParsingProvider(
            new FakeMineruClient(
                "mineru_self_hosted",
                new MineruClient.ParseResult(List.of(), "# Title\nFallback markdown")
            ),
            new MineruDocumentAdapter()
        );
        var source = RawDocumentSource.bytes(
            "guide.docx",
            "docx".getBytes(StandardCharsets.UTF_8),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Map.of()
        );

        var parsed = provider.parse(source);

        assertThat(parsed.plainText()).isEqualTo("# Title\nFallback markdown");
        assertThat(parsed.blocks()).isEmpty();
        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "mineru")
            .containsEntry("parse_backend", "mineru_self_hosted");
    }

    private record FakeMineruClient(String backend, MineruClient.ParseResult result) implements MineruClient {
        @Override
        public ParseResult parse(RawDocumentSource source) {
            return result;
        }
    }
}
