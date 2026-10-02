# postgres-milvus-neo4j 组合栈的可选 AGE 图后端实施计划

**Goal:** 让 `POSTGRES_MILVUS_NEO4J` 组合栈（aiplatform devnew3/联调现用）支持**按需**把图后端从 Neo4j 换成 Apache AGE（保留 Milvus 向量与 PostgreSQL 关系表）；默认行为不变（Neo4j 投影）。AGE 的定位是 Neo4j 的对照系，用于对照评估。

**Architecture:** 组合 provider 的 `graphAdapter` 字段本就是 `GraphStorageAdapter`（`PostgresMilvusNeo4jStorageProvider:54`），新增 `PostgresAgeGraphStorageAdapter`（包装 `PostgresAgeGraphStore`，DataSource 基于同一 PG）替换 `Neo4jGraphStorageAdapter` 作为图投影即可，coordinator/pre-image/restore/delete 机制全部复用：

- **写**：`StorageCoordinator.writeAtomically` 已然双写——关系行经 `transactionalGraphStore`（同 PG 事务）落库，图投影经 `graphAdapter.apply(stagedWrites)` 在操作后应用、失败用 pre-image 补偿（`StorageCoordinator:96-161`、`StagedGraphStore:490+`）。AGE 走同一路径：`apply` 先实体后边（边要求端点存在），补偿用同款 scoped pre-image（点读；实体在边之前回填，`deleteEntities` 是 DETACH DELETE）。
- **读**：`coordinator.graphStore()` = `graphAdapter.graphStore()`（AGE store），`resolveGraphMutation`/`buildMilvusPayloads` 等既有路径无改动。
- **引导**：backend=AGE 时在构造期 `new PostgresAgeBootstrap(dataSource, workspaceId).bootstrap()`（包内可见），与 `PostgresStorageProvider` 一致；图名/属性名与上游 Python 完全一致（支持 Python 共用库）。
- **恢复/清空**：`graphAdapter.restore(GraphSnapshot)` = 单事务（连接绑定 store）`clear()` → 实体 → 边；`truncateAll` 路径复用既有 delete/restore 语义（组合 provider 无独立 truncate，删除走 `deleteDocumentDerivedState` 的 relational+projection 双删）。

**Tech Stack:** Java 17、Gradle、JUnit 5 + AssertJ、Testcontainers（AGE+pgvector 镜像见 `lightrag-core/src/test/resources/postgres-age/Dockerfile`）、Milvus 用既有记录型 projection 桩（现有测试同样不拉 Milvus 容器）。

---

## 改动清单

### core（`io.github.lightrag.storage.postgres`）

| 文件 | 改动 |
|---|---|
| `PostgresAgeGraphStorageAdapter`（新） | `implements GraphStorageAdapter`：`graphStore()`、`captureSnapshot()`（allEntities+allRelations）、`apply()`（实体→边）、`restore()`（连接绑定 store，单事务 clear→实体→边）、`capturePreImage()`/`restorePreImage()`（照搬 Neo4j adapter 的 present→save/absent→delete 算法与实体优先顺序）、`close()` no-op |
| `PostgresGraphBackend` | 增加 `NEO4J` 值；javadoc 写明各值适用类型：`TABLE`/`AGE`→`POSTGRES`；`NEO4J`/`AGE`→`POSTGRES_MILVUS_NEO4J`（`NEO4J` 为该组合默认） |
| `PostgresMilvusNeo4jStorageProvider` | 新增 4 个 AGE 构造器（`PostgresStorageConfig`×`MilvusVectorConfig`×`SnapshotStore`×[`WorkspaceScope`]×`PostgresGraphBackend`，±DataSource），**无 Neo4jGraphConfig**；仅接受 `AGE`（其余值抛 IAE 并提示用既有构造器/type=postgres）；内部 build 方法抽出 backend 分支（NEO4J→现状；AGE→bootstrap+AGE 适配器）；`relationalAdapter.dataSource()`（包内）取 DataSource |

### starter

| 文件 | 改动 |
|---|---|
| `LightRagProperties.PostgresProperties` | `graphBackend` 默认值改为 `null`（未设置），setter 不再归一化；新增 `resolveGraphBackend(Type)` 集中解析+校验（POSTGRES：null→TABLE、table/age 合法、neo4j 报错；POSTGRES_NEO4J：null/neo4j 合法、其余报错并提示 `type=postgres`+`graph-backend=age`；POSTGRES_MILVUS_NEO4J：null→NEO4J、neo4j/age 合法、table 报错） |
| `LightRagAutoConfiguration` / `SpringWorkspaceStorageProvider` | POSTGRES 分支改用 `resolveGraphBackend(Type.POSTGRES)`（行为不变）；POSTGRES_NEO4J 分支调用解析器做校验（结果必为 NEO4J）；POSTGRES_MILVUS_NEO4J 分支：AGE→新构造器（跳过 `neo4jConfig()`，其会强制校验 neo4j 属性）、NEO4J→现状 |
| starter 测试 | 更新既有 graph-backend 绑定测试（默认 null+按类型解析）；新增组合类型 `age`/`neo4j`/非法值用例 |

### 文档

`README.md` / `README_zh.md`：AGE 小节补充组合栈用法（YAML：`type: postgres-milvus-neo4j` + `postgres.graph-backend: age`，Neo4j 属性可省略；说明默认 neo4j、边端点要求、扩展+版本门前置）。

## 测试

1. `PostgresAgeGraphStorageAdapterTest`（Testcontainers apache/age）：apply 往返（实体+边，含特殊字符）；captureSnapshot；restore 替换+清空；pre-image（present→回填、absent→删除、实体删除级联边的场景）；非法 backend 构造器参数拒绝。
2. `PostgresMilvusNeo4jAgeComboTest`（Testcontainers AGE + 记录型 Milvus projection，经既有 `(dataSource, config, snapshotStore, scope, GraphStorageAdapter, VectorStorageAdapter)` 构造器）：`writeAtomically` 后 AGE 可读同一批数据；操作抛错→AGE 回滚为空（pre-image 补偿）；`restore(snapshot)` 替换 AGE 内容；`deleteDocumentDerivedState` 同步删 AGE。
3. starter：`resolveGraphBackend` 全表 + 绑定用例。
4. `./gradlew build` 全绿。

## 边界与说明

- 写入补偿语义与 Neo4j 相同（失败后 pre-image 回滚投影）；因 AGE 与关系行同库，补偿可靠性与事务贴近性更好。
- pre-image v1 用「单事务连接绑定 store 点读」；如对照评测显示热点明显，再考虑批量（上游 `get_nodes_batch` 式）优化。
- `POSTGRES_NEO4J` 类型不新增 AGE 通道（等价换法 = `type=postgres` + `graph-backend=age`）；`MYSQL_MILVUS_NEO4J` 不适用（AGE 是 PG 扩展）。
- `PostgresStorageConfig` 不改（aiplatform API 兼容）。
