# Java LightRAG Multi-Workspace Shared Neo4j Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `lightrag-java` 改造成“一个 `LightRag` 实例服务多个 workspace”，并让 PostgreSQL 继续按表前缀隔离、Neo4j 改成共享 database 下的 `workspaceId` 逻辑隔离。

**Architecture:** 先在 `core` 引入显式 `workspaceId` API、`WorkspaceScope` 和 `WorkspaceStorageProvider`，让每次调用在运行时解析出 workspace-scoped `AtomicStorageProvider`。随后把 `postgres-neo4j` 存储改成“每个 workspace 独立 PostgreSQL provider + 共享 Neo4j database 的 workspace-scoped graph store”，最后收敛 Spring starter 和 demo 到单个 `LightRag` bean 的新调用模型。

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Spring Boot 3.3, PostgreSQL JDBC, Neo4j Java Driver, Testcontainers PostgreSQL, Testcontainers Neo4j

---

## Scope Guardrails

- 这是一次 **breaking change**；不要保留无 `workspaceId` 的 `LightRag` 数据操作 API 作为静默兼容别名。
- PostgreSQL 本期 **不** 改成共享表 + `workspaceId` 列；继续沿用现有 `tablePrefix` 隔离。
- Neo4j 本期必须保证：
  - 同一个 database 内多个 workspace 严格隔离
  - `restore` / rollback / snapshot 只作用当前 workspace
- 不要把 `workspaceId` 扩散到 `GraphStore` / `VectorStore` / `DocumentStore` 的 public SPI 方法签名里。
- `loadFromSnapshot(path)` 只保留给 legacy 单 workspace `storage(...)` 路径；多 workspace `workspaceStorage(...)` 模式改用 runtime snapshot API。
- 未来 Milvus 兼容只要求保留 provider 装配边界和 namespace 抽象，不在本期实现新的 vector backend。

## Prerequisites

- Docker 必须可用，用于 `PostgresNeo4jStorageProviderTest` 与 Neo4j 集成测试。
- 现有 workspace/demos 改造跨度较大，提交粒度必须小，避免一个提交同时混入 core + starter + demo + docs 的未验证改动。
- 开始实现前，先确认当前分支上 `./gradlew :lightrag-core:test` 至少能在本机跑通，避免把已有失败误判成新改动问题。

## File Structure

### Core API / Storage

- Create:
  - `lightrag-core/src/main/java/io/github/lightragjava/api/WorkspaceScope.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/storage/WorkspaceStorageProvider.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/storage/FixedWorkspaceStorageProvider.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagWorkspaceTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`
- Modify:
  - `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/indexing/StorageSnapshots.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStore.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/E2ELightRagTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/ParentChildChunkIntegrationTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationService.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasEvaluationService.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/evaluation/OfflineRetrievalAccuracyTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/evaluation/ParentChildOfflineRetrievalEvaluationTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java`

### Spring Starter / Demo

- Create:
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/SpringWorkspaceStorageProvider.java`
- Modify:
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
  - `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerRoutingTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`
- Delete:
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java`

### Docs

- Modify:
  - `README.md`

## Chunk 1: Core Workspace Primitives And Builder Contract

### Task 1: 引入 `WorkspaceScope`、`WorkspaceStorageProvider` 和 builder 入口

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/api/WorkspaceScope.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/storage/WorkspaceStorageProvider.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/storage/FixedWorkspaceStorageProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: 先写 builder 的 failing tests**

在 `LightRagBuilderTest` 增加至少 4 个用例：

```java
@Test
void rejectsConfiguringStorageAndWorkspaceStorageTogether() { ... }

@Test
void rejectsLoadFromSnapshotWhenWorkspaceStorageIsConfigured() { ... }

@Test
void buildsWithWorkspaceStorageProvider() { ... }

@Test
void closesWorkspaceStorageProviderWhenLightRagIsClosed() { ... }

@Test
void rejectsBlankWorkspaceScopeValues() { ... }
```

关键断言：
- `storage(...)` 和 `workspaceStorage(...)` 同时配置时抛 `IllegalStateException`
- `workspaceStorage(...)` 模式下调用 `loadFromSnapshot(...)` 抛 `IllegalStateException`
- `build()` 成功后 `LightRag` 持有 `WorkspaceStorageProvider`
- `LightRag.close()` 只关闭 `WorkspaceStorageProvider` 一次，不在每次 API 调用后关闭 scoped provider
- `WorkspaceScope` 拒绝 `null`、空串和纯空白 `workspaceId`

- [ ] **Step 2: 运行 builder 测试，确认先红**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: FAIL，提示缺少 `workspaceStorage(...)` builder API 或断言不成立

- [ ] **Step 3: 写最小 workspace 原语**

实现：

```java
public record WorkspaceScope(String workspaceId) { ... }

public interface WorkspaceStorageProvider extends AutoCloseable {
    AtomicStorageProvider forWorkspace(WorkspaceScope scope);
}
```

再补一个 legacy 适配器：

```java
public final class FixedWorkspaceStorageProvider implements WorkspaceStorageProvider {
    private final AtomicStorageProvider delegate;
    public AtomicStorageProvider forWorkspace(WorkspaceScope scope) { return delegate; }
}
```

- [ ] **Step 4: 更新 builder 和 config**

要求：
- `LightRagBuilder` 新增 `workspaceStorage(WorkspaceStorageProvider provider)`
- `LightRagConfig` 从“固定 storage/documentStatus/snapshotPath”模式改成“持有 `WorkspaceStorageProvider` + legacy snapshot base path + models”
- legacy `storage(StorageProvider)` 路径内部自动包成 `FixedWorkspaceStorageProvider`
- builder 保证 `storage(...)` / `workspaceStorage(...)` 二选一
- `LightRag` 实现 `AutoCloseable`，并在 `close()` 中关闭 `WorkspaceStorageProvider`

- [ ] **Step 5: 重新运行 builder 测试转绿**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add lightrag-core/src/main/java/io/github/lightragjava/api/WorkspaceScope.java \
  lightrag-core/src/main/java/io/github/lightragjava/storage/WorkspaceStorageProvider.java \
  lightrag-core/src/main/java/io/github/lightragjava/storage/FixedWorkspaceStorageProvider.java \
  lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java \
  lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java \
  lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: add workspace storage builder contract"
```

## Chunk 2: Multi-Workspace `LightRag` Runtime API

### Task 2: 把 `LightRag` 改成显式 workspace 的运行时 API

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagWorkspaceTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StorageSnapshots.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/ParentChildChunkIntegrationTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationService.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasEvaluationService.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/evaluation/OfflineRetrievalAccuracyTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/evaluation/ParentChildOfflineRetrievalEvaluationTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java`

- [ ] **Step 1: 先写 runtime multi-workspace failing tests**

在新建的 `LightRagWorkspaceTest` 里覆盖：

```java
@Test
void oneLightRagInstanceRoutesDifferentCallsToDifferentWorkspaceProviders() { ... }

@Test
void saveSnapshotAndRestoreSnapshotOperateOnTheTargetWorkspaceOnly() { ... }

@Test
void repeatedWorkspaceResolutionReusesTheSameLogicalProviderInstance() { ... }
```

测试用假的 `WorkspaceStorageProvider`：
- `forWorkspace("alpha")` 返回一套 in-memory provider
- `forWorkspace("beta")` 返回另一套 in-memory provider

关键断言：
- `rag.ingest("alpha", ...)` 后只影响 alpha
- `rag.query("beta", ...)` 不会读到 alpha 数据
- `saveSnapshot("alpha", path)` / `restoreSnapshot("alpha", path)` 不影响 beta
- 同一个 workspace 连续两次调用会命中同一个 cached scoped provider，而不是每次新建

- [ ] **Step 2: 运行新测试，确认先红**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagWorkspaceTest`
Expected: FAIL，提示 `LightRag` 缺少显式 `workspaceId` 方法

- [ ] **Step 3: 在 `LightRag` 上实现新 public API**

新增并迁移所有数据操作方法签名：

```java
public void ingest(String workspaceId, List<Document> documents)
public void ingestSources(String workspaceId, List<RawDocumentSource> sources, DocumentIngestOptions options)
public QueryResult query(String workspaceId, QueryRequest request)
public GraphEntity createEntity(String workspaceId, CreateEntityRequest request)
...
public void saveSnapshot(String workspaceId, Path path)
public void restoreSnapshot(String workspaceId, Path path)
```

实现要求：
- 每个 public 方法第一步都构造 `WorkspaceScope`
- 每次调用都通过 `config.workspaceStorageProvider().forWorkspace(scope)` 取 scoped provider
- 不在 `LightRag` 实例字段里缓存某个 workspace 的 provider

- [ ] **Step 4: 最小化内部重构，按调用组装执行器**

不要修改 `GraphStore` / `VectorStore` SPI。

在 `LightRag` 内部按调用临时组装：
- `IndexingPipeline`
- `DeletionPipeline`
- `GraphManagementPipeline`
- query strategies + `QueryEngine`

让这些内部组件继续消费普通 `AtomicStorageProvider`，只是这个 provider 变成“当前 workspace 的 scoped provider”。

- [ ] **Step 5: 实现 runtime snapshot API**

`saveSnapshot(...)` 建议直接复用 `StorageSnapshots.capture(provider)`：

```java
var provider = workspaceProvider.forWorkspace(scope);
provider.snapshotStore().save(path, StorageSnapshots.capture(provider));
```

`restoreSnapshot(...)`：

```java
var snapshot = provider.snapshotStore().load(path);
provider.restore(snapshot);
```

- [ ] **Step 6: 把 core 内所有 `LightRag` 调用点迁移到显式 workspace**

统一加常量：

```java
private static final String WORKSPACE = "default";
```

需要迁移：
- `E2ELightRagTest`
- `ParentChildChunkIntegrationTest`
- evaluation services / tests

目标是先让编译恢复，再逐步修断言。

- [ ] **Step 7: 运行 core 的 API/E2E 目标测试**

Run:
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagWorkspaceTest`
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java \
  lightrag-core/src/main/java/io/github/lightragjava/indexing/StorageSnapshots.java \
  lightrag-core/src/test/java/io/github/lightragjava/api/LightRagWorkspaceTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/E2ELightRagTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/ParentChildChunkIntegrationTest.java \
  lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationService.java \
  lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasEvaluationService.java \
  lightrag-core/src/test/java/io/github/lightragjava/evaluation/OfflineRetrievalAccuracyTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/evaluation/ParentChildOfflineRetrievalEvaluationTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java
git commit -m "feat: add explicit workspace runtime APIs"
```

## Chunk 3: Shared Neo4j Multi-Workspace Isolation

### Task 3: 实现 workspace-scoped Neo4j graph store

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStore.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java`

- [ ] **Step 1: 先写 workspace 隔离 failing tests**

在 `WorkspaceScopedNeo4jGraphStoreTest` 增加：
- `saveEntity` / `loadEntity` 只命中本 workspace
- `saveRelation` 创建 placeholder 节点时不会串 workspace
- `allEntities` / `allRelations` / `findRelations` 只返回当前 workspace
- `restore(...)` 只清空和重建当前 workspace

至少包含一个双 workspace 用例：

```java
alpha.saveEntity(entity("entity-1", "Alice"));
beta.saveEntity(entity("entity-1", "Bob"));
assertThat(alpha.loadEntity("entity-1")).hasValueSatisfying(...Alice...);
assertThat(beta.loadEntity("entity-1")).hasValueSatisfying(...Bob...);
```

- [ ] **Step 2: 运行 Neo4j workspace 测试，确认先红**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest`
Expected: FAIL，因为 `WorkspaceScopedNeo4jGraphStore` 尚不存在

- [ ] **Step 3: 实现 workspace-scoped store**

关键 Cypher 约束：

```cypher
MERGE (entity:Entity {workspaceId: $workspaceId, id: $id})
...
MATCH (entity:Entity {workspaceId: $workspaceId, id: $id})
...
MATCH (node:Entity {workspaceId: $workspaceId})
DETACH DELETE node
```

要求：
- 每个查询都带 `workspaceId`
- relation 唯一性支持 `(workspaceId, id)` 语义
- 若 Neo4j 关系约束不好做，允许内部引入 `scopedId`
- autosnapshot 路径派生不要写在 graph store；仍由 workspace-provider 装配层负责

- [ ] **Step 4: 如保留 `Neo4jGraphStore`，让它退化成默认 workspace wrapper**

为了降低外部改动面，可以让旧 `Neo4jGraphStore` 作为向后兼容封装：
- 内部固定 `WorkspaceScope("default")`
- 真实逻辑委托给 `WorkspaceScopedNeo4jGraphStore`

不要在新实现里继续保留“全库 `DETACH DELETE`”路径。

- [ ] **Step 5: 重新运行 Neo4j graph 测试**

Run:
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest`
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.Neo4jGraphStoreTest`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java \
  lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStore.java \
  lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/WorkspaceScopedNeo4jGraphStoreTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/Neo4jGraphStoreTest.java
git commit -m "feat: add workspace scoped neo4j graph store"
```

### Task 4: 让 `PostgresNeo4jStorageProvider` 变成 workspace-local 补偿模型

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java`

- [ ] **Step 1: 先写 provider 的 workspace rollback failing tests**

在 `PostgresNeo4jStorageProviderTest` 增加双 workspace 用例：
- workspace `alpha` 写成功
- workspace `beta` 投影失败并触发回滚
- 断言 `alpha` 的 Postgres/Neo4j 数据不受影响

还要补一个锁粒度用例：
- 两个不同 workspace 的写入不会因为一个 provider-wide lock 全阻塞

再补一个 autosnapshot 用例：
- workspace `alpha` 和 `beta` 共用同一个 autosnapshot base path 配置
- 各自写入后产生不同的派生 snapshot 文件
- `alpha` 的 autosnapshot 内容不会覆盖 `beta`

- [ ] **Step 2: 运行 provider 测试，确认先红**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest`
Expected: FAIL，表明当前实现还是 provider-wide lock 或全库图快照

- [ ] **Step 3: 重构 mixed provider**

实现要求：
- `PostgresNeo4jStorageProvider` 只操作一个 workspace-scoped PostgreSQL provider + 一个 workspace-scoped Neo4j graph store
- Neo4j `captureSnapshot()` / `restore(...)` 只作用当前 workspace
- 锁不再是全 JVM 的一个实例字段；锁归属上移到 workspace-provider 缓存层，或由构造参数注入每-workspace 共享锁
- 失败补偿只回滚当前 workspace
- autosnapshot base path 的派生规则由 workspace-provider 层统一处理，provider 只消费已经解析好的 workspace-specific path

- [ ] **Step 4: 重新运行 provider 测试**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java \
  lightrag-core/src/test/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProviderTest.java
git commit -m "feat: scope postgres neo4j rollback by workspace"
```

## Chunk 4: Spring Starter Workspace Assembly

### Task 5: 用 starter 组装 `WorkspaceStorageProvider` 并删除 `WorkspaceLightRagFactory`

**Files:**
- Create: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/SpringWorkspaceStorageProvider.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Delete: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java`

- [ ] **Step 1: 先写 starter wiring failing tests**

在 `LightRagAutoConfigurationTest` 把断言改成：
- context 中存在 `WorkspaceStorageProvider`
- context 中只有一个 `LightRag`
- `lightrag.storage.type=postgres-neo4j` 时，`LightRag` 通过 `workspaceStorage(...)` 构建
- 非默认 workspace 能成功解析 provider，不再抛 `workspace isolation does not support storage type POSTGRES_NEO4J`
- 两个 workspace 使用同一个 autosnapshot base path 时，会得到不同的派生 snapshot 文件名

- [ ] **Step 2: 运行 starter 测试，确认先红**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`
Expected: FAIL，因为当前 starter 仍产出 `WorkspaceLightRagFactory`

- [ ] **Step 3: 实现 `SpringWorkspaceStorageProvider`**

职责：
- 维护 `workspaceId -> AtomicStorageProvider` 缓存
- 维护 `workspaceId -> ReentrantReadWriteLock` 缓存
- 按 workspace 派生：
  - PostgreSQL `tablePrefix`
  - workspace snapshot path
  - `WorkspaceScopedNeo4jGraphStore`
- 在 `close()` 时关闭所有缓存 provider

建议核心方法：

```java
public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
    return cache.computeIfAbsent(scope.workspaceId(), this::createProvider);
}
```

这里必须锁死两个实现约束：
- 同一 workspace 重复 `forWorkspace(...)` 返回同一逻辑 provider
- `LightRag` 生命周期结束时关闭 `WorkspaceStorageProvider`，再由它统一释放缓存 provider

- [ ] **Step 4: 更新 auto-configuration**

`LightRagAutoConfiguration` 改成：
- 提供 `WorkspaceStorageProvider` bean
- `LightRag` bean 用 `.workspaceStorage(workspaceStorageProvider)` 构建
- legacy `StorageProvider` bean 只保留给明确单 workspace 场景或内部复用，不再作为 starter 主运行时入口

- [ ] **Step 5: 删除 `WorkspaceLightRagFactory` 并修测试**

处理：
- 删除 main 类
- 更新所有 starter 测试断言与 imports
- 若 demo 编译因此中断，放到下一个任务统一收口

- [ ] **Step 6: 运行 starter 全量测试**

Run: `./gradlew :lightrag-spring-boot-starter:test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/SpringWorkspaceStorageProvider.java \
  lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java \
  lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java \
  lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java
git rm lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java
git commit -m "feat: wire starter through workspace storage provider"
```

## Chunk 5: Demo Migration To The New `LightRag` API

### Task 6: 把 demo controller 和作业服务改成“单 `LightRag` + 显式 workspaceId”

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerRoutingTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 先改一个最小 failing test 锁定新调用方式**

从 `QueryControllerTest` 开始，把 mock 改成单个 `LightRag`：

```java
verify(lightRag).query(eq("alpha"), requestCaptor.capture());
```

不要再 mock `WorkspaceLightRagFactory`。

- [ ] **Step 2: 运行 query controller 测试，确认先红**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
Expected: FAIL，因为 controller 还在调用 `factory.get(...).query(...)`

- [ ] **Step 3: 迁移 Query / StreamingQuery controller**

目标形态：

```java
var workspaceId = workspaceResolver.resolve(request);
var result = lightRag.query(workspaceId, queryRequest);
```

同理 `StreamingQueryController` 改成：

```java
queryStreamService.stream(lightRag.query(workspaceId, queryRequest))
```

- [ ] **Step 4: 跑 query 相关测试转绿**

Run:
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.StreamingQueryControllerTest`

Expected: PASS

- [ ] **Step 5: 迁移 graph/document-status/job 服务**

把这些路径统一改成：
- `lightRag.createEntity(workspaceId, request)`
- `lightRag.deleteByDocumentId(workspaceId, documentId)`
- `lightRag.getDocumentStatus(workspaceId, documentId)`
- `lightRag.listDocumentStatuses(workspaceId)`
- `lightRag.ingest(workspaceId, documents)`
- `lightRag.ingestSources(workspaceId, sources, options)`

`IngestJobService` 保留现有 job 按 workspace 过滤逻辑，但底层执行不再通过 factory 取 `LightRag`。

- [ ] **Step 6: 运行 graph/status/job 相关测试**

Run:
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.GraphControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerRoutingTest`

Expected: PASS

- [ ] **Step 7: 跑 demo 端到端 smoke**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerRoutingTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java
git commit -m "feat: migrate demo to explicit workspace lightrag api"
```

## Chunk 6: Documentation And Final Verification

### Task 7: 更新文档并跑回归

**Files:**
- Modify: `README.md`
- Verify: `lightrag-core/src/test/java/io/github/lightragjava/**`
- Verify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/**`
- Verify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/**`

- [ ] **Step 1: 更新 README**

至少补 4 部分：
- 新的 `LightRag` public API 需要显式 `workspaceId`
- `workspaceStorage(...)` builder 用法
- runtime snapshot API：`saveSnapshot(workspaceId, path)` / `restoreSnapshot(workspaceId, path)`
- PostgreSQL 与 Neo4j 的隔离策略差异说明

- [ ] **Step 2: 跑 core 关键回归**

Run:
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagWorkspaceTest`
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest`
- `./gradlew :lightrag-core:test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS

- [ ] **Step 3: 跑 starter/demo 回归**

Run:
- `./gradlew :lightrag-spring-boot-starter:test`
- `./gradlew :lightrag-spring-boot-demo:test`

Expected: PASS

- [ ] **Step 4: 跑最终全量测试**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add README.md
git commit -m "docs: describe explicit workspace runtime"
```

## Follow-Up Notes

- `WorkspaceStorageProvider` 的缓存淘汰策略先不做；只要保证生命周期、关闭职责和锁归属稳定即可。
- 若后续接 Milvus，优先在 provider 装配层替换 vector backend，不要在本次实现里把 `workspaceId` 加到 `VectorStore` public SPI。
