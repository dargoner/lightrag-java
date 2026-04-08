# Java Storage Assembly Decoupling Design

## Background

当前 SDK 的存储扩展路径仍然以固定组合类为中心，例如：

- `PostgresMilvusNeo4jStorageProvider`
- `MySqlMilvusNeo4jStorageProvider`
- `PostgresNeo4jStorageProvider`

虽然近期已经补充了 `DataSource`、Milvus 客户端、Neo4j `Driver` 的注入入口，但核心编排逻辑仍然分散在这些组合 Provider 内部。每增加一种新的图库、向量库或者关系库存储形态，都容易再次复制一套“关系型基线 + 图投影 + 向量投影 + 恢复逻辑”的组合实现。

这导致几个问题：

1. 存储能力的边界不清晰，关系型基线、图投影、向量投影、恢复编排耦合在同一类中。
2. 新后端扩展成本高，需要新建组合 Provider，而不是只实现一个能力适配器。
3. `LightRagBuilder` 和 Spring Boot Starter 只能消费“成品 Provider”，无法基于能力做稳定装配。

## Goals

本次设计目标：

1. 引入面向能力的存储适配层，而不是继续扩展固定组合 Provider。
2. 保留当前“关系型数据库作为恢复基线”的架构前提。
3. 将跨存储原子写、投影和恢复逻辑收敛到统一协调层。
4. 保持现有公开 API 可兼容迁移，不做一次性破坏式升级。
5. 为后续新增关系库、图库、向量库提供稳定扩展点。

## Non-Goals

本次不做以下事情：

1. 不移除现有组合 Provider 对外公开类。
2. 不改变 `LightRagBuilder.storage(...)` / `workspaceStorage(...)` 现有语义。
3. 不把图库或向量库升级为新的恢复基线。
4. 不在这一阶段重做 Spring Boot Starter 的全部自动装配模型。
5. 不引入分布式事务或两阶段提交。

## Confirmed Architecture Constraint

关系型数据库继续作为恢复中心。

对于 `PostgresMilvusNeo4jStorageProvider` 这一类组合模式，关系型侧承担如下职责：

1. 文档、分块、文档状态等主数据存储。
2. 快照基线与恢复输入。
3. 事务边界与锁协调。
4. schema/bootstrap 生命周期管理。

图库和向量库在此阶段仍视为派生投影，而不是唯一事实源。

## Recommended Architecture

推荐引入以下核心抽象：

### 1. `RelationalStorageAdapter`

职责：

1. 提供 `DocumentStore`、`ChunkStore`、`DocumentStatusStore`、`SnapshotStore` 等关系型基线能力。
2. 提供事务执行入口，作为跨存储写入的主事务边界。
3. 提供 schema/bootstrap、锁管理、workspace 作用域管理。
4. 提供恢复所需的关系型基线快照。

这一层不是简单 CRUD 封装，而是承载“关系型事实源”的能力边界。

### 2. `GraphStorageAdapter`

职责：

1. 提供实体/关系的读写接口。
2. 提供图库快照与恢复接口。
3. 提供图投影应用能力。

图库适配器只关心图侧数据，不管理关系型事务。

### 3. `VectorStorageAdapter`

职责：

1. 提供向量写入、查询、列举、命名空间清理、flush。
2. 提供向量侧快照与恢复入口。
3. 支持混合检索等后端特有扩展能力。

向量适配器只关心向量/检索视图，不负责主事务。

### 4. `StorageCoordinator`

职责：

1. 依赖 `RelationalStorageAdapter`、`GraphStorageAdapter`、`VectorStorageAdapter`。
2. 封装跨存储 `writeAtomically(...)`。
3. 负责 staged graph/vector 视图、投影应用和失败恢复。
4. 统一对外暴露 `AtomicStorageProvider` 兼容行为。

这是新的核心编排层，替代现有组合 Provider 中重复存在的事务与恢复逻辑。

### 5. `StorageAssembly`

职责：

1. 装配关系型、图库、向量适配器。
2. 生成协调后的 `AtomicStorageProvider`。
3. 为 `LightRagBuilder`、Starter 和兼容 facade 提供统一入口。

`StorageAssembly` 是新的装配模型，不直接承载业务存储逻辑。

## Data Flow

统一的数据流建议如下：

1. `StorageCoordinator` 先从 `RelationalStorageAdapter` 获取关系型基线快照。
2. 再从 `GraphStorageAdapter`、`VectorStorageAdapter` 获取外部投影快照。
3. 在 `RelationalStorageAdapter` 的事务边界内执行主写入。
4. 事务成功后，根据 staged 结果向图和向量适配器应用投影。
5. 若投影失败，则按“关系型基线快照 + 外部投影快照”执行恢复。

这样做的意义：

1. 事务一致性仍然围绕关系型事实源构建。
2. 图和向量后端可以被替换，不需要复制恢复编排逻辑。
3. 后续如果要调整图库/向量库为唯一事实源，可以在适配层边界内单独演进，而不需要再重构 Builder 和上层入口。

## API Strategy

本次采用“新架构先落地，旧 API 保持兼容”的策略。

### New API Direction

推荐新增：

```java
var assembly = StorageAssembly.builder()
    .relationalAdapter(...)
    .graphAdapter(...)
    .vectorAdapter(...)
    .build();

LightRag.builder()
    .storage(assembly.toStorageProvider())
    .build();
```

在第一阶段，可以先通过 `assembly.toStorageProvider()` 复用现有 `builder.storage(...)`。

### Compatibility Facades

现有类保持对外存在，但内部改为委托新协调层：

1. `PostgresMilvusNeo4jStorageProvider`
2. `MySqlMilvusNeo4jStorageProvider`
3. `PostgresNeo4jStorageProvider`

这些类后续不再承载核心编排逻辑，只负责：

1. 将现有构造参数转成对应适配器。
2. 构造 `StorageAssembly` / `StorageCoordinator`。
3. 维持现有二进制兼容和调用习惯。

## Initial Implementation Scope

建议第一阶段范围控制如下：

### Stage 1

1. 定义 `RelationalStorageAdapter`、`GraphStorageAdapter`、`VectorStorageAdapter`、`StorageAssembly`、`StorageCoordinator`。
2. 先将 `PostgresMilvusNeo4jStorageProvider` 迁移到新协调层。
3. 同步迁移 `PostgresNeo4jStorageProvider`。
4. 增加 Builder 侧装配入口或 `StorageAssembly.toStorageProvider()` 兼容出口。
5. 补充最小公开 API 测试和恢复行为测试。

### Stage 2

1. 迁移 `MySqlMilvusNeo4jStorageProvider`。
2. 将更多内部 `Projection` 类型替换为正式适配器实现。
3. 评估 Starter 是否增加基于 `StorageAssembly` 的自动装配。

### Stage 3

1. 评估是否标记旧组合 Provider 为 deprecated。
2. 评估是否开放 Builder 直接接受能力适配器。

## Proposed Type Boundaries

推荐接口边界如下：

### `RelationalStorageAdapter`

至少需要提供：

1. `DocumentStore documentStore()`
2. `ChunkStore chunkStore()`
3. `DocumentStatusStore documentStatusStore()`
4. `SnapshotStore snapshotStore()`
5. `SnapshotStore.Snapshot captureSnapshot()`
6. `void restore(SnapshotStore.Snapshot snapshot)`
7. `<T> T writeInTransaction(RelationalWriteOperation<T> operation)`

`GraphStore` 和 `VectorStore` 不应该再隐式包含在关系型适配器里，否则边界会再次模糊。

### `GraphStorageAdapter`

至少需要提供：

1. `Optional<EntityRecord> loadEntity(...)`
2. `Optional<RelationRecord> loadRelation(...)`
3. `List<EntityRecord> allEntities()`
4. `List<RelationRecord> allRelations()`
5. `List<RelationRecord> findRelations(...)`
6. `void apply(...)`
7. `Neo4jGraphSnapshot captureSnapshot()`
8. `void restore(...)`

### `VectorStorageAdapter`

至少需要提供：

1. `void saveAll(...)`
2. `void saveAllEnriched(...)`
3. `List<VectorRecord> list(...)`
4. `List<VectorMatch> search(...)`
5. `void deleteNamespace(...)`
6. `void flushNamespaces(...)`
7. `Map<String, List<VectorRecord>> captureSnapshot(...)`
8. `void restoreSnapshot(...)`

## Migration of Existing Providers

### `PostgresMilvusNeo4jStorageProvider`

重构后建议拆成：

1. `PostgresRelationalStorageAdapter`
2. `Neo4jGraphStorageAdapter`
3. `MilvusVectorStorageAdapter`
4. facade `PostgresMilvusNeo4jStorageProvider`

该 facade 内部只负责把现有构造器参数映射到新适配器。

### `PostgresNeo4jStorageProvider`

可迁移为：

1. `PostgresRelationalStorageAdapter`
2. `Neo4jGraphStorageAdapter`
3. `PostgresVectorStore` 仍作为关系型内建向量能力的一部分，或者通过 no-op 外部向量适配器保持统一协调流程

### `MySqlMilvusNeo4jStorageProvider`

迁移方式与 PostgreSQL 组合类一致，但 `RelationalStorageAdapter` 实现换成 MySQL。

## Risks

### Risk 1: 抽象层级过多

如果适配器边界定义过细，会把现有可工作的实现拆散，造成理解成本上升。

缓解方式：

1. 第一阶段只定义 3 个一等能力接口。
2. 不再为每个小动作继续拆子接口。

### Risk 2: 恢复模型被削弱

如果把图和向量写入抽象得过于独立，容易丢掉现有恢复保证。

缓解方式：

1. `StorageCoordinator` 必须明确以 `RelationalStorageAdapter` 为恢复中心。
2. 所有 facade 的恢复语义保持与现有 Provider 一致。

### Risk 3: Builder 迁移过早

如果在这一阶段直接把 Builder 变成能力注入入口，会放大破坏面。

缓解方式：

1. 第一阶段只增加 `StorageAssembly` 兼容入口。
2. 现有 `storage(...)` 保持可用。

## Testing Strategy

测试建议分三层：

1. **接口装配测试**
验证 `StorageAssembly` 能正确组装为 `AtomicStorageProvider`。

2. **协调行为测试**
验证关系型成功、图投影失败、向量投影失败、恢复回滚等核心语义。

3. **兼容 facade 测试**
验证旧组合 Provider 仍然具备相同行为和公开 API 能力。

第一阶段必须重点覆盖：

1. `PostgresMilvusNeo4jStorageProvider` 委托新协调层后仍通过现有恢复测试。
2. `LightRagBuilder` 能接受新的装配入口。
3. 公开注入 API 不倒退。

## Recommendation

推荐采用以下实施顺序：

1. 先引入能力接口与协调层。
2. 先迁移 PostgreSQL 组合实现。
3. 保持 facade 兼容。
4. 再逐步迁移 MySQL 组合实现和 Starter。

这是风险最小、长期收益最大的路径。它先解决“扩展模型错误”的问题，而不是继续在固定组合类上叠加能力。
