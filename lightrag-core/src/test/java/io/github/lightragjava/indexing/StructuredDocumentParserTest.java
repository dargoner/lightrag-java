package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDocumentParserTest {
    @Test
    void extractsSectionPathsFromMarkdownHeadings() {
        var parser = new StructuredDocumentParser();
        var document = new Document(
            "doc-1",
            "Guide",
            "# Policies\n## Travel\nCarry your passport.",
            Map.of()
        );

        var structured = parser.parse(document);

        assertThat(structured.blocks())
            .extracting(StructuredBlock::sectionPath)
            .containsExactly("Policies > Travel");
        assertThat(structured.blocks())
            .extracting(StructuredBlock::content)
            .containsExactly("Carry your passport.");
    }

    @Test
    void detectsParagraphListAndTableBlocks() {
        var parser = new StructuredDocumentParser();
        var document = new Document(
            "doc-2",
            "Guide",
            "Intro text.\n\nLead:\n- First\n- Second\n| Name | Cost |\n| --- | --- |\n| A | 1 |",
            Map.of()
        );

        var structured = parser.parse(document);

        assertThat(structured.blocks())
            .extracting(StructuredBlock::type)
            .containsExactly(
                StructuredBlock.Type.PARAGRAPH,
                StructuredBlock.Type.LIST,
                StructuredBlock.Type.TABLE
            );
    }

    @Test
    void filtersChineseNoiseLinesBeforeBuildingBlocks() {
        var parser = new StructuredDocumentParser();
        var document = new Document(
            "doc-3",
            "白皮书",
            "目 录\n第一章........................1\ntext_list text\n- 1 -\n\n# 第一章\n正文第一段。\n第 2 页",
            Map.of()
        );

        var structured = parser.parse(document);

        assertThat(structured.blocks())
            .extracting(StructuredBlock::sectionPath)
            .containsExactly("第一章");
        assertThat(structured.blocks())
            .extracting(StructuredBlock::content)
            .containsExactly("正文第一段。");
    }
}
