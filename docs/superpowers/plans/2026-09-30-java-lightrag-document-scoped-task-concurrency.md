# 文档级任务并发（Document-Scoped Task Concurrency）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让同一 workspace 内多个「文档级」任务（ingest / resume / materialize）能够真正并行执行——今天它们被 `TaskExecutionService` 每个 workspace 唯一的一把公平 `ReentrantLock` 完全串行（`TaskExecutionService.java:51`、`:78`、`:233`、`:237`）；同时保证需要全局独占的操作（restore 型删除、重建、图管理、快照恢复）仍严格串行，且**默认并发度 1 时行为与今天逐字一致**。

**Architecture:**

- 把 `TaskExecutionService` 里的「每 workspace 一把锁」升级为「每 workspace 一个门控 `WorkspaceGate`」：
  - **公平 `ReentrantReadWriteLock`**：文档级操作持**读锁**（共享），独占操作持**写锁**（排他）。公平模式保证写者排队后阻塞新读者，因此独占操作不会被持续到来的文档级操作饿死。
  - **公平 `Semaphore(maxConcurrentDocumentTasks)`**：把读锁侧的无界并发收敛为可配置上限；默认 `1` 时语义与今天的串行完全一致。
  - 获取顺序固定为「先信号量、后读锁」，避免「读者持锁等信号量、写者等读者」的饥饿环。信号量不可重入，因此用 ThreadLocal 记录「本线程已持有该 workspace 的文档级配额」，嵌套调用只重入读锁、不重复占配额。
  - 同线程「文档级 → 独占」是读锁升级，会自锁：显式抛出 `IllegalStateException` 而不是挂死（in-tree 无此调用路径）。
- `LightRag` 在各入口显式声明模式；**不改任何既有方法签名**（旧重载 = 独占），新增重载承载 `WorkspaceConcurrencyMode`。
- 新增 builder 配置 `maxConcurrentDocumentTasks`，默认 `1`。它与既有 `maxParallelInsert` 是两层不同的旋钮（见下）。
- **前置依赖：`2026-09-30-java-lightrag-scoped-commit-compensation.md` 必须先落地。** 理由：今天的每次提交要付全工作区捕获 + 跨进程 Redisson 锁（wait 15min / lease 10min），并发只会让更多线程堵在同一把锁上，并把租约过期风险从「单文档」放大到「多文档」。

**Tech Stack:** Java 17，JUnit 5，AssertJ，Gradle（`./gradlew :lightrag-core:test`），`CountDownLatch` / `CyclicBarrier` 做并发断言。

> **第一轮评审（2026-09-30）判定本计划不可开工，本文件已按裁定修订。** 三处 must-fix：①多文档批量入口被错误归类为 `DOCUMENT_SCOPED`（已改为按请求实际文档数判定）；②aiplatform 的新配置未进入 File Map 与实施步骤（已补为真实代码变更）；③取消路径测试不完整（已补 6 类）。另按跨计划裁定把总顺序改为 **Plan 1 → Plan 3 → Plan 2**（见文末「跨计划顺序」）。逐条闭环见文末「第一轮评审裁定与落实」。
>
> **第二轮评审（2026-09-30）仍判不可开工（1 项 must-fix）**：listener / progress 回调在门控持有期间同步派发，重入契约未定义——已补「回调重入契约」（禁止事项 + 确定性行为 + 2 个用例 + Javadoc 落点），并更正 aiplatform 配置基线。见文末「第二轮评审裁定与落实」。
>
> **第三轮评审（2026-09-30）仍判不可开工（1 项 must-fix）**：①回调内抛出的异常会被 `TaskEventPublisher` 吞掉（`:16-25`），旧文字「任务失败」与真实语义不符——已改为「拒绝 + error 日志 + 任务继续」并给出三层矩阵；②`TASK_SUBMITTED` 在提交线程、进门前派发（`:122`、`:131-141`），已明确排除出契约；③（自审）独占任务的回调内调用文档级 API 会与排队文档任务互锁——新增 `exclusiveSlotHeld` 拒绝。见文末「第三轮评审裁定与落实」。
>
> **第四轮评审（2026-09-30）仍判不可开工（1 项 must-fix）**：①`TASK_SUBMITTED` 的排除边界缺可执行用例——已补 `listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused`；②Javadoc/契约须限定隔离范围只覆盖 `RuntimeException`（`Error` 会穿透 publisher 并导致任务失败），且「不执行」只指门控 work（`providerResolver` / `recoverInterruptedTasks` 的前置副作用照常）——已写入契约第 2 条。另（第五轮前自查，修正本计划一处错误措辞）：该用例原注释写「若被拒会抛 `IllegalStateException`，无人吞掉它」**不成立**——`TASK_SUBMITTED` 的派发同样经 `TaskEventPublisher.publish`（`:122`、`:131-141` → `:16-25`），拒绝与放行都表现为「正常返回」；已改为要求正向证据（spy/可见副作用）。见文末「第四轮评审裁定与落实」。
>
> **第五轮评审（2026-09-30）判定可开工（无 must-fix）。** 四项 should-fix 已落实：①`TASK_SUBMITTED` 用例的 oracle 写死为「可见副作用」（不留二选一）；②`Error` 边界句补「适用范围 = 任务内事件」与 `TASK_SUBMITTED` 例外面；③跨线程回调边界补「转发后同步 `join`/`get` 仍可能形成等待环」；④采纳建议新增 `listenerThrowingErrorFailsTheTask`（用例 17 → 18）。见文末「第五轮评审裁定与落实」。
>
> **第六轮评审（2026-09-30）判定可开工、可冻结（无 must-fix，零残余）。** 三条核验（oracle 写死 / `Error` 边界与用例 / 计数清单）全部 CLOSED；本计划自此**冻结**。见文末「第六轮评审裁定与落实」。

---

## 背景与证据

- 症状：一个知识库 20 份文档，只有一把锁，一个在跑其余全等。aiplatform 的 `LightRagKnowledgeIndexWriter` 是**按文档**调用 `submitIngestChunks(...)` 的，也就是说 20 份文档 = 20 个任务，全部排在 `TaskExecutionService` 的同一把 workspace 锁后面（`queueWaitMs` 已经作为 PERF 指标打在 `TaskExecutionService.java:240-243`，可直接据此验证改造前后的排队时间）。
- 既有旋钮 `maxParallelInsert`（README:411、:1085）作用于**一次 ingest 调用内部**的文档并行；当调用方采用「一文档一任务」的提交模式时它恒为 1，起不到作用。
- 安全边界来自存储层而非任务层：所有写入仍经过 provider 写锁 + `StorageLockManager`（多实例下是 Redisson），任务级并发不会改变任何一次提交的原子性。**跨实例的并发本来就已经存在**（任务锁从来不是跨进程的，`TaskExecutionService.workspaceLocks` 是进程内 `ConcurrentHashMap`），所以本计划不引入新的失败类型，只是把进程内的排队放开到与跨实例现实一致。
- `restore(...)` 型流程必须独占，共 3 处调用方：`DeletionPipeline`（:63、:108、:218、:286、:293 —— `deleteByDocumentId` / `deleteByEntity` / `deleteByRelation` 等全部删除流程）、`LightRag.restoreSnapshot`（:646）、`LightRagBuilder`（:424，构建期，不经过任务服务）。直接对照 `grep -rn "\.restore(" lightrag-core/src/main/java`。

## File Map

**生产代码**

- Create: `lightrag-core/src/main/java/io/github/lightrag/task/WorkspaceConcurrencyMode.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java`
  - `WorkspaceGate`（RW 锁 + 信号量 + ThreadLocal 重入）、`WorkspaceConcurrencyMode` 重载、每 workspace 的 gate 缓存替代 `workspaceLocks`、`queueWaitMs` 语义保持。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
  - 新字段 `maxConcurrentDocumentTasks = 1`、setter 校验、`build()` 透传。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
  - 新构造参数 + 转发给 `TaskExecutionService`（:163-166）；`runInWorkspace`（:746-752）增模式参数；各入口标注模式（见 Task 3 的分类表）。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/TaskEventListener.java` 与 `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingProgressListener.java`
  - **只改 Javadoc**：写死「回调重入契约」——任务内事件在门控持有期间、于任务线程同步派发，`TASK_SUBMITTED` 除外（提交线程、进门前）；禁止在回调内调用任何 workspace 级 API；违反时 = error 日志 + `IllegalStateException` 被 `TaskEventPublisher` 隔离（**仅 `RuntimeException`；`Error` 穿透并失败任务**）、任务继续（矩阵、前置副作用边界与死锁理由见 Task 1 Step 4）。
- Modify: `README.md` / `README_zh.md`（配置表 + builder 旋钮说明；`maxConcurrentDocumentTasks` 必须与 `maxParallelInsert` 的区别写清）。

**aiplatform（下游开关，Task 4 Step 2；全部路径相对 `D:\ai-code\aiplatform`）**

- Modify: `backend/aide-kno/src/main/java/com/finstone/fusion/ai/knowledge/service/index/LightRagRuntimeFactory.java`
  - builder 链（:241）加 `.maxConcurrentDocumentTasks(...)`；按 `maxParallelInsert`（:268-273）的同形写法从配置读取。
- Modify: `backend/aide-admin/src/main/resources/application.yml`
  - 新增 `max-concurrent-document-tasks: ${LIGHTRAG_INDEXING_MAX_CONCURRENT_DOCUMENT_TASKS:1}`，与 `max-parallel-insert` / `chunk-extract-parallelism`（:311-315）并列。
- Modify: `backend/pom.xml`（`<lightrag.version>` 升到含本计划的新版本）。
- 条件 Modify: `backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java`（shadow 同步）——**仅当 Plan 3 尚未落地时需要**；Plan 3 已删除 shadow 则本项不存在（见 Task 4 Step 2(a) 的前置判定）。
- 不改: `KnowledgeGraphServiceImpl.java`（本计划不动它的调用点）。

**测试**

- Create: `lightrag-core/src/test/java/io/github/lightrag/task/TaskExecutionServiceTest.java`（当前不存在 `task/` 测试目录）
- Create: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentConcurrencyTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

## Scope Guardrails

- **不跨进程**：本次只改进程内门控；Redisson 锁、`StorageLockManager`、provider 锁序全部不动。
- **不改 `recoverInterruptedTasks` 的跨实例缺陷**（`TaskExecutionService.java:270-310`：进程内 `recoveredWorkspaces` 只保证本进程执行一次，但会把**其他实例正在运行**的非终态任务标记为 FAILED）。这是一个既有问题，与本计划正交，但必须在 PR 描述里显式记录，不得被「并发已放开」掩盖。
- **不改删除路径**：aiplatform 的 `LightRagDeletionAdapter.deleteMatching` 直接调用 provider（`deleteByTargetedDocumentDelete` → `deleteDocumentDerivedState`），不经过任务服务；它依赖 provider 锁，因此不受本计划影响，也**不需要**纳入分类表。
- **不改语义**：所有既有 `runInWorkspace(workspaceId, work)` / `submit(workspaceId, type, metadata, work)` 重载保持「独占」语义，源二进制兼容。
- **不改 `maxParallelInsert` 的语义与默认值**（SDK 默认 2；**aiplatform 现状是显式配为 1**：`aide-admin/application.yml:312` 的 `max-parallel-insert: ${LIGHTRAG_INDEXING_MAX_PARALLEL_INSERT:1}`——第二轮评审指出的基线文字不一致已按此更正，`chunk-extract-parallelism` 同处为 4）。
- **shadow 类约束（aiplatform 特有）**：aiplatform 在 `backend/aide-kno/src/main/java/io/github/lightrag/` 下有两个与本模块**同 FQCN 的 shadow 类**（模块源码整体替换依赖 jar 的同名类）：`api/LightRag.java`、`indexing/GraphMaterializationPipeline.java`。本计划改了 `LightRag.java`，且 Task 2 给它的包私有构造器**加了参数**——只升 `lightrag.version` 不同步 shadow 会在 `LightRag.builder().build()` 抛 `NoSuchMethodError`（`LightRagBuilder` 在 jar 内、已编译，javac 不检查该调用点，所以编译期静默）。同步清单、失败模式与复核方式见 Task 4 Step 2(a)。`GraphMaterializationPipeline.java` 本计划**不改**：它另有 aiplatform 私有的分片并行抽取、`cancellationCheckpoint`、`chunkSnapshotMismatch → REBUILD`（均不在上游），要一起改就是另一个计划（`2026-09-30-java-lightrag-upstream-shadow-gmp-capabilities.md`；其落地后这两个 shadow 类被删除，本段的同步义务随之终止，见 Task 4 Step 2(a)）。

---

### Task 1: `TaskExecutionService` 门控改造

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/task/WorkspaceConcurrencyMode.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/task/TaskExecutionService.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/task/TaskExecutionServiceTest.java`

- [ ] **Step 1: 先写失败测试（并发语义全部用 latch/barrier 断言，禁止 `Thread.sleep` 判胜负）**

```java
@Test
void defaultModeStillSerializesTasksPerWorkspace() {
    // maxConcurrentDocumentTasks 默认 1：两个 document-scoped 任务不得重叠
    // 用 AtomicInteger 记录同时在 work 内的线程数，断言峰值 == 1
}

@Test
void documentScopedTasksOverlapUpToConfiguredLimit() {
    // maxConcurrentDocumentTasks = 2：两个任务在 barrier 上会合（超时即失败）
}

@Test
void exclusiveTaskWaitsForRunningDocumentScopedTasks() {
    // 先起 document-scoped（占住），再 submit 独占任务，最后再 submit 一个 document-scoped
    // 断言顺序：doc1 → exclusive → doc2（用 AtomicInteger 序号记录进入顺序）
}

@Test
void documentScopedTaskWaitsForQueuedExclusiveTask() {
    // 独占任务已在排队时，新到的 document-scoped 必须先等独占完成（公平写锁的写者优先）
}

@Test
void nestedDocumentScopedAcquisitionDoesNotDeadlock() {
    // 同一线程在 document-scoped work 内再调用 runInWorkspace(..., DOCUMENT_SCOPED, ...)
    // 断言在 1 秒内完成，且外层配额只被占用一次（用 maxConcurrentDocumentTasks = 1 验证不会自锁）
}

@Test
void rejectsExclusiveAcquisitionFromThreadHoldingDocumentSlot() {
    // 同线程 document-scoped → exclusive：抛 IllegalStateException（而不是死锁）
}

@Test
void runInWorkspaceHonoursTheSameGateAsSubmit() {
    // 同步入口与任务入口共用同一门控：同步 runInWorkspace(DOCUMENT_SCOPED) 与 submit 的 document 任务可并行
}
```

**取消与异常路径（第一轮评审 must-fix 3 + should-fix 4，逐个用例，一个都不能省）**

```java
@Test
void cancellingWhileWaitingForPermitHoldsNothingAndBlocksNobody() {
    // maxConcurrentDocumentTasks = 1；A 占住配额且阻塞在 barrier 上；B 提交后停在 documents.acquire()
    // cancel(B, mayInterruptIfRunning = true) → 断言：B 的 work 从未执行；A 放行后 C 立刻拿到配额（无幽灵许可）
}

@Test
void cancellingWhileWaitingForReadLockReleasesThePermit() {
    // 让一个 document-scoped 任务停在 readLock.lockInterruptibly()（写锁被别人持有或写者已排队）
    // 中断后断言：availablePermits() 回到 maxConcurrentDocumentTasks（permit 在 finally 中释放，不能泄漏）
}

@Test
void cancellingAQueuedTaskLeavesNoLockOrThreadLocal() {
    // 在写锁队列里等待的独占任务被 cancel：不留下持锁状态、不留下 ThreadLocal 登记
    // 断言：随后的 document-scoped 与 exclusive 任务都能正常完成（不超时）
}

@Test
void releasesPermitWhenWorkThrows() {
    // work 抛 RuntimeException：随后提交的任务仍能取到配额（try/finally 覆盖所有出口）
}

@Test
void releasesPermitWhenNestedCallThrows() {
    // 嵌套的 document-scoped work 抛异常：外层释放配额，ThreadLocal 登记被清空（用两次连续调用验证不残留）
}

@Test
void doesNotOccupyAnythingWhenProviderResolverThrows() {
    // providerResolver 抛异常（发生在门控之前）：不占配额、不建 gate
}

@Test
void listenerReenteringExclusiveApiIsRefusedWithoutFailingTheTask() {
    // 第二、三轮评审 must-fix：document-scoped 任务的进度回调里调用独占入口（deleteByDocumentId）
    // → 拒绝（error 日志 + IllegalStateException），异常被 TaskEventPublisher 捕获忽略（:16-25）：
    //   被点名的调用未执行、任务照常完成；permit 与锁全部释放，随后任务可正常运行
}

@Test
void rejectsDocumentScopedAcquisitionFromThreadHoldingExclusiveSlot() {
    // 第三轮评审 must-fix + 自审死锁路径：独占任务的进度回调里调用文档级入口（ingestDocuments）
    // → 拒绝（exclusiveSlotHeld 命中），同上被 publisher 忽略、任务照常完成；
    //   若放行会与「持 permit 排队等读锁」的文档任务在 documents.acquire() 上互锁（Step 4 契约第 3 条）
}

@Test
void listenerReenteringDocumentScopedApiRunsInlineWithoutExtraPermit() {
    // 回调内调用文档级入口：按既有嵌套语义在同一线程内联执行；断言 availablePermits() 全程不减少
}

@Test
void listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused() {
    // 第四轮评审 must-fix：TASK_SUBMITTED 派发在提交线程、进门前（TaskExecutionService.java:122、:131-141），
    // 不持有任何门控席位——在它里面调用 workspace API 必须正常执行、不被拒绝。
    // 唯一 oracle（第五轮评审裁定：不留「spy 或副作用」二选一，写死为**可见副作用**）：回调里调用
    // runInWorkspace(..., DOCUMENT_SCOPED, ...)，让它的 work 往本用例的标记集合追加一项；
    // 断言该标记出现（= 调用真的执行了）。不选 spy：本文件既有用例统一用 AtomicInteger / 集合标记，
    // 且 gate 是包内普通类、不引入 Mockito 替身。
    // 「没有异常」不是 oracle——拒绝抛出的 IllegalStateException 同样会被 TaskEventPublisher 吞掉
    // （:16-25，对 TASK_SUBMITTED 与任务内事件同样适用），拒绝与放行都表现为「submit 正常返回」。
    // 另断言：① 回调线程 == 提交线程；② 随后的任务自身照常完成（SUCCEEDED）。
}

@Test
void listenerThrowingErrorFailsTheTask() {
    // 第五轮评审建议（非 must-fix，采纳）：任务内事件的 listener 抛 AssertionError——publisher 只
    // catch RuntimeException（:16-25），Error 穿透；该 reporter.* 调用发生在 runTask 的 try 内，
    // 沿 catch (Throwable) 走任务失败路径（TaskExecutionService.java:244、:254-255）。
    // 断言任务终态 FAILED（隔离范围只覆盖 RuntimeException 的正向反例）。listener 只在
    // TASK_RUNNING 事件上抛，保证随后的 TASK_FAILED 派发不被同一 listener 再次打断。
}
```

断言方式：`availablePermits()` / `ThreadLocal` 状态用**包私有测试钩子**读取（测试与被测类同包），不要靠「事后还能跑」间接推断唯一一个泄漏点——第一轮评审判定间接断言会漏掉幽灵许可。

（第五轮评审补充：两个重入**拒绝**用例可加「日志捕获器断言 error 输出」作为 should-fix 级增强——**仅当本测试类已有日志断言基建时采用**，否则不为此引入新基建；拒绝路径的判定性已由 `IllegalStateException` 与后续任务完成度覆盖。）

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.task.TaskExecutionServiceTest"
```

Expected:
```text
FAIL
... cannot find symbol: class WorkspaceConcurrencyMode
... cannot find symbol: method runInWorkspace(String,WorkspaceConcurrencyMode,WorkspaceWork)
```

- [ ] **Step 3: 新增模式枚举**

```java
package io.github.lightrag.task;

/**
 * 任务在 workspace 门控中的并发模式。
 *
 * <p>{@link #DOCUMENT_SCOPED} 只声明「本操作按文档/切片隔离，可以与其他文档级操作并行」，
 * 它不改变任何存储层保证：每次提交仍要拿 provider 写锁与跨进程 {@code StorageLockManager} 锁。</p>
 */
public enum WorkspaceConcurrencyMode {
    /** 与其他文档级操作共享，受 maxConcurrentDocumentTasks 限制。 */
    DOCUMENT_SCOPED,
    /** 独占整个 workspace：任何其他任务都不得并行。用于 restore 型与破坏型操作。 */
    WORKSPACE_EXCLUSIVE
}
```

- [ ] **Step 4: 实现 `WorkspaceGate` 并接入**

替换 `ConcurrentMap<String, ReentrantLock> workspaceLocks`（:51）为 `ConcurrentMap<String, WorkspaceGate> workspaceGates`：

```java
    private final class WorkspaceGate {
        private final String workspaceId;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        private final Semaphore documents;
        private final ThreadLocal<Set<String>> documentSlotHeld =
            ThreadLocal.withInitial(java.util.LinkedHashSet::new);
        private final ThreadLocal<Set<String>> exclusiveSlotHeld =
            ThreadLocal.withInitial(java.util.LinkedHashSet::new);

        private WorkspaceGate(String workspaceId, int maxConcurrentDocumentTasks) {
            this.workspaceId = workspaceId;
            this.documents = new Semaphore(maxConcurrentDocumentTasks, true);
        }

        <T> T run(WorkspaceConcurrencyMode mode, RuntimeSupplier<T> work) throws InterruptedException {
            return switch (mode) {
                case WORKSPACE_EXCLUSIVE -> runExclusive(work);
                case DOCUMENT_SCOPED -> runDocumentScoped(work);
            };
        }

        private <T> T runExclusive(RuntimeSupplier<T> work) throws InterruptedException {
            if (documentSlotHeld.get().contains(workspaceId)) {
                // 读锁升级会自锁；先记一条 error 日志把调用栈钉在日志里，再抛出（详见下方说明）
                log.error(
                    "LightRAG workspace gate rejected an exclusive acquisition from a thread holding a document-scoped slot: workspace={}",
                    workspaceId,
                    new IllegalStateException("rejected exclusive acquisition")
                );
                throw new IllegalStateException(
                    "workspace-exclusive work cannot start from a thread that already holds a document-scoped slot: "
                        + workspaceId
                );
            }
            var writeLock = lock.writeLock();
            writeLock.lockInterruptibly();
            var registered = false;
            try {
                if (!exclusiveSlotHeld.get().contains(workspaceId)) {
                    exclusiveSlotHeld.get().add(workspaceId);
                    registered = true;
                }
                return work.get();
            } finally {
                if (registered) {
                    exclusiveSlotHeld.get().remove(workspaceId);
                }
                writeLock.unlock();
            }
        }

        private <T> T runDocumentScoped(RuntimeSupplier<T> work) throws InterruptedException {
            if (exclusiveSlotHeld.get().contains(workspaceId)) {
                // 独占线程持有写锁时，documents.acquire() 可能与「已持 permit、正等读锁的排队文档任务」互锁
                //（公平写锁下这些任务不释放 permit）；必须拒绝而不是挂死（详见下方契约第 3 条）
                log.error(
                    "LightRAG workspace gate rejected a document-scoped acquisition from a thread holding an exclusive slot: workspace={}",
                    workspaceId,
                    new IllegalStateException("rejected document-scoped acquisition")
                );
                throw new IllegalStateException(
                    "document-scoped work cannot start from a thread that already holds an exclusive slot: "
                        + workspaceId
                );
            }
            var nested = documentSlotHeld.get().contains(workspaceId);
            var permitAcquired = false;
            if (!nested) {
                documents.acquire();        // 公平信号量：先占配额，再争共享锁，避免「持锁等配额」造成写者饥饿
                permitAcquired = true;
            }
            try {
                var readLock = lock.readLock();
                readLock.lockInterruptibly();   // 公平写锁：独占任务排队后，新读者在此阻塞
                try {
                    var registered = false;
                    if (!nested) {
                        documentSlotHeld.get().add(workspaceId);
                        registered = true;
                    }
                    try {
                        return work.get();
                    } finally {
                        if (registered) {
                            documentSlotHeld.get().remove(workspaceId);
                        }
                    }
                } finally {
                    readLock.unlock();
                }
            } finally {
                if (permitAcquired) {
                    documents.release();
                }
            }
        }
    }
```

新增 API（旧签名全部保留，等价于 `WORKSPACE_EXCLUSIVE`）：

```java
    public <T> T runInWorkspace(String workspaceId, WorkspaceWork<T> work) {
        return runInWorkspace(workspaceId, WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE, work);
    }

    public <T> T runInWorkspace(String workspaceId, WorkspaceConcurrencyMode mode, WorkspaceWork<T> work) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var normalizedMode = Objects.requireNonNull(mode, "mode");
        var workspaceWork = Objects.requireNonNull(work, "work");
        var provider = providerResolver.apply(normalizedWorkspaceId);
        recoverInterruptedTasks(normalizedWorkspaceId, provider);
        var gate = workspaceGates.computeIfAbsent(
            normalizedWorkspaceId,
            id -> new WorkspaceGate(id, maxConcurrentDocumentTasks)
        );
        try {
            return gate.run(normalizedMode, () -> workspaceWork.run(provider));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("workspace operation interrupted", exception);
        }
    }
```

（`WorkspaceWork.run` 可能返回 null，`WorkspaceGate` 内部用 `RuntimeSupplier<T>` 转发即可，不需要额外判空。）

`submit(...)` 同样新增带 `WorkspaceConcurrencyMode` 的重载（旧重载 = `WORKSPACE_EXCLUSIVE`），`runTask` 把 mode 传给 gate；mode 由 `LightRag` 各入口按 Task 3 的分类表计算后传入。`queueWaitMs` 的语义不变（仍是从 `requestedAt` 到真正开始执行的时间，现在它反映的是**门控等待**，正是要观测的指标）。

构造器：新增 `TaskExecutionService(Function<...>, List<TaskEventListener>, int maxConcurrentDocumentTasks)`，既有两个构造器委托为 `1`；`maxConcurrentDocumentTasks <= 0` 抛 `IllegalArgumentException`。

**同线程「文档级 → 独占」的拒绝策略（第一轮评审 should-fix 5）**：抛 `IllegalStateException` 会把一条线上从未出现过的嵌套路径从「挂死」变成「任务失败」，两者都不是好结果，所以本策略分三层兜底：

1. **调用路径审计（实现前做，结果写进 PR）**：`grep -rn "runInWorkspace\|taskExecutionService.submit" lightrag-core/src/main/java` 逐条确认两个方向都没有嵌套入口路径——①文档级 work 内再发起**独占**调用（读锁升级自锁）；②**独占** work 内再发起**文档级**调用（会命中 `exclusiveSlotHeld` 拒绝，从「今天可用」变成抛错，同样是行为变化）。特别注意 `TaskWork` / `TaskEventListener` / `progressListener` 回调线程。**预审结果（第三轮修订时已跑）**：全部调用点都在 `LightRag.java`（:189-645 的 26 处 = 19 处 `runInWorkspace` + 7 处 `submit`，另 :746-752 是私有转发 helper），每个调用点都直接调用 pipeline/provider，不存在「入口套入口」的路径；审计结论 = 「in-tree 无此路径」的**证据**，不是断言。
2. **运行时日志**：拒绝前先 `log.error(..., new IllegalStateException("rejected exclusive acquisition"))` 打出堆栈——首次在集成环境出现时能立刻定位是哪个调用方。
3. **测试**：`cancellingAQueuedTaskLeavesNoLockOrThreadLocal` 与 `rejectsExclusiveAcquisitionFromThreadHoldingDocumentSlot` 分别钉住「拒绝不泄漏」与「拒绝类型」。

（同一条拒绝若发生在 listener 回调内，结局取决于异常落点：直接写在 `work` 里的调用把异常交给 `runTask` 的 `catch (Throwable)` → 任务失败；回调内的调用被 `TaskEventPublisher` 隔离 → 任务继续。gate 的判定完全一致，差别只在下方契约。）

**回调重入契约（第二、三轮评审 must-fix：listener / progress 回调在门控持有期间同步执行）**：两条派发路径，契约范围不同：

- **任务内事件（受本契约约束）**：stage / document / chunk / progress 事件与 `TASK_RUNNING` / `TASK_SUCCEEDED` / `TASK_CANCELLED` / `TASK_FAILED` 都由 `TaskReporter` 经 `eventPublisher` 在**任务线程、门控持有期间**同步派发（`TaskExecutionService.java:244-248` 在锁内调用 `reporter.*`；`work.run(reporter)` 的进度回调复用同一 reporter）。
- **`TASK_SUBMITTED`（明确排除）**：它在 `submit(...)` 内于**提交线程**、任务进入门控**之前**派发（`TaskExecutionService.java:122`；任务专属 listener 的同一事件在 `:131-141`）——此时不持有任何门控席位，重入 workspace API 与普通调用者无异。不把它移入门控：那会把持久化 `PENDING` 记录与回调的顺序绑死，并拖慢提交路径。

本轮**不移动**派发位置（移动会打乱「任务状态写入」与「工作」的顺序，并让 `complete` / `cancel` / `fail` 与 `totalDurationMs` 记账跨出锁边界），也**不改** `TaskEventPublisher` 的异常语义（`TaskEventPublisher.java:16-25` 捕获并忽略 listener 的 `RuntimeException`，注释声明这是 phase 1 的隔离行为；改它会波及所有既有事件的派发路径，超出本计划范围）。契约写死为：

1. **禁止事项**：回调内不得调用任何 workspace 级 API（`ingest*` / `materialize*` / 删除 / 重建 / `clearCache` / 快照），也不得阻塞等待其他任务；回调只允许记录、转发、上报到外部系统。
2. **违反时的确定性行为 = 拒绝 + error 日志 + 任务继续**（不是挂死，也**不是任务失败**）：拒绝发生在门控 work 的任何状态变更之前（`runDocumentScoped` 的检查先于 `documents.acquire()`，`runExclusive` 的检查先于 `writeLock.lockInterruptibly()`），**门控 work 不执行**；gate 先 `log.error` 打印堆栈（首次在真实回调里出现时能立刻定位调用方），再抛 `IllegalStateException`——该异常由 `TaskEventPublisher` 按既有语义捕获忽略，**任务本身照常继续**。两处必须读准的边界：

   - **「不执行」仅指门控 work**：入口在到达 gate 之前仍有 `providerResolver.apply(...)` 与 `recoverInterruptedTasks(...)`（`TaskExecutionService.java:74-81`、`:103-105`），它们的前置副作用照常发生（后者可能把非终态任务标记为 FAILED）。
   - **隔离只覆盖 `RuntimeException`（适用范围 = 任务内事件）**：`TaskEventPublisher` 的 `catch (RuntimeException ignored)` 不捕获 `Error`——任务内事件的 listener 抛出的 `Error` 会穿透 publisher，沿 `runTask` 的 `catch (Throwable)` 走**任务失败**路径（`TaskExecutionService.java:254-255`，由 `listenerThrowingErrorFailsTheTask` 钉住）。门控拒绝抛出的 `IllegalStateException` 属于被隔离的一侧。`TASK_SUBMITTED` 是这条边界的例外面：它在提交线程派发，listener 抛出的 `Error` 会直接冒给 `submit` 调用者（不是任务失败，也没有任务可失败）。

   矩阵：

   | 回调所在任务 | 回调内调用的 API | 行为 |
   | --- | --- | --- |
   | 文档级 | 独占（删除 / 重建 / 快照 / `clearCache`） | 拒绝：error 日志 + `IllegalStateException` → publisher 吞掉，任务继续 |
   | **独占** | **文档级（`ingestDocuments*` 等）** | **拒绝：同上（死锁路径，见第 3 条）** |
   | 文档级 | 文档级 | 既有嵌套语义：同线程**内联执行**、不额外占配额（声明行为，仍属禁止用法） |
   | 独占 | 独占 | 既有行为不变：写锁可重入，同线程直接执行 |

3. **「独占 → 文档级」为什么必须拒绝**：独占任务持写锁，而 `documents.acquire()` 在等配额；此刻若有文档任务**已持 permit 并在等读锁**（公平写锁下写者一旦排队，新读者就被挡在门外，而这些任务在拿到读锁前不会释放 permit），回调线程会卡在 `acquire()` 上，与它们互锁——写锁在整个回调期间无法释放。`exclusiveSlotHeld` 使该判断在同线程内可靠，把「挂死」变成「确定性拒绝」。反方向（文档级 → 独占，读锁升级自锁）由 `runExclusive` 既有检查拦截，两者对称。**边界**：ThreadLocal 判定只覆盖同步回调；回调把调用**转发到别的线程**时，那个线程不持任何席位，行为等同普通调用者（最多等待，不会互锁）——**前提是回调不等待转发结果**：若转发后同步 `join` / `get` 等待，回调线程仍在门控持有期间被阻塞，等待环可能照常形成（与第 1 条「不得阻塞等待其他任务」同类），因此契约第 1 条的禁止事项覆盖「转发后同步等待」这一形态。
4. **测试**：`listenerReenteringExclusiveApiIsRefusedWithoutFailingTheTask`、`rejectsDocumentScopedAcquisitionFromThreadHoldingExclusiveSlot`、`listenerReenteringDocumentScopedApiRunsInlineWithoutExtraPermit`（重入三类），外加 `listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused` 守护 `TASK_SUBMITTED` 的**排除边界**（提交线程内调用 workspace API 必须正常执行、不被拒绝，且任务照常完成）——均在上面的取消/异常用例组。
5. **Javadoc 落点**：契约（含 `TASK_SUBMITTED` 排除声明、「publisher 只隔离 `RuntimeException`、`Error` 穿透并导致任务失败」的限定、以及「拒绝 = 门控 work 不执行 + 任务继续」）写进 `TaskEventListener` 与 `IndexingProgressListener` 的接口注释（File Map 已列，只改注释不改方法）。不重复写进 README——接口注释 + 可执行用例是公开契约的完备载体（第三轮评审判断题 1 的裁定）。

**门控缓存的生命周期（第一轮评审 should-fix 6）**：`workspaceGates` 与今天的 `workspaceLocks` 一样按 workspaceId 只增不减（`TaskExecutionService.java:49-51` 现状即如此；一个 JVM 服务的 workspace 数量有界，这是可接受现状，不是本计划引入的泄漏）。但 `WorkspaceGate` 比 `ReentrantLock` 多持有 semaphore 状态与 `ThreadLocal` 登记，因此实现上**禁止**把 ThreadLocal 登记挂到 gate 之外的静态字段（避免跨 gate 串状态），并保留一条 gate 复用注释；空闲回收（引用计数 + `remove`）明确**不做**，列入第二轮待评审问题，因为回收与「任务在门控中排队时 gate 被摘除」的竞态需要独立设计。

- [ ] **Step 5: 运行，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.task.TaskExecutionServiceTest"
```

Expected: `BUILD SUCCESSFUL`，18 个用例全绿（7 个并发语义 + 11 个取消/异常路径，含 3 个回调重入用例 + 1 个 `TASK_SUBMITTED` 边界用例 + 1 个 `Error` 穿透用例）。若 `nestedDocumentScopedAcquisitionDoesNotDeadlock` 超时，说明 ThreadLocal 重入登记有误（登记发生在拿读锁之后，必须保证嵌套路径不重复占配额）；若 `cancellingWhileWaitingForReadLockReleasesThePermit` 断言失败，检查 permit 释放是否覆盖了 `readLock.lockInterruptibly()` 抛出的分支。

---

### Task 2: 配置打通（builder → LightRag → TaskExecutionService）

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: 先写失败测试（与 `maxParallelInsert` 的既有用例同形：`LightRagBuilderTest` :371-394 透传、:937-939 非法值、:944-954 默认值）**

```java
@Test
void exposesMaxConcurrentDocumentTasksConfiguration() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .maxConcurrentDocumentTasks(4)
        .build();

    assertThat(rag.maxConcurrentDocumentTasks()).isEqualTo(4);
}

@Test
void keepsMaxConcurrentDocumentTasksAtOneByDefault() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .build();

    assertThat(rag.maxConcurrentDocumentTasks()).isEqualTo(1);
}

@Test
void rejectsNonPositiveMaxConcurrentDocumentTasks() {
    assertThatThrownBy(() -> LightRag.builder().maxConcurrentDocumentTasks(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxConcurrentDocumentTasks must be positive");
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"
```

Expected: `FAIL ... cannot find symbol: method maxConcurrentDocumentTasks(int)`

- [ ] **Step 3: 实现**

`LightRagBuilder`（与 `maxParallelInsert` :54 / :190-196 同形）：

```java
    private int maxConcurrentDocumentTasks = 1;

    public LightRagBuilder maxConcurrentDocumentTasks(int maxConcurrentDocumentTasks) {
        if (maxConcurrentDocumentTasks <= 0) {
            throw new IllegalArgumentException("maxConcurrentDocumentTasks must be positive");
        }
        this.maxConcurrentDocumentTasks = maxConcurrentDocumentTasks;
        return this;
    }
```

`build()` 透传（`LightRagBuilder.java:375` 附近），`LightRag` 增加 `private final int maxConcurrentDocumentTasks;`、构造参数、`int maxConcurrentDocumentTasks()` 访问器，并在 :163 组装：

```java
        this.taskExecutionService = new TaskExecutionService(
            workspaceId -> resolveProvider(resolveScope(workspaceId)),
            this.taskEventListeners,
            maxConcurrentDocumentTasks
        );
```

任务元数据（:932-934 已经把 `maxParallelInsert` / `chunkExtractParallelism` 写进 metadata）追加：

```java
        metadata.put("maxConcurrentDocumentTasks", Integer.toString(maxConcurrentDocumentTasks));
```

- [ ] **Step 4: 运行，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"
```

Expected: 全绿。

---

### Task 3: 入口模式分类 + 并发集成测试

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentConcurrencyTest.java`

- [ ] **Step 1: 先写失败测试**

```java
@Test
void twoConcurrentDocumentIngestsInOneWorkspaceBothSucceed() {
    // 用 LightRagBuilder + StorageAssembly 测试装配（Fake adapters，见 StorageAssemblyTestDoubles），
    // maxConcurrentDocumentTasks = 2，两个 submitIngestChunks(...) 并发提交；
    // 断言：两个任务都是 COMPLETED，且图里同时存在两份文档抽取出的实体（并集）。
    // 关键：让 FakeChatModel 在抽取阶段阻塞在一个 CyclicBarrier 上——如果门控仍是串行，barrier 会超时。
}

@Test
void rebuildWaitsForConcurrentDocumentIngests() {
    // maxConcurrentDocumentTasks = 2；先起两个 ingest（占住），再 submitRebuild(...)
    // 断言 rebuild 的 startedAt 晚于两个 ingest 的 finishedAt。
}

@Test
void deleteByDocumentIdWaitsForConcurrentDocumentIngests() {
    // 同上，用 deleteByDocumentId(...)（DeletionPipeline 走 restore，必须独占）
}

@Test
void multiDocumentBatchIngestStaysExclusiveAgainstASingleDocumentTask() {
    // 一个含 2 个文档的 DocumentIngestRequest 与一个单文档 submitIngestChunks 并发提交：
    // 批量请求按规则判定为 WORKSPACE_EXCLUSIVE，两者不得重叠。
    // 断言：RecordingChatModel 的并发峰值 == 1（批量内部的 maxParallelInsert 并行不受影响，
    // 但它不与另一个任务交叠）；且两个任务都 COMPLETED。
}

@Test
void twoConcurrentDocumentIngestsSharingAnEntityMergeRatherThanLose() {
    // 两个单文档任务都抽到同名实体 "Shared"（RecordingChatModel 对两篇都产出该实体，chunk 各自不同）
    // 断言：并发执行后图中 "Shared" 只有一个节点，其 sourceChunkIds 是两篇文档 chunk 的并集（合并而非覆盖）。
    // 原理（不要在此用例里重新论证，见 Step 3 末节）：GMP.saveGraph 的「读—合并—写」整体在 writeAtomically
    // 之内，而 provider 把 writeAtomically 整体包在独占锁里，因此后提交者读到的必然是前者已合并后的行。
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagDocumentConcurrencyTest"
```

Expected: `FAIL`（第一个用例在 barrier 上超时，因为今天所有任务串行）

- [ ] **Step 3: 按分类表改写 `LightRag` 的调用点**

**判定规则（先读这条，再看表）：`DOCUMENT_SCOPED` 只用于「本次调用确定只涉及一个文档」的入口；一切可能涉及多个文档的批量入口一律保持独占。**

依据（第一轮评审 must-fix 1）：`DocumentIngestRequest`、`RawDocumentSource` 列表、`PreChunkedChunk` 列表都允许装载多个文档（`DocumentIngestRequest.of(documents)` 不限制数量；`ingestSources` 的每个 source 产生一个解析文档，`IndexingPipeline.java:496-528`；`PreChunkedIngestRequest.chunks()` 可跨多个 `documentId`）。把这类入口统一标为文档级，会让「只影响本文件派生对象」的前提失效——批量请求与另一个任务并行时，它同时改写多篇文档的派生对象。

模式在**入口方法内**按实际请求内容计算，而不是按方法名：

```java
    private static WorkspaceConcurrencyMode modeForDocumentCount(long documentCount) {
        return documentCount == 1
            ? WorkspaceConcurrencyMode.DOCUMENT_SCOPED
            : WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE;
    }
```

调用点两种接入形态：同步入口 `runInWorkspace(scope, modeForDocumentCount(...), provider -> ...)`；异步入口 `taskExecutionService.submit(scope.workspaceId(), type, metadata, mode, submitOptions.listeners(), work)`（`submit` 的 `WorkspaceConcurrencyMode` 重载，Task 1 Step 4）。文档数在**提交时**对已冻结的请求列表计算（`List.copyOf` / `DocumentIngestRequest` 均为不可变记录），任务执行期间不受调用方后续修改影响。

| 入口（`LightRag.java`，行号为改动前基线） | 文档数来源 | 模式 |
| --- | --- | --- |
| `ingest(String, List<Document>)` :182 → `ingest(String, DocumentIngestRequest)` :186 | `normalizedRequest.documents().size()` | 1 → DOCUMENT_SCOPED；≥2 → EXCLUSIVE |
| `ingestSources` :195 | `sources.size()`（每 source 恰好一个解析文档） | 同上 |
| `ingest(String, PreChunkedIngestRequest)` :203 → `ingestChunks` :212 | `countDocuments(normalizedRequest.chunks())`（既有私有方法 :283-288，按 `documentId` 去重） | 同上 |
| `submitIngest`（4 个重载 :216/:220/:224/:228 与 :239/:243）、`submitIngestChunks` :257/:261、`submitIngestSources` :290/:294 | 同上的各计数（`submitIngestTask` / `submitIngestSources` 内计算后传入 `submit`） | 同上 |
| `resumeDocumentIngest` :458 / `submitResumeDocumentIngest` :466/:470 | 恒为 1（单 documentId） | DOCUMENT_SCOPED |
| `materializeDocumentGraph` :529 / `submitDocumentGraphMaterialization` :567 | 恒为 1 | DOCUMENT_SCOPED |
| `resumeChunkGraph` :551 / `repairChunkGraph` :559 / `submitChunkGraphMaterialization` :595 | 恒为 1 个 chunk | DOCUMENT_SCOPED |
| `deleteByEntity` :428、`deleteByRelation` :437、`deleteByDocumentId` :449/:453、`submitDeleteByDocumentId` :334/:338、`submitRebuild` :316/:320、图管理 :391/:396/:401/:406/:411/:419、`clearCache` :491、`saveSnapshot` :633、`restoreSnapshot` :642 | — | WORKSPACE_EXCLUSIVE（保持旧行为，不改调用点） |

**批量入口保持独占的代价与出路**：aiplatform 的 `LightRagKnowledgeIndexWriter` 是一条文档一次 `submitIngestChunks(...)`，走「恰好 1 个文档 → DOCUMENT_SCOPED」路径（`LightRagRuntimeFactory` 的调用形态见 Task 4 Step 2(b)）；一次提交多文档的调用方继续与今天一致地串行，需要并行时由调用方拆成多次提交——这正是本次改造的推荐用法。不得为了「让批量也并行」而放宽 `modeForDocumentCount`；批量场景要并行只能新增真正的单文档契约入口（不在本计划）。

其它判定依据（写进代码注释与 PR 描述）：

- `DOCUMENT_SCOPED` 的三个条件：① 只通过 `writeAtomically` 写入（upsert，无 restore）；② 只影响本篇文档/本切片派生的对象；③ 不依赖「工作区内没有其他写入者」这一前提。
- materialize 路径的判定证据：图写入走 `writeAtomically`（`GraphMaterializationPipeline.java:296`、`:374`、`:399`），实体/关系向量行在同一回调内 `saveAllEnriched`（`:298-300`、`:400-402`）——不要求「工作区唯一写入者」。
- `restore(...)` 的调用方必须独占：删除流程会用 `StorageSnapshots.capture` + `restore` 重写整个工作区（`DeletionPipeline.java:143`、`:218`、`:281-293`）。
- `saveSnapshot` / `restoreSnapshot` 之所以独占：前者是一致性读，后者是破坏性写。

**共享实体/关系在并发下的语义（第一轮评审 should-fix 7 的落实）**

两篇文档抽到同一实体 id 时，并发提交的最终结果是**合并**，不是 last-write-wins——这一点由既有实现保证，本计划不新增代码：

- 图写入的「读—合并—写」整体发生在 `writeAtomically` 的回调内（`GraphMaterializationPipeline.java:296`/`:374`/`:399` → `saveGraph` :718-753：先 `loadEntities` 读现状、再 `mergeEntity` :1025-1035 取 `sourceChunkIds`/`aliases` 并集、再 `saveEntities`）；
- provider 把**整个** `writeAtomically` 包在 `withExclusiveProviderLock` + `storageLockManager.withExclusiveLock` + `withExclusiveStorageLockScope` 之内（`PostgresMilvusNeo4jStorageProvider.java:261-276`），因此两个并发提交在存储侧完全互斥，后提交者的 `loadEntities` 必然读到前者已合并的行 → `sourceChunkIds`/`aliases` 取并集、`weight` 取 max。

（第一轮评审把这一点判为「provider 写锁只保证单次提交串行，不能保证两篇文档的『读—合并—写』整体串行」。该判断不成立：读—合并—写整体在同一个 `writeAtomically` 回调内，而 `writeAtomically` 整体在独占锁内，不存在两份读—合并—写的交错窗口。Task 3 Step 1 的 `twoConcurrentDocumentIngestsSharingAnEntityMergeRatherThanLose` 就是把这条语义钉死的测试；若它失败，说明该前提被破坏，必须先停下来修正本计划再继续。）

**仍然存在的残余（写进 PR 描述，与本计划正交）**：实体/关系**向量行**是整行 upsert（`saveAllEnriched`，无读—合并），并发时 last-write-wins；`EntityRecord` 的 `description`/`type` 合并规则也是「已存在者优先」。这与跨实例并发下的现状完全一致（跨实例本来就是并发的），不是本计划引入的新失败类型。

- [ ] **Step 4: 运行，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagDocumentConcurrencyTest"
```

Expected: 全绿。

- [ ] **Step 5: 回归既有 API 测试（确认默认语义未变）**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.api.*" --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.task.*"
```

Expected: 全绿。任何依赖「任务严格串行」的既有测试若要改动，必须逐条说明原因（预期不会出现）。

---

### Task 4: aiplatform 接入、文档与全量回归

**Files:**
- Modify（lightrag-java）: `README.md`、`README_zh.md`
- Modify（aiplatform，见下「路径基」）: `backend/pom.xml`（`<lightrag.version>` 升到含本计划的新版本）
- Modify（aiplatform）: `backend/aide-kno/src/main/java/com/finstone/fusion/ai/knowledge/service/index/LightRagRuntimeFactory.java`（builder 链加 `.maxConcurrentDocumentTasks(...)`）
- Modify（aiplatform）: `backend/aide-admin/src/main/resources/application.yml`（新配置项）
- Create（aiplatform）: `scripts/check-no-lightrag-shadow.sh` + CI 作业接入（防 shadow 副本再生）
- 条件 Delete（aiplatform）: `backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java` 与 `.../indexing/GraphMaterializationPipeline.java`——**若 Plan 3 已落地则它们已不存在；若仍在，`LightRag.java` 必须同步（见 Step 2(a) 的判定）**

> **路径基**：本 Task 除 README 外全部相对 `D:\ai-code\aiplatform`。
> **依赖顺序**：第一轮评审裁定总顺序为 **Plan 1 → Plan 3 → Plan 2**（见三份计划文末的「跨计划顺序」）。若按该顺序执行，本 Task 的正常路径是「只升版本 + 打开旋钮」；Step 2(a) 的 shadow 同步只在 Plan 2 被迫先行时执行。

- [ ] **Step 1: 更新 README**

在配置示例（`README.md:378-379`、`:1160-1161` 的 YAML 片段与 `:1071-1072`、`:1085-1086` 的 builder 说明）中补 `max-concurrent-document-tasks` / `.maxConcurrentDocumentTasks(...)`，并写清三条边界：

- 默认 `1`：行为与旧版本一致（同一 workspace 的任务串行）。
- 与 `max-parallel-insert` 的区别：`max-parallel-insert` 限制**一次 ingest 调用内部**的文档并行；`max-concurrent-document-tasks` 限制**同一 workspace 内并发执行的任务数**。当调用方按「一文档一任务」提交时，只有后者起作用；两者同时大于 1 时并行度相乘，`ChatModel` / `EmbeddingModel` / `Chunker` 实现必须线程安全（沿用 `README.md:419` 的既有告警）。
- **批量入口不并行**：一次提交多个文档的调用（`ingest(ws, List<Document>)` 等）按独占执行；要并行请拆成「一文档一次提交」（见 Task 3 Step 3 的判定规则）。

- [ ] **Step 2: aiplatform 接入（真实代码变更，不是 PR 说明）**

**(a) 前置判定 —— 决定走 (a1) 还是 (a2)**

```bash
find backend -path "*io/github/lightrag*" -name "*.java"
```

- **无输出（Plan 3 已落地，正常路径）→ (a1)**：只升 `backend/pom.xml` 的 `<lightrag.version>`；shadow 同步义务已随 Plan 3 的删除而终止（仅对主线成立，`.worktrees/` 副本见 (a3)）。
- **有输出（Plan 3 尚未落地）→ 推荐先把 Plan 3 落掉再回来**（否则会做一次「先同步 shadow、再删除 shadow」的重复劳动）。若因性能修复紧急必须让 Plan 2 先行，则走 **(a2)**：

  **(a2) shadow 同步 —— 硬要求，且必须与版本号升级同一个 commit**

  `backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java` 与 jar 同 FQCN（shadow 类，模块源码优先于依赖 jar）。Task 2 给 `LightRag` 的包私有构造器加了参数，而 `LightRagBuilder.build()`（在 jar 内、已编译）按**新签名**调用它，因此：
  - **只升 `lightrag.version` 不同步 shadow = `NoSuchMethodError`，启动即崩**（`LightRag.builder().build()` 处，即 `LightRagRuntimeFactory.java:241-244`）；编译期不报错——调用点在 jar 里，javac 不检查。
  - 同步基线 = 上游新 `LightRag.java`。已用 token 级 diff 核对 vendored 副本与 tag `v0.23.0`（`v0.23.0 == HEAD~1`，两者除 `gradle.properties` 版本号外代码相同）：本地改动共 3 类，**不是**「唯一一行」，必须逐条重放：
    1. **`cancellationCheckpoint` 线程穿透**——上游完全没有这个特性（`git log --all -S cancellationCheckpoint` 全历史零命中）。公开重载 `materializeDocumentGraph(workspaceId, documentId, mode, Runnable cancellationCheckpoint)`（vendored :597-612）、私有 `newGraphMaterializationPipeline(..., Runnable)` 多级重载（vendored :896-911 / :913-950）、最内层把 `cancellationCheckpoint` 传进 GMP 构造器；`(Runnable) null` 的消歧转换也要保留（vendored :898，否则 `newGraphMaterializationPipeline(scope, provider, null)` 歧义）。生产侧调用点：`KnowledgeGraphServiceImpl.java:2082-2090`（`@Nullable` 公开重载）、`:2395-2420`、`:2683-2698`（行号基线：2026-09-30 checkout；完整清单与刷新说明见 Plan 3 Task 6 Step 4——注意 Plan 3 落地后这里的类型会从 `Runnable` 换成 `CancellationCheckpoint`，本计划的同步义务随之终止）。
    2. **`resolvedChunkExtractParallelism()` 传进 `GraphMaterializationPipeline` 构造器**（vendored :940 附近）——与 shadow GMP 的构造器签名配对，两者必须同版本。
    3. **import 顺序**（`GraphMaterializationPipeline` / `PathAwareAnswerSynthesizer` 等；纯格式，交给 spotless 决定）。
  - 复核方式：**token 级 diff**（CRLF 归一化后逐 token 比较）。行级 diff 会被 spotless 重排完全淹没（vendored 1161 行 vs 上游 1008 行，绝大部分是格式差异）。
  - **不得顺手替换 shadow GMP**：`backend/aide-kno/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java`（1606 行 vs 上游 1189 行）带着 aiplatform 私有的分片并行抽取（`ExecutorCompletionService` + `cancelPending` + `rethrowTaskFailure`，vendored :889-990；`IndexedPrimaryExtraction` 记录在 vendored :1504）、`chunkExtractParallelism`/`cancellationCheckpoint` 字段与构造器重载、以及 `MaterializationState.chunkSnapshotMismatch → REBUILD`（vendored :1284-1287，上游没有该分支）——全是上游没有的。本计划不改它，PR 里显式说明，避免升级时被当作「顺手清理」删掉。

  **(a3) 防 shadow 副本再生 —— CI 扫描（跨计划问题 1 的裁定）**

  「Plan 3 完成后同步义务永久作废」**只对删除了 shadow 的模块成立**：`.worktrees/{chat-access-auth-integration,skill-evolution-s1-22-validation,skill-mcp-progressive-binding}/backend/aide-knowledge/...` 下仍有副本，各自升级 core 时仍需同步或删除。因此新增 `scripts/check-no-lightrag-shadow.sh`：

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

  纳入 aiplatform CI 的必过作业；worktree 若在 CI 覆盖内同样受检。**在 Plan 3 删除主线两个 shadow 之前，本脚本必然失败**——所以它的接入时点 = Plan 3 的删除 commit（可在 Plan 3 里登记，或在本计划执行到 (a1) 时补上并顺势用 Plan 3 的删除把它转绿）。

**(b) 打开新旋钮**

- `LightRagRuntimeFactory.java:241-244` 的 builder 链加 `.maxConcurrentDocumentTasks(...)`，读取方式与 `maxParallelInsert`（:268-273）同形。
- `backend/aide-admin/src/main/resources/application.yml` 在 `max-parallel-insert: ${LIGHTRAG_INDEXING_MAX_PARALLEL_INSERT:1}` / `chunk-extract-parallelism: ${LIGHTRAG_INDEXING_CHUNK_EXTRACT_PARALLELISM:4}`（:312-313，2026-09-30 checkout）旁新增：

  ```yaml
      max-concurrent-document-tasks: ${LIGHTRAG_INDEXING_MAX_CONCURRENT_DOCUMENT_TASKS:1}
  ```

- 默认 `1` = 今天的行为；要让 20 份文档真正并行**必须显式设 > 1**，建议从 4 起步。

**(c) 接入验证（改完必须做）**

1. 用生产同构装配（PG + Neo4j + Milvus，或本机最小三件套）提交 3 个单文档任务，`maxConcurrentDocumentTasks=2`：
   - 断言任一时刻最多 2 个任务的 `writeAtomically` 在执行（看 `LightRAG ... writeAtomically completed` 日志的线程名/时间重叠）；
   - `listTasks(ws)` 的 `queueWaitMs` 不再等于「前一个文档的整体耗时」。
2. 提交一个含 2 个文档的批量请求 + 1 个单文档请求：两者不重叠（批量走独占）。
3. 关掉配置（回 `1`）重跑第 1 步：任务重新串行——确认默认语义。

- [ ] **Step 3: 写 PR 交付说明**

1. **收益判据（第一轮评审 nit 8 修正后的版本）**：改造后 20 份文档同时提交，**任务门控**等待（`queueWaitMs`）应从「前一个文档的整体耗时」量级降到接近 0；但 `queueWaitMs` 只反映门控等待，**不代表整体排队消失**——真正的 provider 本地锁与 Redisson 跨进程锁等待仍发生在 `writeAtomically` 内（`PostgresMilvusNeo4jStorageProvider.java:261-276`）。因此验收要同时看三个来源：① `queueWaitMs`；② provider 内 `writeAtomically completed` 的 `totalMs` 中锁等待占比（若 Plan 1 已落地，看 `preImageMs` 与总时长）；③ 墙钟时间收敛比。若 ① 降而 ③ 不降，说明瓶颈已转移到存储锁，下一步调 Plan 1 的收益而不是继续加并发度。
2. **监控指标拆分（第一轮评审待评审判断 5 的落实）**：至少把 `queueWaitMs` 拆成语义可辨的两段（信号量等待 / 写锁等待按 gate 模式记录），实现上在 `WorkspaceGate` 内各记一个 `AtomicLong` 并通过任务元数据或 PERF 日志输出；本期若嫌重，**最小要求**是在 PR 里写明「`queueWaitMs` 含信号量等待 + 读写锁等待 + 旧路径的锁等待」这一混合语义，禁止按旧语义解读。
3. **残余风险（必须在 PR 里写明）**：本门控是**进程内**的；跨实例之间仍无任务级互斥（今天也没有），正确性仍由存储层锁承担。`recoverInterruptedTasks`（`TaskExecutionService.java:270-310`）的跨实例误判是既有缺陷，本计划不修。共享实体的合并语义与向量行 last-write-wins 残余见 Task 3 Step 3 末节。

- [ ] **Step 4: 全量回归**

Run:
```bash
./gradlew :lightrag-core:test
```

Expected: `BUILD SUCCESSFUL`。

---

## 验收标准

- 默认配置（`maxConcurrentDocumentTasks` 未设置）下所有既有测试与既有 API 行为不变。
- `maxConcurrentDocumentTasks = N` 时，同一 workspace 的 N 个**单文档**任务真正并行；第 N+1 个在信号量上排队。
- **多文档批量入口保持独占**（`ingest` / `ingestSources` / `submitIngest*` 装载 ≥2 文档时），有专门用例 `multiDocumentBatchIngestStaysExclusiveAgainstASingleDocumentTask`。
- 两篇共享实体的文档并发提交后**合并**（`sourceChunkIds` 取并集），有专门用例 `twoConcurrentDocumentIngestsSharingAnEntityMergeRatherThanLose`。
- 独占操作与文档级操作互斥，且独占操作不会饥饿（有专门用例）。
- 取消/异常路径 11 类用例全绿（6 类门控取消/异常 + 3 类回调重入 + 1 类 `TASK_SUBMITTED` 边界 + 1 类 `Error` 穿透），且 permit / ThreadLocal 无泄漏（用包私有测试钩子断言，不靠间接推断）。
- **回调重入契约写进 `TaskEventListener` / `IndexingProgressListener` Javadoc**：文档级任务回调内调独占 API、独占任务回调内调文档级 API → 确定性 `IllegalStateException`，被 `TaskEventPublisher` 隔离（仅 `RuntimeException`；`Error` 穿透并失败任务）、任务照常完成（不是任务失败）；文档级任务回调内调文档级 API → 内联嵌套不额外占配额；`TASK_SUBMITTED` 明确排除，且由 `listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused` 用例以**唯一 oracle = 可见副作用**（门控 work 向共享集合追加标记，断言标记出现；不用 spy——「无异常」不可判别，拒绝也会被 publisher 吞掉）守护「提交线程内调用确实执行、未被拒绝」。
- 四个删除入口、重建、图管理、`saveSnapshot`、`restoreSnapshot` 全部保持独占。
- `./gradlew :lightrag-core:test` 全绿。
- aiplatform：版本升级 + 新旋钮接入（+ 按 Step 2(a) 判定的 shadow 处理）+ CI 扫描脚本接入；「版本升级与 shadow 同步（若仍需）必须同 commit」。

## 第二轮评审裁定与落实（2026-09-30）

结论：**不可开工**（1 项 must-fix）。逐条落实如下；第三轮复审请核验本表。

| 第二轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| must-fix：listener / progress 回调重入契约未定义（回调在门控内同步派发，真实回调线程仍可触发拒绝） | 采纳「明确契约」而非「移动派发」（理由：移动会打乱任务状态写入与工作的顺序、把 `complete`/`cancel`/`fail` 记账移出锁边界） | Task 1 Step 4 新增「回调重入契约」四条（禁止事项、违反时的确定性行为、2 个用例、Javadoc 落点）；File Map 增加 `TaskEventListener.java` / `IndexingProgressListener.java`（只改 Javadoc）；Task 1 Step 1 新增 2 个用例；验收标准同步 |
| should-fix：aiplatform 配置基线与计划文字不一致（`max-parallel-insert` 实际为 1，计划写默认 2） | 采纳并更正 | Scope Guardrails 改为「SDK 默认 2；**aiplatform 现状显式配为 1**（`aide-admin/application.yml:312`，已核）」 |
| 判断 1-6 | 记录：`modeForDocumentCount` 完备性、共享实体合并论证（第一轮机制性判断已撤回，只保留测试要求）、构造器 arity 交换在既定顺序下不再必要、门控回收暂缓（长期多租户另案）、指标拆分本期用混合语义 + 日志、批量并发编排留作后续 API | 见各判断题与正文 |

## 第三轮评审裁定与落实（2026-09-30）

结论：**不可开工**（1 项 must-fix）。逐条落实如下；第四轮复审请核验本表。

| 第三轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| must-fix：必须补齐回调异常传播和 `TASK_SUBMITTED` 的门控范围（`TaskEventPublisher.java:16-23` 吞掉回调异常，故「任务失败」契约不成立；`TASK_SUBMITTED` 在提交线程、门控外派发） | 采纳「明确隔离语义 + 明确排除」而非「改 publisher」：改 publisher 会波及所有既有事件的派发路径且其注释声明 phase 1 隔离是有意为之，超出本计划范围 | Task 1 Step 4 契约重写：两条派发路径（任务内事件受约束 / `TASK_SUBMITTED` 排除 + 证据行号）、「拒绝 + error 日志 + 任务继续」、三层拒绝矩阵、死锁理由；测试改名 `listenerReenteringExclusiveApiIsRefusedWithoutFailingTheTask`；验收标准同步 |
| 判断 1：Javadoc + 可执行用例足够，不必重复到 README | 采纳：README 不重复；`TASK_SUBMITTED` 排除声明并入接口 Javadoc | 契约第 5 条 |
| 判断 2：保留「文档级回调内联、不额外占 permit」，但须明确标为禁止用法且行为确定 | 采纳；并在其之上加一条（自审，非评审意见）：**独占**任务的回调内调文档级 API 必须拒绝 | 契约第 2/3 条与矩阵第 2 行 |
| （自审新增）独占 → 文档级互锁路径 | `exclusiveSlotHeld` ThreadLocal + `runDocumentScoped` 前置拒绝 + 专门用例 | Task 1 Step 4 代码草图与 `rejectsDocumentScopedAcquisitionFromThreadHoldingExclusiveSlot` |

## 第四轮评审裁定与落实（2026-09-30）

结论：**不可开工**（1 项 must-fix）。逐条落实如下；第五轮复审请核验本表。

| 第四轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| must-fix：测试清单没有专门验证 `TASK_SUBMITTED` 的排除边界 | 采纳：新增 `listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused`（断言回调线程 == 提交线程、任务照常 SUCCEEDED；「未被拒绝」取正向证据——第五轮裁定改为**唯一 oracle：可见副作用**，见下方自查行） | Task 1 Step 1 用例组；契约第 4 条；验收标准同步（16 → 17 个用例） |
| must-fix（同条）：Javadoc 须限定为 publisher 隔离的 `RuntimeException`；`Error` 仍会穿过 publisher 并导致任务失败 | 采纳：契约第 2 条补两条边界（隔离只覆盖 `RuntimeException`，`Error` 沿 `runTask` 的 `catch (Throwable)` 走任务失败路径；「不执行」仅指门控 work） | Task 1 Step 4 契约第 2/5 条、File Map 的 Javadoc 条目、验收标准 |
| (a) 措辞 should-fix：「调用不会执行」应限定为「门控 work 不执行」——`providerResolver` / `recoverInterruptedTasks` 仍在门控前执行 | 采纳（同上第一条边界，含 `TaskExecutionService.java:74-81`、`:103-105` 证据） | 契约第 2 条 |
| (e) 可选强化：用日志捕获器断言拒绝路径确实输出 error | 记录：写入用例注释作为可选强化 | Task 1 Step 1 新增用例注释末句 |
| **自查（第五轮前，修正本计划一处错误措辞）**：新增用例原写「若被拒会抛 `IllegalStateException`，且因为回调线程未进入任务线程，无人吞掉它」——不成立：`TASK_SUBMITTED` 的派发同样经 `TaskEventPublisher.publish`（`TaskExecutionService.java:122`、`:131-141` → `TaskEventPublisher.java:16-25`），回调内异常在提交线程同样被吞，「无异常」在拒绝与放行两种结局下都出现 | 采纳：用例注释与验收标准改为要求**正向证据**（spy/可见副作用）；「未被拒绝」不再以「返回值正常」判别（**第五轮进一步写死为唯一 oracle = 可见副作用**，见第五轮表） | Task 1 Step 1 用例注释、验收标准、本节 |
| (b)(c)(d)：`TASK_SUBMITTED` 门控外判定、互锁论证、`exclusiveSlotHeld` 寄存器清理、读锁升级判断 | 全部 CLOSED，无需修改 | — |

## 第五轮评审裁定与落实（2026-09-30）

结论：**可开工**（无 must-fix）。四项 should-fix（oracle 二选一、边界限定语、跨线程等待限定、Error 用例）已全部落实；第六轮复审请核验本表。

| 第五轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| should-fix (a)：`TASK_SUBMITTED` 用例的 oracle 不得留成「spy 或副作用」二选一 | 采纳：写死**唯一 oracle = 可见副作用**（work 往标记集合追加一项，断言标记出现）；并写明不选 spy 的理由 | Task 1 Step 1 用例注释 |
| should-fix (b)：`Error` 边界句应写明适用范围是**任务内事件** | 采纳：边界子项改题「（适用范围 = 任务内事件）」，并补 `TASK_SUBMITTED` 例外面（提交线程上 listener 抛 `Error` 直接冒给 `submit` 调用者） | 契约第 2 条 |
| 问题 1：额外负向证据不需要；日志捕获为 should-fix 级增强 | 记录：两个重入拒绝用例可加日志捕获器断言 error——**仅当测试类已有日志断言基建时采用**，不为此引入新基建 | Task 1 Step 1「断言方式」段末尾 |
| 问题 2：建议增加 `Error` 用例（非 must-fix） | 采纳：新增 `listenerThrowingErrorFailsTheTask`（任务内事件 listener 抛 `AssertionError` → 任务 FAILED；listener 只在 TASK_RUNNING 抛，避免打断随后的 TASK_FAILED 派发）；用例总数 17 → 18 | Task 1 Step 1 用例组；Step 5 与验收标准计数同步 |
| 问题 3：被拒绝调用在隔离下「静默成功」可接受 | 记录：不引入 metadata 计数（避免扩大范围）；`log.error` 即唯一运行时信号，契约第 2 条已写明 | 契约第 2 条 |
| 问题 4：跨线程回调结论须限定「不等待转发结果」 | 采纳：第 3 条边界补「若转发后同步 `join`/`get`，等待环仍可能形成」，并把它归入第 1 条禁止事项的覆盖范围 | 契约第 3 条 + 第 1 条 |
| （顺延的第三轮问题 1/2） | 已随问题 3 / 问题 4 一并回答（静默成功可接受；跨线程结论成立但须加同步等待限定） | 本表 |

## 第六轮评审裁定与落实（2026-09-30）

结论：**可开工、可冻结**（无 must-fix，无 should-fix，无 nit）。三条核验全部 CLOSED，本节为纯记录性补充（不改变正文语义）；本计划自此冻结。

| 第六轮核验点 | 裁定 | 依据 |
| --- | --- | --- |
| (a) oracle 写死为「可见副作用」 | CLOSED：用例注释给出唯一 oracle 与不选 spy 的理由；验收标准同口径；旧轮次表里的「spy/可见副作用」有指向第五轮裁定的指针，不会误导实施者 | Task 1 Step 1 用例 + 验收标准 |
| (b) `Error` 用例与边界 | CLOSED：`TaskEventPublisher` 只 catch `RuntimeException`（`:16-25`），`runTask` 以 `catch (Throwable)` 收口（`:244-255`），`AssertionError` 进失败路径；「只在该 listener 的 TASK_RUNNING 事件上抛」是必要且足够的规避（避免打断随后 `TASK_FAILED` 的派发）；`errorMessage` 非空**不列为必需断言**（无消息的 `AssertionError` 合法；若用例显式用 `"boom"` 可作为增强） | 契约第 2 条 + 用例 `listenerThrowingErrorFailsTheTask` |
| (c) 计数与清单 | CLOSED：18 = 7 并发语义 + 11 取消/异常（6 门控 + 3 回调重入 + 1 `TASK_SUBMITTED` + 1 `Error`）；Step 5 与验收标准同口径；16→17、17→18 仅存在于历史裁定表并写明修订原因 | Step 5 + 验收标准 |
| 冻结后的维护边界 | 实施期只允许两类动作：按当时源码行号作等价校准；按本表口径实现用例（`errorMessage` 断言属可选项，不阻塞）——涉及行为/契约的改动必须先解冻 | 本表 |

## 第六轮评审的待评审问题（第六轮评审已回答，存档）

1. **新增 `Error` 用例的可执行性**：`listenerThrowingErrorFailsTheTask` 依赖「listener 只在 TASK_RUNNING 事件上抛」来保证 `reporter.fail` 的 TASK_FAILED 派发不被同一 listener 二次打断（`TaskExecutionService.java:244`、`:254-255`）——这个规避是否必要且足够？任务终态断言 FAILED 是否应同时断言 `errorMessage` 非空？（第六轮裁定：**CLOSED**——规避必要且足够；`errorMessage` 非空**不列为必需断言**（无消息的 `AssertionError` 合法），若用例显式用 `"boom"` 可作增强断言，不构成 should-fix。）
2. **「转发后同步等待」的落点**：该限定现写在契约第 3 条边界与第 1 条禁止事项；Javadoc 落点（第 5 条）是否也要重复这一句，还是以「禁止阻塞等待其他任务」的上位表述覆盖即可？（第六轮裁定：**维持现状**——Javadoc 以「禁止阻塞等待其他任务」的上位表述覆盖，同步 `join`/`get` 属其特例，契约第 1/3 条已写死，不重复；第六轮未就此提出异议。）
3. **残余 must-fix 检查**：本轮改动共四处（oracle 写死、边界限定、`Error` 用例、计数 17 → 18）。是否仍有未闭合项？（第六轮裁定：**CLOSED**——无残余 must-fix。）

**评审循环关闭（2026-09-30）**：Plan 2 经第 1–6 轮 Codex 评审，最后一轮（第六轮）判定「可开工、可冻结、零残余」。后续动作仅限：实施期按当时源码行号作等价校准、按第六轮表口径实现用例；涉及行为/契约的改动须先解冻并单独修订。

## 第四轮评审的待评审问题（第五轮评审已回答，存档）

1. **`TASK_SUBMITTED` 边界用例的断言强度**：本轮已把「未被拒绝」的判据从「回调正常返回」改为正向证据；是否还需要额外负向证据？（第五轮裁定：不需要；正向后执行证据已足够，日志捕获属 should-fix 级可选增强——已限定为「仅当已有日志断言基建」。）
2. **`Error` 穿透的契约是否要测试**：是否应加一个用例把它钉住，还是接受其为 `TaskEventPublisher` 的既有语义？（第五轮裁定：建议增加、非 must-fix——已采纳。）
3. **被拒绝的调用在异常隔离下「静默成功」是否可接受**（第三轮顺延问题 1）。（第五轮裁定：可接受，不引入 metadata 计数。）
4. **跨线程回调的边界结论是否正确**（第三轮顺延问题 2）。（第五轮裁定：在「不等待转发结果」前提下成立；需补同步 `join`/`get` 限定——已补。）

## 第二轮评审的待评审问题（第三轮评审已回答，存档）

1. **回调重入契约的落点是否足够**：契约写在两个 listener 接口的 Javadoc + 2 个用例守护；是否需要同时在 `LightRag` 的公开 API 文档（README / 类注释）再声明一次「回调禁止重入 workspace API」？（第三轮裁定：Javadoc + 可执行用例足够，不必重复到 README。）
2. **「文档级回调内联嵌套」是否应该也拒绝**：当前对回调内调用文档级 API 沿用既有嵌套语义（内联、不额外占配额），只禁止独占；是否应更严格地一律拒绝（需要区分「work 自身的合法嵌套」与「回调的非法嵌套」，代价是额外状态标记）？（第三轮裁定：保留内联，但标为禁止用法；独占 → 文档级必须拒绝。）

## 第一轮评审裁定与落实（2026-09-30）

| # | 评审意见（要点） | 落实位置 |
| --- | --- | --- |
| must-fix 1 | 多文档批量 API 被错误归入 `DOCUMENT_SCOPED` | Task 3 Step 3 改为 `modeForDocumentCount`（恰好 1 个文档才并行），分类表逐入口标注文档数来源；Step 1 新增批量独占用例 |
| must-fix 2 | aiplatform 新配置未进入实施范围（只在 PR 说明里） | File Map 与 Task 4 改写：三个 aiplatform 文件列入 Files，Step 2 成为真实代码变更步骤并带接入验证 |
| must-fix 3 | 任务取消路径测试不完整 | Task 1 Step 1 新增「取消与异常路径」6 用例；断言方式明确为包私有钩子 |
| should-fix 4 | 异常路径不泄漏未被证明 | 同上 6 用例覆盖 permit 中断/读锁中断/work 抛错/嵌套抛错/provider resolver 抛错 |
| should-fix 5 | 同线程 document→exclusive 的拒绝可能破坏隐藏路径 | Task 1 Step 4 新增三层兜底：调用路径审计（grep 证据）+ 拒绝前 `log.error` 堆栈 + 两类测试 |
| should-fix 6 | 门控缓存无生命周期策略 | Task 1 Step 4 明确：只增不减与 `workspaceLocks` 现状一致、禁止 ThreadLocal 跨 gate、空闲回收不做并列入第二轮问题 4 |
| should-fix 7 | 共享实体并发验证缺失 | Task 3 Step 3 新增「共享实体/关系在并发下的语义」节（含对评审机制判断的反驳与证据）+ 合并用例 |
| nit 8 | `queueWaitMs` 不代表 provider 锁等待 | Task 4 Step 3 收益判据改为三来源验收 + 指标拆分要求 |
| 判断 1 | 门控组合可用，须补全取消/异常测试 | 采纳（must-fix 3） |
| 判断 2 | 分类表有实质错误 | 采纳（must-fix 1） |
| 判断 3 | `clearCache` 保守独占可接受 | 采纳，维持独占 |
| 判断 4 | 默认 1 合理，但必须接通 aiplatform | 采纳（Task 4 Step 2(b)） |
| 判断 5 | 建议拆分指标 | 采纳（Task 4 Step 3：最小要求=写明混合语义；推荐按段记录） |
| 判断 6 | 应写任务 metadata，但不能替代配置 | 采纳（Task 2 Step 3 写 metadata；Task 4 Step 2(b) 接通配置） |
| 判断 7 | 构造器 arity 耦合应避免；至少把 shadow 同步列为真实变更与 CI 检查 | 部分采纳：CI 扫描（Task 4 Step 2(a3)）+ 总顺序改为 Plan 1→3→2 使主线不再需要同步；「改用 `LightRagConfig` 承载」列入第二轮问题 3 |
| 跨计划 1 | 「永久作废」只对主线成立，worktree 仍有副本，需 CI 扫描 | Task 4 Step 2(a3) |
| 跨计划 2 | 顺序改为 Plan 1 → Plan 3 → Plan 2 | 文末「跨计划顺序」节 + Task 4 路径基注记 |

## 第一轮评审的待评审问题（第二轮评审已回答，存档）

1. **`modeForDocumentCount` 的完备性**：规则 = 「恰好 1 个文档才 `DOCUMENT_SCOPED`」。是否还存在其它「名义单文档、实际越界」的入口？（已核 `resumeDocumentIngest` / `materializeDocumentGraph` / `resumeChunkGraph` / `repairChunkGraph` 都以单个 documentId/chunkId 为参数；`ingestSources` 的每个 source 恰好产生一个解析文档，`IndexingPipeline.java:496-528`。）
2. **共享实体合并论证**：Task 3 Step 3 用「`saveGraph` 的读—合并—写整体在 `writeAtomically` 回调内（`GraphMaterializationPipeline.java:296`/`:374`/`:399` → `:718-753`），`writeAtomically` 整体在 `withExclusiveProviderLock` + Redisson 锁内（`PostgresMilvusNeo4jStorageProvider.java:261-276`）」反驳了第一轮「读—合并—写整体不串行」的判断。请复核这两处 file:line 证据；若无误，第一轮的 should-fix 7 机制部分应予撤回，只保留「补测试」的部分（已补）。
3. **构造器 arity 交换是否仍必要**：把 `maxConcurrentDocumentTasks` 放进 `LightRagConfig`（shadow 不同步的后果从启动崩溃降级为特性不生效）vs 维持构造参数（与 `maxParallelInsert` 先例一致）。在总顺序已改为 Plan 1→3→2（主线 shadow 将被删除）的前提下，这笔交换是否已无必要？
4. **门控空闲回收**：`workspaceGates` 只增不减是否需要在本计划内加引用计数回收？若需要，请给出「任务在门控中排队时 gate 被摘除」竞态的最小正确方案。
5. **指标拆分的最小实现**：把 `queueWaitMs` 拆成 permit 等待与锁等待是否值得本期做，还是先用混合语义 + 日志即可？
6. **批量入口的出路**：本次规则下批量调用方需自行拆分提交。是否应（在后续计划）提供「批量并发编排」入口（对外单次调用、内部按文档拆 task）？若是，接口形态建议？

## 跨计划顺序（第一轮评审跨计划问题 2 的裁定）

**Plan 1（scoped commit compensation）→ Plan 3（shadow GMP 能力上游化）→ Plan 2（本计划）。**

- Plan 1 先行：并发会放大 `writeAtomically` 内的全量捕获成本与 Redisson 锁内时长，先收敛补偿范围再放并发。
- Plan 3 第二：删除 aiplatform 主线的两个 shadow 类。此后本计划 Task 4 Step 2 的正常路径只剩「升版本 + 打开旋钮」，**(a2) 的同步清单不再需要**，避免「先同步 shadow、再删除 shadow」的重复劳动。
- **文件交集（与另外两份的表述一致）**：本计划与 Plan 1 无文件交集；与 Plan 3 共享 `LightRag.java`（Plan 3 的取消/GMP 改动 + 本计划的入口模式标注），两者的 `LightRag.java` 编辑必须**串行**——后落地者对另一份做一次 rebase 并重跑各自的 API 契约测试。aiplatform 侧两计划改动不同文件（Plan 3 删 shadow 副本，本计划改 `LightRagRuntimeFactory.java` / `application.yml` / `pom.xml`），无编辑冲突。
- 例外：若性能修复必须让本计划先行，Task 4 Step 2(a) 走 (a2)（同步为硬要求、同 commit），并在 Plan 3 落地删除 shadow 时把 (a3) 的 CI 扫描转绿。
