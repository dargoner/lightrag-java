# 提交补偿范围收敛（Scoped Commit Compensation）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `StorageCoordinator.writeAtomically` 的失败补偿从「写前全量快照 + 失败后全量 restore」改为「只对本次 staged 写集合做定点前像捕获 + 定点回滚」，使单次提交的补偿成本与故障爆炸半径从 O(工作区) 降到 O(本次写集合)，且**保持既有适配器实现兼容**（SPI 只新增 `default` 方法，不改任何既有方法签名）、不改变现有多实例锁序。

> **第一轮评审（2026-09-30）判定本计划不可开工，本文件已按裁定修订。** 修订点集中在：relational 事务回滚契约、fallback 状态机、部分成功恢复、跨重试写集合变化、Milvus 分批、PreImage 类型校验。另按跨计划裁定把总顺序定为 **Plan 1 → Plan 3 → Plan 2**（见文末「跨计划顺序」）。逐条闭环见文末「第一轮评审裁定与落实」。
>
> **第二轮评审（2026-09-30）判 Plan 1 仍有 4 项 must-fix，已二次修订**：①多 payload 补偿协议（SPI 无合并操作）；②Milvus 512 分批草图真实落地；③`readRows` 按去重输入顺序重建（不依赖 Milvus 返回顺序）；④空前像适配器补类型校验；另重写 relational commit 失败契约（「结果未知」列为边界外 + 发版前实测）。见文末「第二轮评审裁定与落实」。
>
> **第三轮评审（2026-09-30）判定可开工**（4 项 must-fix 全部 CLOSED，无新增）。按三条判断题的裁定补一句：多 payload 列表**不写死上界**（SPI 不依赖实现的重试次数）。见文末「第三轮评审裁定与落实」。
>
> **第四轮评审（2026-09-30）判定无回归、可开工。** 全部关键引用核对一致、两条待评审问题 CLOSED，未要求改动；本计划自本轮起**冻结**，本轮修订仅新增文末裁定表与第五轮问题。见文末「第四轮评审裁定与落实」。
>
> **第五轮评审（2026-09-30）判定可开工、可冻结（无 must-fix）。** 一条 should-fix 已落实：Task 4 Step 4 第 4/5 条（Milvus 512 与 PostgreSQL「结果未知」两个发版前实测项）各补「不通过时的回退动作」。见文末「第五轮评审裁定与落实」。
>
> **第六轮评审（2026-09-30）维持可开工、可冻结（无 must-fix，零残余）。** 回退动作闭环核验 CLOSED；本计划维持**冻结**。见文末「第六轮评审裁定与落实」。

**Architecture:**

- 现状：`StorageCoordinator.writeAtomically` 在进入 PostgreSQL 事务前无条件做三份全量捕获（relational / graph / vector），失败时 `rollback(...)` 把三者全部 `restore` 回去（`StorageCoordinator.java:97-101`、`:153-155`、`:255-276`）。graph/vector 的 apply 发生在 PG 事务窗口内、无 2PC，所以补偿必须保留；但 relational 的补偿其实由 PG 事务本身完成（`PostgresRelationalStorageAdapter` :394-406 在异常时 `connection.rollback()`），全量捕获纯属冗余；graph/vector 的补偿则完全可以缩小到本次写集合。
- 改造后：
  1. **删除 relational 捕获**：`writeAtomically` 不再调用 `relationalAdapter.captureSnapshot()`，PG 事务即补偿。
  2. **graph / vector 引入定点前像（scoped pre-image）**：在 apply 之前，按本次 staged 写集合的 id 批量点读当前值（存在=记录，缺席=缺席），失败时只对「已经尝试过 apply」的那一侧做定点回滚（有前像的写回前像，无前像的删除）。
  3. **SPI 用 default method 扩展**，未覆写的第三方 / 测试适配器自动回落为现有全量快照语义（行为不变，只是不收益），保证源兼容。
  4. Milvus 新增「按 pk 批量点读 / 按行写回」链路（`MilvusSdkClientAdapter` → `MilvusVectorStore` → `MilvusVectorStorageAdapter`）。前像载体对协调器不透明（opaque），由适配器自己定义，从而保证 `full_text` / `src_id` / `tgt_id` / `file_path` 与写入时一致——这些字段在通用 `VectorWrite` 里没有对应位置（`MilvusSdkClientAdapter.toJsonRow` :637-650 持久化了 `full_text`，但 `keywords` 并未持久化）。
  5. `restore(Snapshot)` 路径（`DeletionPipeline` 的全部删除流程都走它）**不在本次范围内**，语义与代价保持不变。

**Tech Stack:** Java 17（record / sealed / pattern switch），JUnit 5，AssertJ，Gradle（`./gradlew :lightrag-core:test`），测试替身 `StorageAssemblyTestDoubles`。

---

## 背景：为什么必须做这件事

生产部署是 **PostgreSQL + Neo4j + Milvus 多实例**，每个文档 ingest 至少 3 次 `writeAtomically`（`IndexingPipeline.java:1143`、`:1194`、`:1203`、`:1216`）。今天每次提交都要付：

- 3 份全工作区捕获（relational 全表 list + Neo4j `allEntities`/`allRelations` + Milvus 3 个 namespace 全量 list），全部发生在 provider 写锁 + Redisson 工作区锁**之内**；
- 任何一次失败的重试成本也是 O(工作区)，且 `rollback` 里的 `graphAdapter.restore(...)` 是 **`DETACH DELETE` 全工作区图 + 重建**（`Neo4jGraphStorageAdapter.restore` → `WorkspaceScopedNeo4jGraphStore.deleteEntities` :341-357 为 `DETACH DELETE`），在多实例 + Redisson lease 10min / wait 15min 的配置下，这是「一个文档失败可能拖垮整个知识库并触发租约过期」的风险源。

本计划只收敛**提交路径**的补偿；任务级并发（第二份计划）依赖本计划先落地，否则并发只会让更多线程堵在同一把锁上。

## File Map

**生产代码**

- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/RelationalStorageAdapter.java`
  - **只改文档注释**：给 `writeInTransaction` 写死「异常/commit 失败必须回滚」的 SPI 契约（第一轮评审判定 relational 补偿完全依赖它，而它今天没有任何契约文字）。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStorageAdapter.java`
  - 新增 `capturePreImage(...)` / `restorePreImage(...)` / `interface PreImage`，默认回落为 `captureSnapshot()` / `restore(...)`。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/VectorStorageAdapter.java`
  - 同上（默认回落）。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java`
  - `writeAtomically` 改为「staging → 定点前像 → apply → 提交」；新增 `PreImageAccumulator`（含跨重试的「只补录未捕获 id」规则）；删除 relational 捕获；调整性能日志字段。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java`
  - 用 `Projection` 已有的 `loadEntities/loadRelations` + `deleteEntities/deleteRelations` 实现 scoped 前像。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java`
  - `PreImage` 记录（`StoredVectorRow` 级别）+ `capturePreImage/restorePreImage`；`Projection` 增 `readRows` / `writeRows`（default 抛 `UnsupportedOperationException`）。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStore.java`
  - `readRows(namespace, ids)` / `writeRows(namespace, rows)`；pk 由 `technicalPrimaryKey` 重算（复用 `deleteIds` :157-171 的过滤构造方式）。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java`
  - 新增 `RowReadRequest(collectionName, filter)` 与 `default List<StoredVectorRow> readRows(RowReadRequest)`。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`
  - 实现 `readRows`：复用 `list(ListRequest)` :130-171 的分页/存在性检查，`outputFields` 追加 `vector_id / searchable_text / full_text / src_id / tgt_id / file_path`。
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresVectorStorageAdapter.java`
  - `apply` 是空操作（:37-41，PG 基线向量走事务内写入），显式覆写为「空前像 + 空回滚」，避免继续付全量 Milvus/PG 向量捕获。

**测试**

- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/StorageCoordinatorPreImageTest.java`（失败注入矩阵 + 回落 + 重试合并规则）
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java`（给 Fake graph/vector 增 `captureSnapshot` 调用计数与可选 scoped 支持）
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProviderTest.java`、`.../mysql/MySqlMilvusNeo4jStorageProviderTest.java`（回归 pin：`writeAtomically` 不再触发全量捕获）
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusVectorStoreTest.java`（`readRows` 过滤与字段映射）

## Scope Guardrails

- **不改** `restore(Snapshot)` / `StorageSnapshots` / `DeletionPipeline`：删除、清空、快照恢复仍是全量语义。
- **不删** `RelationalStorageAdapter.captureSnapshot()` / `restore(...)` 这两个 SPI 方法：它们仍被 `StorageCoordinator.restore(...)` 使用（第一轮评审问题 5 的裁定）。本计划只移除 `writeAtomically` 里的 relational 捕获调用。
- **不改** `deletes` 语义：`writeAtomically` 的 staged 写集合只有 upsert（`GraphStorageAdapter.StagedGraphWrites` 无删除表示），本次设计不得引入「写 + 删」混合场景。
- **不改**锁与锁序：`PostgresMilvusNeo4jStorageProvider` 的双层锁（provider 写锁 + `StorageLockManager`）与 `writeAtomically` 的调用位置不动。
- **不改** `InMemoryStorageProvider`、`ArcadeStorageProvider`、`PostgresStorageProvider`（各自实现 `writeAtomically`，不走 `StorageCoordinator`）。
- **不改** 事务重试语义：`PostgresRelationalStorageAdapter.writeInTransaction`（:274-282）→ `PostgresRetrySupport.execute`（`PostgresRetrySupport.java:17-51`）仍是「同一 supplier 最多 3 次、仅对瞬时 SQL 错误重试」，本计划只保证重试下的前像正确性（见 Task 1 的合并规则）。
- MySQL `ProjectionGraphStorageAdapter` / `ProjectionVectorProjectionAdapter`（`MySqlMilvusNeo4jStorageProvider` :789-870，仅投影构造器路径使用）**保持默认回落**，不实现 scoped。

---

### Task 1: SPI 扩展 + 协调器改用定点前像

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/RelationalStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/VectorStorageAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/StorageCoordinatorPreImageTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/StorageAssemblyTestDoubles.java`

- [ ] **Step 1: 先写失败测试，锁定契约**

新建 `StorageCoordinatorPreImageTest`，用 `StorageAssemblyTestDoubles` + 可控 `GraphStorageAdapter` / `VectorStorageAdapter` 包装（记录调用、注入异常）。**所有「补偿是否正确」的断言都必须比较最终存储状态，而不是只比较调用次数**（第一轮评审：调用次数断言无法发现部分成功下的错误结果）。

```java
@Test
void doesNotCaptureFullSnapshotsOnTheWritePath() { /* writeAtomically 成功后：graph/vector 的 captureSnapshot 调用次数 == 0 */ }

@Test
void compensatesOnlyTheSideThatWasApplied() { /* 注入 graphAdapter.apply 抛错：graph 回滚，vector 的 restorePreImage 不被调用 */ }

@Test
void compensatesBothSidesWhenVectorApplyFails() { /* 注入 vectorAdapter.apply 抛错：graph 与 vector 都回滚到前像，且断言最终状态逐字段等于前像 */ }

@Test
void compensatesWhenTransactionCommitFails() { /* PG 提交失败：两侧都回滚，且 relational 不触发 restore */ }

@Test
void skipsCompensationWhenOperationFailsBeforeAnyApply() { /* operation.execute 抛错：pre-image 可以已捕获，但两侧都不回滚 */ }

@Test
void fallsBackToFullSnapshotForLegacyAdapters() { /* 适配器返回 Optional.empty 的 capturePreImage：仍走 captureSnapshot()+restore() */ }

@Test
void supportsScopedGraphWithFallbackVector() { /* graph 返回 Optional.of、vector 返回 Optional.empty：graph 走定点回滚、vector 走全量 restore，两者互不影响 */ }

@Test
void capturesPreImageOnlyForIdsNotYetCapturedAcrossRetries() { /* 同一 writeAtomically 内 supplier 重跑两次：第二次只点读新增 id */ }

@Test
void keepsFirstPreImageWhenRetryWidensTheWriteSet() { /* 第 1 次尝试写 A（已 apply 一半），第 2 次尝试写 A+B：
     A 的前像必须是第 1 次捕获的值（不能被第 1 次已 apply 的值覆盖），B 的前像在第 2 次补录 */ }

@Test
void keepsFirstPreImageWhenRetryChangesRelationAndVectorSets() { /* 同上，但分别断言实体 id、关系 id、namespace+向量 id 三类写集合的增量补录 */ }

@Test
void compensatesAcrossAllCapturedPayloadsWhenRetryWidensAndFails() { /* 多载荷协议（第二轮评审 must-fix）：
     第 1 次尝试捕获 A 的前像、第 2 次尝试捕获 B 的前像后 apply 中途失败 →
     补偿后 A、B 都恢复到各自捕获时的前像（验证 SCOPED 侧按捕获顺序逐个重放 payload 列表） */ }

@Test
void restoresExactPreImageAfterPartialBatchApply() { /* 适配器在一批 5 个 id 中写到第 3 个抛错：
     补偿后存储状态逐条等于前像（存在→原值、缺席→不存在），且补偿自身可被再次调用（幂等） */ }

@Test
void keepsRelationalStateWhenGraphApplyFails() { /* 第一轮评审 must-fix 1：graph apply 失败 → 事务回滚后 relational 不得残留本次写入，
     该用例同时 pin 住 RelationalStorageAdapter.writeInTransaction 的「异常必须回滚」契约（用事务性 test double 实现） */ }

@Test
void reportsCompensationFailureAsSuppressed() { /* restorePreImage 抛错：原异常被抛出且带 suppressed */ }
```

测试替身的实现要求（写进 `StorageAssemblyTestDoubles` 注释）：

- scoped 替身要**真的按 id 存值**（一个 `Map<String, ...>` 即可），使「部分成功 → 定点回滚 → 最终状态等于前像」可以被真实断言；
- 提供一个 `failOnNthWrite(int n)` 开关，用于构造「批量写到中途失败」；
- 提供 `transactionalRelational` double：`writeInTransaction` 在 operation 抛异常时丢弃本事务内所有写入（模拟 PG 的 `connection.rollback()`）。

- [ ] **Step 2: 运行测试，确认当前实现必然失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.StorageCoordinatorPreImageTest"
```

Expected:
```text
FAIL
... cannot find symbol: method capturePreImage(...)
... cannot find symbol: method restorePreImage(...)
```

- [ ] **Step 3: 写死 relational 回滚契约 + 扩展两个 SPI（default method，保持源兼容）**

`RelationalStorageAdapter` —— **只加注释，不加方法**。取消 relational 全量捕获后，「relational 侧不需要补偿」这句话的全部依据就是这条契约，因此必须写进 SPI 而不是只存在于 PG 实现里（当前 `writeInTransaction` 只有一行签名，`PostgresRelationalStorageAdapter.java:274-332` 的 `withTransaction` 行为没有被任何契约文字约束）：

```java
    /**
     * 在单个数据库事务内执行 {@code operation}。
     *
     * <p><b>契约（{@link StorageCoordinator} 依赖它，覆写者必须遵守）</b>：</p>
     * <ul>
     *   <li>{@code operation} 抛异常时，本次事务内的<b>全部</b>写入必须被回滚，且回滚必须发生在
     *       {@code writeInTransaction} 返回/抛出之前——调用方不会为 relational 侧捕获任何前像；</li>
     *   <li>数据库侧判定为失败的提交必须回滚，实现不得停留在部分提交状态；<b>「提交结果未知」是契约边界之外的
     *       情形</b>（提交请求发出后连接中断等）：实现必须把该情形作为异常抛出（不得吞掉），此后 relational 侧
     *       可能已提交——调用方仍会补偿 graph/vector，于是留下一个 relational 已提交、graph/vector 已回滚的
     *       残余窗口。该窗口的存在性与目标 PG 版本的实际行为列为发版前实测项（见 Task 4 Step 4），本计划不
     *       声称能消除它；</li>
     *   <li>本方法可以内部重试（如 {@code PostgresRetrySupport}），但只允许对「事务已被数据库明确拒绝/回滚」的
     *       瞬时错误重试（现有实现仅匹配 {@code SQLTransactionRollbackException} 与
     *       {@code 40001}/{@code 40P01}/{@code 55P03}/{@code 57014}）；<b>不得对「提交结果未知」重试</b>——
     *       重试可能造成重复提交。重试必须复用同一事务语义：前一次尝试的写入不得对下一次可见。</li>
     * </ul>
     *
     * <p>换言之：relational 侧的失败补偿是事务自身的责任，不是 {@link StorageCoordinator} 的责任。</p>
     */
    <T> T writeInTransaction(RelationalWriteOperation<T> operation);
```

`GraphStorageAdapter`：

```java
    /**
     * 捕获写集合的定点前像：对给定 id 逐个点读「apply 之前」的状态。
     *
     * <p>契约：返回的载荷必须能区分「存在」与「缺席」——缺席的 id 在补偿时要被删除。
     * 返回 {@link Optional#empty()} 表示实现不支持定点前像，调用方会回落为
     * {@link #captureSnapshot()} + {@link #restore(GraphSnapshot)}（现有语义，代价仍为全工作区）。</p>
     *
     * <p>实现必须允许被重复调用：{@link StorageCoordinator} 在 PostgreSQL 事务重试时会再次调用，
     * 且只会传入尚未捕获过的 id（保证重试前已部分 apply 的 id 仍拿到真实前像）。
     * <b>多次调用的返回值是彼此独立的载荷</b>——协调器按捕获顺序逐个保存、补偿时逐个回传；
     * 实现<b>不需要</b>（也不应假定）把后续结果合并进先前的载荷：跨重试的前像由
     * 「只传新 id + 多载荷逐个恢复」表达，不同载荷的 id 集合按构造互不相交。</p>
     */
    default Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        return Optional.empty();
    }

    /**
     * 回滚给定前像。载荷对 {@link StorageCoordinator} 不透明，只能回传给**产生它的同一个适配器实例**。
     *
     * <p><b>实现契约</b>：</p>
     * <ul>
     *   <li>{@code capturePreImage} 返回 {@code Optional.of(...)} 的实现**必须**同时覆写本方法——
     *       两者是成对的，只覆写一个属于实现错误；</li>
     *   <li>必须先做类型校验：载荷不是本实现的类型时抛
     *       {@code IllegalArgumentException("unexpected pre-image payload: " + ...)}，
     *       而不是放任裸 {@code ClassCastException}（第一轮评审要求，避免掩盖编排错误）；</li>
     *   <li><b>幂等</b>：同一前像可以被重复回滚，结果不变；</li>
     *   <li>回滚是「按 id 的定点写回/删除」集合：前像中存在的 id 写回前像值，前像中缺席的 id 删除。
     *       对批量写入中途失败（部分 id 已落库）必须同样收敛到前像状态。</li>
     * </ul>
     */
    default void restorePreImage(PreImage preImage) {
        throw new UnsupportedOperationException(
            "scoped pre-image restore is not supported by " + getClass().getName()
        );
    }

    /** 定点前像载荷，由各适配器自行定义（见 {@code Neo4jGraphStorageAdapter.ScopedPreImage}）。 */
    interface PreImage {
    }
```

`VectorStorageAdapter` 同形，参数为 `Map<String, List<String>> idsByNamespace`（namespace 是 staged writes 的天然分组）——**同样以「多次调用返回独立载荷、协调器逐个恢复」为契约，无合并操作**：

```java
    default Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        return Optional.empty();
    }

    default void restorePreImage(PreImage preImage) {
        throw new UnsupportedOperationException(
            "scoped pre-image restore is not supported by " + getClass().getName()
        );
    }

    interface PreImage {
    }
```

- [ ] **Step 4: 改造 `StorageCoordinator.writeAtomically`**

关键结构（删除 relational 捕获，新增前像与「已 apply」标记）：

```java
    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        long started = System.nanoTime();
        var preImage = new PreImageAccumulator(graphAdapter, vectorAdapter);
        var applied = new AppliedFlags();
        try {
            T result = relationalAdapter.writeInTransaction(storage -> {
                var stagedGraphStore = new StagedGraphStore(
                    graphAdapter.graphStore(),
                    storage.transactionalGraphStore().orElse(null)
                );
                var stagedVectorStore = new StagedVectorStore(
                    vectorAdapter.vectorStore(),
                    storage.transactionalVectorStore().orElse(null)
                );
                var operationResult = operation.execute(new AtomicView(...));
                var graphWrites = stagedGraphStore.toWrites();
                var vectorWrites = stagedVectorStore.toWrites();
                long captureStarted = System.nanoTime();
                preImage.captureOnce(graphWrites, vectorWrites);   // 幂等；回落判定只在首次发生
                long capturedAt = System.nanoTime();
                if (!graphWrites.isEmpty()) {
                    applied.graph = true;                          // 先标记，apply 可能部分成功
                    graphAdapter.apply(graphWrites);
                }
                if (!vectorWrites.isEmpty()) {
                    applied.vector = true;
                    vectorAdapter.apply(vectorWrites);
                }
                log.info(
                    "LightRAG storage writeAtomically inner completed: graphEntities={}, graphRelations={}, vectorNamespaces={}, operationMs={}, preImageMs={}, graphApplyMs={}, vectorApplyMs={}",
                    ...
                );
                return operationResult;
            });
            log.info(
                "LightRAG storage writeAtomically completed: preImageMode={}, graphPreImageIds={}, vectorPreImageIds={}, totalMs={}",
                preImage.mode(), preImage.graphIdCount(), preImage.vectorIdCount(), elapsedMillis(started, System.nanoTime())
            );
            return result;
        } catch (RuntimeException | Error failure) {
            preImage.compensate(applied, failure);
            throw failure;
        }
    }
```

`PreImageAccumulator` 的状态机（**按此实现，测试逐条覆盖**；第一轮评审判定原伪代码不足以照着做）：

**1) 每个适配器各自一个 capture 状态槽**，互不影响——graph 可以是 scoped 而 vector 是 fallback，反之亦然。

**2) 能力判定只在首次 `captureOnce` 做一次，其结果被冻结**：

| 首次 `capturePreImage(...)` 返回 | 状态 | 前像载荷 | 补偿动作 | 后续每次 `captureOnce` |
| --- | --- | --- | --- | --- |
| `Optional.of(payload)` | `SCOPED` | **payload 列表**（每个新 id 集合一个，按捕获顺序）+ 已捕获 id 集合 | 按捕获顺序逐个 `restorePreImage(payload_i)` | 只对**尚未捕获过**的 id 再次调用 `capturePreImage(新增id)`，其返回值作为**独立 payload** 追加进列表；**不重读已捕获条目**（已 apply 过的 id 若被重新点读，读到的会是新值，等于把破坏当成前像） |
| `Optional.empty()` | `FALLBACK` | `captureSnapshot()` 的返回值（单个快照） | 旧 `restore(snapshot)` | **什么都不做**（快照只落一次；再落一次会把已 apply 的值拍成「前像」） |

**2.1) 多载荷协议（第二轮评审 must-fix：`PreImage` 不透明且 SPI 没有合并操作）**：跨重试的前像**不做合并**——`SCOPED` 状态槽持有的是 `List<PreImage>`，每次 `captureOnce` 只为「本次的新 id」调一次 `capturePreImage`，把返回值作为**独立 payload** 追加；补偿时按捕获顺序逐个 `restorePreImage(payload_i)`。正确性依据：① 不同 payload 的 id 集合**按构造互不相交**（只传新 id）；② 每个 payload 的恢复本身幂等（SPI 契约）——因此列表整体重放收敛到前像，且可重复执行。实现方**不需要**提供任何 merge/append 能力，「追加」只发生在协调器的列表上。列表长度**不写死上界**：虽然当前 PostgreSQL 实现最多尝试 3 次（`MAX_ATTEMPTS = 3`），但 SPI 与协调器都不依赖该实现细节（第三轮评审判断题 1 的裁定）。

**3) 只在 `SCOPED` 状态下、且这批 id 里有新 id 时才发起点读**；`FALLBACK` 状态永不再调用 `captureSnapshot()`，也永不再尝试 scoped。

**4) 重试语义**：`PostgresRetrySupport`（`PostgresRelationalStorageAdapter.java:274-331`）会用同一个 supplier 重跑整段 `writeAtomically`。因此：
- 第 2 次进入 `captureOnce` 时，第 1 次**已经点读过的 id** 必须原样保留第 1 次的前像；
- 第 1 次**没出现过、第 2 次才出现**的 id 才补录（`keepsFirstPreImageWhenRetryWidensTheWriteSet` / `keepsFirstPreImageWhenRetryChangesRelationAndVectorSets` 两个用例 pin 住这条）；
- 「第 2 次尝试开始时，第 1 次已 apply 的写入仍然留在存储里」是这条规则成立的前提，**不得**在重试前做任何清理（本计划不引入任何重试前的清理动作）。

**5) `compensate(applied, failure)`**：
- 只处理 `applied.graph` / `applied.vector` 为 true 的一侧（`applied` 在 `apply` 调用**之前**置位，因为 Neo4j/Milvus 的批量写可能写到一半失败，见测试 `restoresExactPreImageAfterPartialBatchApply`）；
- `SCOPED` 侧按捕获顺序**逐个** `restorePreImage(payload_i)` 重放全部已捕获 payload；`FALLBACK` 侧调用旧 `restore(snapshot)`；每侧各自 `try/catch`：该侧回滚失败时用 `addSuppressed` 挂到原异常上（复用 `StorageCoordinator.addSuppressedIfDistinct` :278-282），**不允许**因为一侧回滚失败而跳过另一侧；
- 回滚顺序：graph → vector（与 apply 顺序一致，便于对照日志）；
- 最后重新抛出原异常（补偿永不吞异常）。

**6) 不补偿的路径**：`operation.execute` 抛错时 `applied` 全 false，因此不会发生任何回滚写入（避免「未 apply 却回滚」把并发写入者的值覆盖掉）；`SCOPED` 状态下已捕获但未 apply 的前像随对象丢弃，不产生写操作。

- [ ] **Step 5: 运行测试，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.StorageCoordinatorPreImageTest"
```

Expected: `BUILD SUCCESSFUL`，14 个用例全绿（含回落、scoped+fallback 混合、重试增量补录、多 payload 补偿、部分成功恢复、relational 回滚契约）。

- [ ] **Step 6: 跑受影响模块的既有测试**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.*"
```

Expected: 全绿；若 `StorageAssemblyTest.java` 断言了 relational `restoreCount`（`StorageAssemblyTestDoubles.FakeRelationalStorageAdapter.restoreCount`），按新语义更新断言并说明原因。

---

### Task 2: Neo4j 图侧 scoped 前像

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStorageAdapter.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProviderTest.java`（或新增 adapter 级用例）

- [ ] **Step 1: 写失败测试**

在 adapter 级用例里断言四件事（用 `RecordingProjection implements Neo4jGraphStorageAdapter.Projection` 记录调用 + 一个按 id 存值的图 test double）：

1. `capturePreImage(entityIds, relationIds)` 只点读给定 id：调用了 `loadEntities`/`loadRelations`，且**没有**调用 `allEntities`/`allRelations`/`captureSnapshot`。
2. 补偿时：前像中存在的 id 走 `saveEntities`/`saveRelations`，前像中缺席的 id 走 `deleteEntities`/`deleteRelations`。
3. 顺序：实体先于关系（`DETACH DELETE` 会连带删除关系，见实现说明）。
4. **反例收敛（第一轮评审 must-fix：不能只靠注释论证）**——构造 `restoresExactPreImageForSharedEndpointsAndHoles`：前像里有 3 个实体（其中两个是某条关系的端点）、1 条关系；写阶段新增了实体 `b`、关系 `a→b`、关系 `a→c`（`c` 从不存在）；补偿后断言**整图逐条等于前像**：`b` 与 `a→b`、`a→c` 全部消失，两个端点实体与其关系逐字段等于前像。再用一个「前像中关系存在但某端点实体不存在」的畸形输入验证实现不会静默放大差异（允许抛异常，但必须显式断言行为）。

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.*"
```

Expected: `FAIL ... cannot find symbol: method capturePreImage`（未覆写时实际会走 default 回落，断言「不调用 allEntities」失败）。

- [ ] **Step 3: 实现**

```java
    @Override
    public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        var requestedEntityIds = List.copyOf(entityIds);
        var requestedRelationIds = List.copyOf(relationIds);
        return Optional.of(new ScopedPreImage(
            requestedEntityIds,
            projection.loadEntities(requestedEntityIds),
            requestedRelationIds,
            projection.loadRelations(requestedRelationIds)
        ));
    }

    @Override
    public void restorePreImage(PreImage preImage) {
        if (!(preImage instanceof ScopedPreImage scoped)) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        var presentEntityIds = scoped.entities().stream().map(GraphStore.EntityRecord::id).collect(Collectors.toSet());
        var absentEntityIds = scoped.entityIds().stream().filter(id -> !presentEntityIds.contains(id)).toList();
        if (!absentEntityIds.isEmpty()) {
            projection.deleteEntities(absentEntityIds);   // DETACH DELETE：会连带删除指向它的关系
        }
        if (!scoped.entities().isEmpty()) {
            projection.saveEntities(scoped.entities());
        }
        var presentRelationIds = scoped.relations().stream().map(GraphStore.RelationRecord::id).collect(Collectors.toSet());
        var absentRelationIds = scoped.relationIds().stream().filter(id -> !presentRelationIds.contains(id)).toList();
        if (!absentRelationIds.isEmpty()) {
            projection.deleteRelations(absentRelationIds);
        }
        if (!scoped.relations().isEmpty()) {
            projection.saveRelations(scoped.relations());
        }
    }

    private record ScopedPreImage(
        List<String> entityIds,
        List<GraphStore.EntityRecord> entities,
        List<String> relationIds,
        List<GraphStore.RelationRecord> relations
    ) implements PreImage {
    }
```

实现说明（写进注释，评审要点）：

- 实体先于关系处理，是因为 `WorkspaceScopedNeo4jGraphStore.deleteEntities`（:341-357）用 `DETACH DELETE`。被 DETACH 删除的关系只可能是「本次写入新建、且前像中同样缺席」的关系（前像里存在的关系必然对应写入前已存在的实体），因此顺序不影响最终状态。
- 空集合必须短路，避免无意义往返。

- [ ] **Step 4: 运行，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.neo4j.*" --tests "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProviderTest"
```

Expected: 全绿。`PostgresNeo4jStorageProvider`（PG + Neo4j 路径）由此自动获得 scoped 图前像，无需改动。

---

### Task 3: Milvus 向量侧 scoped 前像

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusVectorStoreTest.java`

- [ ] **Step 1: 写失败测试（用 fake `MilvusClientAdapter`，不必连真实 Milvus）**

```java
@Test
void readRowsFiltersByTechnicalPrimaryKey() {
    // saveAllEnriched 两行，readRows(namespace, List.of("a")) 只返回 a 对应的行
    // 断言：client 收到的 filter 同时包含 workspace_id / record_type 与 pk_id in [...]
}

@Test
void readRowsPreservesPersistedFullTextAndEndpoints() {
    // 断言读回的 StoredVectorRow 的 fullText/srcId/tgtId/filePath 与写入值一致（keywords 不持久化，允许为空）
}

@Test
void restorePreImageReupsertsExistingRowsAndDeletesAbsentOnes() {
    // 前像：a 存在、b 缺席；写阶段 upsert 了 a 与 b；补偿后：a 回到前像值、b 被删除
}

@Test
void readRowsBatchesLargeIdSetsAndPreservesOrder() {
    // 600 个 id：断言 fake client 收到 2 次查询（512 + 88），合并结果顺序与输入一致
}

@Test
void readRowsDeduplicatesIdsBeforeQuerying() {
    // 输入含重复 id：查询里的 pk_id 个数等于去重后个数
}

@Test
void readRowsIssuesNoQueryForEmptyIds() {
    // 空输入：client 零调用
}
```

- [ ] **Step 2: 运行，确认失败**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.milvus.MilvusVectorStoreTest"
```

Expected: `FAIL ... cannot find symbol: method readRows`

- [ ] **Step 3: 实现客户端点读（`MilvusClientAdapter` / `MilvusSdkClientAdapter`）**

```java
    // MilvusClientAdapter
    record RowReadRequest(String collectionName, String filter) {
    }

    default List<StoredVectorRow> readRows(RowReadRequest request) {
        throw new UnsupportedOperationException("row point-read is not implemented");
    }
```

`MilvusSdkClientAdapter.readRows` 复用 `list(ListRequest)` 的既有骨架（存在性检查 / `QUERY_PAGE_SIZE` 分页 / `queryConsistency` / 异常包装成 `StorageException`），只改 `outputFields` 与结果映射：

```java
    @Override
    public List<StoredVectorRow> readRows(RowReadRequest request) {
        var readRequest = Objects.requireNonNull(request, "request");
        var targetCollection = readRequest.collectionName();
        if (!hasCollection(targetCollection)) {
            return List.of();
        }
        try {
            var rows = new ArrayList<StoredVectorRow>();
            long offset = 0;
            while (true) {
                var response = client.query(QueryReq.builder()
                    .databaseName(config.databaseName())
                    .collectionName(targetCollection)
                    .filter(readRequest.filter())
                    .outputFields(List.of(
                        VECTOR_ID_FIELD,
                        DENSE_VECTOR_FIELD,
                        SEARCHABLE_TEXT_FIELD,
                        FULL_TEXT_FIELD,
                        SRC_ID_FIELD,
                        TGT_ID_FIELD,
                        FILE_PATH_FIELD
                    ))
                    .limit(QUERY_PAGE_SIZE)
                    .offset(offset)
                    .consistencyLevel(queryConsistency)
                    .build());
                ... // 与 list(...) 相同的翻页与空页短路
                for (var row : page) {
                    var entity = row.getEntity();
                    rows.add(new StoredVectorRow(
                        "",                                             // pk_id 由 MilvusVectorStore 重算，见 Step 4
                        Objects.toString(entity.get(VECTOR_ID_FIELD)),
                        "",                                             // workspace_id / record_type 由调用方按 filter 上下文回填
                        "",
                        Objects.toString(entity.get(VECTOR_ID_FIELD)),
                        toDoubleList(entity.get(DENSE_VECTOR_FIELD)),
                        Objects.toString(entity.get(SEARCHABLE_TEXT_FIELD), ""),
                        List.of(),                                      // keywords 不持久化
                        Objects.toString(entity.get(FULL_TEXT_FIELD), ""),
                        Objects.toString(entity.get(SRC_ID_FIELD), ""),
                        Objects.toString(entity.get(TGT_ID_FIELD), ""),
                        Objects.toString(entity.get(FILE_PATH_FIELD), "")
                    ));
                }
            }
            return List.copyOf(rows);
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to read vector rows from Milvus collection: " + targetCollection, exception);
        }
    }
```

实现要求：

- `full_text` 必须原样带回（补偿写回时不得重算，否则会把 `keywords` 从索引文本里抹掉——`MilvusVectorStore.composeFullText` :250-260 会把 keywords 拼进 `full_text`，而 `keywords` 本身不入库）。

**分批规则（第一轮评审 must-fix：原「1024」是凭空取的数，必须可验证、可测试）**

- 常量集中定义在 `MilvusVectorStore`：`static final int READ_ROWS_ID_BATCH_SIZE = 512;`，并写明推导：`technicalPrimaryKey` = `"pk-" + hex(MD5)` = **35 字符**（:228-236），加上引号与 `", "` 分隔符约 39 字符/个 → 512 个 id ≈ **20 KB** 的表达式文本，对 Milvus 的 `pk_id in [...]` 表达式长度限制留有 3 倍以上余量（余量按 Milvus 3.x 常见配置取值，**发版前必须在目标 Milvus 版本上按 `max_expression_length` 实配复核**，见 Task 4 Step 4 的交付说明）。
- 分批**只在 `MilvusVectorStore.readRows` 做**（SDK 适配器不感知批次）；`writeRows`/`restorePreImage` 不用分批（写回走逐行 upsert）。
- 语义写死并测试：`ids` 先**去重**再切批；批与批之间按**输入顺序**拼接结果；结果顺序**不依赖 Milvus 的返回顺序**（`readRows` 按 `vectorId` 建索引后按去重输入顺序重建）；空输入不发任何查询；某一批返回空不影响其他批。
- 对应测试（`MilvusVectorStoreTest`）：`readRowsBatchesLargeIdSetsAndPreservesOrder`（600 个 id → 断言 fake client 收到 2 次查询、第一/二批分别 512/88 个 id、合并结果顺序与输入一致）、`readRowsDeduplicatesIdsBeforeQuerying`、`readRowsIssuesNoQueryForEmptyIds`、`readRowsReturnsRowsInRequestedOrderEvenWhenClientShuffles`（fake client 打乱各批返回顺序并跨批乱序 → 结果仍等于去重输入顺序）。

- [ ] **Step 4: 实现存储层点读/写回 + 适配器前像（`MilvusVectorStore` / `MilvusVectorStorageAdapter`）**

`MilvusVectorStore`：

```java
    public List<MilvusClientAdapter.StoredVectorRow> readRows(String namespace, List<String> ids) {
        var values = List.copyOf(Objects.requireNonNull(ids, "ids"));
        if (values.isEmpty()) {
            return List.of();                                     // 空输入：零查询
        }
        var normalizedNamespace = normalizeNamespace(namespace);
        var uniqueIds = new ArrayList<>(new LinkedHashSet<>(values));   // 去重且保序
        var rowsByVectorId = new LinkedHashMap<String, MilvusClientAdapter.StoredVectorRow>();
        for (int from = 0; from < uniqueIds.size(); from += READ_ROWS_ID_BATCH_SIZE) {
            var batch = uniqueIds.subList(from, Math.min(from + READ_ROWS_ID_BATCH_SIZE, uniqueIds.size()));
            var idFilter = batch.stream()
                .map(value -> technicalPrimaryKey(workspaceId, normalizedNamespace, value))
                .map(value -> "\"" + escapeFilterLiteral(value) + "\"")
                .collect(Collectors.joining(", "));
            for (var row : clientAdapter.readRows(new MilvusClientAdapter.RowReadRequest(
                collectionName(),
                filter(normalizedNamespace) + " && pk_id in [" + idFilter + "]"
            ))) {
                rowsByVectorId.put(row.vectorId(), new MilvusClientAdapter.StoredVectorRow(
                    technicalPrimaryKey(workspaceId, normalizedNamespace, row.vectorId()),
                    row.vectorId(),
                    workspaceId,
                    normalizedNamespace,
                    row.vectorId(),
                    row.denseVector(),
                    row.searchableText(),
                    row.keywords(),
                    row.fullText(),
                    row.srcId(),
                    row.tgtId(),
                    row.filePath()
                ));
            }
        }
        // Milvus 不承诺结果顺序：按**去重后的输入顺序**重建；缺席的 id 不产出行（即前像缺席）
        return uniqueIds.stream()
            .map(rowsByVectorId::get)
            .filter(Objects::nonNull)
            .toList();
    }

    public void writeRows(String namespace, List<MilvusClientAdapter.StoredVectorRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var normalizedNamespace = normalizeNamespace(namespace);
        var collectionName = collectionName();
        ensureCollection(normalizedNamespace, collectionName);
        clientAdapter.upsert(collectionName, rows);   // 原样写回，不重算 full_text
    }
```

`MilvusVectorStorageAdapter`：

```java
    @Override
    public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        var rowsByNamespace = new LinkedHashMap<String, List<MilvusClientAdapter.StoredVectorRow>>();
        var requestedByNamespace = new LinkedHashMap<String, List<String>>();
        for (var entry : idsByNamespace.entrySet()) {
            var ids = List.copyOf(entry.getValue());
            if (ids.isEmpty()) {
                continue;
            }
            requestedByNamespace.put(entry.getKey(), ids);
            rowsByNamespace.put(entry.getKey(), projection.readRows(entry.getKey(), ids));
        }
        return Optional.of(new ScopedPreImage(requestedByNamespace, rowsByNamespace));
    }

    @Override
    public void restorePreImage(PreImage preImage) {
        if (!(preImage instanceof ScopedPreImage scoped)) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        var namespaces = new LinkedHashSet<String>(scoped.rowsByNamespace().keySet());
        namespaces.addAll(scoped.idsByNamespace().keySet());
        for (var namespace : namespaces) {
            var requestedIds = scoped.idsByNamespace().getOrDefault(namespace, List.of());
            var rows = scoped.rowsByNamespace().getOrDefault(namespace, List.of());
            var presentIds = rows.stream().map(MilvusClientAdapter.StoredVectorRow::vectorId).collect(Collectors.toSet());
            var absentIds = requestedIds.stream().filter(id -> !presentIds.contains(id)).toList();
            if (!absentIds.isEmpty()) {
                projection.deleteIds(namespace, absentIds);
            }
            if (!rows.isEmpty()) {
                projection.writeRows(namespace, rows);
            }
        }
        projection.flushNamespaces(List.copyOf(namespaces));
    }
```

`Projection` 增两个 default 方法（default 抛 `UnsupportedOperationException`，因此 MySQL 的 `ProjectionVectorProjectionAdapter` 无需改动即回落全量）：

```java
        default List<MilvusClientAdapter.StoredVectorRow> readRows(String namespace, List<String> ids) {
            throw new UnsupportedOperationException("row point-read is not implemented");
        }

        default void writeRows(String namespace, List<MilvusClientAdapter.StoredVectorRow> rows) {
            throw new UnsupportedOperationException("row write-back is not implemented");
        }
```

`MilvusStoreProjection` 转发到 `delegate`。

- [ ] **Step 5: 运行，确认通过**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.milvus.*"
```

Expected: 全绿（`MilvusSdkClientAdapterIntegrationTest` 无真实 Milvus 时按既有机制 skip）。

---

### Task 4: 无副作用适配器复位、日志、回归 pin 与交付说明

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresVectorStorageAdapter.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProviderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

- [ ] **Step 1: `PostgresVectorStorageAdapter` 显式空前像**

`apply`（:36-41）不写任何东西，因此它既不需要前像也不需要回滚，显式声明（**注意必须返回 `Optional.of(EMPTY_PRE_IMAGE)` 而不是 `Optional.empty()`**：后者按状态机属于「能力不支持」，会触发一次全量 `captureSnapshot()`，正是本计划要消灭的开销）：

```java
    @Override
    public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        return Optional.of(EMPTY_PRE_IMAGE);   // apply 为空操作：PG 基线向量在事务内写入（见 apply 注释）
    }

    @Override
    public void restorePreImage(PreImage preImage) {
        if (preImage != EMPTY_PRE_IMAGE) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        // EMPTY_PRE_IMAGE：apply 为空操作，无需回滚
    }
```

**类型校验同样适用于空前像（第二轮评审 must-fix）**：`EMPTY_PRE_IMAGE` 是单例常量，用**身份比较**即可校验——不能写成无条件空操作，否则适配器串线（把别的适配器的 payload 传进来）会被静默吞掉，掩盖编排错误。加一个用例 `rejectsForeignPreImagePayloadOnTheNoOpAdapter`（传入非 `EMPTY_PRE_IMAGE` 的载荷 → `IllegalArgumentException`）。

- [ ] **Step 2: 回归 pin（防止全量捕获悄悄回来，也防止顺手删掉 restore 路径的公共能力）**

**第一轮评审裁定（问题 5）**：不删除 `RelationalStorageAdapter.captureSnapshot()` / `restore(...)`——`StorageCoordinator.restore(...)`（`StorageCoordinator.java:160-175`）仍然需要它们，`DeletionPipeline` 的全部删除流程都走这条路径。本计划只从 `writeAtomically` 里移除 `relationalAdapter.captureSnapshot()` 调用。

在 `PostgresMilvusNeo4jStorageProviderTest` / `MySqlMilvusNeo4jStorageProviderTest` 里用已有的 `RecordingGraphStorageAdapter` / `RecordingVectorStorageAdapter`（:998 / :1033 与 :878 / :1092）加断言：

```java
@Test
void writeAtomicallyOmitsFullWorkspaceSnapshots() {
    // 触发一次 provider.writeAtomically(...)（写 1 个实体 + 1 条关系 + 1 个 chunk 向量）
    // 断言：graphAdapter.captureSnapshot / vectorAdapter.captureSnapshot 调用次数 == 0
    //       且 capturePreImage 的 id 集合 == 本次生效写集合
}

@Test
void restorePathStillUsesRelationalSnapshotCapability() {
    // provider.restore(snapshot)：断言 relationalAdapter.restore(...) 仍被调用（能力未被顺手删除）
}
```

- [ ] **Step 3: 运行两个 provider 测试**

Run:
```bash
./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest"
```

Expected: 全绿。

- [ ] **Step 4: 全量回归 + 交付说明**

Run:
```bash
./gradlew :lightrag-core:test
```

Expected: `BUILD SUCCESSFUL`。

在 PR 描述里写清五件事：

1. **收益量化**：`writeAtomically` 不再做 relational 全量 list、不再做 Neo4j `allEntities`/`allRelations`、不再做 Milvus 全 namespace list；改为 2 次点读（图）+ 每 namespace 1 次点读（向量），范围都是本次写集合。失败时的回滚从「全工作区 `DETACH DELETE` + 重建」变为「写集合定点回写/删除」。
2. **兼容性**：SPI 以 default method 扩展，第三方适配器保持旧语义；`lightrag.version` 升级即生效。本计划未触碰 `LightRag.java` / `GraphMaterializationPipeline.java`，所以 **Plan 1 自身不要求 aiplatform 重做 vendor 同步**。跨计划顺序（见文末「跨计划顺序」）已裁定为 **Plan 1 → Plan 3 → Plan 2**：Plan 3 先删除 shadow，Plan 2 落地时只剩「升版本 + 打开旋钮」。只有 Plan 2 被迫先行时，才必须按 Plan 2 Task 4 Step 2(a2) 先同步一次 shadow（Plan 2 改了 `LightRag` 包私有构造器 arity，只升版本不同步会 `NoSuchMethodError`），且该同步与版本号升级必须放在**同一个 commit**。
3. **残余风险**：补偿本身失败时现在只影响本次写集合（旧实现影响整个工作区）；补偿失败仍以 `addSuppressed` 上报，且失败痕迹落在 provider 日志。
4. **发布前必须核对的一条外部约束**：`MilvusVectorStore.READ_ROWS_ID_BATCH_SIZE = 512` 是按 `pk_id`（35 字符）与常见表达式长度限制推算的（≈20 KB/批）。发版前须在**目标 Milvus 版本**上确认 `pk_id in [...]` 的表达式长度上限 ≥ 20 KB 有余量。**不通过时的回退动作**：只改这一个常量（按实测上限 × 80% 余量重算批大小），同步把 `readRowsBatchesLargeIdSetsAndPreservesOrder` 的 600/512/88 断言改为**按常量推导的期望值**（而非硬编码 512），重跑 Task 3 Step 5 与 Task 4 Step 3；若实测上限低于单个 id 的表达式长度（即无法做 `in` 查询），则该条升级为 must-fix，单独修订 `readRows` 的降级策略（逐 id 查询 + 限流），不在冻结范围内自行处理。
5. **发布前必须实测的第二个外部约束（第二轮评审 must-fix：relational 契约的边界）**：「提交结果未知」窗口：用代理连接在 `COMMIT` 之后、响应返回之前注入断连，观察 `writeInTransaction` 是否抛出、PG 侧是否已提交，并据此确认「relational 已提交而 graph/vector 已回滚」这一残余窗口的实际形态与自愈路径（下一次 ingest 重写）。**不通过时的回退动作**：按实测结果三选一——①若常见 PG 版本在该断连下确定**不会**提交（该窗口不存在），把实测结论写回 `writeInTransaction` 的契约注释，本条关闭；②若窗口确实存在（relational 已提交、graph/vector 已回滚）——注意这是**今天就已存在的既有形态、非本计划引入**——按仓库「Consistency without transactions」规则登记为文档化残差（写明检测方式与「下次 ingest 重写」的自愈路径）；③若实测显示的形态比既有更差（出现数据丢失、或窗口扩大），则**解冻本计划**：单独修订契约与实现（例如为 relational 侧恢复 scoped 前像），不得带着该形态发布。第 2 条实测项的结论无论取哪一支，都必须回写到 PR 描述与契约注释里。

---

## 验收标准

- `writeAtomically` 成功路径 0 次全量捕获；失败路径只对「已 apply」的一侧、只对本次写集合做定点回滚。
- 未覆写 SPI 的适配器行为与今天完全一致（有专门用例对照）；`SCOPED` 与 `FALLBACK` 可以按侧混用（有专门用例）。
- `RelationalStorageAdapter.writeInTransaction` 的回滚契约写进 SPI 注释，并有「graph apply 失败 → relational 不残留」的用例守护。
- 重试场景下前像不失真（第 2 次尝试不会把第 1 次已 apply 的值当成前像；**写集合变宽时只补录新 id**，实体/关系/向量三类各自有用例）。
- 批量写入中途失败后的补偿**收敛到前像**（存在→原值、缺席→不存在），且补偿可重复执行（幂等，有用例）。
- Milvus 点读按 `READ_ROWS_ID_BATCH_SIZE` 分批、去重、保序，空输入零查询（有用例）。
- 跨重试捕获的前像以「多 payload 列表、按捕获顺序逐个恢复」表达，SPI 无合并操作（有用例 `compensatesAcrossAllCapturedPayloadsWhenRetryWidensAndFails`；`readRows` 结果顺序不依赖 Milvus 返回顺序）。
- 空前像适配器（`PostgresVectorStorageAdapter`）对非 `EMPTY_PRE_IMAGE` 载荷抛 `IllegalArgumentException`（有用例）。
- `restore(Snapshot)` / 删除 / 清空 / 快照恢复路径零改动（relational 的 `captureSnapshot`/`restore` 能力保留，有用例）。
- `./gradlew :lightrag-core:test` 全绿。

## 第二轮评审裁定与落实（2026-09-30）

结论：**不可开工**（4 项 must-fix）。逐条落实如下；第三轮复审请核验本表。

| 第二轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| must-fix：opaque `PreImage` 没有可实现的合并协议（状态机要求「追加进 payload」，SPI 无 append/merge） | 采纳，选「每次捕获一个独立 payload、补偿时按捕获顺序逐个恢复」，**不新增适配器级 merge SPI** | Task 1 Step 3 两个 `capturePreImage` 契约新增「多次调用返回彼此独立的载荷、实现不需要合并」；Task 1 Step 4 新增「2.1) 多载荷协议」与第 5 条补偿动作（`SCOPED` 侧逐个 `restorePreImage(payload_i)`）；Task 1 Step 1 新增用例 `compensatesAcrossAllCapturedPayloadsWhenRetryWidensAndFails` |
| must-fix：Milvus 分批未落地（草图仍一次性拼 `pk_id in [...]`，与 512 规则矛盾） | 采纳 | Task 3 Step 4 `readRows` 草图重写为真实循环：去重 → 按 512 切批 → 逐批查询 → 合并 |
| must-fix：`readRows` 未保证输入顺序（Milvus 不承诺返回顺序） | 采纳 | 同上，按 `vectorId` 建索引后按**去重输入顺序**重建；新增用例 `readRowsReturnsRowsInRequestedOrderEvenWhenClientShuffles` |
| must-fix：`PostgresVectorStorageAdapter.restorePreImage` 空操作无类型校验 | 采纳 | Task 4 Step 1 改为 `preImage != EMPTY_PRE_IMAGE` 身份校验 + `IllegalArgumentException`，并加用例 `rejectsForeignPreImagePayloadOnTheNoOpAdapter` |
| must-fix（结论项）：relational commit 失败契约过强（网络断开可能「结果未知」） | 采纳：契约限定为「数据库侧明确失败的提交必须回滚」；「结果未知」列为契约边界之外的既有窗口 + 发版前实测 | Task 1 Step 3 契约重写（含「不得对结果未知重试」）；Task 4 Step 4 新增第 5 条实测项 |
| 判断题 1：`PostgresRetrySupport` 边界 | 已核并写进契约：只对 `SQLTransactionRollbackException` 与 `40001/40P01/55P03/57014` 重试（`PostgresRetrySupport.java:60-72`），重试包裹整个 `withTransaction`（`PostgresRelationalStorageAdapter.java:276-326`） | Task 1 Step 3 契约第 3 条 |
| 判断题 2：applied 前置位 + 幂等 | 保持设计；幂等在 SPI 契约中已写死，真实 Milvus 下的组合幂等列入发版前集成测试项 | Task 1 Step 3 `restorePreImage` 契约 |
| 判断题 3：`EMPTY_PRE_IMAGE` 表达 | 保留 `EMPTY_PRE_IMAGE`（不再引入 `NO_OP`），配合身份校验 | Task 4 Step 1 |
| 判断题 4：512 常量位置 | 保留 `MilvusVectorStore` 常量 + 发版前按目标版本复核 `max_expression_length` | Task 3 Step 3 / Task 4 Step 4 第 4 条 |
| 判断题 5：混合 scoped/fallback | 保留（局部收益），多载荷协议已使其可实现 | Task 1 Step 4 状态机 |
| 跨计划 should-fix：「三份计划文件互不重叠」与 `LightRag.java` 共享矛盾 | 采纳 | 文末「跨计划顺序」已改为「Plan 1 与另外两份无文件交集；Plan 3 与 Plan 2 共享 `LightRag.java`」 |

## 第三轮评审裁定与落实（2026-09-30）

结论：**可开工**（第二轮 4 项 must-fix 全部 CLOSED，无新增 must-fix）。第三轮对三条待评审问题的裁定已落实；第四轮复审请核验本表。

| 第三轮问题 | 裁定 | 落实位置 |
| --- | --- | --- |
| 1. 多 payload 列表是否写死上界 | **不写死**（SPI 不应依赖实现的重试次数上限） | Task 1 Step 4 的 2.1) 末句：列表长度不写死上界，按捕获顺序追加、逐个恢复 |
| 2. 「结果未知」是否还需要额外代码 | **不需要**：现有 transient 判定只覆盖明确回滚类错误（`PostgresRetrySupport.java:60-72` 的 `SQLTransactionRollbackException` 与 `40001/40P01/55P03/57014`），已与「不重试未知结果」契约一致 | Task 1 Step 3 契约 + 发版前代理连接实测条目，无代码变更 |
| 3. `EMPTY_PRE_IMAGE` 身份比较是否足够 | **足够**：无状态 no-op sentinel，同类型不同实例共享同一静态常量不产生错误副作用 | Task 4 Step 3 的 `rejectsForeignPreImagePayloadOnTheNoOpAdapter` 维持身份比较 |
| 跨计划（Plan 2 表述不一致） | 非本计划问题：Plan 2 的跨计划节已补「与 Plan 1 无文件交集」表述 | 见 Plan 2 文末 |

## 第四轮评审裁定与落实（2026-09-30）

结论：**无回归，可开工**。第四轮引用核对全部一致、两问均 CLOSED，未要求任何改动；本计划自第四轮起冻结，第五轮仅做无回归确认。

| 第四轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| 引用 1：`PostgresRetrySupport.java:60-72` | CLOSED（与源码一致） | Task 1 Step 3 契约 + 第三轮表第 2 行 |
| 引用 2：`PostgresRelationalStorageAdapter.java:274-331` 与其 `withTransaction` :390-421 | CLOSED | Task 1 Step 3 |
| 引用 3：`StorageCoordinator.java:278-282` | CLOSED | Task 1 Step 4 第 4 条 |
| 引用 4：`PostgresMilvusNeo4jStorageProvider.java:261-276` | CLOSED | Task 2 Step 1 |
| 待评审问题 1（file:line 漂移抽查） | CLOSED：上述关键引用已核对 | 本表 |
| 待评审问题 2（发版前实测清单完备性） | CLOSED：已覆盖 Milvus 表达式长度与 PostgreSQL「COMMIT 后断连 ⇒ 结果未知」窗口；后者仍应作为目标版本实测项，而不是假定已证明 | Task 3 Step 3 / Task 4 Step 4 + Task 1 Step 3 |
| 尾注裁定（「末尾新增裁定表和问题清单没有改变正文语义」） | 确认无回归 | 第一/二/三轮表 + 本表 |

## 第五轮评审裁定与落实（2026-09-30）

结论：**可开工、可冻结**（无 must-fix）。一条 should-fix 已落实；第六轮复审请核验本表。

| 第五轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| should-fix：Task 4 Step 4 的 Milvus 512 与 PostgreSQL「结果未知」两个发版前实测项应各自补「不通过时的回退动作」 | 采纳：第 4 条（512）补「按实测上限 × 80% 重算常量 + 把 600/512/88 硬编码断言改为按常量推导 + 上限低于单个 id 表达式长度时升级 must-fix 单独修订」；第 5 条（未知结果窗口）补「三选一：窗口不存在→写回契约关闭；窗口存在（既有形态）→登记文档化残差 + 自愈路径；形态比既有更差→解冻、单独修订契约与实现」，并写明实测结论必须回写 PR 与契约注释 | Task 4 Step 4 第 4/5 条 |
| 判断 1：冻结结论、关键源码引用与发版前实测边界均保留 | CLOSED：本轮修订仅改 Task 4 Step 4 的两条实测项措辞，正文语义与引用未动 | 本表 |
| 判断 2：本轮新增裁定表与问题清单没有改变正文语义 | CLOSED | 第一/二/三/四轮表 + 本表 |

## 第五轮评审的待评审问题（第五轮评审已回答，存档）

1. **是否可冻结**：第四轮对本计划未提出任何改动；本轮修订仅新增上文裁定表与本节。若认可，请明确 Plan 1 可冻结（后续实施中若目标版本实测推翻 Milvus 512 / PostgreSQL 未知结果窗口任一条款，走单独修订），否则给出残余 must-fix。（第五轮裁定：**可冻结**——无残余 must-fix。）
2. **冻结后的维护边界**：发版前实测清单（Task 4 Step 4）在实施期间可能出现新证据——是否需要在该清单里为每条实测项标注「不通过时的回退动作」（例如 512 → 按实测值调整并重跑 `readRows` 用例），还是保持现状（实测项只写测什么、不写回退）？（第五轮裁定：**应补回退动作**，属发布流程 should-fix——已按上文落实。）

## 第六轮评审裁定与落实（2026-09-30）

结论：**可开工、可冻结**（无 must-fix，无 should-fix，无 nit）。回退动作闭环成立，本节为纯记录性补充（不改变正文语义）；本计划维持冻结。

| 第六轮核验点 | 裁定 | 依据 |
| --- | --- | --- |
| 回退动作闭环（Milvus 512） | CLOSED：按实测上限 × 80% 重算 `READ_ROWS_ID_BATCH_SIZE`、把 600/512/88 改为按常量推导、重跑 Task 3 Step 5 与 Task 4 Step 3；连单个 id 都无法表达时升级 must-fix、单独修订 `readRows` 降级策略 | Task 4 Step 4 第 4 条 |
| 回退动作闭环（PG「结果未知」窗口） | CLOSED：三分支（窗口不存在→回写契约关闭；既有窗口→登记文档化残差 + 自愈路径；比既有更差→解冻、单独修订契约与实现）写明了解冻条件；两类结论都必须回写 PR 描述与契约注释 | Task 4 Step 4 第 5 条 |
| 冻结确认 | CLOSED：本计划经第 1–6 轮评审，最后一轮未提出任何改动 | 本表 |

## 第六轮评审的待评审问题（第六轮评审已回答，存档）

1. **回退动作的闭环核验**：Task 4 Step 4 第 4/5 条新增的「不通过时的回退动作」是否已覆盖第五轮要求的两种场景（512 调整与重跑；PG 实测推翻假设时的单独修订路径），且与冻结边界自洽（解冻条件写明）？（第六轮裁定：**CLOSED**——两种场景均已闭环，PG 分支明确写了解冻条件，冻结边界自洽。）
2. **是否确认冻结**：若无 must-fix，请确认 Plan 1 冻结（实施期只允许两类动作：以当时 `grep`/实测校准行号与常量；按本表写明的「解冻条件」触发单独修订）。（第六轮裁定：**确认冻结**——无残余 must-fix。）

**评审循环关闭（2026-09-30）**：Plan 1 经第 1–6 轮 Codex 评审，最后一轮（第六轮）判定「可开工、可冻结、零残余」。后续动作仅限：实施期以当时 `grep`/实测校准行号与常量；按解冻条件（发版前实测推翻任一外部约束条款，或出现比既有更差的形态）触发单独修订。

## 第三轮评审的待评审问题（第四轮评审已回答，存档）

1. **其余 file:line 引用是否仍有漂移**：第三轮在 Plan 3 发现过时行号；请抽查本计划的关键引用（`PostgresRetrySupport.java:60-72`、`PostgresRelationalStorageAdapter.java:274-331` 与其 `withTransaction` :390-421、`StorageCoordinator.addSuppressedIfDistinct` :278-282、`PostgresMilvusNeo4jStorageProvider.java:261-276`）是否与当前源码一致。（第四轮裁定：全部 CLOSED。）
2. **发版前实测清单的完备性**：Task 4 的交付清单（含代理连接「COMMIT 后断连」的未知结果窗口实测）是否覆盖了本次契约的全部边界外条款——即除「结果未知」外，是否还有别的条款应以实测而非默认信任收尾？（第四轮裁定：CLOSED；「结果未知」窗口保留为发版前实测项。）

## 第二轮评审的待评审问题（第三轮评审已回答，存档）

1. **多 payload 列表协议是否足够简单**：上界 = 单次 `writeAtomically` 内的重试次数（`PostgresRetrySupport.MAX_ATTEMPTS = 3`），故列表长度 ≤ 3；是否需要把「实现可以假定 payload 数量有界」写进契约，还是保持不承诺？（第三轮裁定：不写死上界。）
2. **「结果未知」条款的强度**：Task 1 Step 3 已写「不得对结果未知重试」。现有 `PostgresRetrySupport.isTransientFailure` 只认明确回滚类 SQLState——请复核这条契约与实现一致，且不需要额外代码即成立。（第三轮裁定：一致，无需额外代码。）
3. **`EMPTY_PRE_IMAGE` 身份比较**：单进程内常量 + 适配器实例成对使用（SPI 已写「只能回传给产生它的同一适配器实例」），身份比较是否足够；是否需要同时覆盖「同类型不同实例」的防御。（第三轮裁定：足够。）

## 第一轮评审裁定与落实（2026-09-30）

| 第一轮意见 | 裁定 | 落实位置 |
| --- | --- | --- |
| must-fix：relational 回滚未写成 SPI 契约 | 采纳 | Task 1 Step 3 新增 `RelationalStorageAdapter.writeInTransaction` 契约注释；Task 1 Step 1 新增 `keepsRelationalStateWhenGraphApplyFails`；File Map 增加该文件 |
| must-fix：`PreImageAccumulator` fallback 状态机未定义全 | 采纳 | Task 1 Step 4 改为状态机表格（SCOPED / FALLBACK × 载荷 × 补偿动作 × 后续行为）；新增 `supportsScopedGraphWithFallbackVector` 用例；Task 4 Step 1 写死「必须返回 `Optional.of(EMPTY_PRE_IMAGE)`」 |
| must-fix：批量 apply 部分成功仍无测试 | 采纳 | Task 1 Step 1 新增 `restoresExactPreImageAfterPartialBatchApply`（含 `failOnNthWrite(n)` 替身），所有补偿断言改为比较最终存储状态 |
| must-fix：跨 retry 写集合变化无验证 | 采纳 | Task 1 Step 1 新增 `keepsFirstPreImageWhenRetryWidensTheWriteSet` / `...ChangesRelationAndVectorSets`；Task 1 Step 4 第 4 条写死重试语义 |
| should-fix：Neo4j 实体先于关系需反例测试 | 采纳 | Task 2 Step 1 第 4 条新增 `restoresExactPreImageForSharedEndpointsAndHoles`（含畸形输入的行为断言） |
| should-fix：Milvus 分批未落地 | 采纳 | Task 3 Step 1/3 新增 `READ_ROWS_ID_BATCH_SIZE = 512` 及其推导、去重/保序/空输入语义与 3 个用例；Task 4 Step 4 增加发版前按目标 Milvus 版本复核的条目 |
| nit：「不改变任何对外 API 语义」不准确 | 采纳 | Goal 改为「保持既有适配器实现兼容（SPI 只新增 default 方法）」 |
| 问题 1：opaque `PreImage` 是否接受 | 接受，**但必须做类型校验**而非裸 cast | Task 1 Step 3 契约新增类型校验条款；Task 2 Step 3 / Task 3 Step 4 的实现草图改为 `instanceof` 校验 + `IllegalArgumentException` |
| 问题 5：是否删除 relational `captureSnapshot` | **不删**——`restore(Snapshot)` 仍需要 | Scope Guardrails 新增条目；Task 4 Step 2 新增 `restorePathStillUsesRelationalSnapshotCapability` |
| 问题 6：1024 阈值是否合理 | 不凭经验定值 | 改为 512 + 推导 + 测试 + 发版前实测复核（见上） |

## 第一轮评审的待评审问题（第二轮评审已回答，存档）

1. **`writeInTransaction` 契约与既有实现是否一致**：`PostgresRelationalStorageAdapter.writeInTransaction`（:274-332）经 `PostgresRetrySupport`（:17-51）重试时，是否真的满足「前一次尝试的写入不得对下一次可见」？请核 `PostgresRetrySupport` 的重试边界（是否只在连接级瞬时错误上重试、是否可能在外层已提交后重试）。
2. **`applied` 前置位 + 幂等回滚**：现设计承认「未真正写入却回滚」的窗口，依赖回滚幂等。请确认 `Neo4jGraphStorageAdapter.restorePreImage`（save 前像值 / delete 缺席 id）与 Milvus 侧的 `writeRows`+`deleteIds` 组合在**重复调用**下都幂等，且不会因为「先删后写」的顺序误删前像中存在的行。
3. **`EMPTY_PRE_IMAGE` 的判定边界**：`PostgresVectorStorageAdapter` 返回 `Optional.of(EMPTY_PRE_IMAGE)` 表示「scoped 但无事可做」。是否有更不易误用的表达（例如状态机额外允许 `Optional.of(NO_OP)` 显式语义），还是当前 `EMPTY_PRE_IMAGE` 常量足够？
4. **分批常量放哪里**：`READ_ROWS_ID_BATCH_SIZE` 放在 `MilvusVectorStore` 是否合适，还是应该进 `MilvusVectorStoreConfig`（可配置）；在 Milvus 3.x 上 `pk_id in [...]` 的表达式长度限制是否有公开数字可直接引用以替代「发版前实测」。
5. **`supportsScopedGraphWithFallbackVector` 的语义是否值得支持**：允许「一侧 scoped、一侧 fallback」增加了状态机复杂度（每个适配器一个状态槽 + 组合补偿）；是否应该简化为「任一侧不支持就整体退回全量」？当前选择支持混合，理由是第三方适配器可以局部收益。

## 跨计划顺序（第一轮评审跨计划问题 2 的裁定）

**Plan 1（本计划：scoped commit compensation）→ Plan 3（upstream shadow GMP capabilities）→ Plan 2（document-scoped task concurrency）。**

- 本计划先行：并发与取消都会放大 `writeAtomically` 内的补偿成本（3 次/文档的全量捕获 + 失败时的全量 `DETACH DELETE` 重建），先收敛范围再放开并发。
- Plan 3 第二：删除 aiplatform 主线的两个 shadow（`LightRag` / `GraphMaterializationPipeline`）+ 上 CI 扫描；Plan 2 随后落地时 Task 4 Step 2 只剩「升版本 + 打开旋钮 + 跑通扫描」。
- 例外（Plan 2 被迫先行）：Plan 2 按它的 Step 2(a2) 先同步一次 shadow，Plan 3 落地时删除；两个计划的「同步」步骤不得各做一半。
- **Plan 1 与另外两份计划没有文件交集**（本计划只改存储层）；Plan 3 与 Plan 2 共享 `LightRag.java`，两者的编辑必须串行（后落地者对 `LightRag.java` 做一次 rebase 并重跑各自的 API 契约测试）。
- 本计划与 `LightRag.java` 无交集，因此不受上述串行约束影响；`lightrag.version` 的升级时机由本计划自身决定，但若与 Plan 2 同批发布，须遵守其 Step 2(a) 的前置判定。
