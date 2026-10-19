# Java LightRAG 本体二期实现计划：属性物化 + 确定性 bootstrap + OWL/TTL/SHACL 互操作

**状态：** r4 已评审通过（Codex read-only，2026-10-19：R-M1–R-M4/R-S1–R-S3/NIT 全部 CLOSED，1 项非阻塞 SHOULD 已并入）；可进入实现
**日期：** 2026-10-19
**前置计划：** `docs/superpowers/plans/2026-10-13-java-lightrag-ontology-extraction-and-governance-plan.md`（Phase 1，L1–L5 已实现）
**参考实现：**
- Semantica `D:\ai-code\semantica` @ `ca6c9f19`：`bootstrap_schema`、`OntologyGenerator`/`PropertyGenerator`、`OWLGenerator`/`SHACLGenerator`、`ExtractionSchema.from_owl`、`SchemaValidator`
- GraphRAG-SDK `D:\ai-code\GraphRAG-SDK` @ `3196bb4`：`core/models.py` 的属性模型、`graph_extraction.py` 的属性 prompt/coerce/aggregate、`SET n += props` 存储语义
- 宿主计划：aiplatform `docs/plans/2026-10-13-ontology-asset-and-governance-plan.md`

---

## 0. 结论先行

Phase 1 的决策点 5 选择了**方案 A：属性只声明、只进 prompt、不落图**（Phase 1 计划 `:397`）。本计划就是当时明确"须独立立项、重评六后端改动"的**方案 B**；它不是 Phase 1 预留的"2 期"，而是 Phase 1 之后新开的二期。

二期把本体闭环补成 **"归纳 → 审阅 → 约束抽取"**：

| 能力 | 现状 | 二期落点 | 参考 |
|---|---|---|---|
| **属性物化 + 属性级校验** | 本体可声明属性，prompt 渲染属性说明，但抽取 JSON 没有 `attributes`，类型/记录/存储/快照/API 全链路无属性承载；校验只到 label/pattern | 2A | GraphRAG-SDK（提取/coerce/merge）、Semantica（属性推断） |
| **确定性 bootstrap** | 只有 LLM discovery，成本高、不可复现；无"从既有抽取样本归纳草稿"的路径 | 2B | Semantica `bootstrap_schema`（频率门 + draft 永不自动应用 + TTL） |
| **OWL/TTL/SHACL 互操作** | 无 RDF 能力；Phase 1 D0 明确不引入 OWL/RDF/reasoner | 2C：core 零依赖导出；可选 `lightrag-rdf` 模块用 RDF4J 导入 | Semantica `OWLGenerator`/`SHACLGenerator`/`ExtractionSchema.from_owl` |

**硬约束（用户已定）：** 本体多版本必须**永久保留**，**不得定时/自动删除**。SDK 不写版本、不删版本；bootstrap 与 RDF 导入只产草稿，不分配版本。版本追加、`current_version` 指针、KB `pinned_version` 全部归宿主（见 §6）。

**Tech Stack：** Java 17、Gradle 多模块、JUnit 5、AssertJ、Jackson 2.18.3。`lightrag-core` 不新增依赖；新增可选模块 `lightrag-rdf` 才引入 RDF4J（见 2C）。

**评审后的关键定稿（r1 见 §14.1–14.4，r2 见 §14.5，r3 见 §14.6，r4 见 §14.7）：**

1. typed attribute schema **由 `OntologyTaskContext.ontology()` 确定性派生**，不进入 `OntologyExtractionOverlay`、不写任务上下文 JSON——任务 context `schemaVersion=1` 与 fingerprint 编码保持不变（M4）。
2. bootstrap 输出**完整候选**（保留 base 中未被覆盖的定义），只走 `EVOLUTION_REPLACE` 审批；不改 `REGISTER_INCREMENTAL` 的禁改规则（M6）。
3. RDF 属性声明**一律 owner-scoped**，IRI 按 `kind/owner/property` 分层严格百分号编码（单射、无碰撞回退）；`lg:owner` 双向映射（M7 / R-M3）。
4. 新增 **§3.8 属性守恒清单**：评审 grep 出的 70 处构造调用逐项定性，禁止用旧构造器掩盖漏传（M1）。
5. 文件快照 manifest 升 v2 并做版本校验；数据库快照维持缺失字段→空 map（M4）。

**r2 评审后的关键定稿（详见 §14.5）：**

6. 属性 schema 一律从 **canonical ontology** 派生，物化键一律为 `Identity.identityId`；display name 只留在本体/prompt，不进入图/快照/API。首次执行与 `writeContext/readContext` 恢复执行得到相同 prompt 段落与属性键（R-M1）。
7. 属性处理顺序固定为：`sanitizeRaw` 输出保留全部原始键值对的候选列表 → `OntologyAttributeBinder` 在记录构造前完成 schema 匹配/coerce/identity 重复裁决/caps 并产生诊断 → 构造不可变记录 → 合并 → 最终投影；重复裁决在 coerce 之后，诊断经 `KgExtractionValidator.onAttributeDiagnostics` 转发（R-M1 / r3 R-M1）。
8. bootstrap 的拼写计票、support 身份、诊断排序、冲突计数与 `algorithmVersion` 全部定稿，乱序输入生成**完整 `BootstrapResult` 相等**（R-M2）。
9. RDF IRI 改为 `kind/owner/property` 分层 + 严格百分号编码，**无碰撞回退、无"事后加 hash"**；base IRI 统一要求以 `/` 结尾（R-M3）。
10. RDF4J 锁定 **5.3.2**（Java 11+，6.x 需 Java 25，不适用于本项目 Java 17）；XML/JSON-LD 安全设置逐项断言，设置不可用即拒绝格式（R-M4）。
11. `lightrag-rdf` 接入现有 Vanniktech 发布约定：GAV、POM、签名、sources/Javadoc 与 core 对齐，公开 API 暴露 core 类型时用 `api(project(":lightrag-core"))`（R-S1）。

**r3 评审后的关键定稿（详见 §14.6）：**

12. 原始属性候选使用 `List<RawAttribute>` 而非 `Map`：trim/identity 撞键（如 `{"Age":1," Age ":2}`）不丢候选；绑定入口固定在 parse/window parse、记录构造之前，`OntologyConformanceValidator` 不再承担属性投影；bootstrap 样本规范化复用同一 identity 分组规则（r3 R-M1）。

**r4 评审后的关键定稿（详见 §14.7）：**

13. 属性诊断的排序/去重键对空白安全：`rawLabel` 为空白时映射为固定哨兵，不调用 `Identity.identityId("")`（该函数对空白输入抛 `IllegalArgumentException`）；诊断记录保留原始空字段，补对应验收（r4 S1）。

**垂类无关：** 所有能力只处理 schema 原语，不含行业/客户词；垂直差异由宿主传入的本体与样本表达。

---

## 1. 现状（已核，file:line 为锚点）

### 1.1 Phase 1 已交付

- 本体模型/治理/codec：`Ontology`、`OntologyEntityType`、`OntologyRelationType`、`OntologyProperty`、`OntologyPattern`、`SourceBinding`、`Identity`、`OntologyGovernance`、`OntologyJsonCodec`。
- 注入：`OntologyExtractionOverlay`（patterns + attributeBlocks + mode）、`KnowledgeExtractor.renderGraphExtractionGuidance()`。
- 过滤：`OntologyConformanceValidator`（STRICT/PREFER/OPEN）+ refinement 后校验挂点。
- discovery：`io.github.lightrag.ontology.discovery`（LLM 提议草稿）。
- 检索侧纯函数：`OntologySchemaRenderer`、`CypherOntologyChecker`、`PhysicalGraphProfile`。

### 1.2 属性链路为空

- **抽取类型无属性**：`types/ExtractedEntity.java` 只有 `name/type/description/aliases`；`types/ExtractedRelation.java` 只有 `sourceEntityName/targetEntityName/keywords/description/weight`。
- **prompt 有说明、输出无字段**：`KnowledgeExtractor.SYSTEM_PROMPT_TEMPLATE`（`:59`）只声明 name/type/description/aliases；属性块在 `renderGraphExtractionGuidance()`（`:928-935`）只作为文字说明追加；`Output Rules` 的 JSON 形状（`:93-111`）没有 `attributes`。模型即使"理解"属性，也无处返回。
- **解析器丢弃属性**：`parseEntities`（`:658`）与 `parseRelations`（`:699`）只读既有字段；`parseWindowEntities`/`parseWindowRelations` 同理。
- **领域/存储/快照/API 全无属性**：
  - `types/Entity.java:8`、`types/Relation.java:9`
  - `storage/GraphStore.java:246`（`EntityRecord`）、`:281`（`RelationRecord`）
  - `storage/DocumentGraphSnapshotStore.java:75`（`ExtractedEntityRecord`）、`:89`（`ExtractedRelationRecord`）
  - `api/GraphEntity.java:6`、`api/GraphRelation.java:8`
  - `GraphViewTraversal.viewOf`（`:209/:229`）
- **refinement 候选无属性**：`WindowEntityCandidate`/`WindowRelationCandidate`/`DefaultExtractionMergePolicy` 只搬运既有字段。
- **既有 record 重建点会静默丢属性**（保留旧 arity 构造器后仍能编译，因此必须逐点迁移）：`GraphMaterializationPipeline.toChunkExtractions`（`:1233/:1241`，快照 resume/repair）、`DeletionPipeline`（`:394/:409`）、`GraphManagementPipeline`（`:48/:70/:105/:175/:255/:530/:556/:593/:605/:618/:630/:685`）、`PostgresMilvusNeo4jStorageProvider`（`:904/:916/:956/:992`）、`MySqlMilvusNeo4jStorageProvider`（`:373/:385`）、`KnowledgeExtractor.sanitize`（`:1139`）、`DefaultAttributionResolver`（`:105-110` 的 `putIfAbsent` 会丢弃重复候选的互补属性）。完整清单与处置见 §3.8。
- **overlay 只有文本块**：`OntologyExtractionOverlay` 的 `attributeBlocks` 是 `Map<String,String>`，解析器拿不到"哪个 label 声明了哪些属性/类型"，无法做 coerce。
- **结构校验不查属性名冲突**：`OntologyStructureValidator.validateProperties`（`:126`）只查重复 identity；未处理与内核固有字段（`name`/`type`/`description`/`aliases`/`source_entity` 等）的碰撞。

### 1.3 校验覆盖面与既有偏差

- Phase 1 已在 refinement 合并后补了 per-chunk 校验（`IndexingPipeline.validateRefinedExtractions`、`GraphMaterializationPipeline.validateRefinedExtractions`），但**没有"最终类型投票后的属性投影"**：实体类型在 `IndexingPipeline.mergeEntityGroup`（`:2043-2061`）与 `GraphMaterializationPipeline.mergeEntityBatch`（`:1618-1635`）被投票改变；若不为最终类型重新投影属性，会出现"最终类型是 A、属性却来自类型 B"的脏数据。
- **既有偏差（Phase 1 引入，非本计划新增，须评审）**：Phase 1 D1/D4 冻结的关系谓词是 **∀**（`T(kw)` 非空 ∧ 每个受约束的命中类型都完整匹配端点对），但 `OntologyConformanceValidator.validateRelation`（`:221-244`）的实际行为是：**没有任何受约束类型（所有命中类型 patterns 为空），或至少一个受约束类型匹配**。实现中 `break` 在首个匹配后放行，其他受约束类型不再检查；空 patterns 的类型**不会**让一个不匹配的受约束类型自动通过。两者仅在"多命中类型且其中一个匹配"时分歧，属于实现偏差。本计划建议作为 2A-5 独立提交修正（见 §13 Q11 / §14 S2）。

### 1.4 存储现状（六后端 + 内存）

- Neo4j/Memgraph：`WorkspaceScopedNeo4jGraphStore.saveEntity`（`:1256`）/`saveEntities`（`:1284`）/`saveRelation`（`:1326`）/`saveRelations`（`:1387`）显式 `SET` 字段；Memgraph 复用该 store。
- PostgreSQL 关系后端：`PostgresGraphStore.saveEntity`（`:54`）/`saveRelation`（`:80`）；DDL 在 `PostgresSchemaManager` 的 `entities`（`:154-163`）与 `relations`（`:181-193`）；已有列迁移先例 `ensureEntityFilePathColumn`（`PostgresSchemaManager.java:502-514`）。
- Apache AGE：`PostgresAgeGraphStore.saveEntity`（`:64`）用 `SET n +=`；`saveRelation`（`:79`）把边属性**内联在 CREATE 中**（AGE 忽略 `SET r +=`）。
- FalkorDB：`FalkorDbGraphStore.saveEntity`（`:90`）`SET n += $properties`；`saveRelation`（`:106`）`SET r +=`。
- ArcadeDB：`ArcadeGraphStore.saveEntity`（`:17`）/`saveRelation`（`:30`）按字段写 document 属性；schema 在 `ArcadeSchemaManager`（Entity 从 `:79` 起，Relation 在 `:88` 起）。
- NebulaGraph：`NebulaGraphStore.saveEntity`（`:66`）/`saveRelation`（`:75`）；schema 常量 `NebulaSupport.CREATE_TAG_STATEMENT`（`:40`，SQL 字符串 `:41`）与 `CREATE_EDGE_STATEMENT`（`:45`，SQL 字符串 `:46`）；`INSERT VERTEX/EDGE` 显式列清单。
- InMemory：`InMemoryGraphStore.saveEntity`（`:34`）/`saveRelation`（`:46`）直接存 record。
- 快照 codec：`JdbcJsonCodec`（Postgres）、`MySqlJsonCodec`、`ArcadeJsonCodec` 共用 `DocumentGraphSnapshotStore` 的记录类型；`ObjectMapper` 默认 `FAIL_ON_UNKNOWN_PROPERTIES=true`。

### 1.5 无 bootstrap、无 RDF

- 生产源码没有确定性 bootstrap；只有 `discovery`（LLM 密集、按模型/采样漂移）。
- 没有 Turtle/OWL/SHACL 的导出或导入；Phase 1 D0 明确不引入 OWL/RDF/reasoner。
- `OntologySchemaRenderer.RenderOptions.includeDeclaredProperties` 默认 `false`（声明在 `:35`，默认值在 `:38-39`），Phase 1 因属性未落图而冻结为 false。

### 1.6 版本与删除

- 宿主 `km_ontology_version` 是**不可变快照**，`km_ontology.current_version` 只是指针，KB 绑定 `pinned_version`；旧投影/旧版本保留（宿主计划 `:37-46`）。
- Semantica 的 `prune_versions`（`change_management/managers.py:362`）是**显式调用**，不是定时任务。本计划不采纳任何定时 prune。

---

## 2. 设计总览

```
                  ┌───────────────────────────────┐
   抽取样本 ──────▶│ 2B OntologyBootstrap          │──▶ draft Ontology ──▶ 宿主审阅/治理
   (host 传入)     │ 频率门 + 文档门 + 属性推断     │      (不分配版本)
                  └───────────────────────────────┘
                                 │
                  ┌──────────────▼────────────────┐
   宿主版本库 ────▶│ 2C RDF 导出/导入               │──▶ TTL/OWL/SHACL / 外部本体
   (pinned)       │ core: 零依赖写；rdf: RDF4J 读 │
                  └──────────────┬────────────────┘
                                 │
                  ┌──────────────▼────────────────┐
   LLM 抽取 ─────▶│ 2A 属性物化 + 属性级校验       │──▶ 图/快照/API 带 attributes
                  │ prompt → parse → merge → save  │
                  └───────────────────────────────┘
```

三条能力共享同一 `Ontology` 模型与 `Identity` 口径；2B/2C 的产物都只是**草稿**，不自动生效、不分配版本。

### 2.1 参考实现的采纳边界

| 能力 | Semantica | GraphRAG-SDK | 本计划 |
|---|---|---|---|
| 属性抽取/合并 | 属性生成器、类型格 | `Attribute` 模型、`_render_attribute_block`/`_coerce_attributes`、last-write-wins | 2A 采纳声明属性注入、coerce、逐键覆盖；不做无 schema 的任意属性收集 |
| 属性存储 | 图/文档 | `SET n += props` 扁平节点/边属性 | 六后端统一 canonical JSON 字符串；扁平化/索引另立项 |
| 本体获取 | `ExtractionSchema.from_owl` | `Ontology.from_file` 读取 **JSON 配置**（不是 RDF）/`from_sources`（LLM/grounded）、table mappings | 2C 采纳 RDF 导入；`from_sources`/表映射/backfill 排除（Phase 1 discovery 已覆盖 LLM 路径；evolution/backfill 见附录 A） |
| 确定性 bootstrap | `bootstrap_schema`（频率门/draft/TTL） | 无确定性 bootstrap | 2B 采纳；不采纳 LLM 驱动演化 |
| 版本 | 显式 `prune_versions` | ontology evolution/backfill | 宿主永久保留；不采纳任何定时清理 |

GraphRAG-SDK 的扁平属性便于其单后端查询；本项目要在六后端 + 快照/API 上保持同一形状，故选择 JSON 袋（见 §3.5、§12.1-4）。Java 只采纳其**子集**：不复制 `structured` 属性过滤、也不复制 coerce 失败写 `None` 的降级——Java 对声明属性逐值 coerce，失败丢键并回调；未知属性不收集。

---

## 3. 设计 2A：属性物化 + 属性级校验

### 3.1 D2A-1 属性值模型与规范化

**承载类型：** 所有记录新增末位组件 `Map<String,Object> attributes`。允许的值类型：

| 本体类型 | Java 值 | 说明 |
|---|---|---|
| STRING | `String` | `strip()` 后非空；空串丢弃 |
| INTEGER | `Long` | 拒绝 Boolean；数值按下方 canonical 规则向零截断（`BigDecimal`，不走 `int(float(v))`） |
| FLOAT | `Double` | 拒绝 Boolean/NaN/Infinity |
| BOOLEAN | `Boolean` | 接受 bool、数值（0=false）、`true/yes/y/1`、`false/no/n/0` |
| DATE | `String` | ISO `yyyy-MM-dd`；接受 ISO date/datetime（`Z`→`+00:00`），取日期部分 |
| LIST | `List<Object>` | 元素仅 String/Long/Double/Boolean；拒绝 Map/嵌套 List；标量→单元素列表 |

**规范化规则：**

- **null 语义分域（r1 M2）**：值对象（`ExtractedEntity`/`ExtractedRelation`/`Entity`/`Relation`/`GraphStore.*Record`/`GraphEntity`/`GraphRelation`/snapshot record）与 **create 请求**：null/缺失 → 空 map。**修改类请求**（`EditEntityRequest.attributes`、`UpdateRelationRequest.attributes`、`MergeEntitiesRequest.targetAttributes`）**保留 null**（null = 保持不变），仅非 null 才清洗/复制。该分域不得被 record 构造器的统一规则覆盖。
- 构造函数做防御性复制并冻结：键排序后的不可变 map + 不可变列表（深一层复制）；JSON 反序列化（含三种快照 codec 与文件快照）后同样复核。
- **键身份与冲突（r1 M3 / r2 R-M1 / r3 R-M1）**：键 trim、非空、长度 ≤ 128、无控制字符。`sanitizeRaw` 的输入是 JSON `attributes` 对象或样本 map，输出是 `RawAttributes`（**保留每个原始键值对的候选列表，不是 `Map`**）；`{"Age":1," Age ":2}` 必须保留两个候选，不得在 trim 后按 Map 键合并。schema 匹配、重复裁决、合并与持久化**一律使用 `Identity.identityId(name)` 作为键**。按 identity 分组后逐候选 coerce：任一个候选 coerce 失败 → 整键丢弃并回调 `INVALID_ATTRIBUTE_VALUE`；全部成功但 canonical 值不唯一 → 整键丢弃并回调 `ATTRIBUTE_DUPLICATE_KEY`；唯一则保留一个（键为 identity）。分组遍历与诊断输出按 `(identity, rawKey)` 排序，不依赖 JSON 顺序或 map 迭代顺序。无 schema 的手工输入同样按 identity 归并：值相等保留、不等抛 `IllegalArgumentException`。**不引入"声明 display name 择优"规则**：display name 只用于本体/prompt，不进入物化键；因此多个 owner 声明不同拼写不会影响输出键，也不存在 identity 排序无法裁决的问题。
- canonical JSON：键按自然字符串序排序、无多余空白、不写 `null` 值。**INTEGER 一律 `Long`**：整数入口做范围检查；浮点/字符串入口用 `BigDecimal` 向零截断，拒绝 `abs(value) > 2^53` 与 Long 越界，禁止饱和或经 Double 中转丢精度；**FLOAT 一律 `Double`**（拒绝 NaN/Infinity）；编码结果作为**唯一可比较/可哈希**形式。
- 上限（硬约束，防止属性袋无界）：单记录 ≤ 64 个键、canonical JSON ≤ **16 KiB（按 UTF-8 字节计算）**、LIST ≤ 64 元素、STRING 元素 ≤ 4096 字符、LIST 单元素 ≤ 1024 字符。解析期 `sanitizeRaw` 只施加**防 DoS 结构上限**：单记录原始候选 ≤ 256（超过则整个 `attributes` 对象丢弃并产生一条 `ATTRIBUTE_LIMIT_EXCEEDED`，`rawLabel` 为空串、`ownerLabel` 为当前 owner，不按顺序截断；该空 `rawLabel` 仅作记录字段，排序/去重必须走空白安全规范化，不得对空串调用 `Identity.identityId`，见 §3.3）、原始键长度 ≤ 128 且无控制字符、原始标量字符串 ≤ 64 KiB；它**不执行**上述 64 键/16 KiB/LIST 语义 caps。**语义 caps 在 coerce 与重复键裁决之后执行**：优先保留声明命中的键，其余按 identity 排序填充；超限丢弃并回调（`ATTRIBUTE_LIMIT_EXCEEDED`）；最终投影阶段的裁剪只做确定性收敛并记独立计数（不重复回调，见 S6）；同输入必得同结果。
- **固定处理顺序与执行入口（r2 R-M1 / r3 R-M1）**：`parseEntities`/`parseRelations`（普通路径）与 `parseWindowEntities`/`parseWindowRelations`（window 路径）在构造 `ExtractedEntity`/`ExtractedRelation` **之前**调用 `OntologyAttributeBinder.bindEntity/bindRelation`，依次完成：原始候选 → schema 匹配/coerce → 按 identity 裁决重复键 → caps → 返回 `(identity 键 map, List<UnmatchedLabel> 诊断)`。只有绑定完成后才构造不可变记录；随后 gleaning `merge`、`sanitizeAliasConflicts`（`:1139`）及其他重建点按 identity 键逐键合并。绑定器不抛异常，属性错误只丢键并产生诊断，因此 `:677/:724`、`:1050` 的构造 catch 永远不会因属性错误丢弃整条记录。验证器、投影器、`canonicalize` 不得再提前合并键。

**新增工具类（`io.github.lightrag.ontology`）：**

- `AttributeValues`：`sanitizeRaw(JsonNode) -> RawAttributes`（解析路径）与 `sanitizeRaw(Map<String,Object>) -> RawAttributes`（bootstrap/宿主路径），其中 `RawAttributes(List<RawAttribute> candidates, List<RawIssue> issues)`：保留每个原始键值对，只做防 DoS 结构上限，不做 identity 合并/类型 coerce；`coerce(Object, OntologyPropertyType)`；`canonicalize(...)`（对 identity 分组后的候选做裁决，输出 identity 键）；`merge(Map base, Map incoming)`（入参已是 identity 键；后者逐键覆盖）；`applyCaps(...)`。
- `RawAttribute`（**public 低层 record**：`String rawKey, Object value`；值已结构清洗）与 `RawIssue`（**public 低层 record**：`String rawKey, UnmatchedLabel.Reason reason`）；供 `AttributeValues`/bootstrap 使用，不属于常规业务 API。
- `OntologyAttributeBinder`：解析期唯一绑定入口；`bindEntity(schema, chunkId, type, JsonNode attributesNode)`/`bindRelation(schema, chunkId, keywords, JsonNode attributesNode)` → `BoundAttributes(Map<String,Object> values, List<UnmatchedLabel> diagnostics)`；内部先 `sanitizeRaw`，把 `RawIssue` 映射为带 owner/chunkId 的诊断，再按 `(identity, rawKey)` 排序裁决，返回 identity 键 map；不抛属性异常。
- `AttributeJsonCodec`：`toJson(Map) -> String`、`fromJson(String) -> Map`；使用 Jackson 2.18.3，`ORDER_MAP_ENTRIES_BY_KEYS`；解析失败返回空 map 并记 warning（不因单条坏数据阻断读）。
- `OntologyAttributeSchema`：`record(Map<String,List<OntologyProperty>> entityTypes, Map<String,List<OntologyProperty>> relationTypes)`；`from(Ontology)` **先 `ontology.toCanonical()` 再派生**，owner 与属性名均为 identity，内部按 `identityId` 建索引，提供 `entityProperties(label)`/`relationProperties(label)`；`empty()`。
- `OntologyAttributeProjector`：纯函数，按最终实体类型/关系关键词把属性投影到声明集合（见 3.4）。

> **兼容性总则：** record 追加组件会改变 canonical constructor。**每个被改 record 都保留旧 arity 构造器/重载**，委托新构造器并传空 map；旧二进制消费者仍可链接（新组件不参与旧构造器）。旧构造器**只允许用于真正创建空占位对象**；任何"已有对象→新对象"的重建必须显式传 attributes（清单见 §3.8）——保留旧构造器会让漏传在编译期不可见。`equals/hashCode` 会纳入 attributes（语义正确）；生产源码未发现以 `Entity`/`Relation`/`GraphStore.*Record` 为 key 的缓存或去重（去重按 id/identity），因此不影响既有路径，仍补回归测试。序列化形状变化用 `@JsonInclude(NON_EMPTY)` 控制（见 3.6），空属性时与 Phase 1 字节形状一致。

### 3.2 D2A-2 注入：typed attribute schema + 条件 prompt

**schema 派生（不扩 overlay，r1 M4 / r2 R-M1）：** typed schema **不作为** `OntologyExtractionOverlay` 的组件，也不写任务上下文 JSON；由 `OntologyAttributeSchema.from(context.ontology())` 在消费点**确定性派生**，且 `from` 内部先 `toCanonical()`。因此：
- 首次执行（内存中可能是带 display name 的 ontology）与 `writeContext/readContext` 后恢复执行（codec 已写 `ontology.toCanonical()`，见 `OntologyJsonCodec.contextToJson`）得到**同一 canonical schema**：属性键、属性段 prompt、投影结果一致。
- `OntologyExtractionOverlay` 保持 3 组件与序列化格式不变，任务 context `schemaVersion=1` 与 fingerprint 编码不变，历史任务无需迁移即可恢复。
- 属性段中的 owner/属性名使用 canonical identity；display name 仍保留在 ontology 记录与 overlay 的 patterns 段落中。这是为保证首次/恢复一致性的显式取舍（不新增持久化 display name 的 context v2）。

`KnowledgeExtractor` 新增**可选构造参数** `OntologyAttributeSchema`（旧构造器保留，默认 `empty()`）；两条 pipeline 由 `LightRag` 从同一 `OntologyTaskContext` 派生后注入。provider 仅提供 overlay、无 bound ontology 时 schema 为空 → **保持 Phase 1 的 legacy `attributeBlocks` 文本渲染以维持 prompt 兼容，但解析出的 attributes 一律丢弃**；要物化属性必须提供 `OntologyTaskContext.ontology()`。

**prompt 规则（对齐 GraphRAG-SDK `_render_attribute_block`）：**

- 仅当**至少一个**可抽取属性（排除内核固有字段，见下）存在时，才追加属性段与 `attributes` 输出形状；否则 prompt 必须与 Phase 1 **逐字节一致**（golden 测试）。
- 属性段写明：只抽取声明属性；未在文中出现则省略或 `null`；按声明类型输出；LIST 输出数组；DATE 用 ISO 8601；放在每条 entity/relationship 的 `attributes` 对象内。
- 属性段按**实体/关系分节**从 typed schema 渲染，owner 与属性都使用 canonical identity 并按其排序，保证同本体 prompt 字节稳定、首次/恢复一致（R-M1）；避免同名 label 的 block 互相覆盖（r1 M10；`OntologyExtractionOverlay.from` 原实现先实体后关系写同一 map）；仅当 provider 提供 legacy overlay 且 typed schema 为空时才回退现有 `attributeBlocks` 渲染。
- 输出 JSON 形状在现有 entity/relation 对象上**追加可选** `"attributes": {...}`；不改变既有字段名与关系字段。
- 既有 few-shot examples **不修改**：无属性路径保持字节一致；有属性路径只追加属性段与输出形状，不注入可能与声明冲突的合成示例（示例质量另立回归项）。
- **内核固有字段不物化**（对齐 GraphRAG-SDK `_RESERVED_ATTRIBUTE_NAMES` / Semantica `_CONTROL_FIELDS`）：
  - 实体：`name`、`type`、`description`、`aliases`
  - 关系：`source_entity`、`target_entity`、`relationship_keywords`、`relationship_description`、`weight`
  - 声明这些名字**不报错**（避免破坏既有本体），但判定为 reserved：prompt 不渲染、抽取不物化、`OntologyStructureValidator` 发 warning。**不新增 `Options` 开关**（避免公共 record 扩展；宿主可自行过滤 warning），r1 M2。
- 无本体/本体无属性时：parser **不保留** attributes（不做无 schema 的任意属性收集）。

### 3.3 D2A-3 解析、coerce、合并

**解析：**

- `parseEntities`/`parseRelations`/`parseWindowEntities`/`parseWindowRelations` 读取 `attributes` 节点并交给 `OntologyAttributeBinder`（schema 存在时）；binder 接收 `chunkId`（普通路径 `chunk.id()`，window 路径 `window.chunks().get(anchorChunkIndex).id()`），以便诊断直接构造成完整 `UnmatchedLabel`。binder 内部先 `AttributeValues.sanitizeRaw(JsonNode)` 得到 `RawAttributes`（候选 + 结构问题；保留每个原始键值对，**不构造 Map**，因此 trim/identity 撞键不丢候选），再按 identity 分组与后续裁决。字节完全相同的重复 JSON 键在 Jackson 读树阶段已按最后值合并，不属于本契约保证范围（如宿主需要更严格语义，应在 LLM 响应层另加校验）。
- binder 依次执行：`RawIssue` → 诊断；按 identity 分组 → coerce → 重复裁决 → caps；返回 identity 键 `Map` 与诊断。重复裁决在 coerce 之后，诊断**在构造记录之前**产生（§3.1 固定顺序）；因 owner 与 chunkId 在调用点已知，结构上限诊断也能带全字段。
- 诊断不进入 `ExtractionResult`：普通路径由 `parseExtractionResult` 把 binder 诊断写入 `extractWithCacheIds` 持有的 sink（解析失败时已产生的诊断同样保留），window 路径由 `parseWindowExtractionResponse` 收集（`WindowExtractionResponse` 增诊断列表，旧构造器保留）。`KnowledgeExtractor` 在 `extractWithCacheIds` 结束（primary + 全部 gleaning 合并后）和 `extractWindow` 结束时，把按 `(owner identity, 规范化 rawLabel, reason)` 排序的诊断通过 `kgExtractionValidator.onAttributeDiagnostics(...)` 转发一次；同一 chunk/window 内重复出现的同一三元组诊断先去重（primary 与 gleaning 对同一坏键只计一次）。**规范化只用于排序/去重键**：`ownerLabel` 与 `rawLabel` trim 后非空才调用 `Identity.identityId`，空白一律映射为固定哨兵 `"<empty>"`（`Identity.identityId` 对空白输入抛 `IllegalArgumentException`，`ATTRIBUTE_LIMIT_EXCEEDED` 故意使用空 `rawLabel`）；`UnmatchedLabel` 字段本身保留空串，不改写。普通路径 chunkId 用 `chunk.id()`；window 路径用 `window.chunks().get(anchorChunkIndex).id()`。`KgExtractionValidator` 新增 default 方法（默认 no-op），`OntologyConformanceValidator` 覆写为交给既有 `unmatchedConsumer`；因此 refinement 后的第二次 `validate` 不会重复计数。若注入了 schema 但 validator 为 null，记 WARN 并丢弃诊断。
- 是否有 typed schema 由注入的 `OntologyAttributeSchema` 决定（来自 `context.ontology()` 的 canonical 派生，与 overlay 无关）；无 schema 时直接丢弃 attributes（不做任意属性收集）。
- 关系属性同样挂在 `ExtractedRelation` 上；`weight` 仍固定 1.0。

**逐 chunk 校验（`OntologyConformanceValidator`）：**

- **属性绑定已前移到解析期**：validator 不再 coerce/裁决/裁剪属性，只负责 label/pattern 与 PREFER 类型改写；它持有的 `Ontology` 仍用于这些 Phase 1 语义。
- **属性只影响属性，不 drop 宿主记录**：STRICT/PREFER/OPEN 下属性问题都已在 `OntologyAttributeBinder` 中只丢键并产生诊断；实体/关系是否保留仍由 Phase 1 的 label/pattern 规则决定。
- 实体：已知类型直接保留（attributes 原样）；PREFER 下未知类型被改写为 `Other` 时**属性清空**（绑定器对未知类型本就无声明可依）；OPEN 下 label 放行，attributes 保持绑定结果（未知类型为空）。
- 关系：绑定器已按 `keywords` 命中的关系类型集合 `T(kw)` 完成声明匹配：被 ≥1 个命中类型声明 → 声明类型唯一时 coerce，同名异型 → `ATTRIBUTE_TYPE_CONFLICT`，未被任何命中类型声明 → `UNDECLARED_ATTRIBUTE`，coerce 失败 → `INVALID_ATTRIBUTE_VALUE`，`T(kw)=∅` → 属性全部丢弃。validator 的 pattern 校验不改变 attributes。
- **OPEN 语义：** OPEN 只放宽 label/pattern（保持 Phase 1 早退行为），属性绑定与诊断已在解析期完成，validator 早退不会绕过属性约束；属性是新的、无历史行为可破坏（见 §13 Q3）。
- **诊断转发（唯一入口）**：见上一条；`UnmatchedLabel` 追加 `String ownerLabel`（第 5 组件，保留 4 参构造器）；`Kind` 增 `ATTRIBUTE`；`Reason` 增 `UNDECLARED_ATTRIBUTE`、`INVALID_ATTRIBUTE_KEY`、`INVALID_ATTRIBUTE_VALUE`、`ATTRIBUTE_TYPE_CONFLICT`、`ATTRIBUTE_LIMIT_EXCEEDED`、`ATTRIBUTE_DUPLICATE_KEY`。`rawLabel` = 组内字典序最小的原始拼写，`ownerLabel` = **canonical owner identity**（与 schema 同源，保证首次/恢复一致）。
- **直接调用契约**：进入 `validate` 的 `ExtractedEntity`/`ExtractedRelation.attributes` 必须是 binder 输出的 identity 键 map；validator 不反向解码或重新投影。自定义管道若绕过 `KnowledgeExtractor`，须自行调用公开的 `OntologyAttributeBinder`；自定义 `KgExtractionValidator` 若需要属性诊断，应覆写 `onAttributeDiagnostics`（默认 no-op）。

**合并（逐键 last-write-wins，对齐 GraphRAG-SDK `existing.attributes.update(...)`）：**

| 合并点 | 现状 | 2A 改动 |
|---|---|---|
| gleaning | `KnowledgeExtractor.mergeEntity/mergeRelation`（`:1089`/`:1101`） | 属性 union，右侧覆盖 |
| 图装配 | `GraphAssembler.MutableEntity/MutableRelation.mergeFrom` + `toEntity/toRelation` | 属性 union，右侧覆盖；`ensureEntity` 空实体带空 map |
| refinement 合并 | `DefaultExtractionMergePolicy.mergeEntity/mergeRelation` | 保留属性；右侧覆盖 |
| 存储合并 | `IndexingPipeline.mergeEntityWithCaps`（`:1978`）、`mergeRelationWithCaps`（`:2098`）、`newEntityRecord`（`:2185`）、`newRelationRecord`（`:2152`） | 属性 union，incoming 覆盖；重建 record 时携带 |
| 批次合并 | `GraphMaterializationPipeline.mergeEntityBatch`（`:1618`） | 合并属性；投票改类型时保留属性（随后由最终投影裁剪） |
| 手工合并 | `GraphManagementPipeline.mergeEntities`（`:246`） | source 属性先并、target 覆盖；targetAttributes 显式提供时先替换 target 再并 |

> **关系早退语义（r1 S3）：** `mergeRelationWithCaps` 在 `retainedIncoming.isEmpty()` 时原样返回 existing（`:2116-2118`）。这里的准确语义是**经 caps 过滤后没有任何保留证据**，不是"上游没有新 chunk"；因此早退批次不贡献属性与既有 description/keywords/weight 的处理一致。已知限制：合并是聚合语义，**不保证每个属性都能回溯到具体 chunk 证据**；若宿主需要逐属性证据，须另立来源追踪（本计划不做）。

**最终投影（关键）：**

- 投影在**最终记录产生处 + 持久化之前**统一执行（r1 S1 / r2 R-S3）：`IndexingPipeline.mergeEntityGroup` 的三元返回两个分支（`:2059-2062`）、`GraphMaterializationPipeline.mergeEntityBatch` 的两个分支（`:1631-1634`）都必须投影；关系在最终合并后投影（`GraphMaterializationPipeline` 的保存路径为 `:1324-1335`，含当前批次合并、已有记录合并、新建三条路径）；写图/写 chunk 快照路径上的其他提前返回（含空批次）同样不得绕过。实现上在两条 `saveGraph` 的最终记录集合上统一投影，避免把裁剪塞进通用 `GraphStore`。
- 实体按最终类型调用 `OntologyAttributeProjector.projectEntity(record, ontology)` 重新 coerce/裁剪；关系在最终 keywords 确定后调用 `projectRelation(...)`；若最终类型未声明该属性则丢弃。投影器内部同样从 `ontology.toCanonical()` 派生 schema，输入与输出键均为 identity。
- 最终投影**不再触发回调**（避免同一问题在 chunk 级与最终级重复计数）；只做确定性裁剪，debug 日志记计数。
- 投影器通过管道构造器的**新可选参数**注入（旧构造器默认 no-op），由 `LightRag.resolveKgExtractionValidator` 同源的 `OntologyTaskContext` 构造，确保 prompt/validator/投影消费**同一本体快照**。

### 3.4 D2A-4 类型投票与属性的一致性

投票改变实体类型时，属性可能来自非最终类型。最终投影解决"保留什么"，但不解决"属性值是否来自正确类型"——这是**语义接受**：属性是文本证据，类型投票是整图统计；投影按最终类型裁剪后，留下的同名属性已按最终类型重新 coerce。若宿主需要"属性只来自最终类型"的强保证，须在合并时记录逐属性来源类型（本计划不做，见 §13 Q4）。

### 3.5 D2A-5 存储与迁移

**统一表示：** 每个实体/关系存一个 `attributes` **字符串属性**，值为 canonical JSON。空属性写 `"{}"`（不写 NULL/不删属性），避免"省略即保留旧值"的问题，并统一六后端读写。

| 后端 | 写入 | 读取 | 迁移/DDL |
|---|---|---|---|
| Neo4j / Memgraph | `saveEntity`/`saveEntities`/`saveRelation`/`saveRelations` 的 `SET` 增 `attributes = $attributes` | `toEntityRecord`/`toRelationRecord` 从 properties 取字符串 | 无 DDL；旧节点无该属性 → `"{}"` |
| PostgreSQL 关系后端 | `upsertEntity`/`upsertRelation` SQL 增列与参数 | `load`/`list` 读列 | `entities.attributes TEXT NOT NULL DEFAULT '{}'`、`relations.attributes TEXT NOT NULL DEFAULT '{}'`；`ensureAttributesColumn` 幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（沿用 `ensureEntityFilePathColumn` 模式，`PostgresSchemaManager.java:502-514`） |
| Apache AGE | `entityProperties`/`relationProperties` 增 `attributes`；边属性内联 CREATE | `toEntityRecord`/`toRelationRecord` 解析 properties JSON | 无 DDL |
| FalkorDB | `entityProperties`/`relationProperties` 增 `attributes` | 同左 | 无 DDL |
| ArcadeDB | `saveEntity`/`saveRelation` properties 增 `attributes` | `readEntity`/`readRelation` 取字段 | `CREATE PROPERTY IF NOT EXISTS Entity.attributes STRING`、`Relation.attributes STRING`（`ArcadeSchemaManager`） |
| NebulaGraph | `INSERT VERTEX/EDGE` 列清单与 tuple 增 `attributes` | `toEntityRecord`/`toRelationRecord` 取列 | 新建：`CREATE TAG/EDGE` 增 `attributes string(16384)`（按 UTF-8 字节上限预留）；存量 space：`DESCRIBE TAG entity`/`DESCRIBE EDGE directed` 缺列时执行 `ALTER TAG entity ADD (attributes string(16384))` / `ALTER EDGE directed ADD (attributes string(16384))`，幂等；失败给明确升级错误 |
| InMemory | 直接存 record（map） | 直接返回 | 无 |

**数据库快照：** `ExtractedEntityRecord`/`ExtractedRelationRecord` 增 `Map<String,Object> attributes`；旧 arity 构造器 + JSON 缺失/null → 空 map。`JdbcJsonCodec`/`MySqlJsonCodec`/`ArcadeJsonCodec` 走同一记录形状；新组件用 `@JsonInclude(NON_EMPTY)`，空属性不写字段。数据库快照不做版本号校验：缺失属性即空 map，行为向后兼容。

**文件快照（r1 M4，v2）：**

- `SnapshotManifest.CURRENT_SCHEMA_VERSION` 由 `1` 升为 `2`，新写入一律 v2。
- `FileSnapshotStore.load`（当前在 `:47-59` 读 manifest 后直接反序列化、不校验版本）必须先校验：`1` → 按旧 payload 读取，属性一律空 map；`2` → 按新 payload 读取；`> 2` → 拒绝并提示升级 SDK；坏 manifest（含 `schemaVersion <= 0`）沿用现有 `StorageException`。
- `SnapshotPayload` 当前直接嵌入 `GraphStore.EntityRecord/RelationRecord`（`:20/:21`），扩展后 v1 JSON 缺 `attributes` 仍可读；v2 读写 round-trip 必须保属性。
- **不迁移任务上下文 JSON**：`OntologyJsonCodec` 的 context `schemaVersion` 保持 1，`OntologyExtractionOverlay` 保持 3 组件；typed schema 由持久化的 ontology 派生（见 §3.2），历史任务可直接恢复。

**Nebula 存量 space 升级（r1 M9）：** `DESCRIBE` 探测 → 缺列执行 `ALTER TAG/EDGE` → **真实写入探针**（写入并读回一个带非空 `attributes` 的 tag 与 edge，含超时上限如 10s）→ 探针失败抛明确升级错误。并发 ALTER 的 "already exists/duplicate column" 视为成功；探针与回归测试必须覆盖"旧 schema space 升级后读写"，不能只断言 DDL 文本。

**读容错：** 存储里的 `attributes` 不是合法 JSON → 返回空 map + WARN，不阻断查询。

**迁移与重建口径：**

- 已有图**不会自动获得属性**：升级后需按宿主维护窗 **REBUILD/重抽** 才能物化；旧图仍可按空属性读取。文档与发版说明必须写明。
- 快照恢复按记录携带属性；旧快照恢复后属性为空。
- **升级边界（r1 S4）**：属性物化作用于 SDK 的**当前图**（原地更新）与 chunk 快照；REBUILD 会覆盖当前图与 chunk 快照，但**不触碰宿主 `km_ontology_version`/旧投影**。2A 不提供历史图归档，也不把宿主版本回滚等同于图回滚。
- 降级不支持：旧 SDK 读到含非空 `attributes` 的新快照可能因未知字段失败（`FAIL_ON_UNKNOWN_PROPERTIES=true`）。因此 Phase 2 要求**协调升级**；新 SDK 读旧快照兼容。见 §13 Q10。

### 3.6 D2A-6 API/手工治理/视图

**公共记录：**

- `GraphEntity`/`GraphRelation` 追加 `attributes` 末位组件（`@JsonInclude(NON_EMPTY)`），保留旧 arity 构造器；空属性时 JSON 与 Phase 1 一致。
- `GraphViewTraversal.viewOf`（`:209/:229`）构造时携带属性。
- **结构化查询显式例外（r1 Q5/Q13）**：`QueryEngine` 的 `StructuredQueryEntity/Relation`（`:922/:935`）本期不新增 `attributes`；调用方需要属性时走 `GraphEntity`/`GraphRelation`/`KnowledgeGraphView`。该例外写入 §3.8 守恒表，避免被误判为漏传。

**请求记录（手工治理，host 管理员可信，不做本体过滤）：**

| 记录 | 语义 |
|---|---|
| `CreateEntityRequest` | `attributes` null → 空；结构清洗 + 上限；键规范化为 identity；不按本体裁剪 |
| `CreateRelationRequest` | 同上 |
| `EditEntityRequest` | `attributes == null` → 保持不变；`{}` → 清空；非空 → 整体替换（与现有 nullable 字段语义一致） |
| `UpdateRelationRequest` | 同上 |
| `MergeEntitiesRequest` | `targetAttributes == null` → 沿用 target 现有属性；非 null → 先替换 target 属性；合并时 source 属性并入、target 覆盖 |

> **null 分域（r1 M2）**：上表的 null 语义**优先于**值对象构造器的 "null→空 map" 统一规则。`EditEntityRequest`/`UpdateRelationRequest` 的 `attributes` 与 `MergeEntitiesRequest.targetAttributes` 必须在构造器中原样保留 `null`（不得被空 map 归一），否则 PATCH 会退化成"清空"。
>
> **无 schema 的手工输入**：按键 identity 归并后值不一致时没有声明可依，按 §3.1 抛 `IllegalArgumentException`（调用方错误），不做静默择一；输出键同样是 identity。

- Builder 增 `attributes(...)`；旧 canonical constructor/Builder 用法保留。
- `GraphManagementPipeline` 的 `createEntity`/`createRelation`/`editEntity`/`updateRelation`/`mergeEntities`/`mergeRelationRecord`/`migrateRelations`/`toEntity`/`toRelation`/`toGraphEntity`/`toGraphRelation`（`:48/:70/:105/:175/:255/:530/:556/:593/:605/:618/:630/:685`）全部携带属性。
- 手工写**不触发本体回调**，也不改 `applied_fingerprint`；宿主按既有 dirty 语义处理（Phase 1 契约）。

### 3.7 D2A-7 渲染与检索边界

- `OntologySchemaRenderer` 的 `includeDeclaredProperties` 在 2A 后**仍默认 false**：属性是 JSON 字符串袋，不是可独立过滤的物理字段；渲染成 `n.age` 会诱导 LLM 生成不存在的 Cypher。文档明确"属性可物化 ≠ 属性可物理查询"。
- 检索上下文（LOCAL/GLOBAL 的实体/关系描述）不自动拼接属性；`GraphEntity` API 暴露给调用方自行使用。
- 向量存储/嵌入不受影响。
- 若未来需要属性级 Cypher 查询，另立"属性扁平化/索引"项目（不在本期）。

### 3.8 属性守恒清单（r1 M1；按生产源码 grep 全量核对）

> Codex r1 在 `lightrag-core/src/main/java` 检出 70 处相关构造调用（排除 `OntologyGovernance` 的 `DeletionEntry.Entity/Relation`，它们不是数据记录）。**保留旧构造器只能保证链接，不能保证属性不丢**；以下每处必须显式处置，并用"非空属性经过该路径后仍存在"的测试守护。

**总则：**

1. "已有对象 → 新对象"的重建一律显式传 attributes；旧 arity 构造器只允许用于**真正创建空占位对象**。
2. 属性**语义裁剪/投影**只允许发生在两个边界：`KnowledgeExtractor` 解析期的 `OntologyAttributeBinder.bindEntity/bindRelation`（raw → identity 键：schema 匹配/coerce/重复键/类型冲突/caps）和持久化前的最终投影（按最终类型/关键词收敛）。`OntologyConformanceValidator` 不再拥有属性投影，只转发诊断并在 PREFER 改写时清空属性；`GraphStore`、`StorageCoordinator`、workspace/事务委托只做**透传**，不得在通用层裁剪（否则会误伤可信手工数据/恢复数据）。
3. 向量载荷与嵌入不消费属性（`HybridVectorPayloads` 只读 summary/keywords/filePath），转换时可携带但不得改变向量输入；结构化查询 API 本期**不暴露**属性（显式例外，见下表）。
4. **仓库内门禁（非编译器强制，r2 R-S2）**：旧重载无法在运行时区分"创建占位"与"复制已有对象"，二进制兼容也要求保留旧签名；因此"旧构造器仅空占位"只能作为**仓库内源码规则**。CI 增加 grep/静态检查 + 显式 allowlist；新增构造点必须显式传 attributes。已确认的合法旧签名占位点：`GraphAssembler.java:183`、`DefaultExtractionMergePolicy.java:52`；其余使用须列入 allowlist 并注明理由。
5. **租户隔离**：属性随记录走既有 workspace 作用域，不引入跨 workspace 的共享缓存/索引；Neo4j/Memgraph 的 workspace projection 与 `StorageCoordinator` 委托必须保持过滤条件不变（Q13）。

| 文件 | 构造点 | 处置 |
|---|---|---|
| `indexing/KnowledgeExtractor.java` | `:671/:715` parse、`:1013/:1041` window 提升、`:1093/:1109` merge、`:1139` sanitize | 解析/合并携带；sanitize 保留 attributes |
| `indexing/GraphAssembler.java` | `:183` ensureEntity、`:299` sanitize、`:434` toEntity、`:505` toRelation | 空占位 → 空 map；其余按 `mergeFrom` 逐键合并（右侧覆盖） |
| `indexing/refinement/DefaultExtractionMergePolicy.java` | `:52/:59/:68` | 合并属性（右侧覆盖） |
| `indexing/refinement/DefaultAttributionResolver.java` | `:105-110` `putIfAbsent` | 重复候选不再整条丢弃：同键候选按**窗口内稳定索引顺序**逐键合并（后者覆盖，不依赖并行完成顺序），其余字段保持首见 |
| `ontology/OntologyConformanceValidator.java` | `:101` PREFER → `Other` | 属性清空（无声明） |
| `indexing/IndexingPipeline.java` | `:2002` mergeEntityWithCaps、`:2048-2049/:2059-2062` 实体最终返回、`:2135` mergeRelationWithCaps（`:2116` 早退）、`:2173` newRelationRecord、`:2202` newEntityRecord、`:2221-2228` 关系最终返回、`:2359/:2367` chunk 快照 | 合并携带；实体/关系最终返回都必须带 attributes 并投影（见 S1） |
| `indexing/GraphMaterializationPipeline.java` | `:1196/:1204` chunk 快照、`:1233/:1241` **快照→抽取**（resume/repair）、`:1324-1335` 关系保存（合并/已有/新建）、`:1633` 类型投票重建 | 全部携带并投影；`:1233/:1241` 是 r1 新发现的漏点；关系保存位于 `:1324-1335`（r2 R-S3） |
| `indexing/GraphManagementPipeline.java` | `:48/:70` create、`:105` edit、`:175` update、`:255` merge、`:530/:556` 关系合并、`:593/:605` toEntity/toRelation（向量）、`:618/:630` toGraphEntity/toGraphRelation、`:685` 改名迁移边 | create→请求/空；edit/update→null 保持；merge→source 先并、target 覆盖；其余原样携带 |
| `indexing/DeletionPipeline.java` | `:394/:409` 删除 chunk 引用重建 | 原样携带（r1 M1） |
| `indexing/GraphVectorIndexer.java` | `:148/:160` | 携带；向量 payload 不读取属性 |
| `query/LocalQueryStrategy.java` / `query/GlobalQueryStrategy.java` | Local `:308/:320`、Global `:226/:238` | 携带；检索上下文拼接逻辑不变 |
| `query/QueryEngine.java` | `:922/:935` → `StructuredQueryEntity/Relation` | **显式例外**：本期不新增 attributes 字段；属性经 `GraphEntity`/`GraphRelation`/`KnowledgeGraphView` 暴露 |
| `storage/GraphViewTraversal.java` | `:209/:229` viewOf | 携带 |
| `storage/arcadedb/ArcadeOneShotRetrievalStore.java` | `:244/:256` | 携带 |
| 读取还原 | Neo4j `:1522/:1535`、Postgres `:503/:520/:532`、AGE `:738/:761`、Falkor `:448/:470`、Arcade `:91/:103`、Nebula `:399/:421` | 解析 `attributes` canonical JSON；缺失/null/坏 JSON → 空 map + WARN |
| `storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | `:904/:916` 向量转换、`:956/:992` chunk 引用裁剪 | 携带（裁剪路径是 r1 新发现漏点） |
| `storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | `:373/:385` 向量转换 | 携带 |
| `storage/StorageCoordinator.java` + Neo4j/Memgraph adapter | `:176/:420` 委托、workspace projection | 透传；补 pre-image/失败回滚/restore 含非空属性的用例 |
| `storage/memory/InMemoryGraphStore.java` | 直接保存 record | 依赖规范化构造入口保证深不可变；补变异测试 |
| `storage/DocumentGraphSnapshotStore.java` + 三种 codec + `persistence/*` | 快照记录 | 携带；JSON 缺失 → 空；文件 manifest 版本见 §3.5 |

> 任何**新增**构造点（后续重构）都必须遵守同一规则；r2 已确认 70 处文本命中无新增遗漏（排除 `DeletionEntry.Entity/Relation`），r3 以本表为核对基线。

---

## 4. 设计 2B：确定性本体 bootstrap

### 4.1 定位

对齐 Semantica `bootstrap_schema` 的核心约定：

- 从**样本**归纳草稿；**永不自动应用**（`draft=True`）。
- 频率门（Semantica 默认 2）过滤偶发类型/关系。
- 输出 TTL 供人审（2C 提供 writer）。
- 不调用 LLM、不读存储、不分配版本。

与 Semantica 的差异：Java 输入是**强类型样本**（含 document/chunk 身份），可加文档门与确定性去重；不引入 `owl:Thing` 端点对齐（Java 本体没有这个类型），未过门的端点直接丢弃。

### 4.2 输入/输出

新包 `io.github.lightrag.ontology.bootstrap`：

```java
record EntitySample(
    String name, String type, Map<String,Object> attributes,
    String documentId, String chunkId) {}

record RelationSample(
    String sourceEntityName, String targetEntityName,
    String keywords, Map<String,Object> attributes,
    String documentId, String chunkId) {}

record BootstrapOptions(
    int minOccurrences,        // 默认 2（类型/关系类型/pattern）
    int minDocuments,          // 默认 1（至少来自几个文档）
    int minAttributeSupport,   // 默认 1（Semantica 数据属性无频率门）
    boolean includeAttributes, // 默认 true
    int maxSamples,            // 默认 200_000；超出即拒绝，不截断
    Ontology baseOntology)     // 可选；输出 base + 增量的完整候选

record BootstrapConflict(String ownerLabel, String item, String detail) {}

record BootstrapResult(
    Ontology draft,            // 完整候选，永不为 null；version=null
    List<BootstrapConflict> conflicts, // 按固定字段排序
    List<String> warnings,             // 按字典序排序
    String sampleFingerprint,  // SHA-256(algorithmVersion + canonical samples + options + base)
    int algorithmVersion,      // 固定常量，当前为 1
    BootstrapStats stats) {}

record BootstrapStats(
    int entitySamples, int relationSamples, int duplicateSamples,
    int conflictingDuplicateAttributes,
    int droppedUngatedEndpoints, int droppedAmbiguousEndpoints,
    int droppedUnconstrainedRelationTypes) {}
```

### 4.3 算法（确定性）

1. **规范化（r1 M5 / r3 R-M1）**：样本属性（`Map<String,Object>`）转成候选后走 `AttributeValues.sanitizeRaw(Map)`（仅结构清洗与防 DoS 上限，不做声明类型 coerce），`null` → 空 map。`RawIssue` 转为确定性 warning（含 `documentId`/`chunkId`/样本 name 与该属性 identity）。候选按 `Identity.identityId` 分组：组内原始值（canonical JSON 比较）全相等 → 保留一个；存在异值 → 该键从该样本中丢弃并记一条确定性 warning（不按 map 顺序择一）。属性 JSON 用 canonical 形式。**该步骤与解析路径共用同一 identity 分组规则，确保 `{"Age":1," Age ":2}` 在 bootstrap 中同样不依赖 map 迭代顺序**。
2. **去重、拼写代表与重复属性（r2 R-M2）**：实体按 `(documentId, chunkId, identityId(name), identityId(type))` 去重；关系按 `(documentId, chunkId, identityId(source), identityId(target), 排序后的 keyword identity 集合)` 去重（keyword 先按逗号拆分/trim/去空/按 identity 去重，集合本身按字典序排序）。**去重键不含 attributes**。
   - 每个去重组先选**组内拼写代表**：对该组内同一 identity 的原始拼写按出现次数降序、并列按字典序升序取第一；label/keyword 的跨组计票使用这些代表，而不是"碰巧留下的那条样本"。
   - 同一键的多条样本属性合并：某属性键只在部分样本出现 → 保留该值（缺失不覆盖存在）；多个样本值相同 → 保留；**值不一致 → 该属性键整键丢弃**并记 `BootstrapConflict`，**每个被丢弃的 `(去重键, 属性 identity)` 计 1 次** `stats.conflictingDuplicateAttributes`（同一键三种异值也只计 1 次）。
   - 重复样本（同去重键的额外行）计入 `stats.duplicateSamples`；属性拼写代表只从**未被丢弃**的值中计票，规则同样为次数降序、并列字典序升序。
   - `conflicts` 按 `(ownerLabel identity, item identity, detail)` 排序并去重；`warnings` 按自然字符串序排序。该规则只依赖值、不依赖输入顺序，也不通过任意选择值影响类型推断。
3. **类型门**：在**去重后**按 `identityId(type)` 统计样本数（同一实体键只计一次）与不同 `documentId` 数；`count >= minOccurrences && docs >= minDocuments` 才成为实体类型。排除 kernel reserved（`entity/relation/chunk/document`）与 fallback（`Other/Unknown`）。
4. **label 选择**：对每个 `identityId(type)`，取各去重组的**组内拼写代表**计票；次数最多者胜，并列取字典序最小；保留大小写。
5. **关系类型门**：`keywords` 每个 keyword 按 identity 独立过门（同类型门；组内代表见步骤 2）。bootstrap **只输出至少有一个过门 pattern 的关系类型**；全部端点未过门/歧义的关系类型丢弃并计入 `stats.droppedUnconstrainedRelationTypes`，避免产生"空 pattern = 端点不受限"的关系类型（手工编写的本体仍允许空 patterns，不受此规则约束）。
6. **pattern 推断（r1 M5 端点歧义）**：
   - 建立 `identityId(name) → set(identityId(gatedType))` 索引。
   - 端点解析优先取**同一 `(documentId, chunkId)`** 内的实体样本；找不到再看全局索引。恰好一个过门类型 → 采用；多个 → 歧义，该关系样本不参与 pattern 推断并计入 `stats.droppedAmbiguousEndpoints`（记 `BootstrapConflict`）；零个 → 未过门，计入 `stats.droppedUngatedEndpoints`。
   - pattern 支持度按去重后且成功解析端点的关系样本计数；`count >= minOccurrences` 且不同文档数 `>= minDocuments` 才加入。
7. **属性推断**：
   - 对每个过门 owner（实体类型或已输出的关系类型），收集该类型样本的属性键；`support` = **包含该键的不同去重键数**（实体按 `(documentId, chunkId, nameIdentity, typeIdentity)`，关系按 `(documentId, chunkId, sourceIdentity, targetIdentity, keywordIdentitySet)`）。因此**同一实体在两个 chunk 出现计 2 次 support**，同一实体键的重复行不计；`>= minAttributeSupport` 才成为候选。一个关系样本含多个过门 keyword 时，其属性**分别计入每个 keyword 对应的关系类型**（同一样本可贡献多个 owner），各 owner 独立计 support。
   - 属性键按 `Identity.identityId` 归一；display name 取**未被丢弃值**的组内拼写代表计票，次数降序、并列字典序升序。
   - 类型统一规则（先忽略 `null`，其余值按 sanitize 后的原始值判断）：
     - 全为 `Boolean`，或全为 `"true"/"false"`（忽略大小写）字符串 → `BOOLEAN`；
     - 全为整数数值 → `INTEGER`；整数与浮点混合 → `FLOAT`；
     - 存在 `List` 值（纯 LIST 或 LIST+标量）→ `LIST`；
     - 全为可解析 ISO 日期字符串 → `DATE`；
     - 其余（含 BOOLEAN+数值、INTEGER+字符串等混合）→ `STRING`。
   - 若某键的所有值都不可用（全 `null`/结构清洗后为空）→ 不输出该属性并记 `BootstrapConflict`。
   - 与 `baseOntology` 同 owner、同属性 identity 但类型不一致 → **保留 base 定义**并记 `BootstrapConflict`；类型一致 → 保留 base（含 description），不重复添加。
8. **完整候选（r1 M6）**：提供 `baseOntology` 时，`draft` 必须保留 base 的全部实体类型/关系类型/属性/patterns/sourceBindings/描述，再叠加推断出的新增项；不删除、不改写既有 owner 的既有定义。新增 pattern 以 `(sourceTypeId, targetTypeId)` identity 去重后并入。由于 `OntologyGovernance.REGISTER_INCREMENTAL` 禁止给既有 owner 追加属性/pattern（`:175/:190-199`），此完整候选**只能走 `EVOLUTION_REPLACE`** 审批；bootstrap 自身不得调用任何 governance 变更方法。
9. **描述**：新推断项不生成描述（避免编造）；base 项保留原描述。宿主审阅时补。
10. **输出顺序**：按 `identityId` 排序；属性按 name 的 identity 排序；patterns 按 source/target identity 排序。**同输入 + 同选项 → 同 canonical JSON + 同 fingerprint**。
11. **上限与选项校验**：样本数超过 `maxSamples` 时**直接拒绝**（抛带限额信息的 `IllegalArgumentException`），不截断——截断会静默改变频率门结果；`minOccurrences`/`minDocuments`/`minAttributeSupport`/`maxSamples` 必须为正，非法选项在构造时拒绝。
12. **fingerprint**：`SHA-256(canonical JSON)`，输入 = **固定 `algorithmVersion`** + 排序去重后的样本（含属性）+ 生效选项 + `baseOntology` 的 canonical fingerprint（`OntologyJsonCodec.write(base)` 后哈希）；与输入顺序、map 迭代顺序无关。算法规则变化必须递增 `algorithmVersion`，不得在版本不变时改变归纳结果。
13. **永不应用**：`draft` 只是 `Ontology` 值；不调用 `OntologyGovernance`、不写 `OntologyStore`、不分配 version。宿主审阅后走既有 `synthesizeCandidate`/冻结/审批流程。

### 4.4 与 LLM discovery 的关系

- bootstrap 是**零 LLM、可复现**的归纳路径；discovery 保留为"无既有抽取样本时的补充"。两者产出同一 `Ontology` 草稿类型，都进入宿主审阅。
- 不在 bootstrap 里做多轮/人机反馈；不做 grounding/catalog。
- `sampleFingerprint` 让宿主可回答"这份草稿来自哪批样本"；样本内容不持久化在 SDK。
- 输入使用专用 `EntitySample`/`RelationSample` 而非 `Entity`/`Relation`：bootstrap 需要 `documentId/chunkId` 与原始属性，并要求零存储依赖；宿主负责从图/快照映射。

### 4.5 验收要点

- 阈值边界（1 vs 2、文档门、属性 support）各有单测。
- 同输入重复运行 → 字节相同；打乱样本顺序、打乱 map 迭代顺序 → **完整 `BootstrapResult` 相等**（draft、conflicts、warnings、stats、fingerprint、algorithmVersion 全字段）。
- 同一去重键携带不同属性/不同属性值 → 合并结果确定且与顺序无关，冲突按 `(去重键, 属性 identity)` 计数；同键三值仍计 1。
- 拼写计票：`Person`/`person` 在不同去重组中的代表与最终 label 确定；同组内重复拼写不影响其他组投票。
- 样本属性 trim/identity 撞键（如 map 同时含 `Age` 与 ` Age `）：该样本的该键被确定性丢弃并记 warning，其他键不受影响；打乱 map 顺序结果相同。
- support 身份：同一实体在两个 chunk 出现计 2，同一 `(doc,chunk,name,type)` 的重复行计 1。
- 端点歧义（同名多类型）与未过门端点分别计数，且不生成对应 pattern。
- 全部端点未过门的关系类型不输出（不产生空 pattern 关系类型）。
- 类型推断矩阵：BOOLEAN（布尔/`"true"/"false"`）、INTEGER/FLOAT、LIST、DATE、混合→STRING、全 null→跳过。
- 超过 `maxSamples` 直接拒绝而非截断。
- base 存在时输出完整候选（保留 base 定义）；与 base 类型冲突保留 base 并记 `conflicts`；不调用 `OntologyGovernance` 任何变更方法。
- fingerprint 对样本顺序、属性 map 顺序、base 规范化都不敏感；base 变化会改变 fingerprint；`algorithmVersion` 参与哈希。
- 不产生 version、不写存储（用 mock/无副作用断言）。

---

## 5. 设计 2C：OWL/TTL/SHACL 互操作

### 5.1 模块边界

| 能力 | 落点 | 依赖 |
|---|---|---|
| Turtle/OWL/SHACL **导出** | `lightrag-core` 的 `io.github.lightrag.ontology.rdf` | 零新增依赖；自带最小 Turtle writer |
| Turtle/RDF-XML/JSON-LD **导入** | 新模块 `lightrag-rdf` | RDF4J Rio **5.3.2**（turtle/rdfxml/jsonld） |
| 推理/子类展开/网络 imports | 不做 | — |

`lightrag-rdf` 是可选模块，`lightrag-core` 不依赖它；只在 `settings.gradle.kts` 增加 `include("lightrag-rdf")`。

**RDF4J 版本（r2 R-M4）：** 锁定 **`org.eclipse.rdf4j:*:5.3.2`**（Java 11 字节码，可在 Java 17 toolchain 运行；6.x 的父 POM 要求 Java 25，明确排除）。使用 `rdf4j-rio-turtle`、`rdf4j-rio-rdfxml`、`rdf4j-rio-jsonld` 三个模块，版本写在 `lightrag-rdf/build.gradle.kts` 的单一常量，禁止动态版本；升级补丁版本也必须重跑全部安全用例并记录版本。

**发布与依赖（r1 S5 / r2 R-S1）：** 根 `build.gradle.kts:45-52` 通过 Vanniktech 插件白名单发布（当前只有 core/starter）。`lightrag-rdf` 必须：
- 加入该发布白名单（或完整定义等价的独立 publication），GAV 为 `io.github.dargoner:lightrag-rdf`，与 core **同版本**（根 `:23-24` 已统一）；
- 复用 core `build.gradle.kts:81` 起的 `mavenPublishing` 约定：`publishToMavenCentral()`、存在签名配置时 `signAllPublications()`、同样的 POM（name/description/url/licenses/developers/scm），并生成 sources/Javadoc；
- 使用 `java-library`，公开 API 暴露 `Ontology`/`ImportResult` 等 core 类型，因此依赖声明为 **`api(project(":lightrag-core"))`**，RDF4J 为 `implementation`；
- 增加发布验收：生成的 POM 依赖、Java 17 消费方编译、sources/Javadoc 构件存在。

核心导出测试**不得**反向依赖导入模块（避免 Gradle 循环）：core 内只做 golden/字符串结构断言，凡需 RDF4J 解析的断言放在 `lightrag-rdf` 测试。

### 5.2 导出（core，零依赖）

```java
record RdfExportOptions(
    String ontologyIri,       // 必填，绝对 IRI，无空白/尖括号
    String vocabularyBaseIri, // 必填，绝对 IRI，必须以 "/" 结尾
    String shapesBaseIri,     // 可选；默认 vocabularyBase + "shapes/"，提供时也必须以 "/" 结尾
    boolean includeSourceBindings, // 默认 true
    boolean includeVersion)         // 默认 true

final class OntologyRdfExporter {
    static String exportTurtle(Ontology ontology, RdfExportOptions options);
    static String exportShacl(Ontology ontology, RdfExportOptions options);
}
```

**映射规则：**

- **本体头**：写 `<ontologyIri> a owl:Ontology`；`includeVersion=true` 时追加 `owl:versionInfo`（若 ontology.version 非空）与 `lg:definitionHash`（宿主提供时）。`ontologyIri` 必须为绝对 IRI 且不得与 vocabulary base 下的生成 IRI 冲突。
- **IRI 稳定分配（r1 M7 / r2 R-M3）**：不再使用 `base + identity` 或"碰撞后追加 hash"。采用**包含完整 `(kind, ownerIdentity, propertyIdentity)` 的分层路径 + 严格百分号编码**，编码本身是单射，新增名称不会改变既有 IRI：
  - `pct(x)` = 对 `identityId(x)` 的 UTF-8 字节逐个编码，仅保留 `A-Z a-z 0-9 - _ ~`，其余字节写成 `%HH`（大写 hex）；identity 为空则拒绝。**`.` 也编码**（`%2E`），避免产生 `.`/`..` 路径段被 IRI 规范化吞掉。
  - 实体类：`vocabularyBaseIri + "class/" + pct(label)`。
  - 关系类型（object property）：`vocabularyBaseIri + "relation/" + pct(label)`。
  - 实体属性：`vocabularyBaseIri + "attribute/entity/" + pct(ownerLabel) + "/" + pct(propertyName)`。
  - 关系属性：`vocabularyBaseIri + "attribute/relation/" + pct(ownerLabel) + "/" + pct(propertyName)`。
  - SHACL shape：`shapesBaseIri + "class/" + pct(ownerLabel) + "/shape"`。
  - 三类 IRI 参数（`ontologyIri`/`vocabularyBaseIri`/`shapesBaseIri`）在构造时校验：绝对 IRI，且不含空白、尖括号、双引号、花括号、竖线、脱字符或反斜杠；base 以 `/` 结尾。`shapesBaseIri` 默认由 base + `"shapes/"` 重组，**不会产生双 `#`**。导出前再断言每个 IRI ≤ 8 KiB（UTF-8）；超限/编码冲突直接失败。
  - 所有 label 同时写 `rdfs:label` 保留展示大小写；同一 identity 的大小写变体归一为同一 IRI。
- **导出冲突矩阵（r2 R-M3）**：同一 `(kind, ownerIdentity, propertyIdentity)` 只允许一个声明。字段（display name、valueType、description、sourceBinding）完全相同的重复项去重；任一字段不同 → 抛 `OntologyRdfExportException`，不静默择一。实体/关系可同名（`class/` 与 `relation/` 路径不同），不冲突。
- **实体类型** → `owl:Class`；`rdfs:label`；description → `rdfs:comment`。
- **关系类型** → `owl:ObjectProperty`：
  - 恰好 1 个 pattern → `rdfs:domain` 源类、`rdfs:range` 目标类；
  - 0 个 pattern → 不写 domain/range；
  - ≥2 个 pattern → 不写 domain/range（OWL 多 domain 是交集语义，会失真），改为 `lg:relationPattern` 具体化节点（`lg:relation`、`lg:sourceClass`、`lg:targetClass`），保证可逆。
- **实体属性 → 一律 owner-scoped（r1 M7 / r2 R-M3）**：每个类的每个属性各生成一个 `owl:DatatypeProperty` IRI（`attribute/entity/{owner}/{property}`，见上），并写 `lg:owner <ClassIRI>` 与等价的 `rdfs:domain <ClassIRI>`；**不再使用跨类共享属性或 `owl:unionOf` domain**。这样每个 owner 的描述、类型、SourceBinding 都能独立往返，不会因同名属性被合并而丢失，外部 OWL 工具也能看到 domain。
  - 标量：`rdfs:range xsd:string|integer|double|boolean|date`；
  - LIST：写 `lg:valueType "LIST"`，不写 `rdfs:range`（避免把 JSON 数组误声明为 RDF 集合）。
- **关系属性 → 一律 owner-scoped 具体化**（OWL 不能给 object property 挂属性）：节点 IRI 为 `attribute/relation/{owner}/{property}`，类型 `lg:RelationAttribute`，写 `lg:owner <ObjectPropertyIRI>`、`lg:attributeName`（display name）、`lg:valueType`、可选 `lg:description`，SourceBinding 挂在该节点上。这是 LightRAG 扩展词汇，文档标注非标准 OWL。
- **SourceBinding** → `lg:SourceBinding` 具体化（`lg:tableName`/`lg:column`/`lg:refTableName`/`lg:refColumn`/`lg:nullable`），挂到类/属性/关系上；默认导出，宿主可关闭。
- **版本** → `lg:version` / `owl:versionInfo`、`lg:definitionHash`（若宿主提供）；不导出时间戳（确定性）。
- **SHACL**：每个实体类一个 `sh:NodeShape` + `sh:targetClass`；每个标量属性一个 `sh:property`，`sh:path` 指向该类的 **owner-scoped** 属性 IRI，并写 `sh:datatype`、`sh:maxCount 1`；LIST 只写 `sh:maxCount 1`，不写 datatype；关系属性不在 SHACL 表达（记录为已知限制）。
- **确定性与转义**：所有三元组按 subject/predicate/object 排序输出；前缀固定；无生成时间/随机 blank node id（blank node 用稳定标签）。Turtle 字面量按规范转义 `"`、`\`、换行、回车、制表与控制字符（`\uXXXX`/`\UXXXXXXXX`），IRIREF 对 `<`、`>`、`"`、`{}`、`|`、`^`、`` ` ``、`\` 和控制字符做百分号编码；宿主提供的 base IRI 先通过同一校验，禁止直接拼接未转义文本。导出长度上限按**未转义的 UTF-8 字节**计算（literal 64 KiB、IRI 8 KiB），与导入口径一致。

### 5.3 导入（`lightrag-rdf`，RDF4J）

```java
record RdfImportOptions(
    String baseIriOverride, // 可选
    long maxBytes,          // 默认 10 MiB
    int maxTriples,         // 默认 200_000
    boolean includeSourceBindings)

final class OntologyRdfImporter {
    static ImportResult importOntology(Path file, RdfImportOptions options);
    static ImportResult importOntology(String data, RdfFormatValue format, RdfImportOptions options);
}

record ImportResult(Ontology ontology, List<String> warnings) {}
```

**映射规则（导出子集的可逆读取）：**

- 先收集 `owl:Class` → 实体类型、`owl:ObjectProperty` → 关系类型的 IRI 表；后续 `lg:owner` 通过该表解析 owner，**不靠解码 IRI 猜归属**。
- `owl:Class`/`rdfs:Class` → 实体类型；`rdfs:label` 优先，缺失用 IRI local name。
- `owl:ObjectProperty` → 关系类型；domain/range → 单 pattern；`lg:relationPattern` → 多 pattern；无 domain/range → 空 patterns。
- `owl:DatatypeProperty` → 实体属性；owner 优先取 `lg:owner`，缺失时回退 `rdfs:domain`；两者同时存在且指向不同 owner → 抛 `OntologyImportException`（不静默择一）。属性名取 `rdfs:label`，缺失时取 owner-scoped IRI 的最后一段并百分号解码（不是整个 IRI）。`lg:valueType` 优先，其次 `rdfs:range` 的 XSD 映射。
- **union domain 的唯一例外（r2 R-M3）**：仅当属性**没有** `lg:owner` 且 `rdfs:domain` 是 `owl:unionOf` 的命名类列表时，展开为每个成员类各一个 owner-scoped 属性（结果本体不保留原始 IRI）；union 含匿名类/嵌套复杂表达式 → warning 并跳过。若同一 IRI 既有 `lg:owner` 又有 union domain，按 `lg:owner` 单 owner 处理，union 产生 warning。这解决了"union 每类复制"与"同 IRI 不同 owner 失败"的冲突。
- **导入冲突矩阵 fail-closed（r1 M7 / r2 R-M3）**：同一 `(kind, ownerIdentity, propertyIdentity)` 的多个声明，若 display name、`valueType`、description、SourceBinding 全部相同 → 去重；**任一项不同 → 抛 `OntologyImportException`**。同一 IRI 被解析为不同 kind 或不同 owner → 抛异常。既无 `lg:owner` 也无 `rdfs:domain` 的 datatype property → warning 并跳过（无法确定归属）。
- `lg:RelationAttribute` → 关系属性；owner 取 `lg:owner`。
- `lg:SourceBinding` → `SourceBinding`（挂在对应类/属性/关系/关系属性节点上）；`includeSourceBindings=false` 时忽略这些节点（不报错）。
- `lg:version`/`owl:versionInfo` → `Ontology.version`（仅作为值；导入不分配版本）。
- **忽略并 warning**：`rdfs:subClassOf`、`owl:Restriction`、等价/不相交/基数、`owl:imports`（不跟随）、SHACL shapes（不解析）；OWL/RDFS 标准词汇（`owl:Thing`、`rdfs:Resource` 等）不作为实体/关系类型导入。不做 subclass 传递闭包、不做推理。
- 导入结果**不自动应用**、不写存储、不分配版本；宿主走审阅/治理。

### 5.4 安全与资源限制（RDF4J 5.3.2 实际 API）

- **完全离线（r1 M8 / r2 R-M4）**：不跟随 `owl:imports`；不解析远程 IRI。JSON-LD 设置：
  - `JSONLDSettings.SECURE_MODE=true`、`WHITELIST` 为空、`DOCUMENT_LOADER_CACHE=false`、`EXCEPTION_ON_WARNING=true`；
  - 通过 `JSONLDSettings.DOCUMENT_LOADER` 注入**无条件抛异常**的 `DocumentLoader`（任何远程/本地 context 请求都失败），不依赖默认 loader；
  - 解析前做数据级预检：任何 `@context` 值为字符串（含数组元素）或出现 `@import` → 直接拒绝（inline context 对象仍允许，其中 `@vocab`/`@id` 不触发抓取）；只读本地文件/字符串。
- **RDF/XML XXE（RDF4J 5.3.2 `XMLParserSettings`）**：显式设置 `SECURE_PROCESSING=true`、`DISALLOW_DOCTYPE_DECL=true`、`LOAD_EXTERNAL_DTD=false`、`EXTERNAL_GENERAL_ENTITIES=false`、`EXTERNAL_PARAMETER_ENTITIES=false`、`FAIL_ON_SAX_NON_FATAL_ERRORS=true`。设置后用 `parser.getParserConfig().get(...)` 逐项回读断言；**API 不存在/设置被忽略/值不符 → 该格式拒绝导入，禁止回退默认配置**。任何外部实体/DTD 出现即失败。
- **有界读取**：`maxBytes` 在**读取过程中**通过计数流强制（超限立即中止），不是读完后才检查。
- **三元组计数中止**：用计数 `RDFHandler` 包装解析并在达到 `maxTriples` 时抛异常中止；不得先构建完整 Model 再计数。
- **列表/union 遍历（r2 R-M4）**：`owl:unionOf`、RDF list（`rdf:first`/`rdf:rest`）及 owner 链遍历一律使用**迭代式 worklist + visited 集合**（按 IRI/blank node id）；最大深度 32、最大节点数 10,000，超限抛 `OntologyImportException`；遇到环时停止该分支并记 warning（不无限循环）。
- **单值上限（r2 R-M4）**：literal ≤ 64 KiB、IRI ≤ 8 KiB，均按**解析后的词法值/IRI 字符串的 UTF-8 字节**计算（不含 Turtle 转义语法），在 `RDFHandler` 接收每条语句时检查；导出在写三元组前用同一口径检查。
- **fail-closed**：无法识别/未显式允许的格式直接拒绝；限额必须为正数；任何超限/解析失败抛 `OntologyImportException`（带限额信息），**不返回部分结果**；不做 reasoner、不加载 OWL 公理引擎。

### 5.5 互操作验收与 Semantica 对齐

- core 导出测试：golden TTL/SHACL 字符串 + 结构断言（类/属性/pattern/sourceBinding 数量与方向），**不依赖 RDF4J**。
- `lightrag-rdf` 测试：用 RDF4J 解析 core 导出 → 结构断言；依赖方向只能是 `lightrag-rdf → lightrag-core`，core 测试不得反向依赖 rdf 模块。
- 导出 → 导入 → 与原 `Ontology` 在支持子集上相等（identity/type/pattern/按 owner 属性/sourceBinding/version）；空 patterns、多 patterns、LIST、DATE、关系属性、同名属性跨类（owner-scoped 可逆）、每 owner 描述/绑定各一例。
- 导入冲突：同 owner 同属性异 display name/type/description/SourceBinding、同 IRI 多 owner/kind → `OntologyImportException`；字段全同 → 去重；union domain 展开与 `lg:owner` 优先。
- 提交**由 Semantica `bootstrap_schema` 生成的 TTL 固定夹具**（固定样本 + 固定选项），断言 Java 可导入；Semantica 的 `from_owl` 可读取 Java 导出的子集（以夹具/文档记录手工兼容验证，不引入跨语言 CI）。
- SHACL：用 RDF4J 解析并断言 shape 数量/owner-scoped `sh:path`/datatype；不引入 Java SHACL 引擎（避免新依赖）。
- 确定性：同本体 + 同选项 → 同 TTL 字节；同类/关系同名（`class/` vs `relation/`）IRI 不冲突。
- IRI 参数：base 以 `/` 结尾、`shapesBaseIri` 默认无双重 `#`、非法 base 拒绝。
- 转义：label/description 含引号、反斜杠、换行、制表、Unicode 控制字符时导出→RDF4J 解析→导入往返相等；base IRI 非法字符拒绝。
- 安全：逐项回读 XML/JSON-LD 设置；XXE、JSON-LD 远程 `@context`/`@import`（必须被拒绝且不发生网络访问）、读取中字节超限、三元组计数中止、literal/IRI 按 UTF-8 字节长度超限、union/list 环与深度/节点上限、未知公理 warning。

---

## 6. 版本、审阅与删除约束

| 事项 | 约定 |
|---|---|
| 版本存储 | 宿主 `km_ontology_version` 不可变、追加式；`km_ontology.current_version` 仅指针；KB `pinned_version` 钉具体版本 |
| 保留策略 | **永久保留**；不做 TTL、不做定时 prune、不做后台清理；SDK 不提供删除 API |
| bootstrap | 只产 `Ontology` 草稿（`version=null`），不写版本、不自动应用；有 base 时输出完整候选，宿主只能走 `EVOLUTION_REPLACE` 审批 |
| RDF 导入 | 只产草稿/值，不写版本、不自动应用 |
| 2A 迁移 | SDK 不删除宿主旧本体版本/旧投影；属性需 REBUILD/重抽物化，REBUILD 会**原地更新当前图**；pin 旧版本后重建是"按旧本体重抽当前图"，不是恢复历史图快照（r2 R-S3） |
| 图与版本边界（r1 S4） | 属性物化/重抽只作用于 SDK **当前图**；REBUILD 覆盖当前图与 chunk 快照，但**不修改宿主 `km_ontology_version`/旧投影**；本期不提供历史图归档，版本回滚 ≠ 图回滚 |
| Semantica `prune_versions` | 显式人工调用，与本计划无关；**不采纳**任何定时化改造 |

宿主审阅闭环（沿用 Phase 1/宿主计划）：bootstrap/import/discovery → `synthesizeCandidate` → 冻结/审批 → 写 `km_ontology_version` → 前移 `current_version` → KB 显式升级 `pinned_version`（可勾选重抽）。旧版本与旧投影不删除。

---

## 7. 分期与交付

| 子期 | 内容 | 依赖 | 验收门槛 |
|---|---|---|---|
| **2A-1** | 属性值模型/工具/record 扩展/旧构造器/快照记录/API 记录 | 无 | 旧构造器编译回归；canonical JSON 确定性；空属性 JSON 与 Phase 1 一致 |
| **2A-2** | 从 `OntologyTaskContext.ontology()` 的 canonical 形式派生 typed schema、条件 prompt、解析期绑定（coerce/重复裁决/caps/诊断转发）、合并、逐 chunk label/pattern 校验、最终投影 | 2A-1 | prompt 无属性时字节不变；六类型 coerce 矩阵；`{"Age":1," Age ":2}` 不丢候选；投票后投影；最终投影覆盖所有返回分支；回调只转发一次；**首次执行与 context round-trip 后属性键/prompt/结果一致** |
| **2A-3** | 六后端 + 内存写入/读取；Postgres/Arcade/Nebula 迁移；快照 codec | 2A-1 | 各后端 round-trip；旧库迁移幂等；旧快照读取 |
| **2A-4** | GraphEntity/GraphRelation/视图/手工治理/merge 语义 | 2A-1/2A-3 | create/edit/update/merge 属性语义；API 兼容 |
| **2A-5（建议，独立提交）** | 修正 `OntologyConformanceValidator` 的 ∃→∀ 偏差；附 STRICT/PREFER/OPEN 行为矩阵 | 2A-2 | 新增多命中类型用例；行为变化单列发行说明（见 §13 Q11 / §14 S2） |
| **2B** | `ontology.bootstrap` 包 + 单测（M5 确定性、M6 完整候选） | 2A-1 | 确定性/阈值/冲突/完整候选/不写版本 |
| **2C-1** | core 零依赖 Turtle/OWL/SHACL 导出（owner-scoped 属性） | 2A-1 | core golden；`lightrag-rdf` 可解析；round-trip 夹具 |
| **2C-2** | `lightrag-rdf` 导入模块 + RDF4J 5.3.2 + Vanniktech 同版本发布 + 安全限制 | 2C-1 | 导入支持子集；导入冲突 fail-closed；安全设置逐项回读；XXE/远程 context/限额；Semantica 夹具；POM/Java 17 消费/sources/Javadoc 构件 |

**发版：** 2A 作为一次 minor 发布（含公共 record 扩展与迁移说明）；2B 随后发布；2C 的 core 导出与 `lightrag-rdf` **同版本**发布（`lightrag-rdf` 为可选模块）。2A 的属性迁移要求宿主在维护窗 REBUILD，发版说明必须显著标注；∀ 修正若随 2A 发布，必须单独成条。

**提交粒度（r1 S2/S5）：** 2A-5 独立提交；RDF 导入/导出的发布配置与 core 版本对齐；不得在 core 测试中引入对 `lightrag-rdf` 的依赖。

**建议实施顺序：** 2A-1 → 2A-2 → 2A-3 → 2A-4 → 2B → 2C-1 → 2C-2；2A-5 可与 2A-2 同批（若评审同意）。

**三期（未立项）：** ontology evolution/backfill 见**附录 A**，不随二期发布；二期只预留 A.4 的兼容契约。

---

## 8. 实现清单（文件级）

### 8.1 2A-1 模型/工具

| 文件 | 改动 |
|---|---|
| `types/ExtractedEntity.java` | +`Map<String,Object> attributes`；旧 4 参构造器；空→空 map |
| `types/ExtractedRelation.java` | +attributes；旧 5 参构造器 |
| `types/Entity.java` | +attributes；保留 6 参/旧 7 参重载 |
| `types/Relation.java` | +attributes；保留 7 参/旧 8 参重载 |
| `storage/GraphStore.java` | `EntityRecord`/`RelationRecord` +attributes；旧 arity 构造器 |
| `storage/DocumentGraphSnapshotStore.java` | 两个 Extracted 记录 +attributes；旧构造器；JSON 缺失→空 |
| `api/GraphEntity.java` / `api/GraphRelation.java` | +attributes（NON_EMPTY）；旧构造器 |
| `api/CreateEntityRequest.java` / `CreateRelationRequest.java` / `EditEntityRequest.java` / `UpdateRelationRequest.java` / `MergeEntitiesRequest.java` | +attributes/targetAttributes、Builder 方法、旧构造器 |
| 新 `ontology/AttributeValues.java` | `sanitizeRaw`（候选列表）/coerce/caps/merge |
| 新 `ontology/OntologyAttributeBinder.java` | 解析期绑定：identity 分组、coerce 后重复裁决、caps、诊断；返回 identity 键 map |
| 新 `ontology/AttributeJsonCodec.java` | canonical JSON 编解码 |
| 新 `ontology/OntologyAttributeSchema.java` | typed schema + identity 索引 |
| 新 `ontology/OntologyAttributeProjector.java` | 最终投影纯函数 |
| `ontology/UnmatchedLabel.java` | +ownerLabel；Kind/Reason 扩展 |
| `ontology/OntologyStructureValidator.java` | reserved 属性名 warning；**不扩 `Options`**（r1 M2） |
| `ontology/OntologySchemaRenderer.java` | 更新 `:31-33` javadoc：属性是 JSON 袋、不可物理查询；`includeDeclaredProperties` 仍默认 false（r1 N3） |

### 8.2 2A-2 抽取/校验

| 文件 | 改动 |
|---|---|
| `ontology/OntologyExtractionOverlay.java` | **不改**：保持 3 组件、序列化与 fingerprint（r1 M4） |
| `ontology/OntologyTaskContext.java` | **不改** context JSON；schema 由 `OntologyAttributeSchema.from(ontology)` 派生 |
| `api/KgExtractionValidator.java` | +`default void onAttributeDiagnostics(List<UnmatchedLabel>) {}`（旧实现不受影响） |
| `indexing/KnowledgeExtractor.java` | 可选构造参数 `OntologyAttributeSchema`（旧构造器保留，默认 empty）；kind-aware prompt；parseEntities/parseRelations/window 解析**在构造前调用 binder**；诊断排序后转发一次；gleaning merge；sanitize 保留属性 |
| `indexing/DeletionPipeline.java` | `:394/:409` chunk 引用重建携带属性（r1 M1） |
| `indexing/refinement/DefaultAttributionResolver.java` | `:105-110` 重复候选按属性 merge，不再整条丢弃 |
| `storage/postgres/PostgresMilvusNeo4jStorageProvider.java` / `storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | `:904/:916` 与 `:373/:385` 向量转换、`:956/:992` chunk 引用裁剪携带属性 |
| `indexing/GraphAssembler.java` | `MutableEntity/MutableRelation` 属性合并；`ensureEntity`/`toEntity`/`toRelation`/`MergeGuard` 携带 |
| `indexing/refinement/DefaultExtractionMergePolicy.java` | 合并属性 |
| `indexing/refinement/WindowEntityCandidate.java` / `WindowRelationCandidate.java` | +attributes |
| `indexing/IndexingPipeline.java` | `mergeEntityWithCaps`/`mergeRelationWithCaps`/`newEntityRecord`/`newRelationRecord`/`mergeEntityGroup` 携带并合并；实体 `mergeEntityGroup:2059-2062` 两个返回分支、关系最终合并 `:2221-2228`（含空输入/新建/合并）后投影，其他保存/提前返回分支不得绕过；chunk 快照 `:2359/:2367`；构造器新增可选 projector |
| `indexing/GraphMaterializationPipeline.java` | `:1196/:1204` chunk 快照、`:1233/:1241` **快照→抽取**（resume/repair）、`:1324-1335` 关系保存、`:1633` 投票重建全部携带；在两条 `saveGraph` 最终记录集合上投影（实体 `:1631-1634` 两分支；关系 `:1324-1335`）；构造器新增可选 projector |
| `ontology/OntologyConformanceValidator.java` | 转发 `onAttributeDiagnostics` 到 `unmatchedConsumer`；PREFER 转 Other 清空属性；不承担属性 coerce/裁决；（2A-5）∀ pattern 修正 |
| `api/LightRag.java` | 用同一 `OntologyTaskContext` 构造 projector 并注入两条 pipeline |

### 8.3 2A-3 存储/快照

| 文件 | 改动 |
|---|---|
| `storage/neo4j/WorkspaceScopedNeo4jGraphStore.java` | 单条/批量 entity、relation 的 SET 与 row map；读取 |
| `storage/postgres/PostgresGraphStore.java` | upsert/load SQL |
| `storage/postgres/PostgresSchemaManager.java` | DDL + `ensureAttributesColumn` |
| `storage/postgres/PostgresAgeGraphStore.java` | `entityProperties`/`relationProperties`、边内联属性、读取 |
| `storage/falkordb/FalkorDbGraphStore.java` | properties map + 读取 |
| `storage/arcadedb/ArcadeGraphStore.java` | properties + 读取 |
| `storage/arcadedb/ArcadeSchemaManager.java` | `CREATE PROPERTY IF NOT EXISTS ...attributes STRING` |
| `storage/nebula/NebulaSupport.java` | CREATE TAG/EDGE 增列；存量 ALTER 探测 |
| `storage/nebula/NebulaGraphStore.java` | INSERT VERTEX/EDGE 列与 tuple、读取、schema 升级 |
| `storage/memory/InMemoryGraphStore.java` | 直接携带（无需改） |
| `storage/GraphViewTraversal.java` | view 携带属性 |
| `persistence/SnapshotManifest.java` / `persistence/FileSnapshotStore.java` | manifest `CURRENT_SCHEMA_VERSION=2`；加载时校验 1/2/>2（坏 manifest 报错，r1 M4） |
| `storage/postgres/JdbcJsonCodec.java` / `MySqlJsonCodec` / `ArcadeJsonCodec` | 记录形状；空属性省略；旧 JSON 缺失→空 |
| `indexing/GraphVectorIndexer.java` | `:148/:160` 转换携带属性；向量 payload 仍只读 summary/keywords/filePath |
| `query/LocalQueryStrategy.java` / `query/GlobalQueryStrategy.java` | Local `:308/:320`、Global `:226/:238` 携带；检索上下文拼接不变 |
| `query/QueryEngine.java` | **不改** `StructuredQueryEntity/Relation`（`:922/:935`，显式例外） |
| `storage/arcadedb/ArcadeOneShotRetrievalStore.java` | `:244/:256` 携带 |
| `storage/StorageCoordinator.java` | `:176/:420` 委托透传；补含非空属性的 pre-image/回滚/restore 用例 |

### 8.4 2A-4 手工治理

| 文件 | 改动 |
|---|---|
| `indexing/GraphManagementPipeline.java` | `:48/:70/:105/:175/:255/:530/:556/:593/:605/:618/:630/:685` 全部携带；create→请求/空；edit/update→null 保持；merge→source 先并、target 覆盖；`:685` 改名迁移边不得丢属性 |
| `api/*Request.java` | 见 8.1；修改类请求保留 null 语义（r1 M2） |

### 8.5 2B bootstrap

新包 `io.github.lightrag.ontology.bootstrap`：
`OntologyBootstrap`、`EntitySample`、`RelationSample`、`BootstrapOptions`、`BootstrapResult`、`BootstrapConflict`、`BootstrapStats`（+ 包内推断器）。无存储依赖、无 LLM 依赖。

### 8.6 2C RDF

| 文件 | 改动 |
|---|---|
| 新 `ontology/rdf/RdfExportOptions.java`、`OntologyRdfExporter.java`、`OntologyRdfExportException.java`、`TurtleWriter.java`、`RdfIriEncoder.java` | core 零依赖导出；IRI 分层百分号编码与参数校验；导出冲突 fail-closed |
| `settings.gradle.kts` | `include("lightrag-rdf")` |
| 新 `lightrag-rdf/build.gradle.kts` | `java-library`；RDF4J Rio **5.3.2**（turtle/rdfxml/jsonld）；`api(project(":lightrag-core"))`；加入根 Vanniktech 发布白名单并复用 `mavenPublishing`/POM/签名/sources/Javadoc（r1 S5 / r2 R-S1）；core 不反向依赖它 |
| 新 `lightrag-rdf/.../OntologyRdfImporter.java`、`RdfImportOptions.java`、`ImportResult.java`、`RdfFormatValue.java`、`OntologyImportException.java`、`SecureDocumentLoader.java` | 导入 + 安全限制 |

---

## 9. 兼容性与迁移清单

1. **旧构造器/重载**：每个被扩展的 record 保留旧 arity 构造器；补"已编译旧消费者链接"测试（参照 Phase 1 做法）。旧构造器**在仓库内只允许创建空占位**，已有对象重建必须显式传属性（§3.8）；这是源码审查 + CI allowlist 规则，**不是编译器强制**（旧签名在二进制兼容下无法区分占位与复制，r2 R-S2）。
2. **空属性 JSON**：`@JsonInclude(NON_EMPTY)` 必须经序列化测试确认（Jackson 2.18 record component 注解）；空属性时 API 响应与 Phase 1 一致。
3. **快照**：数据库快照缺字段→空；文件快照 manifest v1 可读（属性空）、v2 读写属性、>2 明确拒绝；旧 SDK 读新快照仅在属性为空时兼容；非空属性要求协调升级，不承诺降级。
4. **存储**：统一写 `"{}"`；旧行 NULL/缺失→空；Postgres/Arcade/Nebula 迁移幂等；Nebula 存量 space 的 ALTER 后必须通过写入探针，失败显式报错并给升级指令。
5. **重抽**：属性需要 REBUILD/重抽才出现在既有图；旧图读取不受影响；REBUILD 覆盖当前图/chunk 快照，不影响宿主版本库（r1 S4）。
6. **prompt**：无属性声明时字节不变；有属性声明时新增段落与输出形状，需跑抽取质量回归（小样本）；同名 label 的实体/关系属性段不得互相覆盖（M10）；属性段使用 canonical identity，首次执行与 context 恢复后逐字节一致（R-M1）。
7. **校验行为（r1 S2）**：属性只丢属性不丢记录。若 2A-5 修正 ∀，行为矩阵为：**STRICT 丢弃该关系**；**PREFER 保留并回调**；**OPEN 保持现状**。该修正独立提交并单列发行说明；任何行为开关若引入，必须同时进入任务上下文与 fingerprint（本期不引入开关）。
8. **API 语义（r1 M2）**：`EditEntityRequest.attributes == null` 保持不变、`{}` 清空；`UpdateRelationRequest` 同理；`MergeEntitiesRequest.targetAttributes == null` 沿用 target。该语义优先于 record 构造器的空 map 归一。
9. **手工写**：不做本体裁剪，管理员可写入任意（结构合法的）属性；与抽取路径的差异在文档写明。
10. **版本**：不新增/删除版本；bootstrap/import 不分配版本；bootstrap 有 base 时输出完整候选并只走 `EVOLUTION_REPLACE`（M6）。
11. **任务上下文**：`OntologyJsonCodec` context `schemaVersion=1`、`OntologyExtractionOverlay` 3 组件与 fingerprint 均不变；历史任务恢复后由 ontology 派生 schema（M4）。schema 从 canonical ontology 派生，首次执行与恢复后的属性键/prompt/结果一致；不引入 context v2 或持久化 display name（R-M1）。
12. **结构化查询**：`StructuredQueryEntity/Relation` 本期不含属性（显式例外），API 消费者改用 `GraphEntity/GraphRelation`。

---

## 10. 测试计划

### 10.1 单测

- `AttributeValuesTest`：六类型 coerce 矩阵（含 Boolean/数值/日期时区、LIST 嵌套/Map、空串、越界）；`sanitizeRaw` 输出 `RawAttributes`（候选 + issues）且保留 `{"Age":1," Age ":2}` 两个候选；键 identity/重复键（**coerce 后**等值保留、异值丢弃+回调、无 schema 抛错）；INTEGER 经 `BigDecimal` 截断与 `2^53`/Long 边界；16 KiB **UTF-8 字节**上限（多字节字符）；语义 caps 在 coerce/重复裁决之后；结构上限不提前执行语义 caps；merge last-write-wins。
- `OntologyAttributeBinderTest`：identity 分组与顺序无关；trim 撞键保留候选；`RawIssue` 映射为带 owner/chunkId 的 `INVALID_ATTRIBUTE_KEY`/`INVALID_ATTRIBUTE_VALUE`/`ATTRIBUTE_LIMIT_EXCEEDED`；未知/类型冲突/重复键诊断；结果按 identity 排序且为 identity 键；**空/空白 `rawLabel` 与 `ownerLabel` 走哨兵排序去重、不抛异常且字段保留空串**；不抛属性异常。
- `AttributeJsonCodecTest`：键排序、Integer/Long 反序列化后归一、数字、Unicode、空 map、坏 JSON、locale 无关、同输入同字节。
- `RecordCompatibilityTest`：旧构造器编译/运行（仅空占位）；`equals` 含属性；`@JsonInclude` 空属性形状；修改类请求 null 保持。
- `OntologyConformanceValidatorAttributeTest`：`onAttributeDiagnostics` 转发到既有回调且 refinement 二次 `validate` 不重复；OPEN 早退不改变绑定结果；PREFER 转 Other 清空；关系多命中类型（含同名异型丢弃）已在绑定期处理。
- `KnowledgeExtractorAttributeTest`：无声明 prompt 字节不变；有声明 prompt 含**实体/关系分节**属性段（同名 label 不互相覆盖）；parse/window/gleaning；无 schema 丢弃 attributes；sanitize 保留属性；**属性错误不进入构造 catch**；诊断只在 `extractWithCacheIds`/`extractWindow` 末尾转发一次；**同一 ontology 首次执行 vs `writeContext/readContext` 后执行**：属性段 prompt、解析后的 identity 键、投影结果一致（大小写 display name 不影响）。
- `GraphAssemblerAttributeTest` / `IndexingPipelineAttributeTest` / `GraphMaterializationPipelineAttributeTest`：逐键合并；类型投票后投影；关系早退不贡献属性；**所有保存返回分支**都执行最终投影；关系在最终合并后投影；`:1233/:1241` 快照→抽取保留属性。
- `GraphManagementPipelineAttributeTest`：create/edit/update/merge（null/空/替换/target 优先）；`:685` 改名迁移边保留属性。
- `SnapshotAttributeTest`：数据库快照旧 JSON（无字段）/null/新形状 round-trip；坏 JSON 读容错；文件快照 manifest v1/v2/>2/坏 manifest；v1 属性为空、v2 保属性、>2 报错。
- `BootstrapTest`：§4.5 全部用例（含拼写计票、support 身份、重复属性确定性、样本 trim 撞键、端点歧义、空 pattern 关系丢弃、BOOLEAN/混合类型、maxSamples 拒绝、完整候选、fingerprint 对 base 与 algorithmVersion 敏感、乱序后完整 `BootstrapResult` 相等）。
- `AttributeConservationTest`：按 §3.8 表驱动"非空属性经过 X 路径后仍存在"，至少覆盖 Deletion、快照 resume、向量转换、query view、手工治理、StorageCoordinator 回滚。
- `RdfExportTest` / `RdfImportTest`：映射、确定性、round-trip、owner-scoped 多类同名属性、class/relation 同名 IRI、导入冲突 fail-closed、union domain 例外、未知公理 warning、转义往返、IRI 参数校验、XML/JSON-LD 设置回读、XXE/远程 JSON-LD context/`@import`/读取中限额/三元组中止/literal-IRI 的 UTF-8 字节长度、list/union 环与深度上限。
- `OntologyStructureValidatorTest`：reserved 属性名 warning；不新增 Options 开关。

### 10.2 契约/集成

- 旧 `entityTypes/relationTypes/examples` 行为不变；旧 overlay 3 参构造器可用；`OntologyJsonCodec` 历史 context 可读并派生 schema；同一任务首次执行与从持久化 context 恢复执行结果一致。
- `lightrag-rdf` 发布接线：加入根发布白名单后 `publishToMavenCentral` 任务可执行；POM 中 core 为 compile 依赖、RDF4J 版本固定 5.3.2；Java 17 消费工程可编译；sources/Javadoc 构件生成。
- 两条建图链路（ingest / REBUILD）都能物化属性；refinement 不丢属性；两条链路的最终投影覆盖所有返回分支。
- 手工治理与快照恢复携带属性；结构化查询保持不含属性（显式例外）。
- 六后端 round-trip（复用现有 Testcontainers/适配器测试；Nebula/Arcade 按现有测试形态）。
- Postgres/Arcade/Nebula 从旧 schema 升级后用新 SDK 读写；Nebula 必须跑 **ALTER + 写入探针** 的真实旧 space 用例。
- 文件快照 v1→v2 升级：旧文件可读、新文件可由 v2 读取、更高版本被拒绝、坏 manifest 报错。
- 抽取 prompt 质量回归：固定小样本，比较"无属性"路径不变；"有属性"路径人工抽查；同名 label 的实体/关系属性段不冲突。

### 10.3 确定性与安全

- 同输入重复运行：属性 JSON、bootstrap 草稿/fingerprint、TTL/SHACL 导出字节一致。
- bootstrap：打乱样本/属性 map 顺序后完整 `BootstrapResult`（draft/conflicts/warnings/stats/fingerprint）一致；重复样本属性冲突的合并与计数确定；拼写代表与 support 不随行序变化。
- RDF 导入：XXE、读取中字节超限、三元组中止、远程 imports/远程 JSON-LD context 均不访问网络且 fail-closed；任何失败不返回部分结果。
- 属性袋上限：同输入截断结果一致；解析期回调计数与最终投影独立计数互不重复；优先保留声明键。

---

## 11. 验收标准（Definition of Done）

**2A：**

1. 声明属性后，实体/关系抽取 JSON 含 `attributes`，六类型按声明 coerce，非法值丢弃并回调；绑定在记录构造前完成，`{"Age":1," Age ":2}` 不会因 trim 合并丢候选。
2. 属性贯穿 extract → assemble → refine → merge → vote → save → reload → snapshot → API；两条建图链路一致，且**所有保存返回分支**都经过最终投影；快照 resume/repair 不丢属性。
3. 类型投票后按最终类型重新投影；不存在"最终类型 A、属性来自 B"的残留。
4. 六后端 + 内存 round-trip；Postgres/Arcade/Nebula 迁移幂等且 Nebula 写入探针通过；数据库快照与文件快照（v1/v2）可读。
5. 无属性声明时 prompt 字节不变；空属性 API JSON 与 Phase 1 一致；旧构造器兼容（仅空占位）。
6. 属性永不导致实体/关系被删；OPEN/PREFER/STRICT 的 label/pattern 语义不变。
7. 同一 ontology 首次执行与 `writeContext/readContext` 后执行，属性段 prompt、identity 键与投影结果一致；不得依赖 display name 择优。
8. 若交付 2A-5：∃→∀ 修正独立提交，STRICT/PREFER/OPEN 行为矩阵有测试，发行说明单列。

**2B：**

9. 同输入 + 同选项 → 同草稿/fingerprint；打乱样本与 map 顺序后完整 `BootstrapResult` 相等（含诊断与统计）。
10. 频率门/文档门/属性 support（按去重键）/拼写计票/reserved/端点门/歧义/空 pattern 关系丢弃/冲突计数符合 §4。
11. 有 base 时输出完整候选且只用于 `EVOLUTION_REPLACE`；不调用 governance、不写存储、不分配版本；`algorithmVersion` 参与 fingerprint。

**2C：**

12. 导出可被 RDF4J 解析；owner-scoped 属性支持子集 round-trip 相等；确定性字节；三类 IRI 参数校验通过、base 无双重 `#`、转义往返成功。
13. Semantica 生成的 TTL 夹具可导入；未知公理 warning；导入冲突（display name/type/description/SourceBinding/owner/kind）fail-closed；union domain 例外有测试；不推理、不联网。
14. SHACL 产出实体 NodeShapes（标量 datatype/maxCount；LIST 限制有文档）。
15. XXE/远程 context/`@import`/读取中限额/三元组中止/literal-IRI UTF-8 字节长度/list-union 环测试通过；XML/JSON-LD 设置逐项回读；失败不产生部分结果。
16. `lightrag-rdf` 与 core **同版本**发布（RDF4J 依赖固定 5.3.2）：GAV、POM、签名、sources/Javadoc、Java 17 消费验收通过。

---

## 12. 风险与排除项

### 12.1 风险

1. **prompt 变长/质量漂移**：属性段只在有声明时出现，且提供关闭开关（本体不声明即可）；仍需小样本回归。
2. **属性幻觉**：只物化声明属性、逐值 coerce、越界丢弃；不信任模型输出。
3. **合并顺序依赖**：last-write-wins 依赖 chunk 处理顺序；并行结果按索引回填（既有行为），需固定顺序测试。
4. **JSON 袋不可物理查询**：renderer 继续默认不渲染属性；不要在文档/产品上宣称可 `WHERE n.age`。未来扁平化另立项。
5. **存储字符串上限**：16 KiB canonical JSON 是**待验证目标**，不是已验证容量；AGE 边属性内联、Nebula `string(16384)` 列宽与 INSERT 必须在 2A-3 实测（含多字节 UTF-8），失败则下调上限或调整 DDL。
6. **快照降级**：旧 SDK 读非空属性快照失败；只能协调升级。
7. **投票与属性来源**：投影只保证"最终类型声明的属性"，不保证"值来自该类型的证据"；若需强保证另立来源追踪。
8. **RDF 语义损失**：多 pattern、关系属性、LIST 用扩展词汇表达；owner-scoped 属性会让外部工具看到"每个类各有一个属性"而非共享属性；与标准 OWL 工具交换时需人工检查；SHACL 仅实体属性。这是为可逆性做的显式取舍（r1 M7）。
9. **∀ 修正的行为变化**：会收紧多命中关键词关系的保留；独立提交 + 行为矩阵 + 发行说明，由评审决定是否随 2A 发布（r1 S2）。
10. **bootstrap 启发式**：频率门/端点解析/类型 lattice 仍是启发式；歧义端点直接丢弃而非猜测，可能漏掉低频但正确的关系类型。草稿永不自动应用，由宿主审阅兜底。
11. **属性袋上限的取舍**：解析期优先保留声明键，未知键可能因上限被挤出；最终投影只静默收敛并用独立计数，调试信息少于 chunk 级回调（r1 S6）。
12. **RDF4J 版本与内部 API**：5.3.2 的 XML/JSON-LD 设置名与行为是当前实现依据；升级到 6.x 需 Java 25，超出本项目 toolchain。任何升级必须重新验证设置回读与失败策略，不能假设配置继续有效（r2 R-M4）。
13. **bootstrap 语义粒度**：support 按 `(document, chunk, entity identity)` 去重键计数，同一实体跨 chunk 会累加；这是频率门一致性的选择，不代表"去重后的实体数"。宿主若需实体级支持度，应在样本侧先聚合。

### 12.2 明确不做

- 属性扁平化为独立图字段/索引、属性级 Cypher 过滤。
- 结构化表格入图、`declared_labels` 吸收（Phase 1 已排除）；ontology evolution/backfill 见**附录 A**（三期立项草案，本期不实现）。
- OWL reasoner、subclass 闭包、SHACL 导入/执行引擎。
- 定时版本清理/`prune_versions` 自动化。
- 检索策略本体化（LOCAL/GLOBAL 按属性过滤）。
- 多轮 bootstrap 人机反馈、grounding catalog。

---

## 13. 评审状态与结论

> **r1（Codex read-only，2026-10-19）结论：方向可行，但不宜按 r1 原文直接实施。** MUST-FIX M1–M10、SHOULD-FIX S1–S6、NIT N1–N3 已逐项处置（§14.1–14.3）。
>
> **r2（Codex read-only，2026-10-19）结论：不通过（No）。** M1/M2/M6/M9/M10、S1/S2/S3/S6、N1–N3 已 CLOSED；M3/M4/M5/M7/M8、S4/S5 PARTIALLY CLOSED，收窄为 4 项阻塞：R-M1 属性键跨 owner/恢复一致性、R-M2 bootstrap 乱序确定性、R-M3 RDF 可逆映射、R-M4 RDF 安全契约。本轮已全部修订，逐条见 §14.5。
>
> **r3（Codex read-only，2026-10-19）结论：不通过（No）。** R-M4、R-S1、R-S2、R-S3、R-M3（除拼写）、R-M2（受 R-M1 影响）已 CLOSED；阻塞收窄为 **R-M1**：`sanitizeRaw` 返回 trim 后 `Map` 会丢掉 `{"Age":1," Age ":2}` 的候选，且"构造前裁决"与"validator 内裁决"阶段矛盾。本轮以候选列表 + 解析期 binder + 诊断转发闭合，逐条见 §14.6。
>
> **r4（Codex read-only，2026-10-19）结论：通过（yes with non-blocking follow-ups）。** R-M1–R-M4、R-S1–R-S3、NIT 全部 CLOSED；唯一剩余为 SHOULD：空白 `rawLabel` 的诊断排序/去重不得直接调用 `Identity.identityId`。本轮已并入哨兵规则，逐条见 §14.7。**计划可进入实现。**

**r1 问题结论（Q1–Q14）：**

| # | r1 结论 | 本修订落点 |
|---|---|---|
| Q1 record 扩展 | 部分（旧构造器掩盖漏传；基础 record 实为 10 个，构造调用 70 处） | §3.8 全量 70 处；旧构造器仅限空占位 |
| Q2 存储形态 | 同意 JSON 字符串；补 16 KiB UTF-8 测试 | §3.1/§3.5 |
| Q3 校验语义 | 部分（OPEN 行为需明确；关系并集规则保留） | §3.3 |
| Q4 合并语义 | 部分（早退语义表述不准；类型投票来源不保证） | §3.3/§3.4 |
| Q5 最终投影落点 | 部分（必须覆盖所有返回分支、关系最终合并后投影） | §3.3/§8.2；S1 |
| Q6 prompt | 部分（golden 只覆盖关闭路径；需 kind-aware） | §3.2；M10 |
| Q7 bootstrap | 部分（默认 2/1/1 合理；端点门/空 pattern 需收紧） | §4；M5/M6 |
| Q8 RDF | 部分（必须可逆；DATE 可；LIST 仅类型注记） | §5.2/§5.3；M7 |
| Q9 导入 | 部分（有损导入可接受；需具体安全配置） | §5.3/§5.4；M8 |
| Q10 快照/升级 | 部分（文件快照/上下文/DB 需协调升级） | §3.5/§9；M4 |
| Q11 ∀ 偏差 | 同意修（独立提交 + 真值表） | §7/§9；S2 |
| Q12 版本保留 | 同意（边界清晰） | §6 |
| Q13 遗漏面 | 不够（Deletion/Coordinator/vector 等） | §3.8 |
| Q14 发布顺序 | 部分（2A minor；2A-5 独立；RDF 同版本） | §7；S5 |

**r2 问题结论（R-M1–R-M4 / R-S1–R-S3 / NIT）：**

| # | r2 结论 | 本修订落点 |
|---|---|---|
| R-M1 属性键/恢复 | PARTIALLY CLOSED → 本轮修订 | §3.1 键一律 canonical identity、删除 display 择优、固定处理顺序；§3.2 schema 从 `toCanonical()` 派生；§3.3 coerce 后裁决；§9/§10 首次 vs 恢复一致性测试 |
| R-M2 bootstrap | PARTIALLY CLOSED → 本轮修订 | §4.2 `algorithmVersion`；§4.3 组内拼写代表、support 去重键、诊断排序与冲突计数、fingerprint 加版本；§4.5/§10 完整结果乱序相等 |
| R-M3 RDF 映射 | PARTIALLY CLOSED → 本轮修订 | §5.2 `kind/owner/property` 分层百分号编码、无 hash 回退、base 必须 `/`、导出冲突矩阵；§5.3 union 例外与完整冲突矩阵 |
| R-M4 RDF 安全 | PARTIALLY CLOSED → 本轮修订 | §5.1 锁定 RDF4J 5.3.2；§5.4 逐项设置/回读/拒绝回退、DocumentLoader、`@import`、list/union 遍历上限、UTF-8 字节长度与转义 |
| R-S1 发布 | SHOULD → 本轮修订 | §5.1/§8.6/§10.2：Vanniktech 白名单、GAV/POM/签名/sources/Javadoc、`api(core)`、Java 17 消费验收 |
| R-S2 旧构造器 | SHOULD → 本轮修订 | §3.8/§9：改为仓库内源码规则 + allowlist，明确非编译器强制，列出两个合法占位点 |
| R-S3 文字/锚点 | SHOULD → 本轮修订 | §6 改为"宿主旧版本/旧投影"；§3.8 补 `GraphMaterializationPipeline:1324-1335`；§12.1 改为待实测目标 |
| NIT | 已修订 | §1.3 关系谓词改为"无受约束类型或至少一个匹配"；§2.1 注明 `Ontology.from_file` 读 JSON 配置 |

**r3 问题结论（R-M1–R-M4 / R-S1–R-S3 / NIT）：**

| # | r3 结论 | 本修订落点 |
|---|---|---|
| R-M1 候选/阶段 | PARTIALLY CLOSED → 本轮修订 | §3.1 `sanitizeRaw` 输出 `RawAttributes`（候选 `List<RawAttribute>` + 结构问题）；新增 `OntologyAttributeBinder` 在构造前完成 schema 匹配/coerce/identity 裁决/caps；诊断经 `KgExtractionValidator.onAttributeDiagnostics` 转发一次；§3.3 普通/window/merge 路径；§3.8 边界改述；§4.3/§4.5 样本规范化复用 identity 分组；§10 测试 |
| R-M2 bootstrap | CLOSED（受 R-M1 影响） | §4.3 样本 Map 的 trim/identity 撞键规则补入；§4.5/§10 增加乱序断言 |
| R-M3 RDF | CLOSED | 映射规则无改动；r3 顺带修正 §5.3/§5.4 的 `owl:UnionOf` → `owl:unionOf` |
| R-M4 | CLOSED | 无改动 |
| R-S1/R-S2/R-S3 | CLOSED | 无改动 |
| NIT 发布措辞 | 已修订 | §11-16 改为"与 core 同版本发布（RDF4J 依赖固定 5.3.2）" |

**r4 问题结论：**

| # | r4 结论 | 处置 |
|---|---|---|
| R-M1 候选/阶段 | CLOSED（P:163–172、205–219 核验） | 无阻塞；`RawAttributes` 贯穿普通/window/bootstrap，binder 在构造前执行，诊断只转发一次 |
| R-M2 bootstrap | CLOSED | 无 |
| R-M3 RDF 映射 | CLOSED（`owl:unionOf` 仅历史记录保留旧拼写） | 无 |
| R-M4 RDF 安全 | CLOSED（按既定验收实测 parser 行为） | 无 |
| R-S1 发布 / R-S2 旧构造器 / R-S3 文字锚点 | CLOSED | 无 |
| NIT 发布措辞 | CLOSED | 无 |
| r4 SHOULD：空白标签诊断排序 | 非阻塞 | §3.1/§3.3 增加空白安全规范化（固定哨兵 `"<empty>"`，不改写字段）与验收；见 §14.7 |

**结论：** 通过（yes with non-blocking follow-ups），无 MUST-FIX；非阻塞 SHOULD 已在本文件闭合，可进入实现。Phase 3（ontology evolution/backfill）不在本期范围，立项草案见附录 A。

---

## 14. 评审修订记录（r1/r2/r3/r4）

**评审输入：** Codex headless read-only（2026-10-19），产物 `C:\Users\chensheng\AppData\Local\Temp\codex-ontology-phase2-review\review-plan-r1.md`。结论：**方向可行，但不宜按 r1 原文直接实施**；须先补齐属性传递清单、上下文迁移、bootstrap 确定性与治理边界、RDF 可逆映射与离线解析规则。

### 14.1 MUST-FIX

| ID | r1 finding | 处置 |
|---|---|---|
| M1 | 保留旧构造器会掩盖漏传；实际构造点远多于计划所列（70 处） | 新增 §3.8 全量清单；§1.2 补漏点；§8.2/§8.3/§8.4 逐文件落点；旧构造器仅限空占位 |
| M2 | "null→空 map"与 PATCH 语义冲突；`OntologyStructureValidator.Options` 公共签名 | §3.1 分域；§3.6 明确 null 优先级；§8.1 明确**不扩 Options** |
| M3 | canonical number/identity 规则不足（Integer/Long、大小写、溢出、UTF-8 字节） | §3.1 重写：identity、重复键、`BigDecimal` 截断、`2^53`/Long 边界、16 KiB UTF-8 |
| M4 | 任务上下文/文件快照格式迁移缺失 | §3.2 改为从持久化 ontology 派生 schema，overlay/context/fingerprint 不变；§3.5 文件 manifest v2 + 版本校验 |
| M5 | bootstrap 顺序不变性缺口（重复键属性、端点歧义、label 频率、support、BOOLEAN、混合类型、maxSamples、fingerprint） | §4.3 逐项定稿；§4.5 补测试 |
| M6 | "增量提议"违反 `REGISTER_INCREMENTAL` 禁改规则 | §4.3 改为完整候选（base + 增量）并只走 `EVOLUTION_REPLACE`；§6 同步 |
| M7 | RDF 映射不可逆（per-owner 描述/绑定丢失、`lg:owner` 未导出、IRI/label 碰撞） | §5.2 属性一律 owner-scoped + `lg:owner` + 稳定 IRI；§5.3 冲突 fail-closed |
| M8 | RDF 离线安全不完整（JSON-LD 远程 context、读取中限额、三元组中止、长度、fail-closed） | §5.4 重写 |
| M9 | Nebula 迁移需要真实写入探针而非仅 DDL | §3.5 ALTER + tag/edge 写探针、超时、并发竞态、真实旧 space 测试 |
| M10 | overlay `from` 同名 label 的实体/关系属性块互相覆盖 | §3.2 改为 kind-aware 分节渲染；legacy block 仅在 typed schema 为空时回退 |

### 14.2 SHOULD-FIX

| ID | r1 finding | 处置 |
|---|---|---|
| S1 | 最终投影必须覆盖所有返回分支，关系在最终合并后投影 | §3.3/§8.2/§10.1 明确 `IndexingPipeline:2059-2062` 与 `GraphMaterializationPipeline:1631-1634` 两个三元返回分支 + 其他保存/提前返回分支 |
| S2 | ∀ 修正需要行为矩阵、独立提交、发行说明、开关须入上下文/fingerprint | §7/§9/§11：2A-5 独立提交；STRICT 丢弃、PREFER 保留+回调、OPEN 不变；本期不加开关 |
| S3 | `mergeRelationWithCaps` 早退语义表述不准；聚合语义不保证逐属性证据 | §3.3 改为"caps 过滤后无保留证据"；§3.4/§12 记录已知限制 |
| S4 | 宿主版本/投影与 SDK 当前图边界需区分 | §3.5/§6：属性作用于当前图；REBUILD 覆盖当前图/chunk 快照，不触碰宿主版本库；不做历史图归档 |
| S5 | 独立发布不完整；core 导出测试不得依赖导入模块 | §5.1/§7/§8.6：`lightrag-rdf` 与 core 同版本并接入 Vanniktech 发布；RDF4J 解析断言放 rdf 模块 |
| S6 | 早期截断可能挤掉合法键；最终静默投影隐藏冲突 | §3.1/§3.3/§10.3/§12：解析期优先声明键；最终投影只确定性收敛 + 独立计数；回调归属解析/校验阶段 |

### 14.3 NIT

| ID | r1 finding | 处置 |
|---|---|---|
| N1 | `ensureEntityFilePathColumn` 锚点错误 | 修正为 `PostgresSchemaManager.java:502-514`（§3.5） |
| N2 | 基础 record 数量、`RenderOptions` 默认值、Nebula 常量锚点 | 基础 record 更正为 10 个（§13 Q1 行）；`RenderOptions` 默认 `:38-39`；Nebula 常量 `:40/:45`、SQL 字符串 `:41/:46` |
| N3 | `OntologySchemaRenderer` 文档注释暗示属性可渲染；Q8→Q10 引用错误 | §3.7/§8.1 增加 javadoc 更新；§3.5 改引 Q10；§1.3 引用改为 §13 Q11 |

### 14.4 保留的取舍（r1 未反对或已确认）

- **JSON 字符串袋**而非六后端扁平列：保持六后端一致与迁移最小；属性不承诺物理查询（Q2）。
- **结构化查询不含属性**：`StructuredQueryEntity/Relation` 本期不变，属性经 `GraphEntity/GraphRelation` 暴露。
- **bootstrap 不读存储、零 LLM、永不自动应用**；不引入 `owl:Thing` 端点对齐。
- **版本永久保留**：不实现任何定时/自动 prune；bootstrap/import 不分配版本。

### 14.5 r2 评审修订（2026-10-19）

**评审输入：** Codex headless read-only，产物 `C:\Users\chensheng\AppData\Local\Temp\codex-ontology-phase2-review\review-plan-r2.md`。结论 **No**，4 项阻塞（R-M1–R-M4）+ 3 项 SHOULD（R-S1–R-S3）。本轮修订如下：

| ID | r2 问题 | 处置 |
|---|---|---|
| R-M1 | 属性键在多 owner/恢复后不唯一；处理顺序矛盾 | 键一律 `Identity.identityId`；删除 display name 择优；`OntologyAttributeSchema.from` 先 `toCanonical()`；固定"原始键→coerce→重复裁决→caps→构造→合并→最终投影"；语义边界在 r3 进一步改为解析期 binder + 最终投影（见 §14.6） |
| R-M2 | bootstrap 拼写/support/诊断/算法版本未闭合 | 组内拼写代表 + 跨组计票；support 按去重键；conflicts/warnings 固定排序；冲突按 `(去重键, 属性 identity)` 计数；`algorithmVersion=1` 进 fingerprint 与结果 |
| R-M3 | RDF IRI 条件 hash、跨 kind、union 冲突、冲突矩阵、双 `#` | `kind/owner/property` 分层严格百分号编码（单射、无 hash 回退）；base 必须 `/`；导出/导入完整冲突矩阵；union domain 显式例外；IRI 参数校验 |
| R-M4 | RDF 安全契约不可验证 | 锁定 RDF4J 5.3.2；列出 `XMLParserSettings`/`JSONLDSettings` 实际常量与回读断言；DocumentLoader 拦截远程 context；预检 `@context` 字符串/`@import`；list/union visited+深度/节点上限；UTF-8 字节长度与转义口径 |
| R-S1 | 发布未接线 | 根 Vanniktech 白名单加入 `:lightrag-rdf`；复用 core 的 `mavenPublishing`/POM/签名；`api(project(":lightrag-core"))`；POM/Java 17/sources/Javadoc 验收 |
| R-S2 | 旧构造器限制被描述为编译器强制 | 改为仓库源码检查 + allowlist；明确二进制兼容下无法运行时区分；列出两个合法占位点 |
| R-S3 | 清理误导文字/锚点 | §6 改"宿主旧版本/旧投影"；§3.8 补关系保存锚点 `:1324-1335`；§12.1 改为待实测目标 |
| NIT | 关系谓词描述、`Ontology.from_file` 格式 | §1.3 改为"无受约束类型或至少一个匹配"；§2.1 注明 JSON 配置 |

**新增/保留的取舍：**

- 属性物化键统一为 canonical identity，**放弃在图/快照中保留属性 display name**；展示名仍在 ontology/prompt。这是保证首次执行与任务恢复一致的最小改动（不引入 context v2）。
- bootstrap `support` 以 `(document, chunk, entity identity)` 去重键计数，与频率门粒度一致；同一实体跨 chunk 会累加，已在 §12.1-13 标注为语义选择。

### 14.6 r3 评审修订（2026-10-19）

**评审输入：** Codex headless read-only，产物 `C:\Users\chensheng\AppData\Local\Temp\codex-ontology-phase2-review\review-plan-r3.md`。结论 **No**，唯一阻塞为 R-M1 残留（候选表示与处理阶段），另有 1 项 SHOULD（`owl:UnionOf` 拼写）与 1 项 NIT（发布措辞）。本轮修订如下：

| ID | r3 问题 | 处置 |
|---|---|---|
| R-M1 候选表示 | `sanitizeRaw` 返回 trim 后 `Map`，`{"Age":1," Age ":2}` 无法保留两个候选到 coerce 后裁决 | §3.1/§3.3 改为 `RawAttributes`（候选 `List<RawAttribute>` + 结构问题）；按 identity 分组、`(identity, rawKey)` 排序裁决；结构上限与语义 caps 分离 |
| R-M1 阶段矛盾 | 计划称重复裁决/caps 先于构造，但 §3.3 放在 validator；实际 `:671/:715` 先构造、`:402` 才校验，诊断无法回填 | 新增 `OntologyAttributeBinder`，在 `parseEntities`/`parseRelations`/window parse **构造前**完成绑定；`KnowledgeExtractor` 通过 `KgExtractionValidator.onAttributeDiagnostics` 在每次提取结束时转发诊断一次；validator 不再投影 |
| R-M1 bootstrap 传染 | bootstrap 复用同一入口，map 乱序等价受影响 | §4.3 样本规范化改为同一 identity 分组：异值整键丢弃 + 确定性 warning；§4.5/§10 增加乱序测试 |
| R-M2 | 算法已闭合，但受 R-M1 影响 | 随 R-M1 修复一并关闭；`BootstrapResult` 全字段乱序相等仍为验收 |
| MUST/SHOULD：union 拼写 | `owl:UnionOf` 应为 `owl:unionOf` | §5.3/§5.4 统一为 `owl:unionOf`（domain 指向类表达式节点，节点经 `owl:unionOf` 指向 RDF list） |
| NIT：发布措辞 | "`lightrag-rdf` 以 5.3.2 同版本发布"混淆依赖版本与 SDK 版本 | §11-16 改为"与 core 同版本发布（RDF4J 依赖固定 5.3.2）" |

**新增/保留的取舍：**

- 属性诊断**不进入 `ExtractionResult`**：避免 refinement 后第二次 `validate` 重复计数；代价是 `KgExtractionValidator` 增加一个 default no-op 方法，自定义 validator 需要覆写才能接收属性诊断。
- `OntologyConformanceValidator` 从"属性投影者"退化为"诊断转发者 + Phase 1 label/pattern 校验者"；属性约束由解析期 binder 承担，二者必须由 `LightRag` 从同一 `OntologyTaskContext` 派生（§3.2/§3.3）。
- JSON 层字节完全相同的重复键仍由 Jackson 读树合并（最后一个值生效）；本计划只保证 trim/identity 撞键不丢候选，不引入 `STRICT_DUPLICATE_DETECTION` 以免收紧既有 LLM 响应容忍度。
- RDF4J 固定 5.3.2 而非最新 6.1.0：后者要求 Java 25，与项目 Java 17 toolchain 不兼容（Maven Central 元数据核对：6.1.0 父 POM `java.version=25`，5.3.2 为 11）。

### 14.7 r4 评审修订（2026-10-19）

**评审输入：** Codex headless read-only，产物 `C:\Users\chensheng\AppData\Local\Temp\codex-ontology-phase2-review\review-plan-r4.md`。结论 **yes with non-blocking follow-ups**；R-M1–R-M4、R-S1–R-S3、NIT 全部 CLOSED，无可进入实现前的阻塞项。

| ID | r4 问题 | 处置 |
|---|---|---|
| SHOULD：空白标签诊断排序 | `ATTRIBUTE_LIMIT_EXCEEDED` 故意产生空 `rawLabel`，但排序/去重要求调用 `Identity.identityId("")`，而 `Identity` 对空白输入抛 `IllegalArgumentException`；未知 owner 也可能为空 | §3.1/§3.3 规定空白安全规范化：trim 后非空才取 identity，空白映射为固定哨兵 `"<empty>"`；只用于排序/去重键，`UnmatchedLabel` 字段保留原值；`OntologyAttributeBinderTest` 增加空/空白标签验收 |

**评审给出的非阻塞提示（已核对，不需改文）：** `parseOrEmpty`/`parseExtractionResult`/四个 parse 方法与 `parseWindowExtractionResponse` 当前均为 `static`，实现时需沿调用链显式传入 schema、chunkId/window 与诊断 sink（或改为实例方法）；`WindowExtractionResponse` 需增诊断列表并保留旧构造器。均为实现细节，不改变本计划契约。

---

## 附录 A：Phase 3 立项草案 —— ontology evolution / backfill（非本期实现）

**定位：** 本附录**不是二期交付范围**（§12.2 仍排除 backfill）；只锁定若启动三期时采用的语义、以及二期必须预留的契约。参考 GraphRAG-SDK 的 evolution 模型（worktree `changzhou-housing-fund-ontology` 的 `docs/ontology-evolution.md` 与 `ingestion/backfill.py`），**只采纳语义**，不照搬其"单后端 + 单当前 schema + 无版本历史"的实现。

### A.1 核心不变量

- 声明属性对其 owner 类型的所有实例"可查询"：缺失键读作 `null`（不是空串，也不是 `"{}"` 里的占位）。
- schema 变更与数据对齐是**一次原子操作**：先回填存量 chunk/实体，最后提交 schema（commit point）；失败则 schema 保持旧版本，读者看不到"已声明但数据未对齐"的中间态（GraphRAG-SDK `ontology-evolution.md:9`、`:48–50`）。
- 回填必须幂等、可预算、可中断续跑：per-chunk 操作标记 + `dry_run`（只算 `chunks_in_scope`）+ 并发上限 + 失败 chunk 清单（`:121`、`:164–171`）。
- 关系属性 evolution 不能沿用 GraphRAG-SDK 的 `NotImplementedError`（`:292`）：二期 2A 已物化关系属性，三期必须覆盖。

### A.2 与版本约束的关系（用户硬约束不变）

- 版本仍是**不可变、追加式、永久保留**；每次审批通过的 schema 变更产生一个新版本，回填操作本身**不产生版本**，只记录 `operationId`。
- **禁止定时/自动删除**；SDK 不提供删除 API。宿主可对误创建/合规场景做显式、审计化删除——这与"不得定时删除"不矛盾。
- `current_version` 指针与 KB `pinned_version` 仍归宿主；回填运行在指定 `ontologyVersion` 上，不对历史版本回写。

### A.3 操作矩阵（草案）

| 操作 | 是否调 LLM | 数据动作 | 提交顺序 |
|---|---|---|---|
| `add_attribute(owner, name, type, ...)` | 是 | 对 owner 类型的存量 chunk 回填属性值；未知写 `null`/缺失 | 回填完成后提交 schema |
| `drop_attribute(owner, name)` | 否 | 全量移除该键 | 迁移后提交 schema |
| `rename_attribute(owner, old, new)` | 否（机械迁移） | 键重命名；新键已存在时按 merge 规则合并 | 迁移后提交 |
| `rename_entity/relation`、`drop_entity/relation` | 否 | 迁移实例与关系端点；drop 级联处理关系 pattern | 迁移后提交 |
| `backfill_entity(type, scope)` / `backfill_relation_pattern(...)` | 是 | 仅补齐实例，不改变 schema | 无 schema commit |
| 关系属性 add/drop/rename | 是/否 | 二期 JSON 袋已支持存储；需按关系类型 + keywords 定位存量边 | 同实体规则 |

### A.4 二期必须预留的契约（不实现，但不得堵死）

1. **缺失即 null 的读取语义**：`attributes` 缺失键与显式 null 在所有后端/API 上等价；`AttributeJsonCodec` 不写 null 值，读取侧按"缺失 = null"解释。
2. **最终投影与存量未声明键的取舍（三期启动前必须决策）**：二期当前规则是"最终类型未声明则丢弃"（§3.3，即下方 B）。三期启用演化后，删属性必须由显式 `drop_attribute` 迁移完成，而不是下一次普通写入顺手清掉；因此三期启动前要在两个语义之间明确选择：
   - **A（推荐，三期启用）**：投影只裁剪"本次抽取新带入的键"，存量键原样保留；未声明存量键可继续读取，直到显式迁移。
   - **B（二期默认，与已评审文本一致）**：维持现规则，但把"本体删属性"定义为立即、全量、不可逆的数据迁移，并纳入宿主版本发布检查。
3. **逐 chunk 操作标记的落点**：GraphRAG-SDK 把 `extracted_ops` 放在 chunk 节点（`backfill.py:19`）；lightrag-java 的 chunk record 需要可扩展 metadata 或宿主侧操作表。二期不改 chunk record 形状，三期由宿主表或新增可选字段承载。
4. **逐属性来源/版本可追溯**：三期回填需要知道"实体当前属性来自哪个 ontologyVersion / 哪次 operation"；二期不引入逐属性来源（§3.4、§12.1-7），三期需另立来源字段或旁路表。
5. **六后端一致性**：回填需要按类型扫描实体/关系（Neo4j/AGE/FalkorDB/ArcadeDB/Nebula/PostgreSQL）并处理快照兼容；二期只保证 attributes 字符串读写一致，不提供扫描/迁移原语。

### A.5 成本与风险

- `add_attribute` 成本 ≈ 范围内全部 chunk 的 LLM 抽取；必须 `dry_run` 预估、分批、可续跑。
- "先数据后 schema"意味着崩溃可能留下数据领先 schema 的状态；依靠操作标记与幂等重跑收敛（GraphRAG-SDK 同样接受该窗口，`:90`）。
- 关系属性回填需 endpoint/keywords 匹配，误配会污染边属性；必须复用二期的 typed schema 与 `OntologyAttributeBinder` 口径，不得另造 coerce 规则。
- 版本永久保留 + 高频演化会使版本表增长；只对审批通过的 schema 变更产生版本，操作记录单独存。

### A.6 参考锚点

- GraphRAG-SDK `docs/ontology-evolution.md`：Invariant（`:9`）、原子提交（`:48–50`）、幂等与 `dry_run`（`:121`、`:164–171`）、关系属性限制（`:292`）。
- GraphRAG-SDK `graphrag_sdk/src/graphrag_sdk/ingestion/backfill.py`：`extracted_ops` 幂等标记（`:19`）、`ChunkContext`/`BackfillResult`。
- Semantica `change_management/managers.py:362` 的 `prune_versions` 仅作为"显式删除 API"的反例，**不采纳**。
