# Java LightRAG Starter Reuse Application DataSource Design

## Context

`lightrag-spring-boot-starter` 在 `POSTGRES` 和 `POSTGRES_NEO4J` 模式下，当前会根据
`lightrag.storage.postgres.jdbc-url/username/password` 自己创建内部 Hikari 连接池。
这导致 LightRAG 的 PostgreSQL 存储虽然可以连到和主应用相同的数据库实例，但不会复用主应用
Spring 容器里的 `DataSource` Bean。

用户希望 starter 在 Spring Boot 场景下优先复用主应用 `DataSource`，让连接池、事务资源、
连接监控和数据库接入策略与主应用保持一致。

## Goal

在 starter 的 PostgreSQL 存储装配中，默认优先复用 Spring 应用的 `DataSource` Bean；
若应用未提供 `DataSource`，则回退到现有 `lightrag.storage.postgres.*` 独立 JDBC 配置。

## Non-Goals

- 不修改纯 SDK 场景下 `PostgresStorageProvider` 的现有用法
- 不改变 `workspace_id` 列隔离模型
- 不让 LightRAG 自动复用主应用的 JPA/MyBatis 事务管理语义
- 不新增复杂开关；优先使用“有就复用、没有就回退”的默认策略

## Recommended Approach

### Approach A: 优先复用 Spring `DataSource`，缺省回退独立配置

这是推荐方案。

- `LightRagAutoConfiguration` 与 `SpringWorkspaceStorageProvider` 注入可选 `DataSource`
- 若存在 `DataSource` Bean，则构造 `PostgresStorageProvider(DataSource, PostgresStorageConfig, SnapshotStore, workspaceId)`
- 若不存在，则继续使用当前基于 JDBC 参数创建内部连接池的构造路径
- `schema`、`table-prefix`、`vector-dimensions` 仍从 `lightrag.storage.postgres.*` 读取

优点：

- 与 Spring Boot 常见预期一致
- 对现有 starter 用户兼容性最好
- 不影响非 Spring / 非 starter 场景

### Approach B: 强制必须复用 Spring `DataSource`

- starter 中只要使用 PostgreSQL，就必须有主应用 `DataSource`
- `jdbc-url/username/password` 回退逻辑删除

不推荐，因为会破坏现有 starter 使用方式。

### Approach C: 新增显式开关控制是否复用

- 比如 `lightrag.storage.postgres.reuse-application-datasource=true`

不推荐，因为会增加配置复杂度，而默认行为已经足够清晰。

## Final Design

采用 Approach A。

### 1. `PostgresStorageProvider` 扩展为双入口

保留现有构造：

- `PostgresStorageProvider(PostgresStorageConfig, SnapshotStore)`
- `PostgresStorageProvider(PostgresStorageConfig, SnapshotStore, String workspaceId)`

新增外部 `DataSource` 构造：

- `PostgresStorageProvider(DataSource, PostgresStorageConfig, SnapshotStore)`
- `PostgresStorageProvider(DataSource, PostgresStorageConfig, SnapshotStore, String workspaceId)`

内部统一持有：

- `dataSource`
- `lockDataSource`
- `ownsDataSource`
- `ownsLockDataSource`

外部 `DataSource` 模式下：

- `dataSource = applicationDataSource`
- `lockDataSource = applicationDataSource`
- `owns* = false`

内部建池模式下：

- 继续创建两个 Hikari 数据源
- `owns* = true`

### 2. `close()` 的资源所有权边界

`PostgresStorageProvider.close()` 只能关闭自己创建的连接池，不能关闭从 Spring 注入的主应用
`DataSource`。这点必须通过测试锁死，避免误关主库连接池。

### 3. Starter 装配逻辑

`LightRagAutoConfiguration.storageProvider(...)` 改为注入 `ObjectProvider<DataSource>`：

- 若存在 `DataSource`，PostgreSQL 存储优先复用
- 若不存在，再根据 `lightrag.storage.postgres.jdbc-url/username/password` 构造

`SpringWorkspaceStorageProvider` 也注入可选 `DataSource`：

- `POSTGRES` 模式下多 workspace scoped provider 共用同一个应用 `DataSource`
- `POSTGRES_NEO4J` 模式下其 PostgreSQL 部分也共用同一个应用 `DataSource`

### 4. 保留的 PostgreSQL 配置

即使复用应用 `DataSource`，以下配置仍然保留并继续由 LightRAG 自己消费：

- `lightrag.storage.postgres.schema`
- `lightrag.storage.postgres.table-prefix`
- `lightrag.storage.postgres.vector-dimensions`

原因是这些是 LightRAG 自身表结构与命名空间配置，不属于主应用通用连接配置。

### 5. 测试策略

新增/调整 starter 自动配置测试：

- 有 `DataSource` Bean 时，starter 复用它
- 无 `DataSource` Bean 时，starter 继续支持独立 JDBC 配置
- workspace provider 在多个 workspace 下仍复用同一个应用 `DataSource`

新增/调整 core 测试：

- 外部 `DataSource` 构造路径可用
- `close()` 不会关闭外部 `DataSource`

## Risks

- 若错误关闭外部 `DataSource`，会影响整个主应用数据库访问
- 若 starter 只改了默认 `StorageProvider` 路径，漏掉 `WorkspaceStorageProvider`，行为会不一致
- `POSTGRES_NEO4J` 的 PostgreSQL 部分若未同步改造，会出现连接来源分裂

## Success Criteria

- Spring Boot 主应用存在 `DataSource` 时，LightRAG PostgreSQL 存储默认复用它
- 应用没有 `DataSource` 时，当前 PostgreSQL 配置方式仍可工作
- 多 workspace 仍然共用主应用连接池，只在数据行层用 `workspace_id` 隔离
- `close()` 不会误关闭主应用 `DataSource`
