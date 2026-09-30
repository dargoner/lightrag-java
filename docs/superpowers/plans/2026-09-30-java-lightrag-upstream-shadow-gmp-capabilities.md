# 消除 aiplatform shadow 类：GraphMaterializationPipeline 私有能力上游化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实施。步骤用 `- [ ]` 复选框跟踪。

**Goal:** 把 aiplatform 私有 fork 里的三项能力（GMP 分片并行抽取、取消检查点、`chunkSnapshotMismatch → REBUILD`）做进 `lightrag-core`，然后删掉 aiplatform 主线的两个 shadow 文件——此后主线版本升级不再需要人工 vendor 同步。

**Architecture:** 三项能力都落在 `GraphMaterializationPipeline` 内，其中并行抽取直接对齐 `IndexingPipeline` 的既有实现（同一仓库内已存在、已有测试守护的形态），`LightRag` 只增加一个 4 参重载并把 `resolvedChunkExtractParallelism()` 透传进 GMP。取消**不是**裸 `Runnable`，而是新增公开函数式接口 `CancellationCheckpoint`（`void check()`，契约写在这个类型上）；**不提供 `Runnable` 重载**——两个同形（`() -> void`）函数式接口并存会让所有传 lambda / 传 `null` 的调用点编译歧义（详见 Task 4 Step 3）。取消语义按「检查点轮询 + 原子提交不可中断」建模：进入 `writeAtomically` 之前必有检查点，一旦进入则提交完整完成、取消延迟到提交之后（见 Task 2 Step 3 的边界一节）。executor 终止按「`shutdownNow` + 限时等待 + 超时告警」实现，模型侧的中断响应契约显式写入 Javadoc（并列出「模型忽略中断时后台线程可能继续运行」这一限制）。不改存储层、不改 `MaterializationState` 的 record 形状。

**Tech Stack:** Java 17、Gradle（`./gradlew :lightrag-core:test`）、JUnit 5 + AssertJ、SLF4J。下游 aiplatform 是 Maven 多模块（Java 17）。

> **第一轮评审（2026-09-30）判定本计划不可开工，本文件已按裁定修订。** 三处 must-fix：①取消与原子写入的边界未定义（已定义边界并补「写入前检查点」；取消用例重写为「提交前零写入 + 提交中要么完整提交要么按补偿回滚」）；②executor 终止可能泄漏线程（已补中断契约、限时等待告警与两个终止用例）；③取消测试断言过强（已重写）。另：`Runnable` 升级为 `CancellationCheckpoint`（should-fix）、补 GMP 与 `IndexingPipeline` 的行为对照矩阵（should-fix）、命令全部改为 Git Bash/PowerShell 可直接执行（should-fix）、Maven 成功标记改为 `BUILD SUCCESS`（nit）、日志级别变更决策为**不做**（待评审 5）。逐条闭环见文末「第一轮评审裁定与落实」。
>
> **第二轮评审（2026-09-30）判定可开工（无 must-fix）。** 两条改进已落实：①不响应中断的用例补测试卫生（`finally` 释放闩锁 + `join(5s)`）；②异常传播断言与矩阵文字统一为「`RuntimeException`/`Error` 原样传播，`InterruptedException` 按既有契约包装」；`materializeNeverLeavesPartialGraphState` 的测试策略按裁定退化为「顺序路径全参数化 + 并发路径两个确定边界」。见文末「第二轮评审裁定与落实」。
>
> **第三轮评审（2026-09-30）维持可开工（无 must-fix）。** 已按核验前提补全：①两处过时行号修正（`:1001-1032` → `:1100-1108` / `:1022-1032`；`949-1033` → `943-1033`）；②测试卫生断言补「`!isAlive()`」。见文末「第三轮评审裁定与落实」。
>
> **第四轮评审（2026-09-30）维持可开工（1 处 should-fix，已落实）。** ①should-fix「aiplatform 行号漂移」：Task 6 Step 4 全量刷新为当前 checkout 行号，并改正 `.run()` 实为 **4 处**（`:2190` 内联形态 + `:2397`/`:2685`/`:2688`）；②自查补漏——同模块测试 `KnowledgeGraphServiceImplTest` 的反射强转 `(Runnable)` 未列入类型改动清单（替换后会运行时 `ClassCastException`，`-Dmaven.test.skip=true` 也不暴露），已补进 Task 6 Step 4/5、File Map 与验收标准；③自查补验收桥——aiplatform 既有 `LightRagSdkUpgradeSmokeTest`（3 用例）写进验收标准。见文末「第四轮评审裁定与落实」。
>
> **第五轮评审（2026-09-30）维持可开工（2 处 should-fix，已落实）。** ①「命中 9 行」笔误更正为 **11 行**完整清单；②`-DskipTests` 从「可选加强」提升为 Task 6 Step 5 的**必跑 2**（`-Dmaven.test.skip=true` 不编译测试源码，而本任务的机械类型改动包含测试文件）。另有一项可选增强（smoke 4 参反射断言）记录为不做。见文末「第五轮评审裁定与落实」。
>
> **第六轮评审（2026-09-30）判定可开工、可冻结（无 must-fix，零残余）。** 三条核验（11 行更正 / Step 5 双必跑 / 增强项不做）全部 CLOSED；本计划自此**冻结**。见文末「第六轮评审裁定与落实」。

---

## 背景与证据

aiplatform 在 `backend/aide-kno/src/main/java/io/github/lightrag/` 下用**同 FQCN 覆盖**了两个 SDK 类（shadow 类：模块源码整体替换依赖 jar 的同名类）：

| shadow 文件（aiplatform） | 行数 | 上游同文件（`v0.23.0` == `HEAD~1`，代码相同） |
|---|---|---|
| `api/LightRag.java` | 1161 | 1008 |
| `indexing/GraphMaterializationPipeline.java` | 1606 | 1189 |

**两处本地改动全部来自 `GraphMaterializationPipeline`，上游一行都没有**（`git log --all -S cancellationCheckpoint`、`git log --all -S chunkSnapshotMismatch` 全历史零命中）：

1. **GMP 分片并行抽取**（vendored :866-946）：`chunkExtractParallelism` 字段驱动 `extractPrimarySequentially` / `extractPrimaryConcurrently`。**这不是新发明**——它是上游 `IndexingPipeline.java` 既有实现的移植：日志与分支 `:949-957`、`extractPrimarySequentially` `:989-996`、`extractPrimaryConcurrently` `:998-1033`、`cancelPending` `:1085-1089`、`shutdownExecutor` `:1091-1098`、`private record IndexedPrimaryExtraction` `:1994`；测试先例 `IndexingPipelineChunkExtractionConcurrencyTest.java`（239 行，latch/barrier + `maxConcurrentCalls()` 断言，私有嵌套 double）。上游化 = 对齐既有设计。
2. **取消检查点**：vendored 有 **9 个轮询点**（清单见 Task 2），语义 = 「轮询外部状态，已取消则抛 RuntimeException 中止」。aiplatform 的实现：`KnowledgeGraphServiceImpl.java:2094-2104` `graphBuildCancellationCheckpoint(taskId)` → 任务 `currentStage == TERMINATED` 时 `throw new KnowledgeGraphBuildCancelledException(taskId)`；两处取检查点（`:2395`、`:2683`）并把它传进 `materializeDocumentGraph`（`:2420`、`:2698`），中间还用它做前置检查（`:2190` 内联形态 + `:2397`、`:2685`、`:2688`，共 4 处；行号基线：2026-09-30 checkout，实现时以 `grep -n cancellationCheckpoint` 为准）。上游今天的取消通道只有线程中断：`TaskExecutionService.java:863-871` 的 `checkCancelled()` 抛包私有 `TaskCancelledException`，**GMP 内部没有任何取消轮询**，`IndexingPipeline` / `DeletionPipeline` 也没有取消钩子（`DeletionPipeline` 只有 `(AtomicStorageProvider, IndexingPipeline, Path)` 一个构造器）。**上游化时的两处强化（第一轮评审 must-fix 1/2）**：轮询点从 9 个扩到 12 个（补齐三处「原子写入之前」的检查点），并在 `shutdownExecutor` 里补「限时等待 + 超时告警」。
3. **`MaterializationState.chunkSnapshotMismatch → REBUILD`**（vendored :1287 分支、:1547-1550 判定）：当前 chunk 仓库里的 chunk id 集合非空、且与快照记录的 chunk id 集合不一致 → 推荐 REBUILD。上游 `MaterializationState`（`GraphMaterializationPipeline.java:1112-1123`，10 个组件）**已含判定所需的全部组件**（`chunkSnapshots`、`storedChunks`），唯一构造点是 `loadState`（`:586-598`）——所以这是纯增量的 helper 方法 + 一个分支，**不动 record 形状、不动 `loadState`**。

**收益**：删掉主线 shadow 后，lightrag-core 主线的版本升级不再需要人工同步；Plan 2 Task 4 Step 2(a) 那条「不同步就 `NoSuchMethodError`」的硬约束**对主线**永久消失（`.worktrees/` 下的副本仍需各自处理，见 Task 6 Step 6）。

**与 Plan 1 / Plan 2 的关系（必读）**

- 与 Plan 1（scoped pre-image）改动文件不重叠，可并行开发；本计划不依赖它。
- 与 Plan 2 都改 `LightRag.java`：**总顺序定为 Plan 1 → Plan 3（本计划）→ Plan 2**（第一轮评审跨计划问题 2 的裁定）。按该顺序，本计划先删除主线 shadow，Plan 2 Task 4 Step 2 的正常路径只剩「升版本 + 打开旋钮」，其 (a2) 同步清单不再需要，避免「先同步 shadow、再删除 shadow」的重复劳动。若 Plan 2 被迫先行（性能紧急），则它按 (a2) 先同步一次，随后被本计划删除——两种顺序都成立，但不要让两个计划的「同步」步骤各做一半。
- **接口一致性**：本计划把取消类型从 `Runnable` 换成 `CancellationCheckpoint`，因此 aiplatform 的 `KnowledgeGraphServiceImpl` 需要一次**机械的类型改动**（`graphBuildCancellationCheckpoint` 的返回类型、生产代码四处 `.run()` → `.check()`、检查点重载参数类型；外加同模块测试 `KnowledgeGraphServiceImplTest` 里一处反射用法，见 Task 6 Step 4），变更点全部由编译器或既有测试暴露，不是 Plan 2 那种隐性 `NoSuchMethodError`。

---

## File Map

**lightrag-core（主体）**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/CancellationCheckpoint.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentGraphApiTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineConcurrencyTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineCancellationTest.java`

**aiplatform（下游切换，Task 6）**
- Delete: `backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java`
- Delete: `backend/aide-kno/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `backend/pom.xml`（`<lightrag.version>`）
- Modify: `backend/aide-kno/src/main/java/com/finstone/fusion/ai/knowledge/service/KnowledgeGraphServiceImpl.java`（**仅机械类型改动**：`graphBuildCancellationCheckpoint` 返回类型 `Runnable` → `CancellationCheckpoint`、四处前置检查 `.run()` → `.check()`、`:2082-2090` 检查点重载参数类型；不改业务逻辑，见 Task 6 Step 4）
- Modify: `backend/aide-kno/src/test/java/com/finstone/fusion/ai/knowledge/service/KnowledgeGraphServiceImplTest.java`（**仅机械类型改动**：`graph_build_cancellation_checkpoint_throws_only_for_terminated_task` 的反射返回值转换 `(Runnable)` → `(CancellationCheckpoint)`、两处 `.run()` → `.check()`；见 Task 6 Step 4）
- Create: `scripts/check-no-lightrag-shadow.sh` + CI 作业接入（防 shadow 副本再生；见 Task 6 Step 3）

## Scope Guardrails

- **不重构 `IndexingPipeline`**：GMP 的并行抽取按既有实现复制（shadow 已在生产验证过这条路径）。抽共享 helper 会动到稳定路径，列入第二轮问题 1，不在本计划；但**必须**用 Task 2 Step 1 末尾的行为对照矩阵把两套实现的行为钉死。
- **取消形态 = `CancellationCheckpoint`（唯一）**：不提供 `Runnable` 重载（同形函数式接口会让 lambda/`null` 调用点歧义，Task 4 Step 3 有推演）；不改 `TaskCancelledException` 可见性、不给 `IndexingPipeline` / `DeletionPipeline` 加检查点。
- **不给 `resumeChunkGraph` / `repairChunkGraph` 加检查点重载**：shadow 也没有（`KnowledgeGraphServiceImpl.java` 的调用点 `:2424`、`:2428`、`:2587`、`:2588` 都不传检查点；行号基线同上）。
- **不改 `GraphMaterializationMode` 枚举、不改 `MaterializationState` 组件、不改 `determineRecommendedMode` 的其他分支**。
- **不改日志级别**（Task 5 已决策：**不做**，理由是这是独立行为变更，不混入核心迁移）。
- **原子的提交不可中断**：不在 `writeAtomically` 内部轮询检查点（边界定义见 Task 2 Step 3）。
- aiplatform 侧只删 shadow + 升版本 + Step 4 的机械类型改动，不借机改 `KnowledgeGraphServiceImpl` 的业务逻辑。

---

### Task 1: `CancellationCheckpoint` 类型 + GMP 构造器与字段

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/CancellationCheckpoint.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineConcurrencyTest.java`

参数顺序**必须与 shadow 一致**（`chunkExtractParallelism` 紧跟 `progressListener`，取消检查点放最后），这样 Task 2 的代码可以从 vendored 逐段搬，不用重排。**唯一差异**：取消参数的类型是 `CancellationCheckpoint` 而不是 `Runnable`（第一轮评审 should-fix 4 裁定；不提供 `Runnable` 重载的理由见 Task 4 Step 3）。

- [ ] **Step 0: 定义 `CancellationCheckpoint`**

```java
package io.github.lightrag.api;

/**
 * 取消检查点：管线在安全的轮询点调用 {@link #check()}，实现方在「已请求取消」时抛出异常以中止操作。
 *
 * <p>契约：</p>
 * <ul>
 *   <li>实现必须是<b>廉价且线程安全</b>的——GMP 会在工作线程内调用它（并发抽取的任务提交体）。</li>
 *   <li>实现只能做「轮询 + 抛异常」：不得阻塞、不得产生副作用。抛出的异常会从
 *       {@code materializeDocumentGraph} 原样冒出，调用方据此判定取消。</li>
 *   <li>轮询点全部位于<b>原子提交之外</b>；进入 {@code writeAtomically} 之后本次提交不可中断，
 *       取消表现为「写入完整提交 + 任务被标记取消」（边界语义见 {@code GraphMaterializationPipeline} 的类注释）。</li>
 *   <li>{@link #NONE} 是一个空实现，`null` 与其等价（GMP 构造器统一归一化）。</li>
 * </ul>
 */
@FunctionalInterface
public interface CancellationCheckpoint {
    CancellationCheckpoint NONE = () -> { };

    void check();
}
```

- [ ] **Step 1: 先写失败测试（默认构造 = 串行；非法并行度被归一化）**

新测试文件用私有嵌套 double（复刻 `IndexingPipelineChunkExtractionConcurrencyTest` 的形态：`RecordingConcurrentChatModel` 带 `CountDownLatch`/`AtomicInteger`、`FakeEmbeddingModel`），并通过反射调用私有 `refineExtractions(List<Chunk>)`（该先例 :110-128 已用此法）：

```java
@Test
void defaultsToSequentialExtractionWhenParallelismIsNotConfigured() throws Exception {
    var chatModel = new RecordingConcurrentChatModel();
    var pipeline = new GraphMaterializationPipeline(
        chatModel, new FakeEmbeddingModel(), InMemoryStorageProvider.create(),
        ExtractionRefinementOptions.disabled(), null,
        TaskMetadataReporter.noop(), IndexingProgressListener.noop());

    var extractions = invokeRefineExtractions(pipeline, List.of(
        chunk("doc-1:0", "zero"), chunk("doc-1:1", "one"), chunk("doc-1:2", "two")));

    assertThat(chatModel.maxConcurrentCalls()).isEqualTo(1);
    assertThat(extractions).extracting(e -> e.chunk().id())
        .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
}

@Test
void normalizesNonPositiveParallelismToOne() throws Exception {
    // 15 参构造器传 chunkExtractParallelism = 0：不得抛异常，且按串行执行
    // 断言同 default 用例：maxConcurrentCalls() == 1
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphMaterializationPipelineConcurrencyTest"
```
Expected: 编译失败（无 15 参构造器）/ `AssertionError`。

- [ ] **Step 3: 实现**

```java
    private final int chunkExtractParallelism;
    private final CancellationCheckpoint cancellationCheckpoint;
```

现有 13 参构造器**保留**（源兼容：既有调用方与测试都用它），改为委托新 15 参构造器，默认 `chunkExtractParallelism = 1`、`cancellationCheckpoint = CancellationCheckpoint.NONE`：

```java
    public GraphMaterializationPipeline(
        ChatModel extractionModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        ExtractionRefinementOptions extractionRefinementOptions,
        Path snapshotPath,
        TaskMetadataReporter metadataReporter,
        IndexingProgressListener progressListener,
        int chunkExtractParallelism,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        List<String> relationTypes,
        List<GraphExtractionExample> graphExtractionExamples,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        // ... 其余赋值保持不变 ...
        this.chunkExtractParallelism = Math.max(1, chunkExtractParallelism);
        this.cancellationCheckpoint = cancellationCheckpoint == null ? CancellationCheckpoint.NONE : cancellationCheckpoint;
        // ...
    }
```

同时给 `ExtractionRefinementPipeline` 的 gap-detector lambda 加轮询（vendored :215）：

```java
        this.extractionRefinementPipeline = new ExtractionRefinementPipeline(
            this.extractionRefinementOptions,
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, ignored) -> {
                this.cancellationCheckpoint.check();
                return this.knowledgeExtractor.extractWindow(window);
            },
            // ...
        );
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: 全绿。

---

### Task 2: 并行/串行抽取 + 12 个检查点轮询点（9 vendored + 3 新增写入前检查）

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineConcurrencyTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineCancellationTest.java`

- [ ] **Step 1: 先写失败测试**

并发/顺序/失败传播（放 `GraphMaterializationPipelineConcurrencyTest`）：

```java
@Test
void runsChunkExtractionInParallelWhenParallelismIsGreaterThanOne() throws Exception {
    // 15 参构造器 chunkExtractParallelism = 3；ChatModel 在抽取时 countDown + await(5s) 形成 barrier
    // 断言 chatModel.maxConcurrentCalls() >= 2，且 extractions 的 chunk 顺序仍为输入顺序
}

@Test
void failsWholeBatchAndCancelsPendingWhenOneParallelExtractionFails() throws Exception {
    // 其中一个 chunk 的抽取抛 RuntimeException("boom for doc-1:1")
    // 断言 RuntimeException **原样传播**（同一实例，不包装——对齐既有 rethrowTaskFailure 契约），
    // 且调用在超时前返回（pending future 被 cancel，executor 被关闭）
}

@Test
void materializeRebuildUsesConfiguredParallelismEndToEnd() {
    // 端到端（不走反射）：InMemoryStorageProvider.create() 塞入 3 个 ChunkRecord，
    // 用 15 参构造器 parallelism=3 调 pipeline.materialize(documentId, GraphMaterializationMode.REBUILD)
    // 断言 chats 峰值 >= 2，且 documentGraphSnapshotStore().listChunks(documentId) 顺序与 chunk 顺序一致
}
```

取消语义（放 `GraphMaterializationPipelineCancellationTest`；**「任何取消都不写入」的旧断言已按第一轮评审 must-fix 1/3 废弃**——进入原子提交后取消延迟，正确的不变量是「提交前零写入，提交后要么完整提交、要么按既有补偿回滚，绝无中间态」）：

```java
@Test
void checkpointBeforeAtomicWriteLeavesStorageUntouched() {
    // 第 1 个检查点（materialize 入口）抛自定义 RuntimeException：
    // 断言：materialize(...) 抛出同一异常实例；chunk store / documentGraphSnapshotStore().listChunks(doc) /
    // graphStore 的实体与关系 / documentStatusStore 全部保持运行前的状态（快照前后逐项相等）
}

@Test
void cancellationArrivingDuringAtomicWriteStillCommitsWholeState() {
    // 用包装的 AtomicStorageProvider：在 writeAtomically 内先置「已请求取消」标志再执行真实写入；
    // 检查点实现看到标志即抛——若 GMP 在提交后还轮询任何检查点，调用就会抛异常。
    // 断言：materialize(...) 正常返回；实体、关系、向量、documentStatus 行齐全（完整提交），
    // 且 post-commit 不抛异常（即提交后无检查点）
}

@Test
void materializeNeverLeavesPartialGraphState() {
    // 测试策略（第二轮评审待评审 5 的裁定）：**顺序路径（parallelism=1）做完整参数化**——
    // 让第 k 个检查点抛（k 遍历一次 REBUILD 实际会经过的全部检查点），各跑一轮；
    // **并发路径只测两个确定边界**（入口检查点抛、提交中取消），不做「第 k 个」参数化
    //（并发下命中同一 k 不稳定，见影子实现的经验）。
    // 每轮断言存储状态 ∈ {与运行前完全一致, 一次完整提交}，不存在「有实体无关系」「有图无状态行」这类中间态
}

@Test
void cancelledRunInterruptsInFlightExtractionAndTerminatesWorkers() {
    // parallelism=3；模型在抽取时阻塞在 CountDownLatch.await()（响应中断）；
    // 主线程在并发抽取完成前用检查点抛异常 →
    // 断言：① 在途模型调用以 InterruptedException 退出（latch 在 finally 中计数）；② 抽取线程全部终止
    // （测试模型记录自己运行过的 Thread，事后 join(5s) 后断言 !isAlive()）
}

@Test
void shutdownDoesNotBlockLongerThanTheTerminationTimeoutWhenModelIgnoresInterrupts() {
    // 模型彻底忽略中断（await(30s) 且不响应 interrupt，返回后可被放行）；
    // 检查点抛异常 → 断言 materialize(...) 在 SHUTDOWN_TIMEOUT + 余量内返回（例如 < 10s），而不是等到 30s；
    // 同时不写任何东西（未进入原子提交）。这是「不无限阻塞」的契约；线程泄漏本身是已声明限制。
    // **测试卫生（第二轮评审 should-fix + 第三轮核验条件）**：被忽略中断的 worker 是非 daemon 线程，测试必须在 finally 里
    // countDown 释放闩锁，并在用例结束前对被测试 worker 逐个 join(5s) **且断言 !isAlive()**——否则 Gradle/JUnit 进程会额外空等，整个测试套被拖慢
}

@Test
void lightRagMaterializePassesCheckpointIntoPipeline() {
    // LightRag.builder()...build()，文档已有 chunks；检查点立即抛 → materializeDocumentGraph(ws, doc, REBUILD, checkpoint) 抛出
    // 断言 checkpoint 的调用计数 > 0（即确实被传进管线，而不是被忽略）
}
```

**GMP 与 `IndexingPipeline` 的行为对照矩阵（第一轮评审 should-fix 5；本计划不抽共享 helper，但两套实现的行为必须逐项对齐）**

| 行为 | `IndexingPipeline` 守护（既有，本计划不改） | GMP 守护（本计划新增） |
| --- | --- | --- |
| 并发峰值 ≥ 2 且结果顺序 = 输入顺序 | `preservesChunkOrderWhenExtractionRunsConcurrently` | `runsChunkExtractionInParallelWhenParallelismIsGreaterThanOne` |
| 单个抽取失败 → 整批失败（pending 取消、executor 关闭；`RuntimeException`/`Error` **原样传播**，`InterruptedException` 按 `rethrowTaskFailure` 既有契约包装） | `failsWholeBatchWhenAnyConcurrentExtractionFails` | `failsWholeBatchAndCancelsPendingWhenOneParallelExtractionFails` |
| 并行度经 builder/配置透传 | `propagatesChunkExtractParallelismThroughLightRagBuilder` | `materializeRebuildUsesConfiguredParallelismEndToEnd` |
| 并行度归一化（≤0 → 1；并受 chunk 数封顶） | 构造器 `Math.max(1, ...)`（隐含） | `defaultsToSequentialExtractionWhenParallelismIsNotConfigured`、`normalizesNonPositiveParallelismToOne` |
| 取消：在途调用被中断 + 线程终止 | **无**（IP 没有取消通道，登记为差距） | `cancelledRunInterruptsInFlightExtractionAndTerminatesWorkers`、`shutdownDoesNotBlockLongerThanTheTerminationTimeoutWhenModelIgnoresInterrupts` |
| 等待机制 | `completionService.take()`（阻塞，取消延迟上限 = 一次在途模型调用） | `completionService.poll(200ms)` + 轮询检查点（取消延迟上限 ≈ 200ms）——**有意差异**，仅此一处，写进 PR |

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphMaterializationPipelineConcurrencyTest" --tests "io.github.lightrag.indexing.GraphMaterializationPipelineCancellationTest"
```
Expected: 并行用例失败（峰值 1）、`materializeDocumentGraph` 4 参重载尚不存在 → 编译失败。

- [ ] **Step 3: 实现**

`refineExtractions` 拆成三块（形态对齐 `IndexingPipeline.java:943-1033`——`refineExtractions` 与其串行/并发抽取器所在区段；检查点按 vendored 加）：

```java
    private List<GraphAssembler.ChunkExtraction> refineExtractions(List<Chunk> chunks) {
        cancellationCheckpoint.check();
        long started = System.nanoTime();
        var parallelism = Math.min(chunkExtractParallelism, Math.max(1, chunks.size()));
        log.info(
            "LightRAG graph refineExtractions started: chunks={}, mode={}, parallelism={}",
            chunks.size(),
            parallelism <= 1 || chunks.size() <= 1 ? "SEQUENTIAL" : "CONCURRENT",
            parallelism
        );
        var primaryExtractions = parallelism <= 1 || chunks.size() <= 1
            ? extractPrimarySequentially(chunks)
            : extractPrimaryConcurrently(chunks);
        long extractedAt = System.nanoTime();
        var refined = extractionRefinementPipeline.refine(primaryExtractions);
        long refinedAt = System.nanoTime();
        log.info("LightRAG graph refineExtractions completed: chunks={}, extractionMs={}, refinementMs={}, totalMs={}", ...);
        return refined;
    }
```

`extractPrimarySequentially`（每轮 `check()`）、`extractPrimaryConcurrently`（提交体内 + 完成循环内 `check()`；`ExecutionException` → `cancelPending` + `rethrowTaskFailure`；`InterruptedException` → `cancelPending` + 复位中断位；`finally shutdownExecutor`）、`primaryChunkExtraction(chunk, index)`（把上游 `:613-630` 的循环体提成方法，日志带 `thread={}`）、`cancelPending`、`rethrowTaskFailure`、`private record IndexedPrimaryExtraction(int index, PrimaryChunkExtraction extraction) {}` —— 全部照搬 vendored :889-990（即上游 `IndexingPipeline` 同形代码 + 检查点；`IndexedPrimaryExtraction` 记录位于 vendored :1504，随同搬迁）。新增 import：`java.util.Collection`、`java.util.concurrent.{ExecutionException, ExecutorCompletionService, ExecutorService, Executors, Future, TimeUnit}`、`java.util.LinkedHashMap`。

**与 vendored 的两处有意强化（第一轮评审 must-fix 2 + 跨计划问题 3）：**

1. **完成循环用 `poll` 而不是 `take`**（取消延迟从「一次在途模型调用」降到 ~200ms）：

   ```java
        private static final long COMPLETION_POLL_MILLIS = 200;
        ...
        while (!pendingTasks.isEmpty()) {
            cancellationCheckpoint.check();
            var completed = completionService.poll(COMPLETION_POLL_MILLIS, TimeUnit.MILLISECONDS);
            if (completed == null) {
                continue;   // 没有任务完成：回到循环头轮询检查点（take() 会一直阻塞到下一个模型调用返回）
            }
            ... // 与 vendored 相同的取出/异常收尾逻辑
        }
   ```

2. **`shutdownExecutor` 的终止契约**（不再只有 `shutdownNow()` + 静默 5 秒）：

   ```java
        private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

        private void shutdownExecutor(ExecutorService executor) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn(
                        "LightRAG graph extraction workers did not terminate within {}s; "
                            + "in-flight ChatModel calls are expected to honour thread interruption (see contract on the ChatModel parameter)",
                        SHUTDOWN_TIMEOUT_SECONDS
                    );
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }
   ```

   **中断契约（写进 GMP 类注释与构造器 Javadoc）**：抽取阶段的 `ChatModel` 实现**必须响应线程中断**（HTTP 客户端通常如此：中断时抛出 `InterruptedException` 或 `IOException`）；不响应中断的实现会导致取消后线程继续运行到调用自然返回——这是**显式列出的限制**，代价是资源滞留而非数据错误（提交前的检查点已经保证不写入）。`shutdownExecutor` 只保证「不无限等待」（限时 5 秒 + 告警），不保证线程必然终止。

**12 个检查点轮询点（9 个与 vendored 一一对应，3 个为新增的提交前检查）：**

| # | 位置 | 来源 |
|---|---|---|
| 1 | 构造器里 `ExtractionRefinementPipeline` 的 gap-detector lambda | vendored :215（Task 1 已做） |
| 2 | `materialize(...)` 入口 | vendored :243 |
| 3 | `materializeDocumentState(...)` 入口 | vendored :412 |
| 4 | `rebuildSnapshot(...)` 入口 | vendored :497 |
| 5 | `rebuildSnapshot` 的 `writeAtomically(...)` 之前 | vendored :539 |
| 6 | `refineExtractions(...)` 入口 | vendored :864 |
| 7 | `extractPrimarySequentially` 每轮循环 | vendored :892 |
| 8 | 并发任务提交体（`completionService.submit` 内、调用 `primaryChunkExtraction` 前） | vendored :910 |
| 9 | 并发完成循环（`while (!pendingTasks.isEmpty())` 每轮；配 `poll(200ms)`） | vendored :919（强化） |
| **10** | **`materializeDocumentState` 的 `writeAtomically(...)` 之前**（上游 :296 之前） | **新增（must-fix 1）** |
| **11** | **`writeChunkMaterialization` 的 `writeAtomically(...)` 之前**（上游 :399 之前） | **新增（must-fix 1）** |
| **12** | **`materializeChunk` 入口**（`resumeChunk`/`repairChunk` 的公共入口，上游 :214） | **新增** |

**取消与原子写入的边界（第一轮评审 must-fix 1 —— 这是本计划的语义定义，写进 GMP 类注释与 `CancellationCheckpoint` 的 Javadoc）**

1. **检查点只在原子提交之外轮询**：每一处 `writeAtomically` 调用之前都有一个检查点（#5 已有、#10/#11 新增）。因此「检查点抛异常 ⇒ 本次调用没写任何东西」在**未进入提交**时严格成立。
2. **一旦进入 `writeAtomically`，本次提交不可中断**：不在事务回调内轮询检查点（那会让取消异常穿过事务/补偿边界，正是 Plan 1 在收紧的路径）。取消请求在提交期间到达 → 提交要么完整成功、要么按 Plan 1/现有补偿语义回滚，**绝无中间态**。
3. **提交之后不再轮询**：取消了也不抛——否则就会产生「数据已写、调用方却收到取消异常」的假象。任务层面的最终状态由 `TaskExecutionService.cancel` 记账决定，与存储状态解耦；允许出现「文档状态 = PROCESSED 且任务被标记取消」的组合，这是**声明的语义**，不是缺陷。
4. **延迟上限**：顺序抽取路径 = 一次模型调用之间；并发抽取路径 ≈ 200ms（`poll` 强化）；提交期间 = 一次提交的时长（含 provider 锁与 Redisson 锁等待）。

对应测试：`checkpointBeforeAtomicWriteLeavesStorageUntouched`（边界 1）、`cancellationArrivingDuringAtomicWriteStillCommitsWholeState` + `materializeNeverLeavesPartialGraphState`（边界 2/3）。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: 全绿。

- [ ] **Step 5: 回归并发先例**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.IndexingPipelineChunkExtractionConcurrencyTest"
```
Expected: 全绿（本任务**不应**改动 `IndexingPipeline`；此步是护栏，确认没被顺手改坏）。

---

### Task 3: `chunkSnapshotMismatch → REBUILD`

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphMaterializationPipelineTest.java`

**代价说明（第一轮评审待评审 4 的裁定：行为与 shadow 一致，可以接受，但必须写明）**：判定是 **chunk id 集合**比较，因此**仅顺序变化**（同一集合、不同顺序）**也会触发整篇重建**；只新增/删除一个 chunk 同理。这是「安全优先」的选择（集合不一致说明快照与当前切片不再对应，逐 chunk 增量修复的正确性论证成本高于重抽），代价是重建比必要情况更频繁。写进 `determineRecommendedMode` 的 Javadoc 与 PR 描述；本计划不做更细的模式（要优化需先给出「按 chunk 增删的最小修复」设计），列入第二轮问题。

- [ ] **Step 1: 先写失败测试（复用该文件既有 helper：`chunk(...)` :261、`seedDocumentGraphState(...)` :296、`chunkSnapshot(...)` :315）**

```java
@Test
void recommendsRebuildWhenStoredChunksDifferFromSnapshotChunkSet() {
    // chunkStore().save(...) 两个 chunk：doc-1:0、doc-1:1
    // seedDocumentGraphState 只写入 doc-1:0 的 chunkSnapshot
    // 断言 pipeline.inspect("doc-1").recommendedMode() == GraphMaterializationMode.REBUILD
}

@Test
void keepsExistingRecommendationWhenSnapshotMatchesStoredChunks() {
    // chunkStore 与 chunkSnapshot 的 chunk id 集合一致 → 断言 recommendedMode() != REBUILD
}

@Test
void doesNotTreatEmptyStoredChunksAsMismatch() {
    // chunkStore 为空、快照非空 → 走原第一分支（chunkSnapshots().isEmpty() 不成立时不得因 mismatch 归为 REBUILD）
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphMaterializationPipelineTest"
```
Expected: `recommendsRebuild...` 失败（当前返回 RESUME/REPAIR/AUTO 之一）。

- [ ] **Step 3: 实现**

在 `MaterializationState` 上补两个集合 helper + 判定（与 vendored :1547-1550 逐字一致；**不新增 record 组件**）：

```java
        private Set<String> storedChunkIds() {
            return storedChunks.stream().map(Chunk::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private Set<String> chunkSnapshotIds() {
            return chunkSnapshots.stream()
                .map(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private boolean chunkSnapshotMismatch() {
            var currentChunkIds = storedChunkIds();
            return !currentChunkIds.isEmpty() && !currentChunkIds.equals(chunkSnapshotIds());
        }
```

`determineRecommendedMode` 在 `chunkSnapshots().isEmpty()` 分支之后插入：

```java
        if (state.chunkSnapshotMismatch()) {
            return GraphMaterializationMode.REBUILD;
        }
```

需要 `java.util.LinkedHashSet` / `java.util.Set` import（若文件已有则复用）。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2。Expected: 全绿。

---

### Task 4: `LightRag` 公开 4 参重载 + 并行度透传

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentGraphApiTest.java`

- [ ] **Step 1: 先写失败测试（该文件是反射式签名契约测试，use `assertMethod` :337）**

在 `exposesDocumentAndChunkGraphMaterializationApiSignatures()`（:18）现有的 3 参断言旁追加：

```java
        assertMethod(
            "materializeDocumentGraph",
            DocumentGraphMaterializationResult.class,
            String.class,
            String.class,
            GraphMaterializationMode.class,
            CancellationCheckpoint.class
        );
```

**注意（第一轮评审 should-fix 4 的落地方式）**：这里断言的是 `CancellationCheckpoint` 而**不是** `Runnable`，且**没有** `Runnable` 重载——理由见 Step 3 末尾。

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagDocumentGraphApiTest"
```
Expected: `AssertionError`（找不到 4 参方法）。

- [ ] **Step 3: 实现（与本计划 shadow 的对应实现同形，差异只在取消类型）**

```java
    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode
    ) {
        return materializeDocumentGraph(workspaceId, documentId, mode, null);
    }

    public DocumentGraphMaterializationResult materializeDocumentGraph(
        String workspaceId,
        String documentId,
        GraphMaterializationMode mode,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        var scope = resolveScope(workspaceId);
        return runInWorkspace(
            scope,
            provider -> newGraphMaterializationPipeline(scope, provider, cancellationCheckpoint)
                .materialize(documentId, mode)
        );
    }
```

**为什么没有 `Runnable` 重载（should-fix 4 的完整推演，评审若不同意请在此反驳）**：

- `Runnable` 与 `CancellationCheckpoint` 的函数类型完全相同（`() -> void`），彼此无子类型关系。按 JLS 15.12.2.5，两个「同签名的无关函数式接口」重载对**隐式 lambda** 是二义的：`materializeDocumentGraph(ws, doc, mode, () -> {})` 无法编译。
- 字面量 `null` 同样二义：3 参重载的内部委托（`..., null`）也必须写成 `(CancellationCheckpoint) null` 才能编译——连 jar 自己都要靠强制转换维持整洁。
- 兼容性收益很小且是编译期可见的：aiplatform 只有 `KnowledgeGraphServiceImpl` 一处（Task 6 Step 4，3 行机械改动），且改动是**编译期报错**而非 Plan 2 那种隐性 `NoSuchMethodError`。用「公共 API 长期带一个会歧义的哑重载」换「一处 3 行改动」不划算。

私有工厂加两级重载（`(scope, storageProvider)` 委托到 `(scope, storageProvider, (CancellationCheckpoint) null)`；`(scope, storageProvider, CancellationCheckpoint)` 委托到 5 参），5 参版本 = 现有 4 参版本 + 两处插入：`graphExtractionOptions.resolvedChunkExtractParallelism()`（放在 `progressListener` 之后）与 `cancellationCheckpoint`（放最后）：

```java
        return new GraphMaterializationPipeline(
            cachedModel("extract", config.extractionModel(), llmCacheStore),
            config.embeddingModel(),
            storageProvider,
            extractionRefinementOptions,
            config.snapshotPath(),
            metadataReporter,
            progressListener,
            graphExtractionOptions.resolvedChunkExtractParallelism(),
            graphExtractionOptions.resolvedEntityExtractMaxGleaning(),
            graphExtractionOptions.resolvedMaxExtractInputTokens(),
            graphExtractionOptions.resolvedLanguage(),
            graphExtractionOptions.resolvedEntityTypes(),
            graphExtractionOptions.resolvedRelationTypes(),
            graphExtractionOptions.resolvedExamples(),
            cancellationCheckpoint
        );
```

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2，随后跑 Task 2 建的两个新测试文件（`lightRagMaterializePassesCheckpointIntoPipeline` 应转为通过）。

- [ ] **Step 5: 回归既有 API 面**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest" --tests "io.github.lightrag.api.LightRagRelationalDocumentGraphPersistenceTest"
```
Expected: 全绿（3 参路径行为不变）。

---

### Task 5: 日志级别与线程名对齐 —— **已决策：不做**（第一轮评审待评审 5 裁定）

shadow 把每 chunk 的 `log.info` 降为 `log.debug` 并加了 `thread={}`（aiplatform 的动机：降低后台限流日志噪声，见其 commit `fix(knowledge): 降低后台限流日志噪声`）。上游今天每 chunk 一条 `info`（`:620-630`）。

**决策：本计划保持上游的 `info` 级别，不改。** 理由（评审原话：「日志级别改动不应混入核心迁移，这是独立行为变更，应单独提交或明确不做」）：

- 降级别是**可观测性行为变更**，与「能力上游化」是两件事；混在一起会让「升级后日志变少」被误读为丢日志。
- 上游化后的并行路径会让每 chunk 日志变为交错输出，噪声问题确实存在——但那应该在 README/PR 里提示「需要安静时把 `io.github.lightrag.indexing.GraphMaterializationPipeline` 调到 DEBUG 之外的自定义 logger 配置」，或后续单独提一个「per-chunk 日志降级」提交。
- 若未来要做：两条聚合 started/completed 保持 `info`，把 `primaryChunkExtraction` 的两条每 chunk 日志改 `debug` 并补 `thread={}`（与 `IndexingPipeline.java` 同口径，届时两边一起改）。

- [ ] **Step 1: 确认本任务无代码改动**（在 PR 描述里写明「有意不做」及理由）。

---

### Task 6: aiplatform 切换（升版本 + 删除两个 shadow）

**本任务全部路径相对 `D:\ai-code\aiplatform`**（其余任务相对 `D:\ai-code\lightrag-java`）。

**Files:**
- Modify: `backend/pom.xml`（`<lightrag.version>` → 含本计划的新版本）
- Delete: `backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java`
- Delete: `backend/aide-kno/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`

- [ ] **Step 1: 发版**：按既有发布流程发布含本计划的 lightrag-core 版本（见 `docs/superpowers/plans/2026-04-01-java-lightrag-maven-central-publishing.md`），并在 PR 交付说明里写明版本号。
- [ ] **Step 2: 升 `lightrag.version`**（`backend/pom.xml:47` 一带，当前 `0.23.0`）。
- [ ] **Step 3: 删除两个 shadow 文件，并上「防再生」检查**

先删（连同空目录），再确认 `backend/aide-kno/src/main/java/io/github/lightrag/` 下再无 `.java`：

```bash
# Git Bash（本文件所有命令的默认 shell）
find backend -path "*io/github/lightrag*" -name "*.java"
```
Expected: 输出为空。

新增 `scripts/check-no-lightrag-shadow.sh` 并接进 CI 必过作业（跨计划问题 1 的裁定）：

```bash
#!/usr/bin/env bash
# Fails if any module source shadows a class that lives in the lightrag-core jar.
set -euo pipefail
matches="$(find backend -path '*/src/*/java/io/github/lightrag/*' -name '*.java' -print)"
if [[ -n "${matches}" ]]; then
  echo "lightrag shadow classes found (must be synced or deleted):"
  echo "${matches}"
  exit 1
fi
```

PowerShell 等价（给不走 Git Bash 的 reviewer）：

```powershell
Get-ChildItem -Path backend -Recurse -Filter *.java |
  Where-Object { $_.FullName -match '\\src\\.*\\java\\io\\github\\lightrag\\' } |
  ForEach-Object { $_.FullName }
```
Expected: 无输出；`bash scripts/check-no-lightrag-shadow.sh` 退出码 0。

- [ ] **Step 4: 改 4 类调用点（机械类型改动：生产代码 4 处 `.run()` + 同模块测试 1 处反射用法）+ 核对其余行为**（本任务的关键验证；**不是「无需改动」**——取消类型从 `Runnable` 换成了 `CancellationCheckpoint`）：
  - `KnowledgeGraphServiceImpl.java:2094-2104` 的 `graphBuildCancellationCheckpoint(taskId)`：返回类型 `Runnable` → `CancellationCheckpoint`（方法体里的 `throw` 不变）。
  - **四处**前置检查——`:2190`、`:2397`、`:2685`、`:2688`（`:2685`/`:2688` 同属 `executeRebuild`）：`cancellationCheckpoint.run()` → `.check()`。
  - 两处传递——`:2420`、`:2698`：**调用点写法不变**（继续传同一个变量；变量的声明类型变了，jar 里只存在 `CancellationCheckpoint` 重载，编译期即可发现遗漏）。
  - `:2082-2090` 的公开重载：参数类型 `@Nullable Runnable` → `@Nullable CancellationCheckpoint`（`:2075-2079` 的另一个重载传 `null`，不动；若被同模块其他类调用，一并按编译器提示更新）。
  - `KnowledgeGraphServiceImplTest.java:1304-1336` 的 `graph_build_cancellation_checkpoint_throws_only_for_terminated_task`：两处反射返回值强转 `(Runnable)`（`:1308-1311`、`:1328-1331`）→ `(CancellationCheckpoint)`，两处 `.run()`（`:1318`、`:1332`）与 `checkpoint::run`（`:1326`）→ `.check()` / `checkpoint::check`。不改的后果是**运行时** `ClassCastException`——`invokeMethod` 泛型返回 `Object`，强转编译期不报错，Step 5 的 `-Dmaven.test.skip=true` 也不暴露；下次全量测试时该用例失败。同模块其余测试对 4 参重载的桩调用均为 `any()`，类型替换后无需改动。
  - `graphBuildCancellationCheckpoint` 抛出的 `KnowledgeGraphBuildCancelledException` 是 `RuntimeException`，会从 GMP 原样冒出（与 shadow 行为一致）——确认捕获它的上层逻辑（`:2189`、`:2396`、`:2684` 起的 try/catch 或上层任务包装）行为不变。
  - `resumeChunkGraph` / `repairChunkGraph`（包装 `:2111`、`:2119`；调用点 `:2424`、`:2428`、`:2587`、`:2588`）不传检查点，行为不变。
  - 全仓核对无遗漏：`grep -rn "cancellationCheckpoint\|graphBuildCancellationCheckpoint" backend/aide-kno/src`——生产代码预期只剩上述位置（`:2086`、`:2089`、`:2094`、`:2190`、`:2395`、`:2397`、`:2420`、`:2683`、`:2685`、`:2688`、`:2698`），测试里只剩 1 处反射字符串（`:1311`、`:1331` 同一方法名）；两个 shadow 文件（`io/github/lightrag/**`）此时已删除，不应再有命中。
  - **行号基线（第四轮评审 should-fix）**：以上行号按 2026-09-30 的 aiplatform checkout 刷新（原 `:2591`/`:2879-2882`/`:2610-2614`/`:2888-2892`/`:2276-2283`/`:2589`/`:2618-2622`/`:2781-2782` 均已漂移，且 `.run()` 实为 4 处而非 2 处）；实施时以当时的 `grep -n cancellationCheckpoint` 为准，符号名不变。
- [ ] **Step 5: 编译验证**（按该仓库 AGENTS.md：后端改动后必须 `clean install`；默认不主动跑单测，需用户授权；命令用 **Git Bash** 执行）：

```bash
export MAVEN_OPTS="${MAVEN_OPTS:+$MAVEN_OPTS }-Dfile.encoding=UTF-8"
# 必跑 1：主代码构建
mvn -f backend/pom.xml -T4 -pl aide-admin -am clean install -Dmaven.test.skip=true
# 必跑 2：测试源码编译（本任务的机械类型改动包含测试文件，见 Step 4 的反射强转；
#          test.skip 连测试源码都不编译，只有 -DskipTests 能在编译期暴露漏改）
mvn -f backend/pom.xml -T4 -pl aide-admin -am clean install -DskipTests
```
Expected: 两条都 **`BUILD SUCCESS`**（注意是 Maven 的成功标记，不是 Gradle 的 `BUILD SUCCESSFUL`）。若 Spotless 报格式，按 AGENTS.md 跑 `spotless:apply` 后重试。是否进一步运行该模块单测需用户授权。

PowerShell 等价：

```powershell
$env:MAVEN_OPTS = "$env:MAVEN_OPTS -Dfile.encoding=UTF-8".Trim()
mvn -f backend/pom.xml -T4 -pl aide-admin -am clean install -Dmaven.test.skip=true
mvn -f backend/pom.xml -T4 -pl aide-admin -am clean install -DskipTests
```

- [ ] **Step 6: worktree 副本的处理边界**：`.worktrees/{chat-access-auth-integration,skill-evolution-s1-22-validation,skill-mcp-progressive-binding}/backend/aide-knowledge/src/main/java/io/github/lightrag/` 各有一份旧 shadow；这些分支升级 lightrag-core 时同样删除（或按需同步），**不在本计划内**改动，Step 3 的 CI 扫描是防止新副本再生的机制。「永久作废」只对本计划删除了 shadow 的**主线**成立——worktree 各自仍是独立的耦合点。**责任与时点（第四轮评审待评审问题 6）**：删除归各 worktree 所属分支——该分支同步 `lightrag-core` 版本升级时一并删除 shadow（与主线的「升版本 + 删 shadow 同 commit」同一规则），并在该 PR 描述里登记 worktree 名与删除清单。
- [ ] **Step 7: 性能与行为抽验**（有测试环境时）：
  - 同一文档在相同 `chunk-extract-parallelism` 下，升级前后 `materialize` 的墙钟时间与图内容一致。
  - 取消一条正在 materialize 的任务：提交前取消 → 存储零变化；提交期间取消 → 文档完整写入且任务被标记取消（声明的组合语义，见 Task 2 Step 3 的边界一节，不是缺陷）。
  - 抽查日志确认正常完成时不会出现 `shutdownExecutor` 的超时告警（说明模型响应中断）。

---

## 验收标准

- `GraphMaterializationPipeline` 支持 `chunkExtractParallelism`（>1 时真并行、结果顺序稳定、失败取消 pending、`Math.max(1, ...)` 归一化）与 `CancellationCheckpoint`（12 个轮询点，`null` 容忍），且行为对照矩阵里 GMP 列的用例全部通过。
- 13 参构造器行为与今天完全一致（并行度 1、无检查点），既有调用方与测试零改动。
- **取消语义**：提交前取消 ⇒ 零写入；提交中取消 ⇒ 完整提交或按补偿回滚，绝无中间态；提交后不轮询（`cancellationArrivingDuringAtomicWriteStillCommitsWholeState`、`materializeNeverLeavesPartialGraphState`）。
- **executor 终止**：响应中断的模型其线程在 5s 内终止；不响应中断的模型不会让 `materialize` 阻塞超过 `SHUTDOWN_TIMEOUT + 余量`（有告警日志，列为文档化限制）。
- `chunkSnapshotMismatch → REBUILD` 生效（**注意：chunk 顺序变化也会触发整篇重建**，写进 PR 与 Javadoc）；`MaterializationState` 组件、`loadState`、`GraphMaterializationMode` 枚举均未改动。
- `LightRag` 提供 4 参 `materializeDocumentGraph(..., CancellationCheckpoint)` 重载（**无 `Runnable` 重载**，理由见 Task 4 Step 3），并把 `resolvedChunkExtractParallelism()` 透传进 GMP。
- `./gradlew :lightrag-core:test` 全绿。
- aiplatform：`io/github/lightrag/` 下零 `.java`；`scripts/check-no-lightrag-shadow.sh` 退出码 0 且已接 CI；`mvn ... clean install` 输出 `BUILD SUCCESS`；`KnowledgeGraphServiceImpl` 与 `KnowledgeGraphServiceImplTest` 的机械类型改动完成（4 处 `.run()` → `.check()` + 3 处类型替换：返回类型、检查点重载参数、测试反射强转）；shadow 删除后既有 `LightRagSdkUpgradeSmokeTest`（3 条用例）仍编译并通过——它反射断言平台需要的 SDK 图 API 面，并验证 `chunkExtractParallelism(4)` 下 REBUILD 真并行（`maxActive > 1`、4 chunk ⇒ 4 次抽取调用），是本计划「swapped SDK 真正可用」的现成验收桥。
- PR 交付说明写明：版本号、shadow 删除清单、`Runnable`→`CancellationCheckpoint` 的 API 决策、Task 5 有意不做、worktree 边界（「作废」只对主线成立）。

## 第一轮评审裁定与落实（2026-09-30）

| # | 评审意见（要点） | 落实位置 |
| --- | --- | --- |
| must-fix 1 | 取消不足以保证「取消后不写入」；须定义写入前检查点、提交后取消语义、任务状态 | Task 2 Step 3 新增「取消与原子写入的边界」4 条；检查点从 9 扩到 12（#10/#11 提交前、#12 chunk 入口）；`CancellationCheckpoint` Javadoc 写明边界 |
| must-fix 2 | executor 终止可能泄漏线程（`shutdownNow` 后 5s 不保证终止） | Task 2 Step 3 强化 `shutdownExecutor`（限时 + `log.warn`）+ 模型中断契约写入 Javadoc + 两个终止用例（含「不无限阻塞」） |
| must-fix 3 | 取消测试断言过强、场景不完整 | 废弃 `checkpointAbortsMaterializeAndWritesNothing`，重写为 6 个用例（提交前零写入 / 提交中完整提交 / 无中间态参数化 / 线程终止 / 不无限阻塞 / API 透传） |
| should-fix 4 | `Runnable` 公开 API 类型过弱 | 新增 `CancellationCheckpoint` 函数式接口（Task 1 Step 0）；**不提供** `Runnable` 重载（Task 4 Step 3 给出 lambda/`null` 二义性推演）；aiplatform 3 处机械改动（Task 6 Step 4） |
| should-fix 5 | 复制实现会漂移，应固定行为测试矩阵 | Task 2 Step 1 末尾的「GMP 与 `IndexingPipeline` 行为对照矩阵」（6 行为 × 两侧用例，含有意差异与差距登记） |
| should-fix 6 | Windows 命令不可直接执行 | Task 6 Step 3/5 全部改为 Git Bash 命令 + PowerShell 等价 |
| nit 7 | Maven 成功标记为 `BUILD SUCCESS` | Task 6 Step 5 修正 |
| 判断 1 | 暂不抽共享 helper，但要有共享行为测试 | 采纳（矩阵） |
| 判断 2 | 建议 `CancellationCheckpoint` + `Runnable` 兼容重载 | 采纳前半（新类型）；**不采纳**后半（兼容重载），理由 = lambda/`null` 二义性，见 Task 4 Step 3 |
| 判断 3 | 9 个检查点不充分；须定义原子提交边界 + 补写入前检查 | 采纳（12 点 + 边界 4 条） |
| 判断 4 | chunk mismatch 整篇重建可接受，但须注明顺序变化也触发 | 采纳（验收标准 + Task 3 说明） |
| 判断 5 | 日志级别改动不应混入核心迁移 | 采纳（Task 5 明确不做） |
| 判断 6 / 跨计划 1 | 删 shadow 才算作废；worktree 仍有副本；需 CI 扫描 | 采纳（Task 6 Step 3 脚本 + Step 6 边界说明） |
| 跨计划 2 | 顺序改为 Plan 1 → Plan 3 → Plan 2 | 采纳（文末「跨计划顺序」+ 背景节） |

## 第二轮评审裁定与落实（2026-09-30）

结论：**可开工**（无 must-fix）。两条改进意见已落实；第三轮复审只需核验本表。

| 第二轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| should-fix：不响应中断的测试会留下非 daemon worker，拖慢测试进程 | 采纳 | Task 2 Step 1 的 `shutdownDoesNotBlockLongerThanTheTerminationTimeoutWhenModelIgnoresInterrupts` 注释写死测试卫生：模型 `await(30s)`（返回后可被放行）、用例在 `finally` 里 `countDown` 释放闩锁、结束前 `join(5s)` 收尾 |
| nit：矩阵「异常原样冒出」与用例「同一实例或包装」强度不一致 | 采纳 | 矩阵行改为「`RuntimeException`/`Error` 原样传播，`InterruptedException` 按 `rethrowTaskFailure` 既有契约包装」；用例注释同步为「同一实例，不包装」（已核 `IndexingPipeline.java:1100-1108` 的既有契约） |
| 判断 1：取消边界完备，`persistSnapshotIfConfigured` / progress 回调等窗口「跑完并成功返回」可接受 | 记录：维持现状 | Task 2 Step 3 边界一节 |
| 判断 2：`poll(200ms)` 与 `take()` 的有意差异合理 | 记录 | 行为对照矩阵末行 |
| 判断 3：不提供 `Runnable` 重载的判断正确 | 记录 | Task 4 Step 3 |
| 判断 4：`CancellationCheckpoint.NONE` 常量可保留 | 记录 | Task 1 Step 0 |
| 判断 5：`materializeNeverLeavesPartialGraphState` 的「第 k 个」参数化应退化 | 采纳 | Task 2 Step 1 用例注释：顺序路径全参数化 + 并发路径只测入口/提交前与提交中两个确定边界 |
| 判断 6：worktree 需独立删除/同步，CI 只防主线再生 | 记录：验收标准与 Step 6 边界说明不变；各 worktree 的处置不阻塞主线实施 | Task 6 Step 3/6 |

## 第三轮评审裁定与落实（2026-09-30）

结论：**可开工**（无 must-fix）。第三轮指出的一处过时行号与两条核验前提已落实；第四轮复审请核验本表。

| 第三轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| 过时行号：本计划第三轮问题中的 `IndexingPipeline.java:1001-1032` 已失效 | 修正为 `:1100-1108`（`rethrowTaskFailure`）与 `:1022-1032`（并发路径 catch/finally）；另一处 `949-1033` 修正为 `943-1033` | Task 2 Step 3 与「第二轮评审的待评审问题」第 2 条 |
| 判断 1：测试卫生足够，前提是 `countDown` 在 `finally`、对每个被测试 worker `join(5s)` 并断言线程已结束 | 采纳：用例注释补「断言 `!isAlive()`」 | Task 2 Step 1 `shutdownDoesNotBlockLongerThanTheTerminationTimeoutWhenModelIgnoresInterrupts` |
| 判断 2：异常断言与 `rethrowTaskFailure` 语义一致（`RuntimeException`/`Error` 原样、其余包装） | 记录：矩阵与用例文字维持不变 | 行为对照矩阵 + 用例注释 |

## 第四轮评审裁定与落实（2026-09-30）

结论：**可开工**（无 must-fix，1 处 should-fix）。should-fix 与本表新增的两条自查项已落实；第五轮复审请核验本表。

| 第四轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| should-fix：aiplatform 行号已漂移（评审给出的 `:2395`/`:2683` vs 计划旧值 `:2589`/`:2877`），实施前应刷新 | 采纳：Task 6 Step 4 全量刷新为 2026-09-30 checkout 行号，并改正 `.run()` 实为 **4 处**（`:2190`/`:2397`/`:2685`/`:2688`，其中 `:2190` 是 `graphBuildCancellationCheckpoint(taskId).run()` 的内联形态）；File Map、背景「证据」第 2 条、Scope Guardrails 的 resume/repair 调用点、验收标准一并同步为刷新后的行号；新增「行号基线」提示——实施时以当时的 `grep -n cancellationCheckpoint` 为准 | Task 6 Step 4 + 背景第 2 条 + Scope Guardrails + File Map + 验收标准 |
| 待评审问题 1（file:line 漂移） | CLOSED：本地 `IndexingPipeline` 引用第四轮确认一致；aiplatform 侧按 should-fix 刷新 | Task 2/3 引用 + Task 6 Step 4 |
| 待评审问题 2（测试卫生：`finally` 释放闩锁 + `join(5s)` + 断言 `!isAlive()`） | CLOSED | Task 2 Step 1 用例注释 |
| 待评审问题 3（不提供 `Runnable` 重载） | CLOSED：两个同形 `() -> void` 接口的 lambda / 字面 `null` 二义成立 | Task 4 Step 3 |
| 待评审问题 4（`CancellationCheckpoint.NONE` 常量） | CLOSED：接口常量字段符合惯例，构造器归一化 `null` | Task 1 Step 1 |
| 待评审问题 5（`materializeNeverLeavesPartialGraphState` 折中） | CLOSED：顺序路径完整参数化 + 并发路径两个确定边界 | Task 2 Step 1 |
| 待评审问题 6（worktree 与 CI 的责任人/时点） | PARTIALLY CLOSED → 已补：「删除归各 worktree 所属分支，在其同步 `lightrag-core` 版本升级的同一 commit 完成，并登记到该 PR」 | Task 6 Step 6 |
| **自查（第四轮后新增）**：测试反射调用漏列——`KnowledgeGraphServiceImplTest.graph_build_cancellation_checkpoint_throws_only_for_terminated_task` 把 helper 返回值强转 `(Runnable)` 并调 `.run()`；类型替换后**运行时** `ClassCastException`（`invokeMethod` 泛型返回 `Object`，编译期不报错；`-Dmaven.test.skip=true` 连测试源码都不编译，也不会暴露） | 采纳：列入机械类型改动清单（两处强转 + 三处调用），File Map 新增该测试文件，验收标准补「3 处类型替换」与「换 SDK 后 `LightRagSdkUpgradeSmokeTest` 3 条用例保持绿」；Step 5 补 `-DskipTests` 验证（**第五轮提升为必跑 2**） | Task 6 Step 4/5 + File Map + 验收标准 |
| **自查（第四轮后新增）**：换 SDK 的验收桥——aiplatform 既有 `LightRagSdkUpgradeSmokeTest` 3 条用例（反射断言平台所需 SDK 图 API 面 + `chunkExtractParallelism(4)` 下 REBUILD 真并行 `maxActive > 1`）当前对 shadow 通过，删除 shadow 后应继续通过 | 采纳：写进验收标准，作为「swapped SDK 真正可用」的现成验收桥 | 验收标准 |

## 第五轮评审裁定与落实（2026-09-30）

结论：**可开工**（无 must-fix）。两条 should-fix 已落实，一条增强项记录为不做；第六轮复审请核验本表。

| 第五轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| should-fix：待评审问题 1 里的「`grep -n cancellationCheckpoint` 命中 9 行」是笔误，当前源码实为 **11 行**（`:2086`、`:2089`、`:2094`、`:2190`、`:2395`、`:2397`、`:2420`、`:2683`、`:2685`、`:2688`、`:2698`）；Task 6 Step 4 里的 11 行完整清单反而是正确的 | 采纳：更正笔误，并把 11 行完整清单与「`grep -n "\.run()"` 中与检查点相关的 4 处」一并写进存档答案 | 本节 + 「第五轮评审的待评审问题（已答，存档）」第 1 条 |
| should-fix：`-DskipTests` 应从「可选加强」改为该机械类型变更的**必跑**验证（`-Dmaven.test.skip=true` 不编译测试源码） | 采纳：Task 6 Step 5 改为两条必跑命令（主代码构建 + 测试源码编译），Git Bash 与 PowerShell 两个代码块同步 | Task 6 Step 5 |
| 增强（非 must-fix）：可给 smoke bridge 再加一个 4 参 `materializeDocumentGraph(..., CancellationCheckpoint.class)` 的反射断言，把平台取消语义也纳入换 SDK 验收 | 记录：**本轮不做**——4 参签名已由 `KnowledgeGraphServiceImpl` 生产代码的编译期解析钉住（Step 5 必跑 1/2 编译主代码时暴露），smoke 里的重复反射断言边际收益低；若用户希望把取消语义也纳入 smoke，实施时再加 | 本节 |
| 判断 1：行号复核（除「9 行」笔误外关键位置一致） | CLOSED | Task 6 Step 4 + 本节 |
| 判断 2：反射强转补漏成立，Step 4/File Map/验收标准自洽完整 | CLOSED | Task 6 Step 4 + File Map + 验收标准 |
| 判断 3：`LightRagSdkUpgradeSmokeTest` 作为换 SDK 验收（API 面 + 并行生效）对 SDK 替换后的需求足够；取消专项由编译与既有测试承担 | CLOSED（增强项见上） | 验收标准 + 本节 |

## 第六轮评审裁定与落实（2026-09-30）

结论：**可开工、可冻结**（无 must-fix，无 should-fix，无 nit）。三条核验全部 CLOSED，本节为纯记录性补充（不改变正文语义）；本计划自此冻结。

| 第六轮核验点 | 裁定 | 依据 |
| --- | --- | --- |
| (a) 「11 行」更正 | CLOSED：Task 6 Step 4 已列完整 11 行；全文残留的「命中 9 行」仅存在于历史问题原文及其裁定说明，均紧跟 11 行答案，不构成实施指令 | Task 6 Step 4 + 本节上方两节 |
| (b) Step 5 必跑 1/必跑 2（含 PowerShell 块） | CLOSED：两条命令自洽、可执行；第二条只编译测试源码不跑单测，符合「单测需授权」约束；Expected 要求两条均 `BUILD SUCCESS` | Task 6 Step 5 |
| (c) 增强项不做（不加 smoke 4 参反射断言） | CLOSED：4 参签名由生产代码编译期解析钉住（必跑构建暴露）；反射断言只重复验证签名存在，不能额外验证取消行为，不追加可接受 | 本节上方第五轮表 |
| 冻结后的维护边界 | 实施期只允许两类动作：以当时的 `grep -n cancellationCheckpoint` 校准行号；按本文写明的边界（行号漂移）作等价替换——涉及行为/契约的改动必须先解冻 | 本表 |

## 第五轮评审的待评审问题（第五轮评审已回答，存档）

1. **行号基线复核**：本轮刷新后的 aiplatform 引用是否与当前 checkout 一致——`.run()` 四处 `:2190`/`:2397`/`:2685`/`:2688`、传递 `:2420`/`:2698`、公开重载 `:2082-2090`、helper `:2094-2104`、try 块 `:2189`/`:2396`/`:2684`、resume/repair 包装 `:2111`/`:2119` 与其调用点 `:2424`/`:2428`/`:2587`/`:2588`？`grep -n "cancellationCheckpoint"` 命中 9 行、`grep -n "\.run()"` 中与检查点相关的 4 处——是否确无第 5 处？（第五轮裁定：**CLOSED**——除「9 行」应为 **11 行**（`:2086`、`:2089`、`:2094`、`:2190`、`:2395`、`:2397`、`:2420`、`:2683`、`:2685`、`:2688`、`:2698`）这一笔误外，关键位置全部一致；与检查点相关的 `.run()` 确为 4 处，`:1256-1284` 的 `dispatch.run()` 是无关命中，无第 5 处。）
2. **类型改动清单的完整性**：除生产代码 4 处 `.run()` 与测试反射用例 `KnowledgeGraphServiceImplTest:1304-1336` 外，是否还有因 `Runnable` → `CancellationCheckpoint` 而漏列的调用方（该模块其余测试桩均为 `any()`）？Step 5 用 `-Dmaven.test.skip=true` 跳过测试编译是否可接受，还是应把 `-DskipTests` 列为必跑？（第五轮裁定：**CLOSED**——清单完整；`-DskipTests` 应列为必跑，已落实为 Step 5 的必跑 2。）
3. **验收桥的充分性**：`LightRagSdkUpgradeSmokeTest` 作为换 SDK 的集成验收（API 面 + 并行生效）是否充分；上游侧 `materializeRebuildUsesConfiguredParallelismEndToEnd`（Task 2 Step 1）已覆盖同一行为，两者是否构成足够闭环？（第五轮裁定：**CLOSED**——对 SDK 替换后的 API 面与并行行为足够；取消专项由编译与既有测试承担。可选的 4 参反射断言属增强，见本节裁定表。）

## 第六轮评审的待评审问题（第六轮评审已回答，存档）

1. **两条 should-fix 的闭环核验**：①「9 行」笔误是否已按 11 行完整清单更正（Task 6 Step 4 与本节存档答案两处一致）？②`-DskipTests` 是否已落实为 Task 6 Step 5 的必跑 2，且 Git Bash 与 PowerShell 两个块一致？（第六轮裁定：**CLOSED**——11 行清单已在 Task 6 Step 4 与第五轮裁定表两处落实；两条必跑命令与 PowerShell 等价块一致、Expected 要求两条均 `BUILD SUCCESS`。）
2. **增强项不做的裁定**：不给 `LightRagSdkUpgradeSmokeTest` 加 4 参 `materializeDocumentGraph(..., CancellationCheckpoint.class)` 反射断言，理由是「4 参签名已由生产代码编译期钉住 + smoke 重复断言边际收益低」——是否可接受？若不可接受，请给出为什么编译期钉住不足以覆盖该风险。（第六轮裁定：**可接受**——签名缺失会在必跑构建中暴露；反射断言只重复验证签名存在，不能额外验证取消行为；若把风险扩大为运行时取消语义验证则属增强项，不构成 must-fix。）
3. **是否可以冻结本计划**：若无 must-fix，请确认 Plan 3 可冻结（后续只剩实施期以当时 `grep` 重新校准行号的常规动作）。（第六轮裁定：**可冻结**——无残余 must-fix、should-fix、nit。）

**评审循环关闭（2026-09-30）**：Plan 3 经第 1–6 轮 Codex 评审，最后一轮（第六轮）判定「可开工、可冻结、零残余」。后续动作仅限：实施期以当时 `grep` 校准行号的等价替换；涉及行为/契约的改动须先解冻并单独修订（见第六轮表末行）。

## 第三轮评审的待评审问题（第四轮评审已回答，存档）

1. **其余 file:line 引用是否仍有漂移**：本轮修正了 `1001-1032` 与 `949-1033` 两处；请抽查其余关键引用（`:998-1033` 并发抽取器、`:1091-1098` shutdown 等待、矩阵与 aiplatform 侧行号）是否与当前源码一致。（第四轮裁定：本地 `IndexingPipeline` 引用 CLOSED；aiplatform 侧行号应刷新，属 should-fix——已按上文落实。）
2. **测试卫生前置条件是否已写全**：用例注释现含「`finally` 释放闩锁 + 对 worker `join(5s)` + 断言 `!isAlive()`」；请确认三者齐备后该用例不再有残留非 daemon worker 拖慢测试进程的风险。（第四轮裁定：CLOSED。）

## 第二轮评审的待评审问题（第三轮评审已回答，存档）

1. **测试卫生的落实程度**：`finally` 释放闩锁 + `join(5s)` 是否足以保证该用例不拖慢整个测试进程（Gradle test JVM 退出前是否还有其他路径等待非 daemon 线程）？（第三轮裁定：足够，前提是 `countDown` 确在测试 `finally`，且对每个被测试 worker `join(5s)` 并断言线程已结束——已补断言。）
2. **异常传播断言的强度**：矩阵与用例都写「`RuntimeException`/`Error` 同一实例、不包装；`InterruptedException` 包装」——是否与 GMP 复制实现的行为完全一致？（第三轮裁定：一致；原先引用的行号已按现址修正。）

## 第一轮评审的待评审问题（第二轮评审已回答，存档）

1. **取消边界的完备性**：定义 = 「提交前检查点（#5/#10/#11）→ 提交不可中断 → 提交后不轮询」。是否还有未被覆盖的窗口？（例如：`rebuildSnapshot` 在写快照**之后**、`materializeDocumentState` 之前；`persistSnapshotIfConfigured` 的落盘；`progressListener` 回调期间。）这些窗口里取消请求到达，按当前设计会「跑完并成功返回」——这是否可接受？
2. **`poll(200ms)` 与 `take()` 的有意差异**：GMP 用 `poll` 换取 ~200ms 的取消延迟，`IndexingPipeline` 保持 `take()`。这个差异是否值得？（备选：两边都改 `poll`，但那会动稳定路径；或 GMP 也用 `take()`，取消延迟 = 一次在途模型调用。）
3. **不提供 `Runnable` 重载**：理由是两个 `() -> void` 函数式接口对 lambda / 字面 `null` 二义。请复核这个编译期判断；若认为该提供兼容重载（例如为了 aiplatform 零改动），请给出避免二义的写法。
4. **`CancellationCheckpoint.NONE` 常量**：接口里的常量字段是 public static final 的惯例用法；是否应改为静态工厂 `none()` 或直接容忍 `null`（本计划两者都支持：`null` → `NONE`）？
5. **`materializeNeverLeavesPartialGraphState` 的可实现性**：「第 k 个检查点抛」的参数化在并发抽取路径上难以稳定命中同一 k。是否接受退化为「顺序路径参数化 + 并发路径用入口检查点」的折中？
6. **worktree 与 CI 的关系**：`.worktrees/` 下的分支若不在 CI 覆盖内，Step 3 的扫描只能防主线再生。是否需要把各 worktree 的删除登记成有主键的清单（谁在什么时候处理）？

## 跨计划顺序（第一轮评审跨计划问题 2 的裁定）

**Plan 1（scoped commit compensation）→ Plan 3（本计划）→ Plan 2（document-scoped task concurrency）。**

- Plan 1 先行：并发与取消都会放大 `writeAtomically` 内的补偿成本，先收敛范围。
- 本计划第二：删除 aiplatform 主线的两个 shadow + 上 CI 扫描；Plan 2 随后落地时 Task 4 Step 2 只剩「升版本 + 打开旋钮 + 跑通扫描」。
- 例外（Plan 2 被迫先行）：Plan 2 按它的 Step 2(a2) 先同步一次 shadow，本计划落地时删除；两个计划的「同步」步骤不得各做一半。
- **Plan 1 与另外两份计划没有文件交集**（Plan 1 只改存储层）；**本计划与 Plan 2 共享 `LightRag.java`**（本计划加 4 参重载与 `resolvedChunkExtractParallelism` 透传，Plan 2 加构造参数与各入口模式标注），两者的编辑必须串行（后落地者对 `LightRag.java` 做一次 rebase 并重跑各自的 API 契约测试）。
