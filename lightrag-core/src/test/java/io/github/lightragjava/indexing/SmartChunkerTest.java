package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmartChunkerTest {
    @Test
    void splitsOnSentenceBoundariesAndKeepsSentenceAwareOverlap() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(20)
            .maxTokens(25)
            .overlapTokens(10)
            .build());
        var document = new Document("doc-1", "Guide", "Alpha one. Beta two. Gamma three.", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly(
                "Alpha one. Beta two.",
                "Beta two. Gamma three."
            );
        assertThat(chunks)
            .extracting(Chunk::order)
            .containsExactly(0, 1);
    }

    @Test
    void preservesSourceMetadataAndAddsSmartChunkMetadata() {
        var chunker = new SmartChunker(SmartChunkerConfig.defaults());
        var document = new Document(
            "doc-1",
            "Guide",
            "Only one sentence.",
            Map.of(
                "source", "guide.md",
                "file_path", "/docs/guide.md"
            )
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata())
            .containsEntry("source", "guide.md")
            .containsEntry("file_path", "/docs/guide.md")
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Guide")
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "text")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "paragraph:0");
    }

    @Test
    void usesHeadingHierarchyAsSectionPath() {
        var chunker = new SmartChunker(SmartChunkerConfig.defaults());
        var document = new Document(
            "doc-2",
            "Guide",
            "# Policies\n## Travel\nCarry your passport.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Policies > Travel");
    }

    @Test
    void keepsLeadSentenceWithListItems() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(64)
            .maxTokens(64)
            .overlapTokens(8)
            .build());
        var document = new Document(
            "doc-3",
            "Guide",
            "# Rules\nExceptions:\n- War\n- Flood",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Exceptions:").contains("- War").contains("- Flood");
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "list")
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Rules");
    }

    @Test
    void repeatsTableHeaderWhenTableIsSplit() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(40)
            .maxTokens(40)
            .overlapTokens(8)
            .build());
        var document = new Document(
            "doc-4",
            "Guide",
            "# Prices\n| Name | Cost |\n| --- | --- |\n| A | 1 |\n| B | 2 |\n| C | 3 |\n| D | 4 |",
            Map.of("source", "prices.md")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
            .extracting(Chunk::text)
            .allSatisfy(text -> assertThat(text).startsWith("| Name | Cost |\n| --- | --- |"));
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "table")
            .containsEntry("smart_chunker.table_part_index", "1")
            .containsEntry("smart_chunker.prev_chunk_id", "");
        assertThat(chunks.get(1).metadata())
            .containsEntry("smart_chunker.table_part_index", "2")
            .containsEntry("smart_chunker.prev_chunk_id", "doc-4:0");
    }

    @Test
    void mergesAdjacentParagraphsWhenSemanticRefinementIsEnabled() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.2d)
            .build());
        var document = new Document(
            "doc-5",
            "Guide",
            "Retrieval pipeline overview.\n\nRetrieval pipeline details.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
            .contains("Retrieval pipeline overview.")
            .contains("Retrieval pipeline details.");
    }

    @Test
    void exposesStructuralChunksBeforeStandaloneSemanticMerge() {
        var builder = SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .semanticMergeThreshold(0.2d);

        var document = new Document(
            "doc-1",
            "Guide",
            "Retrieval overview.\n\nRetrieval details.",
            Map.of()
        );

        var semanticChunker = new SmartChunker(builder.semanticMergeEnabled(true).build());
        var structuralChunks = semanticChunker.chunkStructural(document);

        var baselineChunker = new SmartChunker(builder.semanticMergeEnabled(false).build());
        var baselineChunks = baselineChunker.chunk(document);

        assertThat(structuralChunks).containsExactlyElementsOf(baselineChunks);
    }

    @Test
    void advancesWhenFirstSentenceAloneFillsChunk() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(3)
            .build());

        var document = new Document(
            "doc-2",
            "Guide",
            "1234567. tail.",
            Map.of()
        );

        assertThat(chunker.chunk(document)).isNotEmpty();
    }

    @Test
    void fallsBackWhenSentenceExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_sentence",
            "Guide",
            "1234567890. Follow-up short sentence.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenListItemExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_list",
            "Guide",
            "List:\n- 1234567890123456\n- Short",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenTableRowExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(16)
            .maxTokens(16)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_table",
            "Guide",
            "# Data\n| Name | Value |\n| --- | --- |\n| 12345678901234 | Extra |",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(16));
    }
}
