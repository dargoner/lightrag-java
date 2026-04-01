package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkBuilderTest {
    @Test
    void skipsParentChildBuilderWhenDisabled() {
        var builder = new ParentChildChunkBuilder();
        var parent = new Chunk("chunk-1", "doc-1", "abcdefghij", 10, 0, Map.of("source", "unit-test"));
        var result = new ChunkingResult(List.of(parent), ChunkingMode.SMART, false, null);

        var chunkSet = builder.build(result, ParentChildProfile.disabled());

        assertThat(chunkSet.parentChunks()).containsExactly(
            new Chunk(
                "chunk-1",
                "doc-1",
                "abcdefghij",
                10,
                0,
                Map.of(
                    "source", "unit-test",
                    ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT,
                    ParentChildChunkBuilder.METADATA_PARENT_SUMMARY, "abcdefghij"
                )
            )
        );
        assertThat(chunkSet.childChunks()).isEmpty();
        assertThat(chunkSet.searchableChunks())
            .extracting(Chunk::id)
            .containsExactly("chunk-1");
    }

    @Test
    void buildsSentenceAwareChildChunksAndCopiesParentSummaryMetadata() {
        var builder = new ParentChildChunkBuilder();
        var parent = new Chunk(
            "chunk-1",
            "doc-1",
            "第一段介绍系统背景。第二段说明检索策略。第三段总结收益。",
            27,
            0,
            Map.of(
                "source", "unit-test",
                SmartChunkMetadata.SECTION_PATH, "第二章 检索设计"
            )
        );
        var result = new ChunkingResult(List.of(parent), ChunkingMode.SMART, false, null);

        var chunkSet = builder.build(result, ParentChildProfile.enabled(16, 4));

        assertThat(chunkSet.parentChunks()).hasSize(1);
        assertThat(chunkSet.parentChunks().get(0).metadata())
            .containsEntry(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT)
            .containsKey(ParentChildChunkBuilder.METADATA_PARENT_SUMMARY);
        assertThat(chunkSet.childChunks())
            .extracting(Chunk::text)
            .containsExactly(
                "第一段介绍系统背景。",
                "第二段说明检索策略。",
                "第三段总结收益。"
            );
        assertThat(chunkSet.childChunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD)
                .containsEntry(ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-1")
                .containsEntry(
                    ParentChildChunkBuilder.METADATA_PARENT_SUMMARY,
                    "第二章 检索设计 | 第一段介绍系统背景。"
                ));
    }

    @Test
    void keepsCaptionAsParentOnlySearchableChunk() {
        var builder = new ParentChildChunkBuilder();
        var caption = new Chunk(
            "chunk-caption",
            "doc-1",
            "图 1-4：历次医疗改革中医疗信息化政策重点一览图",
            25,
            0,
            Map.of(
                SmartChunkMetadata.CONTENT_TYPE, "caption",
                SmartChunkMetadata.SECTION_PATH, "1.2.1 医疗产业政策",
                SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/demo.jpg"
            )
        );

        var chunkSet = builder.build(
            new ChunkingResult(List.of(caption), ChunkingMode.SMART, false, null),
            ParentChildProfile.enabled(16, 4)
        );

        assertThat(chunkSet.childChunks()).isEmpty();
        assertThat(chunkSet.searchableChunks())
            .extracting(Chunk::id)
            .containsExactly("chunk-caption");
    }

    @Test
    void suppressesStandaloneImagePlaceholderFromSearchableChunks() {
        var builder = new ParentChildChunkBuilder();
        var image = new Chunk(
            "chunk-image",
            "doc-1",
            "images/demo.jpg",
            15,
            0,
            Map.of(
                SmartChunkMetadata.CONTENT_TYPE, "image_placeholder",
                SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/demo.jpg"
            )
        );

        var chunkSet = builder.build(
            new ChunkingResult(List.of(image), ChunkingMode.SMART, false, null),
            ParentChildProfile.enabled(16, 4)
        );

        assertThat(chunkSet.childChunks()).isEmpty();
        assertThat(chunkSet.searchableChunks()).isEmpty();
    }

    @Test
    void splitsTableIntoHeaderAnchoredRowGroupChildren() {
        var builder = new ParentChildChunkBuilder();
        var table = new Chunk(
            "chunk-table",
            "doc-1",
            """
            | Name | Cost |
            | --- | --- |
            | A | 1 |
            | B | 2 |
            | C | 3 |
            """,
            49,
            0,
            Map.of(
                SmartChunkMetadata.CONTENT_TYPE, "table",
                SmartChunkMetadata.SECTION_PATH, "附录 A"
            )
        );

        var chunkSet = builder.build(
            new ChunkingResult(List.of(table), ChunkingMode.SMART, false, null),
            ParentChildProfile.enabled(30, 4)
        );

        assertThat(chunkSet.childChunks()).isNotEmpty();
        assertThat(chunkSet.childChunks())
            .allSatisfy(chunk -> assertThat(chunk.text()).startsWith("| Name | Cost |\n| --- | --- |"));
        assertThat(chunkSet.searchableChunks())
            .extracting(Chunk::id)
            .allMatch(id -> id.startsWith("chunk-table#child:"));
    }

    @Test
    void searchableChunksNormalizesOrdersWhenCaptionAndChildChunksCoexist() {
        var builder = new ParentChildChunkBuilder();
        var result = new ChunkingResult(List.of(
            new Chunk(
                "chunk-text",
                "doc-1",
                "2014年至今，国家先后从医院信息化层面进行政策改革。后续治理流程逐步固化。",
                40,
                0,
                Map.of(
                    SmartChunkMetadata.CONTENT_TYPE, "text",
                    SmartChunkMetadata.SECTION_PATH, "1.2.1 医疗产业政策"
                )
            ),
            new Chunk(
                "chunk-caption",
                "doc-1",
                "图 1-4：历次医疗改革中医疗信息化政策重点一览图",
                20,
                1,
                Map.of(
                    SmartChunkMetadata.CONTENT_TYPE, "caption",
                    SmartChunkMetadata.SECTION_PATH, "1.2.1 医疗产业政策",
                    SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/demo.jpg"
                )
            )
        ), ChunkingMode.SMART, false, null);

        var chunkSet = builder.build(result, ParentChildProfile.enabled(18, 4));

        assertThat(chunkSet.searchableChunks())
            .extracting(Chunk::order)
            .containsExactly(0, 1, 2, 3);
    }
}
