# Java LightRAG SDK Task Monitoring Design

## Goal

为 `lightrag-core` 增加原生异步任务体系，支持 `ingest` 与 `rebuild` 的持久化任务管理、阶段级监控与查询，并让 demo 层复用 SDK 任务接口而不是继续维护进程内 job 状态。

## Scope

本次范围：

- 新增 SDK 原生任务提交与查询接口
- 新增任务、阶段两层持久化模型
- 支持 `INGEST_DOCUMENTS`、`INGEST_SOURCES`、`REBUILD_GRAPH` 三类任务
- 支持阶段监控：`PREPARING`、`PARSING`、`CHUNKING`、`PRIMARY_EXTRACTION`、`REFINEMENT_EXTRACTION`、`GRAPH_ASSEMBLY`、`VECTOR_INDEXING`、`COMMITTING`、`COMPLETED`
- 支持任务取消
- Demo 层 job 接口改为复用 SDK 任务体系

本次不做：

- 分布式 worker / 外部队列
- 重试策略平台化
- 百分比进度估算
- 跨进程恢复运行中的任务，只保证状态持久化和重启后可查询

## Architecture

### 1. API Layer

`LightRag` 新增：

- `submitIngest(...)`
- `submitIngestSources(...)`
- `submitRebuild(...)`
- `getTask(...)`
- `listTasks(...)`
- `cancelTask(...)`

同步接口继续保留，行为不变。

### 2. Task Runtime

新增 `task` 子包，核心组件：

- `TaskService`：统一任务提交、查询、取消
- `TaskExecutor`：基于单机线程池执行任务
- `TaskReporter`：写任务与阶段状态
- `TaskRequestPayload`：封装提交参数，供执行器与取消/查询使用

### 3. Storage

新增两个存储接口：

- `TaskStore`
- `TaskStageStore`

它们加入 `StorageProvider` / `AtomicStorageProvider`，与现有 `DocumentStatusStore` 并列。

职责边界：

- `DocumentStatusStore`：文档结果状态
- `TaskStore`：任务整体生命周期
- `TaskStageStore`：阶段级执行进度

### 4. Pipeline Integration

`IndexingPipeline` 新增可选阶段监听器，在关键阶段写结构化状态：

- 准备文档/解析源文档
- chunking
- primary extraction
- refinement extraction
- graph assembly
- vector indexing
- committing

`DeletionPipeline` 新增公开 `rebuildAllDocuments()`，供 `submitRebuild(...)` 复用，避免通过删除语义侧入。

## Data Model

### Task

字段：

- `taskId`
- `workspaceId`
- `taskType`
- `status`
- `requestedAt`
- `startedAt`
- `finishedAt`
- `summary`
- `errorMessage`
- `cancelRequested`
- `metadata`

### Task Stage

字段：

- `taskId`
- `stage`
- `status`
- `sequence`
- `startedAt`
- `finishedAt`
- `message`
- `errorMessage`

## Persistence Strategy

### In-Memory

增加对应内存 store，供默认和单测使用。

### PostgreSQL

新增两张表：

- `${tablePrefix}task`
- `${tablePrefix}task_stage`

继续使用 workspace 维度隔离。遵守当前 `schema` / `tablePrefix` 规则，不新增 schema fallback 逻辑。

### MySQL

补齐相同能力，保证模块编译与现有多存储 provider 一致。

## Demo Integration

Demo 层保留现有 `/documents/jobs/*` 路由，但内部改为调用 SDK 任务接口。

- `jobId == taskId`
- 列表与详情从 `TaskSnapshot` 转换
- `cancel` 走 SDK
- `retry` 本次保留在 demo 层，通过读取失败任务元数据重新提交对应 SDK 任务

## Failure Semantics

- 任务失败：整体状态 `FAILED`，当前阶段写 `FAILED`
- 任务取消：整体状态 `CANCELLED`，未开始阶段保持 `PENDING`
- 进程重启后发现 `PENDING/RUNNING` 旧任务：在首次读取对应 workspace 时标记为 `FAILED`，错误信息为 `task interrupted before completion`

## Testing

- `lightrag-core`：任务 API、阶段上报、取消、rebuild、内存/PG store
- `lightrag-spring-boot-demo`：控制器路由与返回结构复用 SDK 任务体系
- 回归：`lightrag-core:test` 与 `lightrag-spring-boot-demo:test`
