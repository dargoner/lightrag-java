package dev.io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkGranularity;
import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.indexing.ChunkingOrchestrator;
import io.github.lightrag.indexing.ChunkingStrategyOverride;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.DocumentTypeHint;
import io.github.lightrag.indexing.MineruApiClient;
import io.github.lightrag.indexing.MineruDocumentAdapter;
import io.github.lightrag.indexing.MineruParsingProvider;
import io.github.lightrag.indexing.ParentChildProfile;
import io.github.lightrag.indexing.PlainTextParsingProvider;
import io.github.lightrag.indexing.RegexChunkerConfig;
import io.github.lightrag.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MineruSmartChunkerRealApiManualTest {
    private static final String DEMO_PDF_URL = "https://cdn-mineru.openxlab.org.cn/demo/example.pdf";

    @Test
    void parsesOnlinePdfAndProducesSmartChunks() {
        var apiKey = System.getenv("MINERU_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "MINERU_API_KEY is required for this manual test");

        var parsingOrchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new MineruApiClient(new MineruApiClient.HttpTransport(
                    "https://mineru.net/api/v4/extract/task",
                    apiKey,
                    Duration.ofSeconds(90),
                    1_000,
                    90
                )),
                new MineruDocumentAdapter()
            ),
            null
        );
        var source = RawDocumentSource.bytes(
            "example.pdf",
            new byte[] {1},
            "application/pdf",
            Map.of(MineruApiClient.SOURCE_URL_METADATA_KEY, DEMO_PDF_URL)
        );
        var parsed = parsingOrchestrator.parse(source, new DocumentIngestOptions(
            DocumentTypeHint.BOOK,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled()
        ));

        var result = new ChunkingOrchestrator().chunk(parsed, new DocumentIngestOptions(
            DocumentTypeHint.BOOK,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled()
        ));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "mineru")
            .containsEntry("parse_backend", "mineru_api");
        assertThat(result.effectiveMode().name()).isEqualTo("SMART");
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks().stream()
            .map(chunk -> chunk.metadata().get("smart_chunker.section_path"))
            .filter(path -> path != null && !path.isBlank()))
            .isNotEmpty();
        assertThat(result.chunks().stream()
            .map(chunk -> chunk.metadata().get("smart_chunker.content_type"))
            .filter(type -> type != null && !type.isBlank()))
            .isNotEmpty();
        assertThat(result.chunks().stream()
            .map(chunk -> chunk.text())
            .anyMatch(text -> text.contains("Abstract") || text.contains("Introduction")))
            .isTrue();

        System.out.println("parsed_blocks=" + parsed.blocks().size());
        System.out.println("smart_chunks=" + result.chunks().size());
        result.chunks().stream()
            .limit(5)
            .forEach(chunk -> System.out.println(
                "chunk order=" + chunk.order()
                    + " section=" + chunk.metadata().getOrDefault("smart_chunker.section_path", "")
                    + " type=" + chunk.metadata().getOrDefault("smart_chunker.content_type", "")
                    + " text=" + preview(chunk.text())
            ));
    }

    private static String preview(String text) {
        var normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
