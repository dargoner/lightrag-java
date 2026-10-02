# Java LightRAG 核心对齐规划（entity filePath / 上下文行 / embedding 优先级 / BFS 下推 / 抽取 prompt）

> 本版为评审轮 6 修订版：轮 1（9 must-fix + 7 should-fix + 4 nit）、轮 2（5 must-fix + 2 补强）、轮 3（4 项「目标态 vs 现状」措辞/验收）处置见附录 A/B/C；轮 4 双轨复核（规划轨**通过**、框架轨**一致**，附录 D）；轮 4 自核补全 **B2 委托链闭合**——PG table 适配层等包装类未委托 `getKnowledgeGraph`，不修则下推被截留（§B2 接点、附录 D）；轮 5 双轨复核（规划轨**通过**、框架轨**一致**）逐条核验 B2 委托链补全（附录 E）；轮 6 设计层复审（无 must-fix，4 should-fix + 3 nit）：**B2 AGE 下推降为可选**（对照系定位）、**A2 端点渲染改为统一 entityId**（映射表方案因 Hybrid/Mix/QueryEngine 在已截断上下文上重组装而否决）、**A2/C1 增评估回归门**、B1 补第三个 embedding 消费方，处置见附录 F；轮 6 双轨复核（规划轨**通过**、框架轨**一致**，两条非方向性提示已并入正文，附录 G），**评审循环收敛**。关键事实均按两仓库源码复核（含 `git log -S` 溯源）。

**Goal:** 把 6 项核心小项的上游对齐研究结论落成可执行的实施规划。分三包 + 一项暂缓：

- **A 包（可见性对齐）**：A1 实体 filePath 全链路溯源；A2 实体/关系上下文行改为上游 JSON 记录行。
- **B 包（性能/规模对齐）**：B1 embedding 两级优先（查询优先于入库）；B2 图视图 BFS 下推到远程存储。
- **C 包（提示词对齐）**：C1 抽取 prompt 选择性吸收上游指令块（不整段平移）。
- **暂缓**：chunk id 格式（仅记录，触发条件见文末）。

每项均已对照 `D:\ai-code\LightRAG` 完成研究，上游引入时间逐项标注（本仓库要求）。**实施顺序（评审后调整）**：A1（数据契约与迁移）→ A2（context 投影；技术上不依赖 A1，但受门槛 #1 约束）→ B1（独立）→ B2（先定 Java 契约再逐后端 override）→ C1（收口）；chunk id 若有需求单独立项。

**上游对齐状态一览（含引入时间）：**

| 项 | 上游锚点（引入时间/短 hash） | Java 现状（file:line） | 差距性质 |
|---|---|---|---|
| A1 entity filePath | 实体 `file_path` 写入与合并 `operate.py:1788-1791`，实体限流 `:1939-1973`，实体 vdb 载荷 `utils_graph.py:2411-2420`（create，缺省 `manual_creation`）/:3072-3081（merge）（`bf18a5406` 2025-03-17 "add citation"） | `EntityRecord` 无 filePath（`GraphStore.java:263-279`）；PG entities 表无列（`PostgresSchemaManager.java:144-153`）；实体 vdb 载荷无路径（`HybridVectorPayloads.java:52-71`）；关系侧 2026-10-01 已补（`fd99792`） | 一直未对齐（实体侧从未移植；关系侧已先行） |
| A2 上下文行 | 行构建（含 `created_at`/`file_path`）`operate.py:5573-5610` → 复制剥离二者后截断 `:5617-5649`（`utils.py:4082-4123`：按含分隔符整串计预算 + 前缀复核，`a433eed33` 2026-08-04）→ 逐行 `json.dumps(ensure_ascii=False)` `:5941-5946`；CSV→JSON `f2f3a2721` 2025-04-20，逐行 JSON `a49c8e4a0` 2025-09-10，首移元数据 `d3fde6093` 2025-08-18，改剥离副本并保留数据面元数据 `37d01e2df` 2025-09-15 | 管道行 `- id \| name \| score`（`QueryBudgeting.java:32-43`，2026-03-15/16 引入）；预算逐行累加、漏计 `\n` 分隔符（`:67-82`） | 一直未对齐（Java 引入时上游已是 JSON 行） |
| B1 embedding 优先级 | 优先级仅在同队列内排序 `constants.py:671-673`，query=5/summary=8/processing=10 `:680-691`；角色队列 extract/keyword/query/vlm `llm_roles.py:52-57`；查询 embedding 传 QUERY priority `operate.py:5347-5349`；`priority_limit_async_func_call`（`utils.py:1193`，2025-04-28） | `LlmConcurrencyBudget` 每角色独立池 + 单 embedding 池，公平 FIFO；类注释声明 priority 故意不移植（`:8-30`，2026-10-01 引入） | 有意偏离（现修正为仅 embedding 两级：唯一跨用途共享池） |
| B2 BFS 下推 | 各后端逐项（引入短 hash）：Neo4j `neo4j_impl.py:1301-1694`（BFS 1338-1530、fallback 1531-1694；`3dba40664` 2025-01-25；近期去重/有向匹配 `76ce47e38`/`146d02f85` 2026-09-16/19）；AGE `_bfs_subgraph` `postgres_impl.py:8881`/`get_knowledge_graph` `:9097`（`11681fdd6` 2025-04-24）；PGTable `pgtable_impl.py:1137-1290`/`:1291`（`da4cab654` 2026-06-22；读扩散优化 `5ee167872`/`4f1f1b6e4` 2026-08-04/10）；Memgraph `:1051`（`ff1927d36` 2025-06-26）；OpenSearch `:5412`/`:5726`（`b57d88d51` 2026-03-02）；Mongo `:3463`（`a600beb61` 2025-02-15；label `*` 重写 `5739f52d2` 2025-06-28）；NetworkX `networkx_impl.py:1087-1190`（排名调整 `d202ebe13` 2026-08-08） | 默认全量物化（`GraphStore.java:123-261`；javadoc `:124-130` 仍以「mirroring upstream networkx」为权威表述，实施时改述）；`Neo4jGraphStore` facade 仅 CRUD 转发（`:10-74`），全仓唯一的 `getKnowledgeGraph` 是 `GraphStore:135` 默认实现 | 部分对齐（API 有、下推无；实现点与包装委托链均缺，后者为轮 4 补全） |
| C1 抽取 prompt | JSON 模式 prompt `prompt.py:175-295`（`8c6f8e6b5` 2026-02-07）；section 不可抽取 `:208`、`prompt.py:51-54` | Java 自研恒定 JSON schema（`KnowledgeExtractor.java:53-139`，现行 `361f21e` 2026-04-17）；已有 fence 清理/LaTeX 修复/`Other` 兜底/section context（`SectionContextFormatter.java:25-27` 与上游逐字一致） | 半对齐（结构安全已部分移植；差异见 C1 表） |
| 暂缓 chunk id | 格式 `{doc_id}-chunk-{order:03d}` 首现 `3c4aa8a08` 2026-03-08（`lightrag.py:2327`）；helper 抽取 + 显式 `chunk_id` 优先 + 碰撞 hash `8effb8c21` 2026-05-08（`utils_pipeline.py:144-181`）；自定义 chunk 身份加固 `dda9d6763` 2026-07-17 | `{documentId}:{order}`（`FixedWindowChunker.java:123-125` 等；子块 `#child:{n}` `ParentChildChunkBuilder.java:101-102`），2026-03-16 起 | 有意差异（语义收敛、文本不同） |

---

## 阶段 0：门槛核验（做 A2/B2/暂缓项之前必须先回答）

| # | 待核验事实 | 核实方法 | 影响 |
|---|---|---|---|
| 1 | aiplatform 是否消费 `QueryResult.assembledContext()` 文本（行级解析/落库/展示） | 已 grep `assembledContext`/`assembled_context`：**无符号命中**（2026-10-02）；残余风险是「其它形式的文本消费」（如把整段 context 存库、前端按行渲染），需在实施前向 aiplatform 确认 | A2 直接改该字符串内容 |
| 2 | `getKnowledgeGraph`（`LightRag.java:488-497`）是否有大图可视化消费方、当前是否出现 JVM 内存瓶颈；AGE 对照是否涉及大图视图 | 问 aiplatform / 看日志；确认调用规模（节点数）与所用图后端 | B2 的投入依据；AGE 下推是否启用（§B2，轮 6） |
| 3 | 是否存在 Python↔Java 共库或数据迁移规划 | 问 aiplatform 数据面规划 | 暂缓项触发条件；A1 在 AGE 上的属性命名兼容决策 |

核验结论应记录在本目录的 spec 文档（或本文件旁注），再进入实施。

> **核验 #2 结论（2026-10-02，B2 启动前，只读取证）：** aiplatform 全仓 `getKnowledgeGraph` **零命中**；平台图谱工作台走 `runtime.storageProvider().graphStore().allEntities()/allRelations()` 全量装载 + 平台侧 BFS（`KnowledgeGraphServiceImpl:1579-1619`、`buildNeighborhood:3532-3602`），OpenAPI 邻域端点同源（`OpenApiKnowledgeGraphQueryService:65-84`），不经过 SDK 视图。⇒ ① SDK 视图当前无生产消费方（平台对该 API 的潜在采用在 2026-09-30 构建侧计划 `:1825` 已标注「optional，tracked outside this plan」，未变）；② **AGE 对照（评估 A/B）不涉及大图视图 → AGE 下推不启用**（轮 6 门槛条款），AGE 以默认实现身份参与对拍矩阵；③ PG table / Neo4j 两后端 override + 六包装层委托按附录 F（`工作量 B2 改 M–L（2 后端 + AGE 可选）`）保留实施——「消费方只有小图可视化 → 降级 P2」条款在轮 6 的落地即为该口径（AGE 可选、2 后端保留），若后续复核认为应整体降级，回退面即两处 override 与委托（独立提交，可单点还原）。

---

## A 包（P0，可见性对齐）

### A1 实体 filePath 溯源

**目标：** 实体与关系一样在存储层携带来源文件路径集合，并贯穿图视图与结构化查询数据面（上游 `data.entities[].file_path`，`utils.py:7634-7655`）。

**1. 数据契约（类型层 + API 层）**

- `GraphStore.EntityRecord`：追加第 7 组件 `String filePath`（join 后字符串，与 `RelationRecord.filePath` 一致）；补 6 参旧构造器（`filePath = ""`）；**compact 构造器对 null 容忍**：`filePath = filePath == null ? "" : filePath.strip()`（null→`""` 镜像 `RelationRecord:319`，保证旧文件快照 JSON 缺字段反序列化不炸；`strip()` 为实体侧新增——关系侧 `:319` 现不 strip，轮 6 nit，实施时同步给 `RelationRecord` 补 strip 以保两侧一致）。提供 `filePaths()`（`RelationCanonicalizer.splitValues`）与 `filePath()`（镜像关系）。
- `types.Entity`：追加 `filePath` 组件 + 6 参旧构造器（先例 `types.Relation.java:19-38`）。
- `api.GraphEntity`：追加 `filePath`（对齐上游节点属性面）；构造点 `GraphStore.viewOf:230`、`GraphManagementPipeline:603` 传入；`KnowledgeGraphView` 消费方与测试同步。
- `api.StructuredQueryEntity`：追加 `filePath`（与 `StructuredQueryRelation.filePath` 对称；上游 `utils.py:7640` 的 `file_path` 默认 `unknown_source`）；映射点 `QueryEngine:921`。
- **实施目标：`Entity` 构造点全表（8 处；现状全部无 `filePath` 参数，本轮为「新增」而非「补传」）**：`GraphAssembler:413`、`GraphManagementPipeline:579`、`GraphVectorIndexer:148`（向量重建）、`LocalQueryStrategy:305`、`GlobalQueryStrategy:225`（查询）、`PostgresMilvusNeo4jStorageProvider:662`、`MySqlMilvusNeo4jStorageProvider:372`、`ArcadeOneShotRetrievalStore:244`——逐点新增末位 `filePath` 实参（实测 6 处为非限定 `new Entity(...)`，2 处为全限定 `new io.github.lightrag.types.Entity(...)`）。**完成判据**：8 处均传对应记录值/`Entity.filePath()`；`rg -n "new (\w+\.)*Entity\("` 无遗漏命中；测试见 §8。
- `PostgresMilvusNeo4jStorageProvider:713`（合并重建 EntityRecord）带上既有值。

**2. 组装层（`GraphAssembler`）**

- `MutableEntity` 现状无路径容器 → 新增 `LinkedHashSet<String> filePaths` + `addFilePath`（镜像 `MutableRelation:432/476-480`），`mergeFrom(MutableEntity):348` 合并路径集合，`toEntity:412` join 写入。
- `mergeEntity` **现状**：签名 `:74-80` 无 `filePath`、调用点 `:32` 未传、`:107` 仅记 chunkId（评审轮 2 修正：原稿漏此正常实体入口）→ **变更**：签名增 `filePath` 入参，`assemble:29-33` 调用点传 `chunkExtraction.filePath()`，`:107` 处旁记路径。
- `ensureEntity:157-179` 新增 filePath 入参：新建（`:171-172`）与已存在（`:167`）两分支都记入；调用点 `mergeRelation:123/:124` 传 `chunkExtraction.filePath()`。
- 数据来源已就绪：`ChunkExtraction.filePath()`（`:212-230`）由 refinement 路径经 `MetadataKeys.filePathOf` 填充（`indexing/refinement/ExtractionRefinementPipeline:43`、`DefaultExtractionMergePolicy:47`）；主链全部经 `refinementPipeline.refine()` 组装（`IndexingPipeline:1247/1250`、`GraphMaterializationPipeline:945`），无旁路，主抽取管线无需改动。

**3. 写入层（构造点清单，实施前用 `rg -n "new (\w+\.)*EntityRecord\("` 复核，含全限定类名）**

- `IndexingPipeline`：实体新建与合并 `:1864/:1922/:2046-2061`；限流镜像关系做法（`:1991-2004/:2033-2042`）：`FilePathLimits.apply(union(existing.filePaths(), incoming), maxFilePaths, method)` 后 join。
- `GraphMaterializationPipeline`：`:1477/:1502`。
- `GraphManagementPipeline`：`:48/:105/:254`（手工实体无路径入参 → 缺省 `""`，有意；如需手工路径入参单独立项）。
- `DeletionPipeline:394`、`PostgresMilvusNeo4jStorageProvider:713`：既有值透传。
- 存储读侧构造点（5 处）：`PostgresGraphStore:301/317`、`WorkspaceScopedNeo4jGraphStore:723`、`PostgresAgeGraphStore:442`、`ArcadeGraphStore:90`。
- 实测 `new GraphStore.EntityRecord`/`new EntityRecord(` main 共 15 处 = 写 10（上述）+ 读 5（下述）。
- 测试面数十处（E2ELightRagTest、StorageAssemblyTest、LocalQueryStrategyTest、PostgresStorageProviderTest 等）：旧构造器零改动编译，逐项勾销。

**4. 存储矩阵（读写 + schema）**

| 后端 | 写 | 读 | schema/属性 |
|---|---|---|---|
| PG table（`PostgresGraphStore`） | `upsertEntity:429-445` 增列（`INSERT :432` 现为 5 列；镜像关系写点 `:462-471` 的 file_path 列） | `readEntity:299-309` / 批量 `:311-325` 带出（实体 SELECT 列表同步加列） | `entities` 表加列 `file_path VARCHAR(32768) NOT NULL DEFAULT ''`（镜像 `relations` 列 `PostgresSchemaManager.java:180`）；bootstrap 改 `:144-153`，已存在库走条件 `ADD COLUMN`（仿 `:428-441` 的幂等样式；该处现有的是 `document_status.metadata`，仅样式参照） |
| AGE（`PostgresAgeGraphStore`） | **待实施变更**：`entityProperties:416-423` 现仅有 `source_id`（`:422`，评审轮 2 锚点修正）→ 增 `file_path`（镜像 `relationProperties:435`） | **待实施变更**：`toEntityRecord:439-452` 现只读 `source_id` → 增读 `file_path`，属性缺失默认 `""`（`textOrDefault(properties.get("file_path"), "")`） | 无（AGE 节点属性自由）；验收：AGE 往返测试（写-读含 `file_path`、旧图缺属性读为 `""`），见 §8 |
| Neo4j（`WorkspaceScopedNeo4jGraphStore`） | 实体 `saveEntities` MERGE 增 `entity.file_path` 与参数（`:514-535`，镜像关系 SET `:587`/参数 `:601`、行构建 `:621`） | 实体读回 `toEntity:721-731` 增 `file_path`（镜像 `toRelation:743`） | 无；属性名 `file_path`（与关系一致） |
| Arcade（`ArcadeGraphStore`） | 属性集（`:24` 现为 `sourceChunkIds`）加 `filePath` | 实体 SQL 列表 `:44/:56` 与映射 `:96` 同步加 | `ArcadeSchemaManager:78-86`（Entity 属性块）加 `Entity.filePath STRING`（与 `Relation.filePath` `:97` 同款式，`CREATE PROPERTY IF NOT EXISTS` 幂等） |
| InMemory（`MutableGraphStore`） | 记录透传 | 记录透传 | 无 |
| MySQL 栈 | 已核 `MySqlSchemaManager` **无 entities/relations 表**，图在 Neo4j 侧 | 随 Neo4j 改动 | — |

**5. 向量载荷**

- `HybridVectorPayloads.entityPayloads:52-71` 增 `filePath`，取值 `entity.filePath()`，对齐上游实体 vdb 载荷（`utils_graph.py:2418/3079`）。**改法（轮 6 nit 明确）**：现用 `EnrichedVectorRecord` 4 参重载（`id, vector, searchableText, keywords`，`:63-68`），`filePath` 是全量构造器第 7 组件（`HybridVectorStore:18-25`，`relationPayloads:84-100` 即走全量构造器）→ 实体侧改走全量构造器（其余组件沿用 4 参重载的默认值）或新增含 `filePath` 的 5 参重载，二选一并补单测锁定载荷字段（实测 `entityPayloads` 现无直接单测，`src/test` 无调用；新建 `HybridVectorPayloadsTest`）。
- 删除/补偿路径不受影响（补偿走图存储）。

**6. 快照与回放（评审 MF-4 修正）**

- 文件快照：`SnapshotPayload:20` 持 `List<EntityRecord>`，`FileSnapshotStore` 经 Jackson 序列化 → **会**持久化新组件；旧快照缺字段由 null 容忍读为 `""`；补往返 + 旧格式兼容测试。
- 文档图快照（`DocumentGraphSnapshotStore.ExtractedEntityRecord/ChunkGraphSnapshot`）**不改**：路径属 chunk 层而非抽取层；PG/MySQL `chunk_graph_snapshots` 行（LONGTEXT/JSONB）因此无迁移。
- 回放调用面（评审轮 2 补全；`toChunkExtractions` 实测 3 个调用点）：`:1105-1131` 现用 3 参构造（filePath=`""`）→ 提供带路径重载并注入解析器——`:898`（作用域已有 `storedChunks:893`）与物化写入路径 `assembleChunkGraph:1133` 的 `:563` 调用（经 `chunkStore` 按 chunkId 解析，与 `:893` 同款）；`GraphChunkAttribution:57` 与状态核对 `:855`（及 `:756` journal 键）保持无路径重载——只读实体 id/关系 id，路径不参与 id 生成（框架评审确认无碍）；取值用 `MetadataKeys.filePathOf`。

**7. 限流与 `unknown_source` 边界**

- `FilePathLimits` 解除 "Entity file_path is deliberately out of scope"（`:11-16`）；实体与关系**共用** `maxFilePaths`/`method`（上游实体 `operate.py:1939-1974`、重建场景 `_surviving_chunk_file_paths:1721-1752`；关系 `:3067-3120`），复用同一 KEEP/FIFO + placeholder + 去重语义实现。
- `LightRagBuilder.maxFilePaths` javadoc/命名说明更新为「实体与关系共用」（现仅写 Relation，`:186-190`）。
- `unknown_source` 规则（评审轮 2 重写、评审轮 3 明确现状/目标）：`MetadataKeys.filePathOf:19-23` 在抽取边界把缺失/空白解析为上游哨兵；按上游行为（实体合并兜底即写 `unknown_source`，`operate.py:1788-1790`；`_surviving_chunk_file_paths:1727-1733` 只滤空值不滤哨兵），**哨兵是合法路径值，将进入实体/关系路径集合**——关系侧现状即如此（`addFilePath:476-480` 仅滤空白），实体侧为**本轮实施目标**（依赖 §2 路径传播落地）；已 grep 确认主链无其它哨兵过滤。`EntityRecord.filePath` 的 `""` 仅表示「无记录」（旧数据/未接线路径），与哨兵不互相归一化。`GraphEntity`/`StructuredQueryEntity` 透传存储值（上游数据面默认即 `unknown_source`，`utils.py:7640`）。

**8. 测试**

- 组装：多 chunk 同实体 filePaths 合并去重、`mergeEntity` 记入、`ensureEntity` 两分支、`mergeFrom`。
- 转换点：8 个 Entity 构造点带 filePath（6 非限定 + 2 全限定；含 Local/Global 查询策略、`GraphVectorIndexer`、两个组合 provider、`ArcadeOneShotRetrievalStore`）。
- 写入：实体限流（KEEP/FIFO/placeholder）对照现有关系测试写法。
- `unknown_source` 边界：实体与关系双侧各一条——哨兵作为合法路径进入路径集合、`""`/空白不进入（评审轮 3 补）。
- 记录往返 + 旧构造器默认 `""`（`GraphStoreReadSurfaceTest`/`InMemoryGraphStoreTest`）。
- 快照：`FileSnapshotStore` round-trip + 缺字段旧 JSON → `""`；回放保路径（物化/verify 路径注入有路径；attribution/状态核对无路径重载不影响 id）。
- 向量：`entityPayloads` 带 filePath。
- Testcontainers IT（环境门控；运行前 `./gradlew --stop`）：`Neo4jGraphStoreTest`、`WorkspaceScopedNeo4jGraphStoreTest`、`PostgresGraphStoreTest`、`PostgresAgeGraphStoreTest`（含 AGE 往返 `file_path`、旧图缺属性读为 `""`）、Arcade；`PostgresDocumentGraphStoresTest`/`MySqlDocumentGraphStoresTest` 确认无回归。
- 迁移幂等：对同一 PG 库跑两次 schema bootstrap 不报错。

### A2 实体/关系上下文行对齐（JSON 记录行）

**上游真相（本轮逐行复核）：**
- 先构建**含** `created_at`/`file_path` 的行（`operate.py:5573-5610`）；
- 复制剥离二者后截断，截断结果**覆盖**原变量（`:5617-5649`；`truncate_list_by_token_size` 按 `separator.join(rendered)` 整串计预算并对前缀复核后返回输入列表前缀，`utils.py:4082-4123`，`a433eed33` 2026-08-04）；
- 最终 `"\n".join(json.dumps(row, ensure_ascii=False))`（`:5941-5946`）。
- **因此最终 LLM 行 = `{entity,type,description}` / `{entity1,entity2,description}`**，不含 `file_path`/`created_at`/`source_id`/`keywords`/`weight`/score。首移元数据 `d3fde6093`（2025-08-18 "remove file_path and created_at from context"）；`37d01e2df`（2025-09-15）改为对副本剥离并保留完整元数据供 aquery_data。**A2 与 A1 无数据依赖。**

**目标：** Java 实体/关系行从管道文本改为上游同构 JSON 记录行；token 计数与渲染使用**同一投影**（Java 行内本无 file_path，不存在「成本/渲染分离」——原稿的分离设计作废）。

- 实体行：`{"entity": <name>, "type": <type>, "description": <description>}`
- 关系行：`{"entity1": <srcName>, "entity2": <tgtName>, "description": <description>}`
- 行内不再出现 score（排序仍由 score 决定，score 保留在 API 元数据，不进 LLM 文本）。

**实现：**
- `QueryBudgeting.formatEntity/formatRelation` 改 JSON 序列化（静态共享 `ObjectMapper` + `LinkedHashMap` 保序）。
- `limitByTextTokens` 预算算法对齐（评审轮 2 修正 SF-1）：计数对象为**与渲染完全一致**的 `String.join("\n", 行)` 整串（分隔符计入）；截断后再对最终 join 串独立复核 `countTokens ≤ maxTokens`，超限逐项回退（镜像 `utils.py:4104-4123` 的 `separator.join` 计数 + re-verify 循环；上游调用处 `operate.py:5626-5632/5643-5649` 传 `separator="\n"`、`key=json.dumps(..., ensure_ascii=False)`）。
- 实体行取 `entity().name()`（Java 的 `entityId` 是归一化键、显示名在 `name`）；**关系端点统一渲染 `srcId/tgtId`（即 entityId），不引入 id→name 映射表（轮 6 决策）**。依据：① entityId = `normalizeKey(name)` = `strip().toLowerCase(ROOT)`（`GraphAssembler:193-195`，`MutableEntity.create:327-329`/`ensureEntity:163`），语义上就是名称，仅丢大小写；② 映射表若按 `context.matchedEntities()` 构建会漏掉被 token 预算截掉的端点（Local `:85`/Global `:81` 先截断再入 `QueryContext`），而 Hybrid（`:57-58`）/Mix（`:78-79/:142-143`）在已截断的子上下文上合并重限、`QueryEngine:630/822/834` 再次重组装，无处取得截断前实体集，穿透需改 `QueryContext` 公开形状或 10+ 调用面，与「不影响 API 形状」冲突；③ 混用「可解析用 name、不可解析回退 id」会在同一 Relations 节出现大小写不一致。**预算计数与渲染同投影**（`limitRelations` 的 formatter 即 `formatRelation`，无需额外输入）。与上游差异（上游 `entity1/entity2` 为原始大小写名称）记入 C 表之外的「有意差异」并由 §SF-C 评估门把关；若评估显示退化，备选为在 `ScoredRelation`/`types.Relation` 增端点显示名组件（单独立项）。
- 转义由 JSON 序列化天然覆盖（引号/换行/CJK），补测试锁定。

**envelope 决策（评审 SF-7）：** **只对齐记录行**。`ContextAssembler:26-47` 的 `Entities:/Relations:/Chunks:/Reference Document List:` 分节保持 Java 现状（上游 `kg_query_context` 为 ```json fenced 模板，`prompt.py:442-467`，逐字对齐无收益且扩大下游消费面）；补 QueryEngine 请求快照测试钉住 assembled context 形态。

**兼容性/风险：** 只影响生成文本，不影响 API 形状；风险集中在下游解析（门槛 #1：aiplatform 已 grep 无 `assembledContext` 符号）。若核验出消费方且短期不可改：备选 legacy 渲染开关（默认新格式）。

**测试：** `ContextAssemblerTest` 期望更新（JSON 行、转义、空集合 `(none)` 不变、**关系端点为 entityId 且与实体行 `entity` 字段的归一化值一致**）；预算测试按新行重算 + 含分隔符边界用例（构造仅因 `\n` 翻转的恰界样例）+ 最终串复核断言；快照测试锁定四节结构；**评估回归**见「全局约束与验收」（轮 6）。

---

## B 包（P1，性能/规模对齐）

### B1 embedding 两级优先（查询优先于入库）

**范围决策（评审 MF-6 处置）：只移植 embedding 侧，chat 侧维持每角色 FIFO。**
- 上游优先级只在单队列内排序（`constants.py:671-673`），队列共 4 个：extract/keyword/query/vlm（`llm_roles.py:52-57`）；唯一跨用途共享的队列是 embedding（查询 5 vs 入库 10，`operate.py:5347-5349`）。
- summary=8 vs processing=10 的可见场景是「summary 与 extraction 共用 EXTRACT 队列」；Java 中 summary 与 extract 是**独立池**（`LightRag.java:965-966` → `LlmConcurrencyBudget:75` 每角色独立 semaphore），两者并发互不占位，该排序在 Java 无观测面 → 记录为有意偏离（隔离更强），不改 chat 调用契约。

**设计：**
- `LlmConcurrencyBudget`：新增 `public enum EmbeddingPriority { HIGH, LOW }`（javadoc 锚定 `constants.py:671-691`）与 `limitEmbedding(EmbeddingPriority, EmbeddingModel)`；旧 `limitEmbedding(delegate)` 保留并委托 `LOW`。
- 实现 `PrioritySemaphore`（替换裸 `Semaphore`）：公平锁 + condition，等待队列按 `(priority, sequence)` 排序；释放时唤醒队首。保持既有契约——单次委托调用持一个槽、不可重入、入队线程中断即中止（恢复标志 + `IllegalStateException`）、空闲时直接获取不查中断标志。
- `LightRag`：`limitedEmbeddingModel()`（`:1248-1250`）改为按用途取包装——入库 `:967/:1055` 传 `LOW`，查询策略 `:1194-1198` 传 `HIGH`。**第三个消费方（轮 6 补）**：`ops/RebuildVectorIndexService:116` 直接调 `budget.limitEmbedding(embeddingModel)` 做向量重建（入库侧）→ 显式改为 `LOW`，不依赖旧重载的默认委托；`limitEmbedding` 调用面以 `rg -n "limitEmbedding\("` 清单为准（实测 main 共 3 处）。
- 类注释（`:8-30`）修正：chat 侧维持「priority 不移植」的论据（每角色独立池 + 阻塞式管线），embedding 侧改为「两级优先已移植，锚点 constants.py」。
- Java 无 embedding 缓存 → cache-hit 语义不适用（chat 侧缓存命中不占槽，见 `LightRag.java:1244-1245` 注释）。
- 饿死风险：按上游严格优先实现；测试锁定「无 HIGH 等待时 LOW 正常推进」；线上出现入库停滞再评估老化/配额（记录为已知风险，不预做）。

**测试：** `LlmConcurrencyBudgetTest` 增加——HIGH 先于已排队 LOW 通过、同优先级 FIFO、无 HIGH 时 LOW 不被拖慢、中断语义与现有一致、并发压力下无丢失唤醒；`RebuildVectorIndexServiceTest` 断言重建走 `LOW`。

### B2 图视图 BFS 下推（Neo4j / PG table；AGE 可选）

**契约基准（评审 MF-7 处置）：对拍基准 = Java 默认实现**（`GraphStore.java:123-261`），不再声称统一的上游契约。上游各后端排序/截断规则互不相同（逐项短 hash：Neo4j `neo4j_impl.py:1301-1694` 含 fallback，`3dba40664` 2025-01-25；AGE `:8881`/`:9097`，`11681fdd6` 2025-04-24；PGTable `:1137-1290`/`:1291`，`da4cab654` 2026-06-22；Memgraph `:1051`，`ff1927d36` 2025-06-26；OpenSearch `:5412`/`:5726`，`b57d88d51` 2026-03-02；Mongo `:3463`，`a600beb61` 2025-02-15；NetworkX `networkx_impl.py:1087-1190`，排名调整 `d202ebe13` 2026-08-08）——参照算法骨架，不作为逐位语义来源。**实施时同步更新 `GraphStore:123-134` javadoc**（评审轮 2 nit）：以 Java 契约（排序/BFS/未知 label 空视图/truncated 三来源）为规范表述，注明语义来源为上游 networkx 视图、上游仍在演进，不再以「mirroring upstream」为权威。默认契约（下推必须逐项等价）：
- `"*"`：全图按 `(degree desc, label asc)` 排序取前 `maxNodes`；
- 具名 label：从该实体逐层 BFS，每层同规则排序、frontier 受剩余预算 cap；未知 label 返回空视图；
- 边仅在两端点都在结果内时返回；`truncated` 覆盖三种截断来源（节点预算 / 深度上限 / 层内未处理节点，`:213-215`）。

**接点（评审 SF-2 + 轮 4 补全：委托链须整链闭合，否则下推被包装层截留）：** `LightRag.getKnowledgeGraph`（`LightRag.java:488-497`）经 `provider.graphStore()` 取到的对象在多数栈里是**包装类**；实测 main 全仓除 `GraphStore.java:135` 默认实现与 `LightRag` 调用点外**无任何 `getKnowledgeGraph` override**——包装类当前全部落回接口默认实现（全量物化），实现点即使 override 也到不了调用方。分两层：
- **实现（下推落点）：** `PostgresGraphStore`（PG table）、`WorkspaceScopedNeo4jGraphStore` 各自 override（必做）；`PostgresAgeGraphStore` **可选（轮 6 降级）**——AGE 后端定位为 Neo4j 的对照系（2026-10-02 决策：不追加生产级加固，除非对照本身需要），BFS 下推属性能加固；仅当门槛 #2 核验出 AGE 对照涉及大图视图时实施，否则保留默认实现并在对拍矩阵中以默认实现身份参与（此时 AGE 行只能证明「经包装与直连结果一致」，截留本身在无 override 的后端不可观测；委托链有效性由 PG table 行承担——同一 `LockedGraphStore` 包装两种后端，`:188-190/:214`）。
- **委托（评审轮 4 补全）：** `PostgresStorageProvider.LockedGraphStore:604-650`（`graphStore():233-234` 返回它；包装 `graphBackend` 选择的 PG table / AGE 存储，`:188-190/:214`）——**PG table 路径的适配层**；`Neo4jGraphStore` facade（`:10-74`）→ `WorkspaceScopedNeo4jGraphStore`（纯 Neo4j 栈）；组合栈读取链 `PostgresNeo4jStorageProvider.MirroringGraphStore:268`、`PostgresMilvusNeo4jStorageProvider.MirroringGraphStore:852`、`MySqlMilvusNeo4jStorageProvider.LockedGraphStore:621`（delegate 经 `coordinator.graphStore()`，构造见 `MySql…:160`）→ `Neo4jGraphStorageAdapter.WorkspaceStoreProjection:132-223`（`graphStore():33-35` 返回的 Neo4j 投影层，组合栈必经）。委托均为只读转发，保持各包装既有锁/镜像语义，`LightRag` 调用方无需改动。
- **无需改动（已核）：** `PostgresAgeGraphStorageAdapter.graphStore():35` 裸返回存储；`StorageCoordinator.StagedGraphStore:490` 仅写入暂存（`:104` 内部创建；公开 `graphStore():46` 不返回它）不在查询路径；InMemory 保留默认实现。

| 后端 | 方式 | 上游参照 |
|---|---|---|
| `WorkspaceScopedNeo4jGraphStore`（+facade 转发） | 逐层 Cypher：层候选带 degree 聚合 → Java 侧排序切预算 → 展开下一 frontier | `neo4j_impl.py:1338-1530`（`3dba40664` 2025-01-25；近期去重/有向 `76ce47e38`/`146d02f85` 2026-09-16/19） |
| `PostgresGraphStore` | SQL 逐层 frontier：候选集 degree 聚合 `GROUP BY`，按剩余预算 LIMIT；`"*"` 一条 `ORDER BY degree DESC, id LIMIT` | `pgtable_impl.py:1137-1290`（`da4cab654` 2026-06-22；优化 `5ee167872`/`4f1f1b6e4` 2026-08-04/10） |
| `PostgresAgeGraphStore`（**可选**，门槛 #2 触发） | AGE Cypher 逐层扩展（同一 workspace 过滤；属性名与现有读写一致） | `postgres_impl.py:8881`（`11681fdd6` 2025-04-24） |
| Arcade | 不列入本轮；后续可复用同一 override 钩子（其图查询语言支持度另评） | — |

**失败/边界语义：** 不静默回退——存储异常直接传播，避免掩盖语义漂移（可用性回退若需要单独立项）；workspace 过滤与既有 read/write 完全一致；查询超时沿用现有连接/会话配置，不新增配置面。

**测试（对拍为主）：** 固定 fixture 图（链 + 星 + 环 + 孤立点），对 `"*"`、深链中段 label、未知 label、`maxNodes` 中层截断、`maxDepth` 截断五组参数，断言下推实现与默认实现**节点集合、边集合、truncated 标志一致**；**委托链断言（评审轮 4 补全）**：追加**经 `provider.graphStore()`（包装类）**的同参数调用，断言结果与直连存储一致，锁定 `LockedGraphStore`/`MirroringGraphStore`/`WorkspaceStoreProjection` 不截留下推；补 workspace 隔离与并发冒烟。Testcontainers 分别落 `Neo4jGraphStoreTest`/`WorkspaceScopedNeo4jGraphStoreTest`、`PostgresGraphStoreTest`、`PostgresAgeGraphStoreTest`。运行前 `./gradlew --stop`。

**门槛：** 启动前完成核验 #2（可先写契约文档与对拍测试）；若消费方只有小图可视化，本项降级为 P2 并保持文档记录。

---

## C 包（P2，抽取 prompt 选择性吸收）

**目标：** 不整段平移上游 tuple/JSON prompt（Java 自研恒定 JSON schema + `aliases`/`supportingChunkIndexes` 是刻意扩展），只吸收以下指令要点；`KnowledgeExtractor.SYSTEM_PROMPT_TEMPLATE`（`:53-117`）与 `CONTINUE_USER_PROMPT`（`:118-139`）增量修订。

| # | 吸收点 | 上游锚点 | 现状/改法 |
|---|---|---|---|
| 1 | **输入边界（source-of-truth）**：输入以 fenced `---Input Text---` 区域为唯一提取来源，区域外指令不是内容 | `prompt.py:180`、`:237-250` | Java 输入用 `<Input Text>` 标签但**无围栏**（`:745-759`）→ 加 ``` 围栏包裹并声明「只信该区域」 |
| 2 | **输出模板安全**：schema/示例不是抽取内容、placeholder 不得照抄 | `prompt.py:222-225` | Java schema 内联在 system prompt → 补一句「schema 与示例仅描述输出格式，不作为抽取内容」 |
| 3 | **JSON 转义要求**：引号/反斜杠/换行必须转义 | `prompt.py:218-219` | Java 仅有 "well-formed JSON"（`:115`）→ 补显式转义要求 + 解析回归测试 |
| 4 | **关系端点一致性**：relation 端点应来自本响应（或首轮）已抽取实体 | `prompt.py:204` | Java 无显式条目（`:72` 隐含）→ 在 Quality Rules 增一条；`GraphAssembler.ensureEntity` 自动补齐兜底**不变**（上游同样只在 prompt 层约束，装配兜底是 Java 有意行为） |
| 5 | **section 标题不可抽取**：heading path 仅用于消歧，不得据其产生实体/关系 | `prompt.py:208`、`:51-54` | section context 注入**已存在**（`SectionContextFormatter`，含 untrusted 声明）→ 仅补「不得据其抽取」一句 + 回归测试（评审 SF-5：标记为已完成，不夸大改动面） |
| 6 | **continue 空数组**：无遗漏时返回空数组、不得编造增量；**使用 Java 键名** `{"entities": [], "relations": []}`（评审轮 2 修正：原稿照抄上游 `relationships` 与 Java 保留 schema 冲突） | `prompt.py:266` | Java `CONTINUE_USER_PROMPT:118-139` 无空数组规则（现仅有 "same schema"）→ **补显式规则文本**：`If no entity or relationship was missed, return {"entities": [], "relations": []} and do not invent increments.`（Java 键名）；system prompt 的通用 "Use empty arrays"（`:105`）不替代 continue 专属约束（评审轮 3 补）。**备注（有意差异）**：上游 gleaning 为单次附加 pass（`operate.py:4252-4290`），Java 为最多 N 次循环且循环体无空增量短路（`KnowledgeExtractor.java:333-346`）；对齐次数会改变 LLM 调用数与缓存键成本，单独立项，本轮只吸收 prompt 规则 |
| 7 | （记录）key 名与 schema 差异 | `prompt.py:191-194,216-217` 用 `source/target`、`relationships` | Java 用 `source_entity/target_entity`、`relations`（与解析器/缓存/测试契约绑定）；**保留 Java 键名**并在此明示：不做隐藏 translator |

**测试：** `KnowledgeExtractorTest` 增加——prompt 关键段断言（金样式：围栏、source-of-truth、模板安全、system prompt 空数组、continue 专属空数组规则与 Java 键名 `{"entities": [], "relations": []}`）、解析器对转义/围栏/杂质宽容回归。prompt 属行为面，测试只钉结构与规则要点，不做整段快照（避免文案小改动频繁碎测试）。

---

## 暂缓项：chunk id 格式（记录，不实施）

- **现状：** Java `{documentId}:{order}`（各 chunker 一致；子块 `#child:n`），上游 `{doc_id}-chunk-{order:03d}`（补零、可字典序）。
- **上游时间线（`git log -S` 复核）：** 格式首现 `3c4aa8a08`（2026-03-08，`lightrag.py:2327`）；helper 抽取 + 显式 `chunk_id` 优先 + 同文档碰撞 hash `8effb8c21`（2026-05-08，`utils_pipeline.py:144-181`）；自定义 chunk 身份加固 `dda9d6763`（2026-07-17）。
- **暂缓理由：** ① 语义已收敛（唯一、有序），差异仅在文本形态；② 改格式需跨存储迁移（chunk store、Milvus 主键与载荷、图侧 sourceChunkIds/关系 sourceId join 值、快照 chunkId），且 aiplatform 已有存量库；③ 上游 2026-07-17 仍在加固自定义 id/hash 规则，尚未稳定；④ 与「共库/迁移规划」强耦合。
- **触发条件与预案：** 门槛 #3 核验若为「需要 Python↔Java 共库」或有显式迁移需求，单独立项，采用**版本化映射 + 双读/回填**（旧 id→新 id 映射遍历 chunk store/向量载荷/图行/快照；新格式不含 `|`，与现有 join 分隔符无冲突），不做全库一步改写。

## 规划外缺口（评审补充，本轮记录不实施）

| 缺口 | 上游 vs Java | 建议 |
|---|---|---|
| 角色级并发/超时配置面 | 上游 `RoleLLMConfig` 每角色 `max_async`/`timeout`（`llm_roles.py:63-77/170-191`）；Java 仅全局 `maxAsyncLlm`/`embeddingMaxAsync`（`LightRagConfig:19-45`，`LlmConcurrencyBudget:52-75` 所有 chat 角色同一上限） | 单独立项（配置面扩展，需评估 aiplatform 兼容；不属本轮 6 项） |
| LLM JSON 错误恢复 | 上游去 fence + 剥离包裹文本 + `json_repair`（`operate.py:916-960`）；Java 去 fence/LaTeX 修复已有，但最终抛 `ExtractionException`（`KnowledgeExtractor.java:438-456`） | C1 之后单列小项评估（重试/修复策略，注意抽取质量与缓存语义） |
| `created_at` 统一模型 | 上游行与数据面带 `created_at`（截断前剥离，最终行不含）；`data.entities[].created_at` 保留（`utils.py:7641`）；Java 实体/关系记录无该字段 | 记为有意裁剪（A2 最终行不需要）；若数据面需要再单独立项，不在本轮引入时间戳存储 |

## 全局约束与验收

- 不改 `PostgresStorageConfig`（aiplatform API 兼容）；不新增 `LightRagBuilder` 配置项（A1 复用 `maxFilePaths`；B1/B2 无配置面）。
- A1 实施前先跑 `rg -n "new (\w+\.)*Entity\(|new (\w+\.)*EntityRecord\("`（含全限定类名）生成构造/转换点清单逐项勾销；B2 先落契约文档与对拍测试再动实现。
- 每项独立提交面：`feat|fix` 前缀 conventional commits，按包/按层拆分（契约 → 组装 → 存储 → 测试）。
- 阶段验收：`./gradlew build` 全绿；Testcontainers 门控 IT 手动补跑（先 `./gradlew --stop`）；每项行为变更都有对应测试更新。
- **评估回归门（轮 6 补，A2/C1 专属）**：两项都改 LLM 面文本，单测只能钉结构。实施前先用当前 HEAD 刷新基线，实施后同数据集对比：
  - A2（查询上下文）→ `evaluation/ragas/eval_rag_quality_java.py --baseline-name sample-default`（RAGAS 需 LLM；基线文件 `baselines/sample-default.*` 现不存在，先 `--update-baseline` 生成）；回归阈值超限脚本自动非零退出。**口径说明（轮 6 框架轨提示）**：RAGAS 的 `contexts` 取 `QueryResult.contexts()`（chunk 级，`RagasBatchEvaluationService:51`），A2 改的实体/关系行只经 answer 侧指标（faithfulness/answer relevancy）可见；`sample_dataset.json` 仅 6 例、默认阈值 0.02（`eval_rag_quality_java.py:267`）易受 LLM 噪声影响——基线先跑两遍估噪声幅度，再定裁决口径（必要时换 BEIR 子集扩大样本）。
  - C1（抽取 prompt）→ 图结构变化只在**启用 LLM 抽取**时可见：`LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY=false` 跑 `eval_retrieval_quality_java.py`（默认 retrieval-only 跳过 chat 抽取，对 C1 无感；既有 `baselines/beir-scifact-retrieval.*`/`sample-retrieval.*` 是 retrieval-only 产物，不能直接作 C1 基线，需另建 `--baseline-name beir-scifact-extract` 之类）。
  - 结果与 delta 留档至 `evaluation/ragas/results/`，并在各项提交说明中引用；退化超阈值视为未通过验收。
- README（双语）：A1/B1/C1 无配置面变化则不动；A2 若 README 有 assembled context 示例输出则同步更新。
- 本规划只到设计层；实施与提交需明确指令。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| A2 改 `assembledContext` 文本影响下游解析 | 门槛 #1（已核无 `assembledContext` 符号，残余风险为其它形式消费）；备选 legacy 渲染开关（默认新格式）；envelope 明确不动 |
| A1 组件扩展波及面（main 15 处 EntityRecord 构造 + 8 处 Entity 构造（含 2 处全限定）+ 测试数十处 + facade/Milvus 映射） | 旧构造器零改动编译 + null 容忍；`rg` 清单逐项勾销；快照/回放/载荷/转换点均有专项测试 |
| A1 旧文件快照/文档图快照兼容 | EntityRecord null 容忍；文档图快照不改 schema；回放路径测试锁定 |
| PG entities 加列迁移 | `ADD COLUMN IF NOT EXISTS ... DEFAULT ''` 幂等；对同库二次 bootstrap 测试 |
| 关系端点/行格式变化影响 token 预算 | 预算按新行重算并测试锁定；预算含 `\n` 分隔符且对最终串复核；行内无 score 属预期 |
| A2 关系端点渲染 entityId（小写）而非原始名称 | 有意差异（轮 6）：entityId = `strip().toLowerCase()`，语义即名称；RAGAS 评估门把关，退化则单独立项给 `Relation` 补端点显示名 |
| A2/C1 文本改动导致答案/抽取质量回归而单测无感 | 评估回归门（见「全局约束与验收」）：A2 走 RAGAS 基线对比；C1 以 `RETRIEVAL_ONLY=false` 跑检索基线 |
| B2 语义偏移（排序/截断 flag 细节） | 以 Java 默认实现为基准五组参数对拍；truncated 三来源逐条覆盖；不静默回退 |
| B1 严格优先导致入库饿死 | 按上游语义实现 + 测试锁定「无 HIGH 时 LOW 推进」；线上出现停滞再评估老化策略 |
| C1 prompt 文案改动回归抽取质量 | 只吸收指令要点、保留 Java schema/键名；解析宽容度测试兜底 |

## 工作量（含测试，单人估算）

| 项 | 规模 | 说明 |
|---|---|---|
| A1 | L（1.5-3 天） | 契约 + 组装 + 写入/读侧清单 + 4 后端读写 + 实体载荷 + 快照兼容 + 回放 + PG 迁移 + IT |
| A2 | S（0.5-1 天） | 格式化重构 + 预算重算 + 测试；风险取决于门槛 #1 |
| B1 | S（0.5-1 天） | 优先级信号量 + 接线 + 并发测试 |
| B2 | M–L（1.5-3 天；AGE 启用则 +0.5-1 天） | 2 后端实现（Neo4j / PG table）+ 委托链 override（facade/包装类/投影）+ 对拍矩阵 + IT；AGE 下推可选 |
| C1 | S（0.5-1 天） | prompt 修订 + 解析回归 |

---

## 附录 A：评审轮 1 处置表

**规划评审（review-plan-r1）must-fix：**

| 编号 | 评审要点 | 处置与证据 |
|---|---|---|
| MF-1 | A2 把截断前后混为一谈（称最终行含 file_path） | **已修正**：复核 `operate.py:5617-5649`（剥离副本后截断并覆盖变量）+ `utils.py:4082-4123`（返回前缀）→ 最终行 = `{entity,type,description}`/`{entity1,entity2,description}`；A2 重写为单一投影、无预算/渲染分离、无 A1 依赖；`unknown_source` 仅在抽取元数据边界 |
| MF-2 | A1 存储锚点错误（PG entities 无列、Arcade 属性、ALTER 锚点为 document_status） | **已修正**：A1 存储矩阵逐行重写（entities 加列 + 条件迁移样式参照 `:428-441` 但注明非复用；Arcade `:24` 为 sourceChunkIds 改加 filePath；AGE/Neo4j 读写点逐条列出） |
| MF-3 | 实体 filePath 不会沿管线传播 | **已修正**：新增显式传播清单——`MutableEntity`/`ensureEntity:157-174`/`mergeFrom`/`toEntity`、`viewOf:230`、`GraphManagementPipeline:603`、`QueryEngine:921`（StructuredQueryEntity 加字段）、`toEntity:661`、以及 9 个写入构造点 |
| MF-4 | 快照/向量投影/回放被低估 | **已修正**：修正「快照层不改」的错误表述——`SnapshotPayload`/`FileSnapshotStore` 会序列化 EntityRecord（null 容忍兼容旧档）；`HybridVectorPayloads.entityPayloads` 加入迁移面；回放 `:1105` 改为带 filePath 解析（`:898` 有 storedChunks、`:1134` 经 `chunkStore().load`）；文档图快照保持不改并说明理由 |
| MF-5 | FilePathLimits 只支持关系 | **已修正**：明确实体与关系共用 `maxFilePaths`/`method` 与同一 KEEP/FIFO/placeholder 语义；解除 javadoc 的 entity out of scope；`LightRagBuilder` 文档更新 |
| MF-6 | B1 把上游优先级错误缩成 embedding HIGH/LOW | **已处置（范围论证）**：上游 priority 仅在同队列内排序且队列共 4 个（`constants.py:671-673`、`llm_roles.py:52-57`）；summary 8 vs processing 10 的可见场景是共用 EXTRACT 队列，而 Java summary/extract 为独立池（`LightRag.java:965-966`）→ 无可观测面；embedding 是唯一跨用途共享池（`operate.py:5347-5349`），本轮只移植它，chat 侧维持 FIFO 并记录偏离理由 |
| MF-7 | B2 统一契约非上游契约 | **已修正**：对拍基准改为 Java 默认实现（`GraphStore.java:123-261`），明确上游各后端规则互不相同、仅参照实现骨架；补短 hash 与完整行号 |
| MF-8 | C1 schema/输入隔离/continue 规则不兼容 | **已修正**：C1 表新增输入 fence/source-of-truth、模板安全、转义、端点一致性、continue 空数组；键名明确「保留 Java 并显式声明不 translator」；端点约束仅在 prompt 层吸收、装配兜底不变（与上游 prompt-only 约束一致）；gleaning 次数差异如实记录为单独立项 |
| MF-9 | chunk-id 断言与迁移方案不可靠 | **已修正**：时间线复核为 3c4aa8a08（2026-03-08 格式首现）→ 8effb8c21（2026-05-08 helper/显式 id/碰撞 hash）→ dda9d6763（2026-07-17 加固）；明确不做全库改写，改为版本化映射 + 双读/回填预案 |

**规划评审 should-fix / nit：**

| 编号 | 处置 |
|---|---|
| SF-1（A2 budget 不能只换 formatter） | 已处置：token 计数与渲染同投影（上游剥离只为预算，Java 行内本无 file_path）；`limitByTextTokens` 前缀截断语义保持并在测试锁定 |
| SF-2（B2 facade 无 override） | 已修正：接点写明 facade 加等位 override（`Neo4jGraphStore:10-74` 现仅 CRUD） |
| SF-3（测试矩阵缺持久化面） | 已修正：A1 测试覆盖 FileSnapshotStore round-trip/旧档、entityPayloads、回放保路径、Arcade/provider 读写、PG 迁移幂等 |
| SF-4（构造点数量错误） | 已修正：改为「实施前 rg 生成清单」并列出当前已知 main 写入 9 处 + 读侧 4 处 |
| SF-5（section context 已存在） | 已修正：C1 第 5 行标为「已存在，仅补一句 + 回归测试」 |
| SF-6（阶段依赖重排） | 已修正：顺序改为 A1 → A2 → B1 → B2（契约先行）→ C1；chunk id 单独立项 |
| SF-7（envelope 需明确） | 已修正：明确「只对齐记录行」，`ContextAssembler` 分节保持，补请求快照测试 |
| nit（LightRagBuilder 文档/GraphStore 注释锚点/unknown_source 边界/BFS 短 hash） | 均已并入 A1-7、B2、附录说明 |

**框架方向评估（review-frameworks-r1）：**

| 项 | 裁定 | 处置 |
|---|---|---|
| A1/B1/B2/chunk id | 一致 | 按规划实施（B1 限定 embedding 的边界与评估结论一致） |
| A2 | 部分一致（称需补 `created_at`） | 已按上游最终行事实收敛：最终行不含 `created_at`（`:5617-5649` 剥离），Java 无需补字段；数据面 `created_at` 记入「规划外缺口」为有意裁剪 |
| C1 | 部分一致 | 已补 source-of-truth/模板安全/转义/端点一致性为明确验收项（C1 表 1/2/3/4） |
| 缺口①角色级并发/超时配置 | — | 记入「规划外缺口」，单独立项 |
| 缺口②LLM JSON 错误恢复 | — | 记入「规划外缺口」，C1 之后评估 |
| 缺口③created_at 模型 | — | 记入「规划外缺口」，本轮有意裁剪 |
| 无法核实（aiplatform 消费/生产 benchmark） | — | 门槛 #1/#2 保留为实施前置；性能收益以对拍与实现路径论证，不承诺 benchmark 数字 |

---

## 附录 B：评审轮 2 处置表

**规划评审（review-plan-r2，裁定「需修订」）：**

| 编号 | 评审要点 | 处置与证据 |
|---|---|---|
| 新增①（MF-3 未闭环）A1 全链路传播 | 正常入口 `mergeEntity:74-112` 未接 filePath；`GraphManagementPipeline.toEntity`、`GraphVectorIndexer.toEntity`、Local/Global 查询策略、MySQL provider、Arcade one-shot 转换点漏列 | **已修复**：§A1-1 补 8 个 `new Entity(` 转换点全表（实测）；§A1-2 补 `mergeEntity:74-80` 增参与调用点 `assemble:29-33`（并注明主链经 refinement 无旁路，`IndexingPipeline:1247/1250`、`GraphMaterializationPipeline:945`）；§A1-3 载明实测计数（写 10 + 读 5）；§A1-8 测试覆盖转换点 |
| 新增② AGE 写入锚点错误 | 原稿称实体 `file_path` 写点为 `:422`，该行实为 `source_id` | **已修复**：§A1-4 AGE 行改述 `entityProperties:416-423` 增列、`toEntityRecord:439-452` 读回（本机复核 `PostgresAgeGraphStore.java:416-452`） |
| 新增③ `unknown_source` 边界自相矛盾 | 边界解析为哨兵 vs 存储层保持空串、避免占位符进路径集合，二者矛盾 | **已修复**：§A1-7 重写——哨兵是合法路径值并按上游进入路径集合（`operate.py:1788-1790`、`_surviving_chunk_file_paths:1727-1733`）；`""` 仅表「无记录」；已 grep 确认主链无其它哨兵过滤 |
| 新增④ C1 continue 键名错误 | 规划示例 `relationships` 与 Java 保留 `relations` 冲突 | **已修复**：§C1-6 改为 Java 键 `{"entities": [], "relations": []}` 并注明不照抄上游键名 |
| 新增⑤ A2 提交归因不准确 | 首次移除是 `d3fde6093`（2025-08-18）；`37d01e2df`（2025-09-15）改为对副本操作 | **已修复**：一览表 A2 行与 §A2 双处更正（`git show -s` 复核两提交 message/日期） |
| SF-1 预算计数 | Java 逐行累加未计分隔符，与上游整串计数不等价 | **已修复**：§A2 明确 `String.join("\n")` 整串计数 + 最终串复核 + 边界测试（同框架补强①） |
| SF-3/SF-4 清单不全 | 构造/转换清单仍漏查询策略、`GraphVectorIndexer`、MySQL provider、Arcade | **已修复**：§A1-1/§A1-3 全表 |
| nit（注释/短 hash） | `GraphStore:124-130` 仍留「mirroring upstream networkx」；BFS 各后端未逐项给短 hash | **已修复**：B2 增「实施时更新 javadoc 为 Java 基准契约」条款；各后端短 hash 并入一览表 B2 行与 B2 后端表 |

**框架方向评估（review-frameworks-r2，裁定「基本一致，2 项需补强」）：**

| 项 | 处置 |
|---|---|
| ① A2 预算未计换行分隔符 | **已修复**：并入 §A2（`utils.py:4104-4123` 计数语义 + `limitByTextTokens` 改法 + 验收测试） |
| ② 快照回放调用面未闭合 | **已修复**：§A1-6 补 `GraphChunkAttribution:57`（保持无路径、只读 id 不落盘）与物化 `:563`、verify `:898` 的解析器注入；明确 `:756`/`:855` 同属只读 id 消费方 |
| 独立核对确认项（A2 最终行、B1 论证、快照序列化面） | 无异议，维持原文 |
| 无法核实（aiplatform 消费 `assembledContext`、大图 benchmark） | 维持门槛 #1/#2 为实施前置；不承诺 benchmark 数字 |

---

## 附录 C：评审轮 3 处置表

**规划评审（review-plan-r3，裁定「需修订」，4 项均为「目标态写成已核验事实」类措辞/验收问题）：**

| 编号 | 评审要点 | 处置与证据 |
|---|---|---|
| ① A1 全链路处置仍把目标态写成已核验事实 | 8 个构造点当前无 `filePath`；`mergeEntity:32/74-80` 未传路径；字面 `rg "new Entity("` 只中 6 处（2 处全限定） | **已修复**：§A1-1 改「实施目标：`Entity` 构造点全表（8 处；现状全部无 `filePath` 参数，本轮为「新增」而非「补传」）」+ 逐点实参 + 完成判据 `rg -n "new (\w+\.)*Entity\("`；§A1-2 `mergeEntity` 标「**现状** → **变更**」三处（签名/调用点/旁记）；§A1-3 与全局约束的 rg 模式改全限定式；风险表同步 |
| ② AGE 仅修锚点，未证明读写设计与现状闭合 | `entityProperties:416-423` 现仅 `source_id`；`toEntityRecord:439-452` 只构造 `sourceChunkIds` | **已修复**：§A1-4 AGE 行两处均标「**待实施变更**」、属性缺失默认 `""`（`textOrDefault(properties.get("file_path"), "")`）、验收指向 §8；§A1-8 Testcontainers 行补「含 AGE 往返 `file_path`、旧图缺属性读为 `""`」 |
| ③ `unknown_source` 实体侧尚未由现状支持 | `filePathOf` 返回哨兵、关系侧已接收；实体侧无路径参数/存储字段 | **已修复**：§A1-7 拆分「关系侧现状（`addFilePath:476-480` 仅滤空白）/实体侧本轮实施目标（依赖 §2 路径传播落地）」；§A1-8 补实体与关系双侧测试（哨兵进入路径集合、空值不进入） |
| ④ C1 continue 规则仍不完整 | 模板只有 "same schema"；system prompt "Use empty arrays"（`:105`）不替代 continue 专属约束 | **已修复**：§C1-6 补显式规则文本 `If no entity or relationship was missed, return {"entities": [], "relations": []} and do not invent increments.`（Java 键名）；§C1 测试行补 continue 规则与键名断言 |

**框架方向评估（review-frameworks-r3，裁定「一致」）：**

| 项 | 处置 |
|---|---|
| A2 预算（`String.join("\n")` 计数 + 最终串复核）与 A1-6 回放调用面（`:898`/`:1134` 注入路径 vs `GraphChunkAttribution:57`/状态核对/journal 只读 id） | 抽查核对一致，未发现新的方向性不一致，维持原文 |
| 抽查记录（上游 `utils.py:4104-4123`、`operate.py:5626-5649`、Java 回放/ID 生成） | 无补强项 |

---

## 附录 D：评审轮 4 处置表

**规划评审（review-plan-r4，裁定「通过」）：** 复核轮 3 四项处置——§A1-1/2/3 实施目标与计数（8 个 `Entity` 构造点、15 个 `EntityRecord` 构造点）、§A1-4 AGE 待实施与缺失属性默认值、§A1-7/8 `unknown_source` 双侧测试、§C1-6 continue 规则与 Java 键名；抽查源码/上游锚点未发现新的 must-fix。

**框架方向评估（review-frameworks-r4，裁定「一致」）：** §C1-6 规则文本与 `KnowledgeExtractor` 保留 schema（`entities`/`relations`）一致；§A1 目标态表述、全局约束（含全限定类名检索）无新冲突；门槛与 chunk id 暂缓决策未受影响。

**轮 4 自核补全（PG table 适配层 / B2 委托链闭合）：** §B2 接点原仅覆盖「实现点 + Neo4j facade」；实测 main 全仓除 `GraphStore.java:135` 默认实现与 `LightRag.java:488-497` 调用点外**无任何 `getKnowledgeGraph` override**——各栈经 `provider.graphStore()` 暴露的是包装类，不增委托则下推永远落回接口默认实现（全量物化）：

| 包装层（须增 `getKnowledgeGraph` 委托） | 位置 | 服务路径 |
|---|---|---|
| `PostgresStorageProvider.LockedGraphStore` | `:604-650`（`graphStore():233-234` 返回；`:188-190/:214` 包装后端选择） | **PG table** / AGE（纯 PG 栈） |
| `Neo4jGraphStore` facade | `:10-74` | 纯 Neo4j 栈 |
| `PostgresNeo4jStorageProvider.MirroringGraphStore` | `:268`（读经 `coordinator.graphStore()`） | PG+Neo4j |
| `PostgresMilvusNeo4jStorageProvider.MirroringGraphStore` | `:852`（同上） | PG+Milvus+Neo4j |
| `MySqlMilvusNeo4jStorageProvider.LockedGraphStore` | `:621`（delegate `coordinator.graphStore()`，`:160`） | MySQL+Milvus+Neo4j |
| `Neo4jGraphStorageAdapter.WorkspaceStoreProjection` | `:132-223`（`graphStore():33-35` 返回） | 组合栈 Neo4j 投影层（必经） |

已核无需改动：`PostgresAgeGraphStorageAdapter.graphStore():35` 裸返回存储；`StorageCoordinator.StagedGraphStore:490` 仅写入暂存（`:104` 创建、公开 `graphStore():46` 不返回）不在查询路径；InMemory 保留默认实现。处置：并入 §B2 接点（实现/委托两层 + 无需改动清单）、§B2 测试（经 `provider.graphStore()` 的委托链断言）、一览表与工作量行。

---

## 附录 E：评审轮 5 双轨复核结论（无修订）

**裁定（2026-10-02）：规划轨「通过」、框架轨「一致」——B2 委托链补全逐条核验无误，无 must-fix。** 原定 Codex 评审轮 5 因工作区额度耗尽失败（exit 2、未产出），经确认改由 Qoder 内置子代理（独立上下文、只读）执行，问题集与输出要求沿用 `prompt-plan-r5.md` / `prompt-frameworks-r5.md`；产物 `review-plan-r5.md` / `review-frameworks-r5.md`。

- **规划轨（核验 §B2 四条主张）**：`LightRag.java:488-497`（`:495` 经 `provider.graphStore()`）精确；枚举 main 全部 11 个 `GraphStore` 实现类逐链闭合——六个包装层锚点全部精确、无遗漏无误列；`StagedGraphStore:490` 排除理由成立（`:104` 仅 `writeAtomically` 内创建、公开 `graphStore():46` 不返回）；默认实现经 `this.allEntities/allRelations` 虚分派，包装层确实截留下推；附录 D 行号逐项吻合；抬头/一览表/§B2/工作量/附录 D 相互一致。
- **框架轨（方向收口）**：委托为只读转发，沿各包装既有读锁/镜像路径，不改锁面与镜像语义；不新增配置、不动 `PostgresStorageConfig`；与上游语义（各后端直接暴露存储）及门槛/暂缓决策无冲突；补全对全部内置装配路径（`config`/`dataSource` 构造均经 `WorkspaceStoreProjection`）必要且完备。
- **循环收敛**：轮 1–5 全部处置闭合；未写业务代码、未提交。（轮 6 设计层复审另发现 4 should-fix + 3 nit，见附录 F。）

---

## 附录 F：评审轮 6 处置表（设计层复审）

评审者：Qoder 主会话直接复审（抽查前 5 轮覆盖较少的 A1 契约/组装、A2 预算与端点解析、B1 接线、C1 prompt 共 20 余处锚点，全部与源码吻合；问题集中在设计层）。裁定：**无 must-fix，4 should-fix + 3 nit**。

| 编号 | 评审要点 | 处置与证据 |
|---|---|---|
| SF-A（B2 范围与 AGE 定位冲突） | §B2 把 `PostgresAgeGraphStore` 列为必做实现点；但 AGE 已定位为 Neo4j 对照系（2026-10-02 决策：不追加生产级加固，除非对照本身需要），BFS 下推属性能加固 | **已修订**：§B2 标题/实现点/后端表均改「AGE 可选，门槛 #2 触发」；门槛 #2 增「AGE 对照是否涉及大图视图」；工作量 B2 改 M–L（2 后端 + AGE 可选）；AGE 仍参与对拍矩阵（以默认实现身份验证委托链不截留） |
| SF-B（A2 端点 id→name 映射取错集合） | 原稿按 `context.matchedEntities()` 建映射，但该列表已被 token 预算截断（Local `:85`/Global `:81`），被截端点全部回退 id；且 Hybrid `:57-58`/Mix `:78-79,142-143`/`QueryEngine:630/822/834` 在已截断上下文上重限/重组装，无处取得截断前实体集 | **已修订（方案改变）**：§A2 改为**端点统一渲染 entityId、不引入映射表**——entityId = `normalizeKey(name)` = `strip().toLowerCase(ROOT)`（`GraphAssembler:193-195`、`:327-329`、`:163`），语义即名称；穿透映射需改 `QueryContext` 公开形状或 10+ 调用面，与「不影响 API 形状」冲突；混用可解析/回退会致同节大小写不一致。差异记入风险表，由评估门把关；备选（退化时）为 `Relation` 补端点显示名组件单独立项 |
| SF-C（A2/C1 缺评估回归门） | 两项改 LLM 面文本，验收只有 build + IT | **已修订**：「全局约束与验收」增评估回归门——A2 走 `eval_rag_quality_java.py`（`sample-default` 基线需先 `--update-baseline` 生成）；C1 须 `LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY=false`（默认 retrieval-only 跳过 chat 抽取，对 C1 无感；既有 retrieval-only 基线不可直接复用）；结果留档 `results/`，超阈值不通过 |
| SF-D（B1 漏第三个 embedding 消费方） | `ops/RebuildVectorIndexService:116` 直接调 `budget.limitEmbedding(...)`，原稿只列 `LightRag:967/1055/1194-1198` | **已修订**：§B1 接线补该处显式 `LOW`（不依赖旧重载默认委托），以 `rg -n "limitEmbedding\("` 清单为准（main 3 处）；测试补 `RebuildVectorIndexServiceTest` 断言 |
| nit-1 | 阶段 0 `LightRag.java:488-495` 与 §B2 `:488-497` 不一致 | **已修订**：统一为 `:488-497`（实测方法体） |
| nit-2 | §A1-1 称 `strip()` 「镜像 `RelationRecord:319`」，该行仅 null→`""` 不 strip | **已修订**：改述为「null 容忍镜像、strip 为新增，实施时同步给 `RelationRecord` 补 strip」 |
| nit-3 | §A1-5 「第 7 参」未说明现用 4 参重载 | **已修订**：写明 `EnrichedVectorRecord` 全量构造器第 7 组件（`HybridVectorStore:18-25`）vs 现 4 参重载（`:63-68`），改走全量构造器或新增 5 参重载；`entityPayloads` 现无直接单测，新建 `HybridVectorPayloadsTest` |

**轮 6 复核**：双轨子代理复核结论见附录 G。

---

## 附录 G：评审轮 6 双轨复核结论（无 must-fix）

**裁定（2026-10-02）：规划轨「通过」、框架轨「一致」。** 由 Qoder 内置子代理（独立上下文、只读）执行，提示词 `prompt-plan-r6.md` / `prompt-frameworks-r6.md`，产物 `review-plan-r6.md` / `review-frameworks-r6.md`。

- **规划轨**：附录 F 七项处置全部成立——SF-A 五处（标题/实现点/后端表/门槛 #2/工作量）一致且与附录 D/E 无矛盾；SF-B 论证成立，并排查反例：`new ScoredEntity(` 5 处均取存储 `entity.id()`，`GraphManagementPipeline:48/105` 手工实体 id 亦经 `normalizeKey`（`:561-566`），`IndexingPipeline`/`GraphMaterializationPipeline` 沿用 `existing.id()`，未发现不以 normalizeKey 生成 id 的路径；alias 合并不改 `name`，「id == normalizeKey(name)」不变量成立；SF-C 基线/`RETRIEVAL_ONLY` 默认值（`eval_retrieval_quality_java.py:103`、`RagasBatchEvaluationCli:101-102`）与文档一致；SF-D `limitEmbedding(` main 恰 3 处；nit 三项锚点均准确。
- **框架轨**：A2 统一 entityId 相对现状（`formatRelation:36-43` 本就渲染 `srcId -> tgtId`）零退化，备选（`Relation`/`RelationRecord` 加端点名）需改公开 record + 4 后端回填，属补强而非方向错误；B2 AGE 降级自洽（同一 `LockedGraphStore` 包装两后端，默认实现经虚分派结果必一致）；评估门与既有脚本匹配、前置成本合理。两条非方向性提示已并入正文：AGE 对拍行仅证「结果一致」、委托链有效性由 PG table 行承担（§B2）；RAGAS `contexts` 为 chunk 级、A2 只经 answer 侧指标可见、6 例样本 + 0.02 阈值需先估噪声（验收节）。
- **循环状态**：轮 1–6 全部处置闭合，无未处置 must-fix；本版为当前最终版。未写业务代码、未提交。
