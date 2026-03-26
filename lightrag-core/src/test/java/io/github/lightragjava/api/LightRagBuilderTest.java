package io.github.lightragjava.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.indexing.FixedWindowChunker;
import io.github.lightragjava.indexing.SmartChunker;
import io.github.lightragjava.indexing.SmartChunkerConfig;
import io.github.lightragjava.indexing.DocumentTypeHint;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.storage.WorkspaceStorageProvider;
import io.github.lightragjava.storage.memory.InMemoryDocumentStatusStore;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import io.github.lightragjava.types.ExtractedRelation;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagBuilderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void buildsWithRequiredDependencies() {
        var chatModel = new FakeChatModel();
        var embeddingModel = new FakeEmbeddingModel();
        var storageProvider = new FakeStorageProvider();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .storage(storageProvider)
            .build();

        assertThat(rag).isNotNull();
        assertThat(rag.config().chatModel()).isSameAs(chatModel);
        assertThat(rag.config().embeddingModel()).isSameAs(embeddingModel);
        assertThat(rag.config().storageProvider()).isSameAs(storageProvider);
    }

    @Test
    void rejectsConfiguringStorageAndWorkspaceStorageTogether() {
        var builder = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider());

        assertThatThrownBy(() -> builder.workspaceStorage(new TestWorkspaceStorageProvider(new FakeStorageProvider())))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsLoadFromSnapshotWhenWorkspaceStorageIsConfigured() {
        var builder = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .workspaceStorage(new TestWorkspaceStorageProvider(new FakeStorageProvider()));

        assertThatThrownBy(() -> builder.loadFromSnapshot(Path.of("snapshots", "repository.json")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildsWithWorkspaceStorageProvider() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider(new FakeStorageProvider());

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .build();

        assertThat(rag).isNotNull();
        assertThat(rag.config().workspaceStorageProvider()).isSameAs(workspaceStorageProvider);
    }

    @Test
    void closesWorkspaceStorageProviderWhenLightRagIsClosed() {
        var workspaceStorageProvider = new TestWorkspaceStorageProvider(new FakeStorageProvider());

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .workspaceStorage(workspaceStorageProvider)
            .build();

        assertThat(workspaceStorageProvider.closeCount()).isZero();

        rag.close();
        rag.close();

        assertThat(workspaceStorageProvider.closeCount()).isEqualTo(1);
    }

    @Test
    void rejectsBlankWorkspaceScopeValues() {
        assertThatThrownBy(() -> new WorkspaceScope(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WorkspaceScope(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkspaceScope("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsSnapshotPathAfterBuild() {
        var snapshotPath = Path.of("snapshots", "repository.json");

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .loadFromSnapshot(snapshotPath)
            .build();

        assertThat(rag.config().snapshotPath()).isEqualTo(snapshotPath);
    }

    @Test
    void buildsWithOptionalRerankModel() {
        var rerankModel = new FakeRerankModel();

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .rerankModel(rerankModel)
            .build();

        assertThat(rag.config().rerankModel()).isSameAs(rerankModel);
    }

    @Test
    void buildsWithCustomRetrievalQualityOptions() {
        Chunker chunker = document -> List.of();

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .chunker(chunker)
            .automaticQueryKeywordExtraction(false)
            .rerankCandidateMultiplier(4)
            .embeddingBatchSize(3)
            .maxParallelInsert(2)
            .entityExtractMaxGleaning(2)
            .maxExtractInputTokens(4_096)
            .entityExtractionLanguage("Chinese")
            .entityTypes(List.of("Person", "Organization"))
            .build();

        assertThat(rag.chunker()).isSameAs(chunker);
        assertThat(rag.automaticQueryKeywordExtraction()).isFalse();
        assertThat(rag.rerankCandidateMultiplier()).isEqualTo(4);
        assertThat(rag.embeddingBatchSize()).isEqualTo(3);
        assertThat(rag.maxParallelInsert()).isEqualTo(2);
        assertThat(rag.entityExtractMaxGleaning()).isEqualTo(2);
        assertThat(rag.maxExtractInputTokens()).isEqualTo(4_096);
        assertThat(rag.entityExtractionLanguage()).isEqualTo("Chinese");
        assertThat(rag.entityTypes()).containsExactly("Person", "Organization");
    }

    @Test
    void usesEmbeddingSemanticMergeDefaults() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build();

        assertThat(rag.embeddingSemanticMergeEnabled()).isFalse();
        assertThat(rag.embeddingSemanticMergeThreshold()).isEqualTo(0.80d);
    }

    @Test
    void retainsConfiguredEmbeddingSemanticMergeOptions() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .chunker(new SmartChunker(SmartChunkerConfig.builder()
                .targetTokens(128)
                .maxTokens(256)
                .overlapTokens(32)
                .build()))
            .enableEmbeddingSemanticMerge(true)
            .embeddingSemanticMergeThreshold(0.65d)
            .build();

        assertThat(rag.embeddingSemanticMergeEnabled()).isTrue();
        assertThat(rag.embeddingSemanticMergeThreshold()).isEqualTo(0.65d);
    }

    @Test
    void usesStructuralSmartChunksForEmbeddingSemanticMergeDuringIngest() {
        var storageProvider = new FakeStorageProvider();
        var embeddingModel = new ScriptedEmbeddingModel(Map.ofEntries(
            Map.entry("Common anchor detail opens the section clearly.", List.of(1.0d, 0.0d)),
            Map.entry("Common anchor detail extends the section further.", List.of(1.0d, 0.0d)),
            Map.entry("Distinct ending sentence changes the subject entirely.", List.of(0.0d, 1.0d)),
            Map.entry(
                "Common anchor detail opens the section clearly.\nCommon anchor detail extends the section further.",
                List.of(1.0d, 0.0d)
            )
        ));
        var rag = LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(embeddingModel)
            .storage(storageProvider)
            .chunker(smartChunkerWithStandaloneSemanticMerge())
            .enableEmbeddingSemanticMerge(true)
            .embeddingSemanticMergeThreshold(0.95d)
            .embeddingBatchSize(2)
            .build();

        rag.ingest(List.of(semanticMergeDocument()));

        assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
            .extracting(ChunkStore.ChunkRecord::text)
            .containsExactly(
                "Common anchor detail opens the section clearly.\nCommon anchor detail extends the section further.",
                "Distinct ending sentence changes the subject entirely."
            );
        assertThat(embeddingModel.batchSizes()).containsExactly(2, 1, 2);
    }

    @Test
    void preservesLegacyChunkerPathWhenEmbeddingSemanticMergeIsDisabled() {
        var storageProvider = new FakeStorageProvider();
        var embeddingModel = new ScriptedEmbeddingModel(Map.ofEntries(
            Map.entry(
                "Common anchor detail opens the section clearly.\nCommon anchor detail extends the section further.",
                List.of(1.0d, 0.0d)
            ),
            Map.entry("Distinct ending sentence changes the subject entirely.", List.of(0.0d, 1.0d))
        ));
        var rag = LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(embeddingModel)
            .storage(storageProvider)
            .chunker(smartChunkerWithStandaloneSemanticMerge())
            .embeddingBatchSize(2)
            .build();

        rag.ingest(List.of(semanticMergeDocument()));

        assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
            .extracting(ChunkStore.ChunkRecord::text)
            .containsExactly(
                "Common anchor detail opens the section clearly.\nCommon anchor detail extends the section further.",
                "Distinct ending sentence changes the subject entirely."
            );
        assertThat(embeddingModel.batchSizes()).containsExactly(2);
    }

    @Test
    void usesConfiguredChunkerDuringIngest() {
        var storageProvider = new FakeStorageProvider();
        var rag = LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storageProvider)
            .chunker(document -> List.of(
                new Chunk(document.id() + ":custom-1", document.id(), "alpha", 1, 0, Map.of()),
                new Chunk(document.id() + ":custom-2", document.id(), "beta", 1, 1, Map.of())
            ))
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "ignored source text", Map.of())));

        assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
            .extracting(ChunkStore.ChunkRecord::id)
            .containsExactly("doc-1:custom-1", "doc-1:custom-2");
    }

    @Test
    void ingestsUtf8MarkdownSourceWithoutCallingMineruOrTika() {
        var rag = testLightRag();
        var source = RawDocumentSource.bytes(
            "guide.md",
            "# Title\nBody".getBytes(StandardCharsets.UTF_8),
            "text/markdown",
            Map.of("source", "unit-test")
        );
        var options = new DocumentIngestOptions(DocumentTypeHint.LAW, ChunkGranularity.COARSE);

        rag.ingestSources(List.of(source), options);

        var documentRecord = rag.config().storageProvider().documentStore()
            .load(source.sourceId())
            .orElseThrow();
        assertThat(documentRecord.title()).isEqualTo("guide.md");
        assertThat(documentRecord.content()).isEqualTo("# Title\nBody");
        assertThat(documentRecord.metadata())
            .containsEntry("source", "unit-test")
            .containsEntry(DocumentIngestOptions.METADATA_DOCUMENT_TYPE_HINT, DocumentTypeHint.LAW.name())
            .containsEntry(DocumentIngestOptions.METADATA_CHUNK_GRANULARITY, ChunkGranularity.COARSE.name());

        var chunkRecords = rag.config().storageProvider().chunkStore().listByDocument(source.sourceId());
        assertThat(chunkRecords).isNotEmpty();
        assertThat(chunkRecords)
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry("source", "unit-test")
                .containsEntry(DocumentIngestOptions.METADATA_DOCUMENT_TYPE_HINT, DocumentTypeHint.LAW.name())
                .containsEntry(DocumentIngestOptions.METADATA_CHUNK_GRANULARITY, ChunkGranularity.COARSE.name()));
        assertThat(chunkRecords)
            .extracting(ChunkStore.ChunkRecord::text)
            .allSatisfy(text -> assertThat(text).contains("Body"));
    }

    @Test
    void keepsLegacyDocumentIngestApiUnchanged() {
        var rag = testLightRag();

        rag.ingest(List.of(new Document("doc-1", "Title", "body", Map.of())));

        assertThat(chunkTexts(rag)).isNotEmpty();
    }

    @Test
    void batchesEmbeddingRequestsDuringIngest() {
        var storageProvider = new FakeStorageProvider();
        var embeddingModel = new CountingEmbeddingModel();
        var rag = LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(embeddingModel)
            .storage(storageProvider)
            .chunker(document -> List.of(
                new Chunk(document.id() + ":0", document.id(), "alpha", 1, 0, Map.of()),
                new Chunk(document.id() + ":1", document.id(), "beta", 1, 1, Map.of()),
                new Chunk(document.id() + ":2", document.id(), "gamma", 1, 2, Map.of()),
                new Chunk(document.id() + ":3", document.id(), "delta", 1, 3, Map.of()),
                new Chunk(document.id() + ":4", document.id(), "epsilon", 1, 4, Map.of())
            ))
            .embeddingBatchSize(2)
            .build();

        rag.ingest(List.of(new Document("doc-1", "Title", "ignored source text", Map.of())));

        assertThat(embeddingModel.batchSizes()).containsExactly(2, 2, 1);
    }

    @Test
    void ingestsDocumentsConcurrentlyWhenMaxParallelInsertIsEnabled() throws Exception {
        var storageProvider = InMemoryStorageProvider.create();
        var chunker = new BlockingChunker();
        var rag = LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storageProvider)
            .chunker(chunker)
            .maxParallelInsert(2)
            .build();

        Future<?> future;
        var executor = Executors.newSingleThreadExecutor();
        try {
            future = executor.submit(() -> rag.ingest(List.of(
                new Document("doc-1", "Doc 1", "alpha", Map.of()),
                new Document("doc-2", "Doc 2", "beta", Map.of())
            )));
            assertThat(chunker.awaitBothDocuments()).as("entered=%s", chunker.enteredDocumentIds()).isTrue();
            assertThat(chunker.maxInFlight()).isEqualTo(2);
            chunker.release();
            future.get(2, TimeUnit.SECONDS);
        } finally {
            chunker.release();
            executor.shutdownNow();
        }

        assertThat(storageProvider.documentStore().list())
            .extracting(DocumentStore.DocumentRecord::id)
            .containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void doesNotCommitOtherDocumentsAfterConcurrentFailure() throws Exception {
        var storageProvider = InMemoryStorageProvider.create();
        var slowDocumentStarted = new CountDownLatch(1);
        var chunker = new InterruptIgnoringChunker("doc-slow", slowDocumentStarted);
        var rag = LightRag.builder()
            .chatModel(new CoordinatedFailingIngestionChatModel("explode", slowDocumentStarted))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storageProvider)
            .chunker(chunker)
            .maxParallelInsert(2)
            .build();

        Future<?> future;
        var executor = Executors.newSingleThreadExecutor();
        try {
            future = executor.submit(() -> rag.ingest(List.of(
                new Document("doc-fail", "Doc fail", "explode", Map.of()),
                new Document("doc-slow", "Doc slow", "steady", Map.of())
            )));
            assertThat(chunker.awaitBlockedDocument()).isTrue();
            chunker.release();
            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        } finally {
            chunker.release();
            executor.shutdownNow();
        }

        assertThat(storageProvider.documentStore().contains("doc-slow")).isFalse();
        assertThat(rag.getDocumentStatus("doc-slow").status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void rejectsMissingChatModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingEmbeddingModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new MalformedStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("documentStore");
    }

    @Test
    void rejectsNullChatModel() {
        assertThatThrownBy(() -> LightRag.builder().chatModel(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("chatModel");
    }

    @Test
    void rejectsNullEmbeddingModel() {
        assertThatThrownBy(() -> LightRag.builder().embeddingModel(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("embeddingModel");
    }

    @Test
    void rejectsNullStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder().storage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("storageProvider");
    }

    @Test
    void rejectsNullSnapshotPath() {
        assertThatThrownBy(() -> LightRag.builder().loadFromSnapshot(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("path");
    }

    @Test
    void rejectsNullChunker() {
        assertThatThrownBy(() -> LightRag.builder().chunker(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("chunker");
    }

    @Test
    void rejectsEmbeddingSemanticMergeThresholdOutsideUnitRange() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .embeddingSemanticMergeThreshold(1.1d))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0.0 and 1.0");

        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .embeddingSemanticMergeThreshold(Double.NaN))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0.0 and 1.0");

        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .embeddingSemanticMergeThreshold(Double.POSITIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0.0 and 1.0");

        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .embeddingSemanticMergeThreshold(Double.NEGATIVE_INFINITY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 0.0 and 1.0");
    }

    @Test
    void failsBuildWhenEmbeddingSemanticMergeIsEnabledForNonSmartChunker() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .chunker(new FixedWindowChunker(100, 10))
            .enableEmbeddingSemanticMerge(true)
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SmartChunker");
    }

    @Test
    void rejectsNonPositiveRerankCandidateMultiplier() {
        assertThatThrownBy(() -> LightRag.builder().rerankCandidateMultiplier(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("rerankCandidateMultiplier must be positive");
    }

    @Test
    void rejectsNonPositiveEmbeddingBatchSize() {
        assertThatThrownBy(() -> LightRag.builder().embeddingBatchSize(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddingBatchSize must be positive");
    }

    @Test
    void rejectsNonPositiveMaxParallelInsert() {
        assertThatThrownBy(() -> LightRag.builder().maxParallelInsert(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxParallelInsert must be positive");
    }

    @Test
    void rejectsNegativeEntityExtractMaxGleaning() {
        assertThatThrownBy(() -> LightRag.builder().entityExtractMaxGleaning(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("entityExtractMaxGleaning must not be negative");
    }

    @Test
    void rejectsNonPositiveMaxExtractInputTokens() {
        assertThatThrownBy(() -> LightRag.builder().maxExtractInputTokens(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxExtractInputTokens must be positive");
    }

    @Test
    void rejectsBlankEntityExtractionLanguage() {
        assertThatThrownBy(() -> LightRag.builder().entityExtractionLanguage(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("entityExtractionLanguage must not be blank");
    }

    @Test
    void rejectsEmptyEntityTypes() {
        assertThatThrownBy(() -> LightRag.builder().entityTypes(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("entityTypes must not be empty");
    }

    @Test
    void rejectsMissingStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonAtomicStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new PlainStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AtomicStorageProvider");
    }

    @Test
    void queryRequestDefaultsToMixMode() {
        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .build();

        assertThat(request.query()).isEqualTo("Where is the evidence?");
        assertThat(request.mode()).isEqualTo(QueryMode.MIX);
        assertThat(request.topK()).isEqualTo(QueryRequest.DEFAULT_TOP_K);
        assertThat(request.chunkTopK()).isEqualTo(QueryRequest.DEFAULT_CHUNK_TOP_K);
        assertThat(request.maxEntityTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_ENTITY_TOKENS);
        assertThat(request.maxRelationTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_RELATION_TOKENS);
        assertThat(request.maxTotalTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_TOTAL_TOKENS);
        assertThat(request.responseType()).isEqualTo("Multiple Paragraphs");
        assertThat(request.enableRerank()).isTrue();
        assertThat(request.onlyNeedContext()).isFalse();
        assertThat(request.onlyNeedPrompt()).isFalse();
        assertThat(request.includeReferences()).isFalse();
        assertThat(request.stream()).isFalse();
        assertThat(request.modelFunc()).isNull();
        assertThat(request.userPrompt()).isEmpty();
        assertThat(request.hlKeywords()).isEmpty();
        assertThat(request.llKeywords()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
    }

    @Test
    void queryRequestLegacyConstructorDefaultsPromptControls() {
        var request = new QueryRequest(
            "Where is the evidence?",
            QueryMode.LOCAL,
            5,
            7,
            "text",
            false
        );

        assertThat(request.query()).isEqualTo("Where is the evidence?");
        assertThat(request.mode()).isEqualTo(QueryMode.LOCAL);
        assertThat(request.topK()).isEqualTo(5);
        assertThat(request.chunkTopK()).isEqualTo(7);
        assertThat(request.maxEntityTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_ENTITY_TOKENS);
        assertThat(request.maxRelationTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_RELATION_TOKENS);
        assertThat(request.maxTotalTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_TOTAL_TOKENS);
        assertThat(request.responseType()).isEqualTo("text");
        assertThat(request.enableRerank()).isFalse();
        assertThat(request.onlyNeedContext()).isFalse();
        assertThat(request.onlyNeedPrompt()).isFalse();
        assertThat(request.includeReferences()).isFalse();
        assertThat(request.stream()).isFalse();
        assertThat(request.modelFunc()).isNull();
        assertThat(request.userPrompt()).isEmpty();
        assertThat(request.hlKeywords()).isEmpty();
        assertThat(request.llKeywords()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
    }

    @Test
    void queryRequestPromptControlConstructorDefaultsShortcutFlags() {
        var request = new QueryRequest(
            "Where is the evidence?",
            QueryMode.LOCAL,
            5,
            7,
            "text",
            false,
            "Answer in one sentence.",
            List.of(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"))
        );

        assertThat(request.onlyNeedContext()).isFalse();
        assertThat(request.onlyNeedPrompt()).isFalse();
        assertThat(request.includeReferences()).isFalse();
        assertThat(request.stream()).isFalse();
        assertThat(request.modelFunc()).isNull();
        assertThat(request.userPrompt()).isEqualTo("Answer in one sentence.");
        assertThat(request.maxEntityTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_ENTITY_TOKENS);
        assertThat(request.maxRelationTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_RELATION_TOKENS);
        assertThat(request.maxTotalTokens()).isEqualTo(QueryRequest.DEFAULT_MAX_TOTAL_TOKENS);
        assertThat(request.hlKeywords()).isEmpty();
        assertThat(request.llKeywords()).isEmpty();
        assertThat(request.conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
    }

    @Test
    void queryRequestAcceptsTokenBudgetOverrides() {
        var overrideModel = new FakeChatModel();
        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .maxEntityTokens(111)
            .maxRelationTokens(222)
            .maxTotalTokens(333)
            .includeReferences(true)
            .stream(true)
            .modelFunc(overrideModel)
            .build();

        assertThat(request.maxEntityTokens()).isEqualTo(111);
        assertThat(request.maxRelationTokens()).isEqualTo(222);
        assertThat(request.maxTotalTokens()).isEqualTo(333);
        assertThat(request.includeReferences()).isTrue();
        assertThat(request.stream()).isTrue();
        assertThat(request.modelFunc()).isSameAs(overrideModel);
    }

    @Test
    void queryRequestRejectsNonPositiveTokenBudgets() {
        assertThatThrownBy(() -> QueryRequest.builder()
            .query("Where is the evidence?")
            .maxEntityTokens(0)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxEntityTokens must be positive");

        assertThatThrownBy(() -> QueryRequest.builder()
            .query("Where is the evidence?")
            .maxRelationTokens(-1)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxRelationTokens must be positive");

        assertThatThrownBy(() -> QueryRequest.builder()
            .query("Where is the evidence?")
            .maxTotalTokens(0)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxTotalTokens must be positive");
    }

    @Test
    void queryRequestCopiesConversationHistory() {
        var history = new ArrayList<ChatModel.ChatRequest.ConversationMessage>();
        history.add(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));

        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .conversationHistory(history)
            .build();
        history.clear();

        assertThat(request.conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
        assertThatThrownBy(() -> request.conversationHistory().add(
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        ))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryRequestNormalizesKeywordOverrides() {
        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .hlKeywords(List.of(" high ", "", "level "))
            .llKeywords(List.of(" low ", " ", "detail"))
            .build();

        assertThat(request.hlKeywords()).containsExactly("high", "level");
        assertThat(request.llKeywords()).containsExactly("low", "detail");
        assertThatThrownBy(() -> request.hlKeywords().add("extra"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.llKeywords().add("extra"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chatRequestCopiesConversationHistory() {
        var history = new ArrayList<ChatModel.ChatRequest.ConversationMessage>();
        history.add(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));

        var request = new ChatModel.ChatRequest("System prompt", "Current user prompt", history);
        history.clear();

        assertThat(request.conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
        assertThatThrownBy(() -> request.conversationHistory().add(
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        ))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryModeExposesNaiveAndQueryRequestAcceptsIt() {
        var request = QueryRequest.builder()
            .query("Find the direct chunk")
            .mode(QueryMode.NAIVE)
            .build();

        assertThat(QueryMode.valueOf("NAIVE")).isEqualTo(QueryMode.NAIVE);
        assertThat(request.mode()).isEqualTo(QueryMode.NAIVE);
    }

    @Test
    void queryModeExposesBypassAndQueryRequestAcceptsIt() {
        var request = QueryRequest.builder()
            .query("Talk directly to the model")
            .mode(QueryMode.BYPASS)
            .build();

        assertThat(QueryMode.valueOf("BYPASS")).isEqualTo(QueryMode.BYPASS);
        assertThat(request.mode()).isEqualTo(QueryMode.BYPASS);
    }

    @Test
    void queryResultCopiesContexts() {
        var contexts = new ArrayList<QueryResult.Context>();
        contexts.add(new QueryResult.Context("chunk-1", "supporting context"));

        var result = new QueryResult("answer", contexts);
        contexts.clear();

        assertThat(result.answer()).isEqualTo("answer");
        assertThat(result.contexts()).containsExactly(new QueryResult.Context("chunk-1", "supporting context"));
        assertThat(result.streaming()).isFalse();
        try (var stream = result.answerStream()) {
            assertThat(stream.hasNext()).isFalse();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        assertThatThrownBy(() -> result.contexts().add(new QueryResult.Context("chunk-2", "more context")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryResultSupportsStructuredReferencesWithLegacyConvenienceConstructors() {
        var references = new ArrayList<QueryResult.Reference>();
        references.add(new QueryResult.Reference("1", "demo-source"));
        var contexts = List.of(new QueryResult.Context("chunk-1", "supporting context", "1", "demo-source"));

        var result = new QueryResult("answer", contexts, references);
        references.clear();

        assertThat(result.references()).containsExactly(new QueryResult.Reference("1", "demo-source"));
        assertThat(result.contexts()).containsExactly(new QueryResult.Context("chunk-1", "supporting context", "1", "demo-source"));
        assertThat(result.streaming()).isFalse();
        try (var stream = result.answerStream()) {
            assertThat(stream.hasNext()).isFalse();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        assertThat(new QueryResult.Context("chunk-2", "plain context").referenceId()).isEmpty();
        assertThat(new QueryResult.Context("chunk-2", "plain context").source()).isEmpty();
        assertThatThrownBy(() -> result.references().add(new QueryResult.Reference("2", "other-source")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryResultLegacyConstructorsPreserveValueEquality() {
        var contexts = List.of(new QueryResult.Context("chunk-1", "supporting context"));
        var references = List.of(new QueryResult.Reference("1", "demo-source"));

        var left = new QueryResult("answer", contexts, references);
        var right = new QueryResult("answer", contexts, references);

        assertThat(left).isEqualTo(right);
        assertThat(left.hashCode()).isEqualTo(right.hashCode());
    }

    @Test
    void documentRequiresNonBlankId() {
        assertThatThrownBy(() -> new Document(" ", "Title", "Body", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractedRelationRequiresSourceTargetAndType() {
        assertThatThrownBy(() -> new ExtractedRelation("Alice", "", "works_with", "", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractedRelationDefaultsMissingWeightWhenDeserialized() throws Exception {
        var relation = OBJECT_MAPPER.readValue("""
            {
              "sourceEntityName": "Alice",
              "targetEntityName": "Bob",
              "type": "works_with",
              "description": "collaboration"
            }
            """, ExtractedRelation.class);

        assertThat(relation.weight()).isEqualTo(1.0d);
    }

    @Test
    void extractedRelationPreservesExplicitZeroWeightWhenDeserialized() throws Exception {
        var relation = OBJECT_MAPPER.readValue("""
            {
              "sourceEntityName": "Alice",
              "targetEntityName": "Bob",
              "type": "works_with",
              "description": "collaboration",
              "weight": 0.0
            }
            """, ExtractedRelation.class);

        assertThat(relation.weight()).isEqualTo(0.0d);
    }

    private static LightRag testLightRag() {
        return LightRag.builder()
            .chatModel(new IngestionChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build();
    }

    private static List<String> chunkTexts(LightRag rag) {
        return rag.config().storageProvider().chunkStore().list().stream()
            .map(ChunkStore.ChunkRecord::text)
            .toList();
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return request.userPrompt();
        }
    }

    private static final class IngestionChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class SelectiveFailingIngestionChatModel implements ChatModel {
        private final String failingMarker;

        private SelectiveFailingIngestionChatModel(String failingMarker) {
            this.failingMarker = failingMarker;
        }

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains(failingMarker)) {
                throw new IllegalStateException("boom");
            }
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class CoordinatedFailingIngestionChatModel implements ChatModel {
        private final String failingMarker;
        private final CountDownLatch releaseFailure;

        private CoordinatedFailingIngestionChatModel(String failingMarker, CountDownLatch releaseFailure) {
            this.failingMarker = failingMarker;
            this.releaseFailure = releaseFailure;
        }

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains(failingMarker)) {
                try {
                    if (!releaseFailure.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting for concurrent document to start");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting to fail", exception);
                }
                throw new IllegalStateException("boom");
            }
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> List.of((double) text.length()))
                .toList();
        }
    }

    private static final class CountingEmbeddingModel implements EmbeddingModel {
        private final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            batchSizes.add(texts.size());
            return texts.stream()
                .map(text -> List.of((double) text.length()))
                .toList();
        }

        List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }
    }

    private static final class ScriptedEmbeddingModel implements EmbeddingModel {
        private final Map<String, List<Double>> embeddingsByText;
        private final List<Integer> batchSizes = new ArrayList<>();

        private ScriptedEmbeddingModel(Map<String, List<Double>> embeddingsByText) {
            this.embeddingsByText = Map.copyOf(embeddingsByText);
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            batchSizes.add(texts.size());
            return texts.stream()
                .map(text -> {
                    var embedding = embeddingsByText.get(text);
                    if (embedding == null) {
                        throw new IllegalStateException("Missing scripted embedding for text: " + text);
                    }
                    return embedding;
                })
                .toList();
        }

        List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }
    }

    private static final class BlockingChunker implements Chunker {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> enteredDocumentIds = new ConcurrentLinkedQueue<>();
        private final CountDownLatch twoEntered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public List<Chunk> chunk(Document document) {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            enteredDocumentIds.add(document.id());
            twoEntered.countDown();
            try {
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release chunking");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            } finally {
                inFlight.decrementAndGet();
            }
            return List.of(new Chunk(document.id() + ":0", document.id(), document.content(), 1, 0, Map.of()));
        }

        boolean awaitBothDocuments() throws InterruptedException {
            return twoEntered.await(2, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int maxInFlight() {
            return maxInFlight.get();
        }

        List<String> enteredDocumentIds() {
            return List.copyOf(enteredDocumentIds);
        }
    }

    private static final class InterruptIgnoringChunker implements Chunker {
        private final String blockedDocumentId;
        private final CountDownLatch externalBlockedSignal;
        private final CountDownLatch blockedDocumentEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private InterruptIgnoringChunker(String blockedDocumentId, CountDownLatch externalBlockedSignal) {
            this.blockedDocumentId = blockedDocumentId;
            this.externalBlockedSignal = externalBlockedSignal;
        }

        @Override
        public List<Chunk> chunk(Document document) {
            if (document.id().equals(blockedDocumentId)) {
                blockedDocumentEntered.countDown();
                externalBlockedSignal.countDown();
                while (true) {
                    try {
                        if (release.await(50, TimeUnit.MILLISECONDS)) {
                            break;
                        }
                    } catch (InterruptedException ignored) {
                        // Simulate a user model implementation that does not stop immediately on interruption.
                    }
                }
            }
            return List.of(new Chunk(document.id() + ":0", document.id(), document.content(), 1, 0, Map.of()));
        }

        boolean awaitBlockedDocument() throws InterruptedException {
            return blockedDocumentEntered.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    private static final class FakeRerankModel implements RerankModel {
        @Override
        public List<RerankResult> rerank(RerankRequest request) {
            return request.candidates().stream()
                .map(candidate -> new RerankResult(candidate.id(), 1.0d))
                .toList();
        }
    }

    private static class FakeStorageProvider implements AtomicStorageProvider {
        private final DocumentStore documentStore = new FakeDocumentStore();
        private final ChunkStore chunkStore = new FakeChunkStore();
        private final GraphStore graphStore = new FakeGraphStore();
        private final VectorStore vectorStore = new FakeVectorStore();
        private final DocumentStatusStore documentStatusStore = new InMemoryDocumentStatusStore();
        private final SnapshotStore snapshotStore = new FakeSnapshotStore();

        @Override
        public DocumentStore documentStore() {
            return documentStore;
        }

        @Override
        public ChunkStore chunkStore() {
            return chunkStore;
        }

        @Override
        public GraphStore graphStore() {
            return graphStore;
        }

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return documentStatusStore;
        }

        @Override
        public SnapshotStore snapshotStore() {
            return snapshotStore;
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            return operation.execute(new AtomicStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return documentStore;
                }

                @Override
                public ChunkStore chunkStore() {
                    return chunkStore;
                }

                @Override
                public GraphStore graphStore() {
                    return graphStore;
                }

                @Override
                public VectorStore vectorStore() {
                    return vectorStore;
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return documentStatusStore;
                }
            });
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
        }
    }

    private static final class TestWorkspaceStorageProvider implements WorkspaceStorageProvider {
        private final AtomicStorageProvider delegate;
        private final AtomicInteger closeCount = new AtomicInteger();

        private TestWorkspaceStorageProvider(AtomicStorageProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
            return delegate;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        int closeCount() {
            return closeCount.get();
        }
    }

    private static final class PlainStorageProvider implements StorageProvider {
        private final FakeStorageProvider delegate = new FakeStorageProvider();

        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }
    }

    private static final class MalformedStorageProvider extends FakeStorageProvider {
        @Override
        public DocumentStore documentStore() {
            return null;
        }
    }

    private static final class FakeDocumentStore implements DocumentStore {
        private final List<DocumentRecord> documents = new ArrayList<>();

        @Override
        public void save(DocumentRecord document) {
            documents.removeIf(existing -> existing.id().equals(document.id()));
            documents.add(document);
        }

        @Override
        public Optional<DocumentRecord> load(String documentId) {
            return documents.stream().filter(record -> record.id().equals(documentId)).findFirst();
        }

        @Override
        public List<DocumentRecord> list() {
            return List.copyOf(documents);
        }

        @Override
        public boolean contains(String documentId) {
            return documents.stream().anyMatch(record -> record.id().equals(documentId));
        }
    }

    private static final class FakeChunkStore implements ChunkStore {
        private final List<ChunkRecord> chunks = new ArrayList<>();

        @Override
        public void save(ChunkRecord chunk) {
            chunks.removeIf(existing -> existing.id().equals(chunk.id()));
            chunks.add(chunk);
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            return chunks.stream().filter(record -> record.id().equals(chunkId)).findFirst();
        }

        @Override
        public List<ChunkRecord> list() {
            return List.copyOf(chunks);
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return chunks.stream().filter(record -> record.documentId().equals(documentId)).toList();
        }
    }

    private static final class FakeGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
        }

        @Override
        public void saveRelation(RelationRecord relation) {
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return Optional.empty();
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return Optional.empty();
        }

        @Override
        public List<EntityRecord> allEntities() {
            return List.of();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return List.of();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return List.of();
        }
    }

    private static final class FakeVectorStore implements VectorStore {
        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return List.of();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }

    private static final class FakeSnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }

    private static SmartChunker smartChunkerWithStandaloneSemanticMerge() {
        return new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(32)
            .maxTokens(120)
            .overlapTokens(0)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.70d)
            .build());
    }

    private static Document semanticMergeDocument() {
        return new Document(
            "doc-1",
            "Semantic Merge Guide",
            """
                Common anchor detail opens the section clearly.
                Common anchor detail extends the section further.
                Distinct ending sentence changes the subject entirely.
                """,
            Map.of("source", "unit-test")
        );
    }
}
