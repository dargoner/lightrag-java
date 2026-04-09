# Java LightRAG SDK Task Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Java LightRAG 增加 SDK 原生异步任务体系、阶段级构图监控，并让 demo 复用这套能力。

**Architecture:** 在 `lightrag-core` 引入 `task` 子包和持久化 store，把任务提交/查询收敛到 `LightRag`。`IndexingPipeline` 与 `DeletionPipeline` 通过阶段上报器发布结构化进度。内存、Postgres、MySQL 存储层补齐任务表和阶段表，demo controller 改为读取 SDK 任务快照。

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Spring Boot Test, PostgreSQL/MySQL storage adapters

---

### Task 1: 定义任务 API 与失败测试

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightrag/task/TaskServiceApiTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`

- [ ] 写失败测试，覆盖 `submitIngest` / `submitRebuild` / `getTask` / `listTasks` 基本语义
- [ ] 运行针对性测试，确认因 API 缺失而失败
- [ ] 实现最小 API 骨架与模型类型
- [ ] 重新运行测试，确认通过

### Task 2: 引入任务与阶段存储接口

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/TaskStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/TaskStageStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/StorageProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/AtomicStorageProvider.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/InMemoryTaskStoreTest.java`

- [ ] 先写内存 store 的失败测试
- [ ] 扩展 storage 抽象并实现内存 store
- [ ] 跑新测试确认通过

### Task 3: 实现任务执行器与阶段上报

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/task/*`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/DeletionPipeline.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/task/TaskExecutionIntegrationTest.java`

- [ ] 先写失败测试，覆盖 ingest/rebuild 阶段轨迹
- [ ] 实现 runtime、reporter、取消与 interrupted-task 回收
- [ ] 跑测试确认通过

### Task 4: 补齐 Postgres/MySQL 存储实现

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresTaskStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresTaskStageStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlTaskStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlTaskStageStore.java`
- Modify: 各内置 storage provider 与 schema manager
- Create: 对应 store/provider 测试

- [ ] 先写 PostgreSQL 失败测试
- [ ] 实现 PG/MySQL store 与 provider 接线
- [ ] 跑存储测试确认通过

### Task 5: demo 复用 SDK 任务接口

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/UploadController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java`
- Modify: demo 相关测试

- [ ] 先写/调整失败测试，锁定 job=task 映射与阶段返回
- [ ] 改 demo service 为 SDK 任务包装层
- [ ] 跑 demo 测试确认通过

### Task 6: 全量验证与集成

**Files:**
- Modify as needed based on failures

- [ ] 运行 `:lightrag-core:test --rerun-tasks`
- [ ] 运行 `:lightrag-spring-boot-demo:test --rerun-tasks`
- [ ] 若失败，按失败原因补测试和修复
- [ ] 验证 `git status` 干净后提交并 push
