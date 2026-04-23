# Chunk Extraction Thread Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ingestion 路径增加“切片后知识抽取”的受控线程池，提高多 chunk 文档的抽取吞吐，同时保持结果顺序、失败语义和现有存储提交流程不变。

**Architecture:** 只改 ingestion 使用的 `IndexingPipeline.refineExtractions(...)`，把当前串行的 `knowledgeExtractor.extract(chunk)` 改为固定并发执行，再按原 chunk 顺序组装 `PrimaryChunkExtraction`。配置项通过 `LightRagBuilder -> LightRag -> IndexingPipeline` 透传，默认值保持 `1`，因此现有行为默认不变；`GraphMaterializationPipeline` 暂不纳入本次范围，避免把 repair/rebuild 子系统一并扩大。

**Tech Stack:** Java 17, JUnit 5, AssertJ, Gradle multi-module build, `ExecutorService` / `ExecutorCompletionService`

---

## File Map

- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
  - 新增 `chunkExtractParallelism` 配置字段、builder 方法与参数校验。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
  - 持有配置值，并在 `newIndexingPipeline(...)` 装配时透传。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
  - 新增受控并发抽取逻辑，保持顺序与失败传播。
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
  - 覆盖 builder 新配置的默认值、透传值、非法值。
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java`
  - 覆盖并发抽取的顺序保持、实际并发发生、失败中断语义。

## Scope Guardrails

- 只覆盖 `IndexingPipeline` ingestion 路径。
- 不改 `GraphMaterializationPipeline`。
- 不改 commit 落库锁、snapshot 持久化、embedding 并发。
- 默认配置必须保持兼容：`chunkExtractParallelism == 1` 时行为与当前版本一致。

### Task 1: 暴露 chunk 抽取并发配置

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: 先在 builder 测试里写失败用例，锁定默认值、透传值、非法值**

```java
@Test
void exposesChunkExtractParallelismConfiguration() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .chunkExtractParallelism(4)
        .build();

    assertThat(rag.chunkExtractParallelism()).isEqualTo(4);
}

@Test
void keepsChunkExtractParallelismAtOneByDefault() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .build();

    assertThat(rag.chunkExtractParallelism()).isEqualTo(1);
}

@Test
void rejectsNonPositiveChunkExtractParallelism() {
    assertThatThrownBy(() -> LightRag.builder().chunkExtractParallelism(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chunkExtractParallelism must be positive");
}
```

- [ ] **Step 2: 运行 builder 测试，确认新 API 尚不存在而失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"
```

Expected:
```text
FAIL
... cannot find symbol: method chunkExtractParallelism(int)
... cannot find symbol: method chunkExtractParallelism()
```

- [ ] **Step 3: 在 `LightRagBuilder` 增加字段、setter、校验，并在 `build()` 时透传**

```java
private int chunkExtractParallelism = 1;

public LightRagBuilder chunkExtractParallelism(int chunkExtractParallelism) {
    if (chunkExtractParallelism <= 0) {
        throw new IllegalArgumentException("chunkExtractParallelism must be positive");
    }
    this.chunkExtractParallelism = chunkExtractParallelism;
    return this;
}
```

```java
return new LightRag(
    new LightRagConfig(...),
    chunker,
    documentParsingOrchestrator,
    automaticQueryKeywordExtraction,
    rerankCandidateMultiplier,
    embeddingBatchSize,
    maxParallelInsert,
    chunkExtractParallelism,
    entityExtractMaxGleaning,
    maxExtractInputTokens,
    entityExtractionLanguage,
    entityTypes,
    embeddingSemanticMergeEnabled,
    embeddingSemanticMergeThreshold,
    extractionRefinementOptions,
    List.copyOf(taskEventListeners)
);
```

- [ ] **Step 4: 在 `LightRag` 增加字段、getter，并装配到 `IndexingPipeline`**

```java
private final int chunkExtractParallelism;

LightRag(
    LightRagConfig config,
    Chunker chunker,
    DocumentParsingOrchestrator documentParsingOrchestrator,
    boolean automaticQueryKeywordExtraction,
    int rerankCandidateMultiplier,
    int embeddingBatchSize,
    int maxParallelInsert,
    int chunkExtractParallelism,
    int entityExtractMaxGleaning,
    int maxExtractInputTokens,
    String entityExtractionLanguage,
    List<String> entityTypes,
    boolean embeddingSemanticMergeEnabled,
    double embeddingSemanticMergeThreshold,
    ExtractionRefinementOptions extractionRefinementOptions,
    List<TaskEventListener> taskEventListeners
) {
    this.chunkExtractParallelism = chunkExtractParallelism;
    // 其余赋值保持不变
}

int chunkExtractParallelism() {
    return chunkExtractParallelism;
}
```

```java
return new IndexingPipeline(
    config.extractionModel(),
    config.summaryModel(),
    config.embeddingModel(),
    storageProvider,
    config.snapshotPath(),
    chunker,
    documentParsingOrchestrator,
    embeddingBatchSize,
    maxParallelInsert,
    chunkExtractParallelism,
    entityExtractMaxGleaning,
    maxExtractInputTokens,
    entityExtractionLanguage,
    entityTypes,
    embeddingSemanticMergeEnabled,
    embeddingSemanticMergeThreshold,
    extractionRefinementOptions,
    progressListener
);
```

- [ ] **Step 5: 重新运行 builder 测试，确认配置链已打通**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"
```

Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: 提交这一小步**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java
git commit -m "feat: add chunk extraction parallelism config"
```

### Task 2: 为 ingestion 抽取增加固定线程池并保持顺序

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java`

- [ ] **Step 1: 先写失败测试，锁定“结果顺序不变”和“确实发生并发”**

```java
@Test
void preservesChunkOrderWhenExtractionRunsConcurrently() {
    var extractionModel = new RecordingConcurrentChatModel(2, 50);
    var pipeline = new IndexingPipeline(
        extractionModel,
        new FakeEmbeddingModel(),
        InMemoryStorageProvider.create(),
        null,
        new FixedWindowChunker(5, 0),
        null,
        Integer.MAX_VALUE,
        1,
        3,
        0,
        KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
        KnowledgeExtractor.DEFAULT_LANGUAGE,
        KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
        false,
        0.80d,
        ExtractionRefinementOptions.disabled(),
        IndexingProgressListener.noop()
    );

    pipeline.ingest(List.of(new Document("doc-1", "t", "aaaaabbbbbccccc", Map.of())));

    assertThat(extractionModel.maxConcurrentCalls()).isGreaterThanOrEqualTo(2);
    assertThat(extractionModel.seenChunkIdsInReturnedOrder())
        .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
}
```

- [ ] **Step 2: 再写失败测试，锁定并发任务失败时要中断整批抽取**

```java
@Test
void failsWholeDocumentWhenAnyConcurrentExtractionFails() {
    var extractionModel = new FailingOnSecondChunkChatModel();
    var pipeline = new IndexingPipeline(
        extractionModel,
        new FakeEmbeddingModel(),
        InMemoryStorageProvider.create(),
        null,
        new FixedWindowChunker(5, 0),
        null,
        Integer.MAX_VALUE,
        1,
        3,
        0,
        KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
        KnowledgeExtractor.DEFAULT_LANGUAGE,
        KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
        false,
        0.80d,
        ExtractionRefinementOptions.disabled(),
        IndexingProgressListener.noop()
    );

    assertThatThrownBy(() -> pipeline.ingest(List.of(new Document("doc-1", "t", "aaaaabbbbbccccc", Map.of()))))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("boom");
}
```

- [ ] **Step 3: 运行新测试，确认当前串行实现无法满足并发断言**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineChunkExtractionConcurrencyTest"
```

Expected:
```text
FAIL
... expected maxConcurrentCalls to be greater than or equal to 2 ...
```

- [ ] **Step 4: 在 `IndexingPipeline` 增加配置字段和构造参数归一化**

```java
private final int chunkExtractParallelism;

this.chunkExtractParallelism = Math.max(1, chunkExtractParallelism);
```

```java
public IndexingPipeline(
    ChatModel extractionModel,
    ChatModel summaryModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    Path snapshotPath,
    Chunker chunker,
    DocumentParsingOrchestrator documentParsingOrchestrator,
    int embeddingBatchSize,
    int maxParallelInsert,
    int chunkExtractParallelism,
    int entityExtractMaxGleaning,
    int maxExtractInputTokens,
    String entityExtractionLanguage,
    List<String> entityTypes,
    boolean embeddingSemanticMergeEnabled,
    double embeddingSemanticMergeThreshold,
    ExtractionRefinementOptions extractionRefinementOptions,
    IndexingProgressListener progressListener
) { ... }
```

- [ ] **Step 5: 把串行 `refineExtractions(...)` 抽成“串行 / 并发”两条路径**

```java
private List<GraphAssembler.ChunkExtraction> refineExtractions(List<Chunk> chunks) {
    progressListener.onStageStarted(TaskStage.PRIMARY_EXTRACTION, "extracting entities and relations");
    var primaryExtractions = chunkExtractParallelism <= 1 || chunks.size() <= 1
        ? extractPrimarySequentially(chunks)
        : extractPrimaryConcurrently(chunks);
    progressListener.onStageSucceeded(TaskStage.PRIMARY_EXTRACTION, "primary extraction completed");
    if (!extractionRefinementOptions.enabled()) {
        progressListener.onStageSkipped(TaskStage.REFINEMENT_EXTRACTION, "contextual refinement disabled");
        return extractionRefinementPipeline.refine(primaryExtractions);
    }
    progressListener.onStageStarted(TaskStage.REFINEMENT_EXTRACTION, "running contextual refinement");
    var refined = extractionRefinementPipeline.refine(primaryExtractions);
    progressListener.onStageSucceeded(TaskStage.REFINEMENT_EXTRACTION, "contextual refinement completed");
    return refined;
}

private List<PrimaryChunkExtraction> extractPrimarySequentially(List<Chunk> chunks) {
    return chunks.stream()
        .map(chunk -> new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
        .toList();
}
```

- [ ] **Step 6: 实现受控并发抽取，按原顺序回填结果，并保留失败传播**

```java
private List<PrimaryChunkExtraction> extractPrimaryConcurrently(List<Chunk> chunks) {
    ExecutorService executor = Executors.newFixedThreadPool(Math.min(chunkExtractParallelism, chunks.size()));
    try {
        @SuppressWarnings("unchecked")
        var results = (PrimaryChunkExtraction[]) new PrimaryChunkExtraction[chunks.size()];
        var completionService = new ExecutorCompletionService<IndexedPrimaryExtraction>(executor);
        var pending = new LinkedHashMap<Future<IndexedPrimaryExtraction>, Integer>();
        for (int index = 0; index < chunks.size(); index++) {
            final int taskIndex = index;
            var chunk = chunks.get(index);
            pending.put(completionService.submit(
                () -> new IndexedPrimaryExtraction(taskIndex, new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
            ), taskIndex);
        }
        while (!pending.isEmpty()) {
            var completed = completionService.take();
            pending.remove(completed);
            var extraction = completed.get();
            results[extraction.index()] = extraction.value();
        }
        return List.of(results);
    } catch (ExecutionException exception) {
        cancelPending(pending.keySet());
        rethrowTaskFailure(exception.getCause());
        throw new IllegalStateException("unreachable");
    } catch (InterruptedException exception) {
        cancelPending(pending.keySet());
        Thread.currentThread().interrupt();
        throw new RuntimeException("chunk extraction interrupted", exception);
    } finally {
        shutdownExecutor(executor);
    }
}

private record IndexedPrimaryExtraction(int index, PrimaryChunkExtraction value) {}
```

- [ ] **Step 7: 跑并发抽取测试，确认顺序和失败语义正确**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineChunkExtractionConcurrencyTest"
```

Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 8: 提交这一小步**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java
git commit -m "feat: parallelize chunk extraction during ingest"
```

### Task 3: 回归验证默认兼容与批量图写入不回退

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java`

- [ ] **Step 1: 在现有 batch persistence 测试里显式覆盖默认并发值，确认旧构造路径仍可用**

```java
var pipeline = new IndexingPipeline(
    new FakeChatModel(),
    new FakeEmbeddingModel(),
    storage,
    null
);

pipeline.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

assertThat(storage.graphRecorder.savedEntityBatches).hasSize(1);
assertThat(storage.graphRecorder.savedRelationBatches).hasSize(1);
```

- [ ] **Step 2: 跑定向回归测试，确认新并发逻辑没有破坏批量图写入和 builder 默认行为**

Run:
```bash
./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.api.LightRagBuilderTest" \
  --tests "io.github.lightrag.indexing.IndexingPipelineChunkExtractionConcurrencyTest" \
  --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest"
```

Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: 运行更贴近实际 ingestion 的一组回归，确认没有破坏文档入库主链路**

Run:
```bash
./gradlew :lightrag-core:test \
  --tests "io.github.lightrag.indexing.DocumentIngestorTest" \
  --tests "io.github.lightrag.api.LightRagBuilderTest" \
  --tests "io.github.lightrag.indexing.IndexingPipelineBatchGraphPersistenceTest"
```

Expected:
```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: 提交回归收尾**

```bash
git add lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java
git commit -m "test: cover chunk extraction concurrency regression"
```

## Self-Review

- Spec coverage:
  - 已覆盖配置面、pipeline 并发实现、顺序保持、失败传播、默认兼容和回归验证。
  - 明确排除 `GraphMaterializationPipeline`、commit 锁、embedding 并发，范围收敛。
- Placeholder scan:
  - 无 `TBD` / `TODO` / “自行处理” 类占位语。
  - 所有任务都给了明确文件、代码片段、命令和预期结果。
- Type consistency:
  - 统一使用 `chunkExtractParallelism`。
  - 并发结果包装统一使用 `IndexedPrimaryExtraction`。
  - `LightRagBuilder -> LightRag -> IndexingPipeline` 参数名保持一致。

