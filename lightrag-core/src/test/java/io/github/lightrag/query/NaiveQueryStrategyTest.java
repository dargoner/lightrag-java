package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.indexing.ParentChildChunkBuilder;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NaiveQueryStrategyTest {
    @Test
    void naiveUsesDirectChunkSimilarityWithoutGraphMatches() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedEntities()).isEmpty();
        assertThat(context.matchedRelations()).isEmpty();
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
        assertThat(context.assembledContext())
            .contains("Chunks:")
            .contains("chunk-1")
            .contains("Alice works with Bob");
    }

    @Test
    void naiveTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedEntities()).isEmpty();
        assertThat(context.matchedRelations()).isEmpty();
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    @Test
    void naiveIgnoresTopKAndUsesChunkTopKForDirectChunkRetrieval() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void naiveAppliesMetadataFiltersBeforeParentExpansion() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("naive question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("naive question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(2)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
        assertThat(context.matchedChunks())
            .allSatisfy(match -> assertThat(match.chunk().metadata()).containsEntry("region", "shanghai"));
    }

    @Test
    void naiveBreaksScoreTiesByChunkId() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-a",
            "doc-1",
            "Alpha",
            1,
            0,
            Map.of()
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-b",
            "doc-1",
            "Beta",
            1,
            1,
            Map.of()
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-b", List.of(1.0d, 0.0d)),
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-a", List.of(1.0d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("tie question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("tie question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-a", "chunk-b");
    }

    @Test
    void retrievesChildVectorsButReturnsParentContext() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent",
            "doc-1",
            "Parent context with full explanation",
            5,
            0,
            Map.of(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT)
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent#child:0",
            "doc-1",
            "full explanation",
            2,
            1,
            Map.of(
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent"
            )
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-parent#child:0", List.of(1.0d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("parent question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("parent question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-parent");
        assertThat(context.matchedChunks().get(0).chunk().text()).isEqualTo("Parent context with full explanation");
        assertThat(context.assembledContext()).contains("chunk-parent");
    }

    @Test
    void aggregatesMultipleChildMatchesIntoHigherRankedParentContext() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent-a",
            "doc-1",
            "Parent A full context",
            4,
            0,
            Map.of(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT)
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent-a#child:0",
            "doc-1",
            "Alpha details",
            2,
            1,
            Map.of(
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent-a"
            )
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent-a#child:1",
            "doc-1",
            "Alpha appendix",
            2,
            2,
            Map.of(
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent-a"
            )
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent-b",
            "doc-2",
            "Parent B full context",
            4,
            3,
            Map.of(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT)
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent-b#child:0",
            "doc-2",
            "Beta detail",
            2,
            4,
            Map.of(
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent-b"
            )
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-parent-a#child:0", List.of(0.6d, 0.0d)),
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-parent-a#child:1", List.of(0.55d, 0.0d)),
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-parent-b#child:0", List.of(0.9d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("aggregate question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("aggregate question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(3)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-parent-a", "chunk-parent-b");
        assertThat(context.matchedChunks().get(0).score()).isGreaterThan(context.matchedChunks().get(1).score());
    }

    @Test
    void naiveDropsExpandedParentWhenParentMetadataDoesNotMatch() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent",
            "doc-1",
            "Parent context",
            4,
            0,
            Map.of("region", "beijing")
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent#child:0",
            "doc-1",
            "Child context",
            2,
            1,
            Map.of(
                "region", "shanghai",
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent"
            )
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-parent#child:0", List.of(1.0d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("parent filter question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("parent filter question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks()).isEmpty();
    }

    @Test
    void expandsCaptionHitsIntoAdjacentFigureContext() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-text",
            "doc-1",
            "2014年至今，国家先后从医院信息化层面进行政策改革，具体如下图所示。",
            32,
            0,
            Map.of(
                "smart_chunker.content_type", "text",
                "smart_chunker.section_path", "1.2.1 医疗产业政策",
                "smart_chunker.prev_chunk_id", "",
                "smart_chunker.next_chunk_id", "chunk-image"
            )
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-image",
            "doc-1",
            "images/demo.jpg",
            15,
            1,
            Map.of(
                "smart_chunker.content_type", "image_placeholder",
                "smart_chunker.section_path", "1.2.1 医疗产业政策",
                "smart_chunker.primary_image_path", "images/demo.jpg",
                "smart_chunker.prev_chunk_id", "chunk-text",
                "smart_chunker.next_chunk_id", "chunk-caption"
            )
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-caption",
            "doc-1",
            "图 1-4：历次医疗改革中医疗信息化政策重点一览图",
            25,
            2,
            Map.of(
                "smart_chunker.content_type", "caption",
                "smart_chunker.section_path", "1.2.1 医疗产业政策",
                "smart_chunker.primary_image_path", "images/demo.jpg",
                "smart_chunker.prev_chunk_id", "chunk-image",
                "smart_chunker.next_chunk_id", "chunk-tail"
            )
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-tail",
            "doc-1",
            "后续治理流程逐步固化。",
            12,
            3,
            Map.of(
                "smart_chunker.content_type", "text",
                "smart_chunker.section_path", "1.2.1 医疗产业政策",
                "smart_chunker.prev_chunk_id", "chunk-caption",
                "smart_chunker.next_chunk_id", ""
            )
        ));
        storage.vectorStore().saveAll("chunks", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("chunk-caption", List.of(1.0d, 0.0d))
        ));

        var strategy = new NaiveQueryStrategy(
            new FakeEmbeddingModel(Map.of("figure question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("figure question")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-caption");
        assertThat(context.matchedChunks().get(0).chunk().text())
            .contains("2014年至今，国家先后从医院信息化层面进行政策改革")
            .contains("images/demo.jpg")
            .contains("图 1-4：历次医疗改革中医疗信息化政策重点一览图")
            .contains("后续治理流程逐步固化。");
    }

    private record FakeEmbeddingModel(Map<String, List<Double>> vectorsByText) implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> vectorsByText.getOrDefault(text, List.of(0.0d, 0.0d)))
                .toList();
        }
    }
}
