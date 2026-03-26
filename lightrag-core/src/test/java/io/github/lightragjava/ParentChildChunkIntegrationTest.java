package io.github.lightragjava;

import io.github.lightragjava.api.ChunkGranularity;
import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.indexing.ChunkingStrategyOverride;
import io.github.lightragjava.indexing.DocumentTypeHint;
import io.github.lightragjava.indexing.ParentChildProfile;
import io.github.lightragjava.indexing.RegexChunkerConfig;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkIntegrationTest {
    private static final String WORKSPACE = "default";

    @Test
    void ingestSourcesUsesParentSummaryAugmentedEmbeddingTextForChildChunks() {
        var storage = InMemoryStorageProvider.create();
        var embeddingModel = new RecordingEmbeddingModel();
        var rag = LightRag.builder()
            .chatModel(new StubChatModel())
            .embeddingModel(embeddingModel)
            .storage(storage)
            .build();

        rag.ingestSources(WORKSPACE, 
            List.of(RawDocumentSource.bytes(
                "charter.md",
                """
                # 第二章 研究章程

                第一段给出研究章程。第二段详细说明执行步骤。第三段收束实施边界。
                """.getBytes(StandardCharsets.UTF_8)
            )),
            new DocumentIngestOptions(
                DocumentTypeHint.AUTO,
                ChunkGranularity.MEDIUM,
                ChunkingStrategyOverride.AUTO,
                RegexChunkerConfig.empty(),
                ParentChildProfile.enabled(16, 4)
            )
        );

        assertThat(embeddingModel.recordedTexts())
            .anySatisfy(text -> assertThat(text)
                .contains("第二章 研究章程 | 第一段给出研究章程。")
                .contains("第二段详细说明执行步骤。"));
    }

    @Test
    void ingestSourcesIndexesChildChunksButQueryReturnsParentContext() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new StubChatModel())
            .embeddingModel(new RecordingEmbeddingModel())
            .storage(storage)
            .build();
        var source = new RawDocumentSource(
            "doc-source",
            "charter.md",
            "text/plain",
            """
            # 第二章 研究章程

            第一段给出研究章程。第二段详细说明执行步骤。第三段收束实施边界。
            """.getBytes(StandardCharsets.UTF_8),
            java.util.Map.of()
        );

        rag.ingestSources(WORKSPACE, 
            List.of(source),
            new DocumentIngestOptions(
                DocumentTypeHint.AUTO,
                ChunkGranularity.MEDIUM,
                ChunkingStrategyOverride.AUTO,
                RegexChunkerConfig.empty(),
                ParentChildProfile.enabled(16, 4)
            )
        );

        assertThat(storage.vectorStore().list("chunks"))
            .extracting(vector -> vector.id())
            .allSatisfy(id -> assertThat(id).contains("#child:"));

        var result = rag.query(WORKSPACE, QueryRequest.builder()
            .query("研究章程")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(result.contexts())
            .extracting(io.github.lightragjava.api.QueryResult.Context::sourceId)
            .containsExactly("doc-source:0");
        assertThat(result.contexts().get(0).text()).contains("第一段给出研究章程。");
    }

    @Test
    void ingestSourcesSkipsImagePlaceholderVectorsButKeepsCaptionSearchable() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new StubChatModel())
            .embeddingModel(new RecordingEmbeddingModel())
            .storage(storage)
            .build();
        var source = new RawDocumentSource(
            "doc-caption",
            "caption.md",
            "text/plain",
            """
            图 1-4：历次医疗改革中医疗信息化政策重点一览图
            """.getBytes(StandardCharsets.UTF_8),
            java.util.Map.of()
        );

        rag.ingestSources(WORKSPACE, 
            List.of(source),
            new DocumentIngestOptions(
                DocumentTypeHint.AUTO,
                ChunkGranularity.MEDIUM,
                ChunkingStrategyOverride.AUTO,
                RegexChunkerConfig.empty(),
                ParentChildProfile.enabled(16, 4)
            )
        );

        assertThat(storage.vectorStore().list("chunks"))
            .extracting(vector -> vector.id())
            .containsExactly("doc-caption:0");

        var result = rag.query(WORKSPACE, QueryRequest.builder()
            .query("医疗信息化政策重点一览图")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(result.contexts())
            .extracting(QueryResult.Context::sourceId)
            .containsExactly("doc-caption:0");
    }

    @Test
    void parentChildProfileImprovesSectionAwareRetrievalComparedWithFlatChunks() {
        var disabled = ingestWithProfile(ParentChildProfile.disabled(), new SectionAwareEmbeddingModel());
        var enabled = ingestWithProfile(ParentChildProfile.enabled(18, 4), new SectionAwareEmbeddingModel());

        var disabledResult = disabled.query(WORKSPACE, QueryRequest.builder()
            .query("研究章程 执行步骤")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());
        var enabledResult = enabled.query(WORKSPACE, QueryRequest.builder()
            .query("研究章程 执行步骤")
            .mode(QueryMode.NAIVE)
            .chunkTopK(1)
            .build());

        assertThat(disabledResult.contexts()).hasSize(1);
        assertThat(enabledResult.contexts()).hasSize(1);
        assertThat(disabledResult.contexts().get(0).text())
            .contains("合规章程")
            .doesNotContain("执行步骤");
        assertThat(enabledResult.contexts().get(0).text())
            .contains("执行步骤")
            .doesNotContain("合规章程");
    }

    private static LightRag ingestWithProfile(ParentChildProfile profile, EmbeddingModel embeddingModel) {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new StubChatModel())
            .embeddingModel(embeddingModel)
            .storage(storage)
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.ingestSources(WORKSPACE, 
            List.of(RawDocumentSource.bytes(
                "charter-comparison.md",
                """
                # 第二章 研究章程

                这里概述研究章程的制定背景与目标。

                详细说明执行步骤，包括采集、清洗、复核与归档。

                # 第三章 合规章程

                这里概述合规章程的制度边界与检查项。
                """.getBytes(StandardCharsets.UTF_8)
            )),
            new DocumentIngestOptions(
                DocumentTypeHint.AUTO,
                ChunkGranularity.MEDIUM,
                ChunkingStrategyOverride.AUTO,
                RegexChunkerConfig.empty(),
                profile
            )
        );
        return rag;
    }

    private static final class RecordingEmbeddingModel implements EmbeddingModel {
        private final List<String> recordedTexts = new ArrayList<>();

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            recordedTexts.addAll(texts);
            return texts.stream()
                .map(text -> List.of(1.0d, 0.0d))
                .toList();
        }

        List<String> recordedTexts() {
            return List.copyOf(recordedTexts);
        }
    }

    private static final class SectionAwareEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(SectionAwareEmbeddingModel::embed)
                .toList();
        }

        private static List<Double> embed(String text) {
            if (text.contains("研究章程") && text.contains("执行步骤")) {
                return List.of(1.0d, 0.0d);
            }
            if (text.contains("合规章程")) {
                return List.of(0.8d, 0.0d);
            }
            if (text.contains("研究章程")) {
                return List.of(0.7d, 0.0d);
            }
            if (text.contains("执行步骤")) {
                return List.of(0.0d, 1.0d);
            }
            return List.of(0.0d, 0.0d);
        }
    }

    private static final class StubChatModel implements ChatModel {
        @Override
        public String generate(ChatModel.ChatRequest request) {
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }
}
