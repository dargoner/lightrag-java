# Java LightRAG 本体抽取与本体治理实现计划（Ontology Extraction & Governance）

**Goal:** 参考 Python GraphRAG-SDK 的本体机制，为 lightrag-java 增加**本体（ontology）能力**：本体模型、把本体注入抽取 prompt、本体治理语义（只增不改、冻结、矛盾校验）、本体一致性过滤（conform/prune），以及本体自动发现（discovery）。目标是让"建图质量"由本体受控，而不是任由 LLM 自由产出标签。

**Architecture:** 全部落在 `lightrag-core`，包 `io.github.lightrag.ontology`（新增）。复用已有接缝：抽取配置走 `GraphExtractionOptions`，硬过滤走已存在的 `KgExtractionValidator` 钩子，图级治理复用 `GraphManagementPipeline` 的实体/关系原语。**SDK 提供机制（模型 + 校验 + 注入 + 过滤 + 发现）**，本体持久化与审核流程由宿主（aiplatform）负责。

**存储决策（已定，与宿主一致）：** SDK 只出 `OntologyStore` **接口**（默认 in-memory / No-op 实现），**本体不入图后端、SDK 不做持久化**。source of truth 在宿主关系库（aiplatform `km_ontology`），SDK 侧本体存储保持 storage-agnostic 可选。理由：本体是 schema 资产，与抽取产物（实体/关系）解耦；下沉六个图后端会带来重复实现与跨库一致性成本。

**垂类无关：** 本体在本层是**数据**——SDK 只处理类型 / 属性 / 模式等 schema 原语，不含任何行业 / 客户 / 业务词。垂直差异全部由宿主传入的本体数据表达。

**Tech Stack:** Java 17、Gradle 多模块、JUnit 5、AssertJ、Jackson。不引入新第三方依赖。

**参考实现:** `D:\ai-code\GraphRAG-SDK\graphrag_sdk\src\graphrag_sdk`（Python）——`core/models.py`（Ontology/Entity/Relation/Attribute + 结构校验器 + 保留标签）、`core/tables.py`（声明式映射 Column/Link/TableMapping + 签名）、`storage/ontology_store.py`（register 只增不改 + 冻结 + 矛盾校验）、`storage/deduplicator.py`（声明标签吸收）、`ingestion/pipeline.py`（`_prune` 硬过滤）、`ingestion/extraction_strategies/graph_extraction.py`（prompt 注入 entity_types/relation_patterns 三态/attribute_block）、`ingestion/mapping_proposal.py`（LLM 提议表→本体映射）、`ingestion/backfill.py`（幂等定向回填）、`discovery/`（pipeline/catalog/instructor）、`retrieval/strategies/cypher_generation.py`（本体渲染 + Cypher 白名单校验）。**全量能力覆盖矩阵与逐项处置见 D7（r13 扩展）。**

**配套计划:** aiplatform 侧 `docs/plans/2026-10-13-ontology-asset-and-governance-plan.md`（本体资产、审核、KB 绑定、`onto_*` 工具、存量治理）。

---

## 现状（已核，file:line 为锚点）

- **可注入项有限**：`GraphExtractionOptions` 只支持 `entityTypes` / `relationTypes` / `examples`（`api/GraphExtractionOptions.java:8-19`）；prompt 里 `entityTypes` 拼进系统提示（`indexing/KnowledgeExtractor.java:820-828`），`relationTypes`/`examples` 只进软引导块（`:830-840`，措辞为 "Allowed relationship keywords/types are … skip relationships that do not fit"）。**没有关系方向模式（patterns）、没有属性块（attribute block）**——这是与 GraphRAG-SDK 的最大差距。
- **合并语义已就绪**：per-scope 选项按 `KB options → global → defaults` 合并（`api/LightRag.java:1450-1459`，`GraphExtractionOptions.mergeOver` `:76-90`），`entityTypes` 一旦非 null 不能为空列表（`:39-46`）。
- **硬过滤钩子已存在但未用于本体，且覆盖面有限**：`KgExtractionValidator` 是 per-chunk 校验钩子，返回的 `ExtractionResult` 在主抽取（gleaning 之后）替换实体与关系（**warnings 仍用原列表**，`indexing/KnowledgeExtractor.java:361-370`），builder 上暴露（`api/LightRagBuilder.java:463`）。**但它只覆盖主抽取一步**：refinement 窗口抽取 `KnowledgeExtractor.extractWindow()`（`:375`）**不经过** validator，而 refinement 结果经 `IndexingPipeline`（`:1240`）与 `GraphMaterializationPipeline`（`:941`）最终并入图（`indexing/refinement/DefaultExtractionMergePolicy`）。**"validator 够用"只对主抽取成立，对最终图不成立。**
- **关系是"无类型 + 默认无向"表示**：`types/ExtractedRelation` 只有 `sourceEntityName/targetEntityName/keywords/description/weight`（**无独立 `rel_type` 字段**），关系类型承载在 `keywords`（逗号分隔的多个关键词，prompt `:82-84` 明确"one or more … separated by comma and space"）；prompt 声明"Treat relationships as undirected unless direction is explicitly stated"（`:76`），`RelationCanonicalizer.canonicalize` **按端点字典序排序端点并拒绝 self-loop**（`:23-31`），`KnowledgeExtractor`（`:1005-1016`）与 `GraphAssembler`（`:146-159`）按端点对归并关系。→ **本体的有向 `(src,tgt)` 模式与当前内核的"无向 + 端点对归并"直接冲突，需先定适配方案。**
- **兜底类型是 `Other` 不是 `Unknown`**：prompt `:70` "If none of the provided entity types apply, use \"Other\"."
- **无本体模型、无本体存储、无 discovery**：**生产源码中未找到本体运行时实现**（仓库内仅有本计划等文档产物）。
- **图级治理原语已存在**：`indexing/GraphManagementPipeline` 提供 `createEntity` / `createRelation` / `editEntity` / `updateRelation` / `deleteRelation` / `mergeEntities`，可用于存量图的实体归一/清洗；**但这些原语无本体校验，且部分依赖全量 snapshot/restore，不是按版本迁移/对账的治理引擎**。
- **抽取器有两处构造点**：`IndexingPipeline`（`:660`）与 `GraphMaterializationPipeline`（`:420`）**各自** `new KnowledgeExtractor(...)`（12 个位置参数）；本体新增字段必须**同时**穿这两条链，否则 REBUILD 路径拿不到本体。
- **多图后端**：Neo4j / AGE / FalkorDB / Memgraph / Nebula / ArcadeDB。本体模型必须与存储无关，避免在六个后端各实现一遍。

---

## 设计

### D0 本体来源与定位

- **两类来源，同一表示**：本体有两个来源——**知识**（非结构化文本，LLM 抽取 / discovery）与**数据模型**（结构化 schema，表→类、列→属性、外键→关系）。二者**汇入同一 `Ontology` 模型**：汇合点是**类型化的 `SourceBinding` 字段**——实体类型/关系类型/属性各自携带 `sourceBinding`（见 D1），结构化来源带表签名（如 `employees__title`）与文本来源的属性互不覆盖。**本计划先做知识来源**；数据模型来源为相邻能力，复用同一本体模型。
- **轻量定位**：本体的用途是**约束 LLM 抽取 + 引导检索**，不是形式化推理。不引入 OWL / RDF 公理、不使用 reasoner、不采用全局 URI。关系模式 `(srcLabel, tgtLabel)` 的语义**对齐 RDFS 的 `domain` / `range`**，属性类型对齐 `rdfs:Datatype`，为将来的标准互操作（RDF/OWL 导出、SHACL 校验）预留，但不在本计划引入推理。

### D1 本体模型（storage-agnostic）

新增 `io.github.lightrag.ontology`：

- `OntologyEntityType(label, description, List<OntologyProperty>, SourceBinding sourceBinding?)`
- `OntologyRelationType(label, description, List<OntologyPattern> patterns, List<OntologyProperty>, SourceBinding sourceBinding?)`
- `OntologyPattern(sourceLabel, targetLabel)`——**有向**，且**归属某个关系类型**（模式挂在 rel label 下，不是孤立端点对；第一期只做无通配的 `(src,tgt)`，不做自反/传递）
- `OntologyProperty(name, type, description, SourceBinding sourceBinding?)`，`type ∈ {STRING,INTEGER,FLOAT,BOOLEAN,DATE,LIST}`
- `SourceBinding(tableName, column, refTableName, refColumn, nullable)`——**统一的数据模型来源契约**（源自 r1/r2 M7，跨仓同名）：**实体**填 `tableName`（表身份）；**属性**填 `tableName + column`；**关系**（外键）填 `tableName + column + refTableName + refColumn`。文本来源时为 `null`。**契约先在此冻结，数据模型接入留到后续期**
- `Ontology(entityTypes, relationTypes, version, metadata)`；**identity 用公共 `identityId(label)`**（`trim + 空白折叠 + NFC + 小写`得到比较键；`display_label` 保留原样）；`merge(other)` 供 discovery / 多来源合并——**identity、merge、diff、pattern 匹配、codec 全部复用同一 `identityId`**，不得各自按原始 `label` 比较（`Person`/`person` 必须判为同一身份）
- `Identity.java`（`identityId(String) -> String`）——**跨仓唯一的身份函数**，平台侧 DDL 的 `identity_key` 须与之同算法（源自 r3 S1/Q3）

> **关系适配（关键，源自 r1/r2 评审 M3）**：内核 `ExtractedRelation` 无 `rel_type` 字段、关系默认**无向**且 `RelationCanonicalizer` 按端点排序归并。因此本体的有向模式**不能直接照搬**到图：
> - **关系类型 ↔ `keywords` 的映射**：本体 rel label 作为"高层关键词"参与抽取引导；校验时对 `keywords`（逗号分隔）做**归一后取命中的关系类型集合** `T(kw)`。
> - **明确的校验谓词（r2 要求，r3 消除量词冲突）**：关系 `r`（端点 `a,b`、命中关系类型集 `T(kw)`）**通过**当且仅当 **`T(kw)` 非空 ∧ ∀ 关系类型 `t ∈ T(kw)`：`t.patterns` 为空，或 ∃ `p ∈ t.patterns` 使得 `(p.sourceLabel,p.targetLabel)` 与 `(typeOf(a),typeOf(b))` 完全相等、或与 `(typeOf(b),typeOf(a))` 完全相等**（即**完整端点对匹配**，允许正反两方向）。**量词为全称（∀）**——同一关系的 `keywords` 会被合并（`KnowledgeExtractor` 同端点关系合并关键词），故多个命中类型**必须全部满足**，不允许"仅一端类型命中"或"仅部分命中类型满足"放行；也不允许用**未命中**的另一关系类型的 pattern 代偿。
> - **空 patterns**：该关系类型无模式约束 → 视为"类型声明即允许其出现"，该 `t` 自动满足（不约束端点）。**未知关键词**（`T(kw)=∅`）→ 不通过（drop），并入未命中回调。
> - **混合"已知＋未知"关键词**：`T(kw)` 只含**命中本体**的关系类型；未知词**不参与** `∀` 判定。是否对未知词整体 drop 或仅回调，由宿主策略（validator 开关）决定；默认**保留该关系但回调**未知词（避免误删含已知类型的合法关系），宿主可切"命中未知即 drop"。此开关须在 `OntologyResolutionContext` 中显式携带并纳入 fingerprint。
> - **能保证的范围（r2 要求）**：上述谓词只保证**抽取结果**符合模式；图装配会按端点对**归并**并**投票改变实体类型**（`IndexingPipeline` 存储类型投票），故**不承诺最终图端点类型**严格落在模式内。若需更强保证，须另立（超出本计划）。
> - **overlay 保留归属**：`OntologyExtractionOverlay` 携带**带关系类型归属的 patterns**（`Map<relLabel, List<OntologyPattern>>`），不是裸 `(src,tgt)` 列表，否则校验无法执行上述谓词。

对齐 GraphRAG-SDK `core/models.py` 的 `Entity` / `Relation` / `Attribute`（Python 侧 identity 按 `label`；**Java 侧改用公共 `identityId`** 统一大小写/空白/Unicode，见 S1/Q3）。**注意**：Python 参考模型的 `TableMapping` / `Attribute.structured` 是 **Python 侧概念**，**Java 没有**，也**不得**作为 Java 模型出现；Java 侧等价契约统一为 `sourceBinding`（见上）。

### D2 注入增强：把 patterns 与 attributes 送进 prompt

通过独立 `OntologyExtractionOverlay` 把 `ontologyPatterns`（有向 `(src,tgt)` 列表，见 D1 适配）与 `attributeBlock`（类型→属性说明文本）送进 `KnowledgeExtractor.renderGraphExtractionGuidance()` 追加渲染。**只做软引导**（与 Python 的 prompt 段对齐），硬过滤交给 **L3**。

> **模式强度三态（r13 对齐 graphrag-sdk `relation_patterns` 三态）**：`OntologyConstraintMode ∈ {STRICT, PREFER, OPEN}` 控制 **prompt 渲染强度**——STRICT=**只允许**声明模式；PREFER=优先声明模式、其他可提但不建议（默认）；OPEN=不渲染模式段。**与过滤层正交**：D4 谓词不随 mode 改变；"未知关键词策略"开关（D1）仍独立管 drop 决策。mode 入 `OntologyResolutionContext` 与 fingerprint。

> **兼容性（源自 r1 评审 M1，必须修正）**：追加 record 组件会**改变 canonical constructor 的描述符**，破坏已发布 SDK 的源码/二进制兼容——"字段可空"不能保护既有 `new GraphExtractionOptions(...)` 消费者。当前主工作树有 **3 处内部调用**（`GraphExtractionOptions.mergeOver`、同文件 `Builder.build()`、`LightRag` 全局初始化）需同步，但**消费者调用点不受我们控制**。故：
> - **首选**：**独立 `OntologyExtractionOverlay`**（record/类），不作为 `GraphExtractionOptions` 的新组件，避免构造器签名膨胀（本体配置预期会持续扩展）。
> - **次选**（若坚持并入 Options）：**显式保留旧 10 参数构造器**（委托新构造器）+ 保留 `KnowledgeExtractor` 旧构造器重载；并补"已编译消费者兼容"回归。
> - 无论哪种，都要检查 `equals/hashCode` 与序列化形状变化。
>
> **`mergeOver` 语义（源自 r1 评审 S3 类）**：沿用 `x != null ? x : fallback.x()` 对**标量**够用；但集合与字符串需显式定义——`null` = 继承；**空列表 = 显式清除约束**（非"未声明"）；空字符串 = 非法输入（归一为 `null` 或报错，二选一写明）。**更稳妥**：类型/关系/模式/属性应来自**同一个已解析本体快照**，避免逐字段继承产生"KB 类型 + 全局旧模式"的混合配置；集合一律防御性复制。

### D3 治理语义：受限 register 只增不改 + 冻结 + 矛盾校验

新增 `OntologyGovernance`（**纯函数，无存储/状态依赖**），实现 GraphRAG-SDK `OntologyStore.register` 的兼容校验语义。**SDK 只做"结构兼容性判定"，不承载状态机**——提案冻结、审批人、权限、过期、并发基线、版本分配、发布决策**全部归宿主**（对齐既有 `KgExtractionValidator` / `GraphExtractionOptionsProvider` 的宿主注入哲学）。

- **新 label** → 接受（带声明属性/模式）。
- **已存在 label** → 传入声明必须与已存定义**兼容**：**允许相等（幂等重放）**；只允许**真子集或相等**，不得新增属性/模式。
  > **修正（源自 r1 评审 M6）**：原稿写"真子集"会排除相等定义，导致同一本体重复 register 报错。Python 参考实现实际通过**差集**检查新增属性/模式，**允许相同定义重复注册**（`storage/ontology_store.py`）。本计划对齐：**子集（含相等）通过**。
- **给已存在 label 加属性 / 加模式** → 在**受限 register 语境**下抛 `OntologyModificationNotAllowedException`。
  > **边界（源自 r1 评审 M6）**：若把"只增不改"应用到**所有** apply，宿主将无法完成"编辑、扩展、数据模型变更"驱动的本体演进。故须**区分两套入口**：(1) **受限 register**（抽取期 / 自动归一，只增不改）；(2) **经批准的版本演进**（宿主审核通过后，允许给已有类型加属性/改模式，产出新版本）。**SDK 只提供判定原语（`isSubset` / `diff` / `isContradiction`），两套入口由宿主选择**。
- **改已存在属性类型** → `OntologyContradictionException`（类型矛盾，任何语境都不允许隐式改）。
- **两套入口的合成原语（源自 r3 M13，正文冻结）**：SDK 提供 `synthesizeCandidate(baseOntology, draft, mode)` 纯函数，`mode ∈ {REGISTER_INCREMENTAL, EVOLUTION_REPLACE}`：
  - `REGISTER_INCREMENTAL`：草稿是**增量声明**——先与 `baseOntology` **合并成完整候选** `completeCandidate`（只增不改；未列出项**保留**），再对候选算 **`candidateHash(mode, completeCandidate)`**；
  - `EVOLUTION_REPLACE`：草稿是**完整替换**——删除须**显式**（见平台侧 deletion manifest），不得靠"未列出即删除"隐式删除；候选即草稿本身；
  - **`mode` 与合成后的 `completeCandidate` 一同进入 hash**；`apply` 按同一 `mode` 与同一内容**重验**，防止 freeze 与 apply 语义漂移。
  > **跨仓 hash 契约（r5 M13；字节契约 r6/r8 M2 冻结）**：SDK **只**定义 `candidateHash(mode, completeCandidate)`（SHA-256 over canonical JSON），**不含删除清单**；平台另行定义 `proposal_payload_hash`。
  > **字节契约（唯一、冻结，跨仓同码；r9 M2 消除"单/双字段"歧义）**：外层一律用**带域分隔符 + 长度前缀 + 版本号**的 UTF-8 tuple 编码，**禁止**裸拼接。**两处 hash 的 tuple 定义（唯一口径，字段名固定、顺序固定）**：
  > - `candidateHash = SHA-256( tuple( domain="candidate", v=1, fields=[("mode", mode), ("candidate", canonical(completeCandidate))] ) )`——**两字段**（`mode` + `candidate`）。
  > - `proposal_payload_hash = SHA-256( tuple( domain="proposal_payload", v=1, fields=[("candidate", candidateHash), ("manifest", canonical(DeletionManifest)), ("base", base_version)] ) )`——**三字段**。
  > 字段编码 `name\0<len>:<utf8-bytes>`，`<len>` 为 UTF-8 字节数十进制，分隔符 `\0`；`candidate` 值先做 canonical JSON 规范化。**`definitionHash` 不单独列 tuple**（= 被 apply 候选的 `candidateHash`，同字节别名）。
  > **跨仓 golden fixture（r9 M2 要求）**：SDK 与平台各持**同一组 golden 输入 → 同一 digest**的测试夹具（含 REGISTER 增量、EVOLUTION 完全展开、大小写/全半角/空白变体），CI 断言两仓 digest 相等，否则"同码"不可证。
  > **两者不同名、职责分离**：SDK 管"候选本体"的规范化哈希，平台管"提案 payload"（含删除声明与基线）的哈希。平台 codec 复用 SDK canonical 规则。**禁止**把两个 hash 混用一个名字。
  > **边界（源自 r3 M13）**：若受限入口直接把增量草稿写成新版本，会出现"已有 `Person{name,age}`、草稿只声明 `Person{name}` → 子集校验通过 → `age` 被删"，违反"只增不改"。故**合成必须在 freeze 之前完成**，冻结与审批对象是 `completeCandidate` 而非原始草稿。
- 校验在**任何状态写入之前**完成（对齐 Python 的"不产生部分状态"）。
- **归一先于编码与哈希（r13 收口）**：`identityId` / 属性类型归一 / 空白归一**全部发生在 `canonical(candidate)` 与 `candidateHash` 之前**；L5 renderer 与 checker（及平台 P7）**消费同一版本快照**，不得各自解析归一。

`version` / **`definitionHash`（= `candidateHash`）** 由本类**计算**；**并发安全的版本分配与资产状态由宿主负责**（见 S7）；差分（diff）供宿主审核展示。canonical JSON 规则（键排序、空值、数字、Unicode）须与宿主 codec 一致。

> **三个 hash 的名称与职责（r6 M2 冻结名称 / r8 M2 冻结字节契约，禁止混用）**：
> 1. **`candidateHash(mode, candidate)`**（SDK 计算）：候选本体的规范化哈希（SHA-256 over canonical JSON，**不含删除清单**）。计算 = `SHA-256(tuple(domain="candidate", v=1, fields=[("mode", mode), ("candidate", canonical(candidate))]))`。
> 2. **`definitionHash`**（= 被 apply 候选的 `candidateHash`，**同字节别名**，非独立哈希）——即平台 `km_ontology_version` 版本行的定义哈希列，标识一个**已应用版本**的定义。**它不另起域名、不与 `candidateHash` 相异**；"已应用"是角色语义，不是字节差异。（r8 M2 澄清：此前第 97 行"C/definitionHash 各自独立域名"的措辞取消——独立域名只在 `candidate` 与 `proposal_payload` 之间。）
> 3. **`proposal_payload_hash`**（平台计算）：`SHA-256(tuple(domain="proposal_payload", v=1, fields=[("candidate", candidateHash), ("manifest", canonical(DeletionManifest)), ("base", base_version)]))`，标识**提案 payload**。
> **框架（framing，冻结，禁止裸拼接）**：一律用**带域分隔符 + 长度前缀 + 版本号**的 UTF-8 tuple 编码，**禁止** `A∥B∥C` 裸字节拼接。规范：`domain` 为 ASCII 字面量；`v` 为十进制版本号（当前 `1`）；字段按**固定顺序**列出；每字段编码 `name\0<len>:<bytes>`，`<len>` 为该字段值的 **UTF-8 字节数十进制**、`<bytes>` 为值的 UTF-8 字节（canonical JSON 时先做键排序/空值/数字/Unicode 规范化）；域内字段间以 `\0` 分隔。**SDK 与平台必须共用同一 codec 实现**，否则删除集合可比、hash 仍不可比。
> **删除的 normal form（r6 M2，冻结）**：`DeletionManifest` 采用**完全展开**范式——owner（entity/relation）被删时，其 property/pattern **逐条显式列出**，**不**由 owner 隐式级联；`diff(base, candidate).deletions` 与平台 codec 使用**同一完全展开 + canonical 排序**；REGISTER 模式下 manifest 为**有序空列表**（非 `null`）。这样"同一提案 → 同一字节 → 同一删除集合"跨仓稳定。

### D4 一致性过滤：OntologyConformanceValidator

新增 `OntologyConformanceValidator implements KgExtractionValidator`（**主抽取钩子**）：

- 节点：label 不在本体声明集合 → drop，**但保留兜底类型**（低置信兜底，对齐 Python `_prune`）。
  > **修正（源自 r1 评审 M4）**：当前 prompt 兜底是 **`Other`**（`KnowledgeExtractor.java:70`），**不是 `Unknown`**。须明确归一：本体校验保留的兜底集合 = `{Other}`（可配置），`Unknown` 仅作为可选别名；不定义清楚会误删所有兜底节点。
- 关系：按 D1 的**明确谓词**校验——`T(kw)` 归一后命中的**每个**关系类型，须 `patterns` 为空或**存在完整端点对**匹配其某个 pattern（允许正反方向），即 `T(kw) 非空 ∧ ∀t∈T(kw).patterns 为空 ∨ 完整端点对匹配`。仅 `T(kw)=∅`（纯未知关键词）→ drop + 回调；混合已知＋未知时未知词按宿主策略回调（默认保留关系）。**不使用"任一端点命中"或"跨关系类型借 pattern"放行**（r2/r3 消除歧义与量词冲突）。
- **悬空端点**：drop 关系或节点时，须**成对处理**——若删除节点导致关系端点悬空，同步删除该关系。
  > **修正（源自 r1 评审 M4）**：`GraphAssembler`（`:133-134`、`167-183`）会为悬空关系端点**补建空 type 实体**；只删节点会被重新补回，导致本体约束被绕过。
- **未命中回调**：暴露 `Consumer<UnmatchedLabel>`（`UnmatchedLabel(kind, rawLabel, chunkId, reason)`）。**回调在抽取热路径（多 chunk 并行）同步触发**，SDK **只负责回调，不做聚合/持久化**；宿主须自行按任务/批次聚合后批量落库，并保证幂等（见 S3）。

> **覆盖面（源自 r1 评审 M2，必须修正）**：`KgExtractionValidator` **只覆盖主抽取**（gleaning 后，`KnowledgeExtractor.java:361-367`）。`extractWindow()`（`:375`）**不经过** validator，而 refinement 结果经 `IndexingPipeline`（`:1240`）与 `GraphMaterializationPipeline`（`:941`）**并入最终图**（`DefaultExtractionMergePolicy`）。若不补，**refinement 会把已过滤的非法实体/关系重新加回**，"最终图符合本体"的承诺不成立。
>
> 因此 L3 交付**必须包含**：在 **refinement 合并后**增加**最终校验挂点**（新增/复用一个"post-merge validator"钩子，覆盖 `IndexingPipeline` 与 `GraphMaterializationPipeline` **两条建图链路**：常规 ingest 与 graph REBUILD）。这**会触及内核传参链**（见 M5），不是纯粹的"零内核改动"。

### D5 本体发现（discovery）

新增 `io.github.lightrag.ontology.discovery`：采样 chunks → 逐 chunk 让 LLM 提议类型/关系 → 合并 + 归一（大小写/别名/单复数）→ 产出 `Ontology` **草稿**（不自动生效）。对齐 Python `discovery/pipeline.py`。**发现只产草稿，不 write 本体**——生效必须经 **L1 治理**判定 + 宿主审核。

- **LLM 抽象（源自 r1 评审，问题 8）**：复用现有 `model/ChatModel`（`generate` / `generateResponse` / `ChatRequestOptions.JSON_OBJECT`），**不新增供应商接口**；但 discovery **显式接收注入的 `ChatModel`**，并自带 **token 预算 / 并发 / 取消 / 重试 / 输出解析**策略，不默认绕过宿主的并发与缓存包装。
- **采样输入由宿主提供**：SDK 接收宿主传入的 chunks（或采样输入），**不自行查询平台 KB**。
- **基线感知增量发现（r12 扩展）**：`DiscoveryRequest` 接受可选 `baseOntology`（宿主传入的当前绑定快照）。**有基线**时 discovery 只提议基线**之外**的项，输出**增量草稿**，且**不得**借"未列出"表达删除——应用与否由宿主走 `synthesizeCandidate(base, draft, REGISTER_INCREMENTAL)` 合成完整候选（与 D3 冻结语义一致）；与基线**矛盾**的提议（同 identity 改类型/改模式）**不得静默丢弃**——收集为 `DiscoveryResult.conflicts[]` 随结果返回，由宿主按 `risk_level` 审核处置。**无基线**时输出完整草稿（"空基线 + REGISTER_INCREMENTAL"即全量新增，**不新增第二 mode**）。
- **采样代表性与可追溯（r12 扩展）**：默认策略为**分层采样**——按**文档分层**（每文档配额）、chunk 内容**去重**、**覆盖下限**（草稿至少源自 `minDocuments` 个不同文档），避免单文档主导；`DiscoveryResult.sampleFingerprint` = `(随机种子 + 样本 ID 清单 + 采样策略版本)` 的 canonical hash，**草稿可复现、可追溯**（同 fingerprint + 同 base + 同模型 → 同草稿）。
- **多轮迭代/人机反馈：第一版不做（留口）**：`DiscoveryRequest` 保留 `rounds`（默认 1）与 `hints`（可选人工反馈/优先方向）字段位，但**不实现多轮编排**——避免在评审链路外引入状态机。
- **成本**：discovery 是 LLM 密集操作，按 KB 采样运行，须有预算与限流（宿主侧）。

### D6 检索侧本体约束（r13 扩展 / r14 修订：物理描述符 + 参数化校验 + 可查询字段口径）

GraphRAG-SDK 检索侧的本体消费在 `cypher_generation`：把活本体渲染成 schema 文本（entity labels + properties + rel_type 白名单）喂给 LLM 生成**只读** Cypher（`render_ontology_block`），并对生成结果做白名单校验（`validate_cypher`：只读前缀白名单、必须 RETURN、引用 label 必须 ∈ 本体 ∪ 结构标签），失败带错误反馈重试。

**Java 侧事实（r13 核实，file:line 已核）**：SDK 检索策略为 `NAIVE/LOCAL/GLOBAL/HYBRID/MIX/BYPASS`（`api/QueryMode.java:3-10`，`api/LightRag.java:1484-1500` 注册），**不存在 LLM 生成 Cypher 的路径，检索链路不使用本体**。但**平台侧存在等价链路**：`graph_query` 内置工具接收 **LLM 已生成**的 Cypher 直接执行（`GraphQueryBuiltinTool.java:266-297`，执行 `:282`，`parameters` 已在 `:282-286` 直传服务），其 schema 提示**硬编码物理结构**（平台系统提示 `CanonicalSystemPromptFactory.java:754-763` 分两族、工具参数 description `GraphQueryBuiltinTool.java:117-146`）——LLM 看不到业务类型，生成结果也**无校验**。

**物理后端事实（r13 核实，物理描述符的经验依据）**：Neo4j/Memgraph 系节点 `Entity`、边 `RELATION`、业务类型字段 `type`（`WorkspaceScopedNeo4jGraphStore.java:45`；平台提示 `CanonicalSystemPromptFactory.java:756-758` 用 `n.type`）；FalkorDB/AGE 系节点 `base`、边 `DIRECTED`、业务类型字段 `entity_type`（`FalkorDbGraphStore.java:97-123`；平台提示 `:760-762` 用 `entity_type`）；NebulaGraph 用 `entity` tag（`NebulaGraphStore.java:286`，无 Cypher、平台已屏蔽工具）；ArcadeDB 用 `Entity`/`Relation`（`ArcadeGraphStore.java:17-40`）。**六后端物理 label 不同——不存在"共同物理 label 集"（r13 M1）**。

本计划**不新增**"LLM 生成 Cypher"检索策略（SDK 无此链路、平台已有）；SDK 交付**三个纯函数/描述类型**供平台接线（平台 P7）：

- `PhysicalGraphProfile(Set<String> nodeLabels, Set<String> relationshipTypes, String typePropertyName)`：**后端物理描述符，由平台按配置的图后端传入**（SDK 不内置后端映射表，避免与适配器事实漂移；经验值即上文四族，实施时逐后端以适配器源码冻结）。L5 的一切物理校验以此为**唯一依据**。
- `OntologySchemaRenderer.render(Ontology, RenderOptions) -> String`：输出**纯逻辑 schema 段**——业务实体类型值列表、业务关系关键词列表、有向模式行 `SRC -[REL]-> TGT`（注"物理图关系按端点对归并，方向仅供参考"）。**属性口径（r13 M2）**：一期（决策点 5 已裁定方案 A）属性只声明、不落图 → **不渲染声明但未落图的属性**，只列实际可查询字段（即可用于过滤的类型值）；`RenderOptions.includeDeclaredProperties` 一期恒 `false`，方案 B 落地后才置 `true` 输出属性行。**物理结构约定段（`Entity`/`base`/`DIRECTED` 等及 `type`/`entity_type` 过滤说明）由平台按 `PhysicalGraphProfile` 拼接**（对齐"逻辑/物理并存不互替"，平台 D5）。
- `CypherOntologyChecker.check(cypher, parameters, allowlist, profile) -> CheckResult`：**校验规则（r13 M1/M3 修订）**：
  - **只读**：拒绝 CREATE/MERGE/SET/DELETE/REMOVE/CALL/LOAD CSV/APOC 等写操作与前缀外关键字；
  - **必须含 RETURN**；
  - **物理 label 白名单（r13 M1，按位置分集）**：词法扫描 `:Label` 并按出现位置区分——节点模式位置 ∈ `profile.nodeLabels`，关系位置 ∈ `profile.relationshipTypes`；**不假设任何跨后端共同物理 label**——防 LLM 编造 `:Person` 这类不存在的物理 label；
  - **业务类型值校验（r13 M3：可验证子集 + 参数）**：仅对**类型属性**（`profile.typePropertyName`）上的等值/`IN` 谓词做白名单比对——字面量（`= 'X'`、`IN ['X','Y']`）直接比对；`$param` 引用**按传入 `parameters` 实值解析后**比对（标量或多值列表）；**无法静态判定**的类型条件（函数包裹、子查询、动态拼接、参数未提供）→ 归 `unverified[]`；
  - **`ok` 判定 = 只读 ✓ ∧ RETURN ✓ ∧ 物理 label 全通过 ∧ 无未知类型值 ∧ `unverified` 为空**——**无法核验不算 ok**（漏报不可）；`warnings[]` 承载词法上无法归属的 token（误报可控，不阻断）。
  - `CheckResult(boolean ok, List<String> unknownLabels, List<String> unknownRelTypes, List<String> unknownTypeValues, List<String> unverified, List<String> warnings)`；允许集合（本体 label/关系关键词 identity 集 + 兜底类型 `Other`）由平台组装传入。
  - 平台按 `CheckResult` 决定放行/反馈重试（平台 P7）；**平台必须把白名单绑定到 KB 的 `applied_fingerprint` 对应快照**（图实际生效版本；r13 M4，见平台计划 P7 实现设计）。

> **边界**：renderer/checker 是**纯函数**（无存储、无网络）；SDK 检索策略本体化（local/global 按本体过滤）**不在本计划**。

### D7 graphrag-sdk 能力覆盖矩阵（r13 全量对齐）

对照 `D:\ai-code\GraphRAG-SDK`（Python）全量本体能力面逐项处置（盘点范围：`core/models.py`、`core/tables.py`、`storage/ontology_store.py`、`storage/deduplicator.py`、`discovery/`、`ingestion/mapping*.py`、`ingestion/backfill.py`、`ingestion/extraction_strategies/graph_extraction.py`、`retrieval/strategies/cypher_generation.py`）：

**（a）已覆盖（本计划既有条目）**

| graphrag-sdk 能力 | 本计划对应 |
|---|---|
| `Ontology/Entity/Relation/Attribute` 模型 | D1 |
| `register` 只增不改 + 冻结 + 矛盾校验（property retype） | D3 |
| 子集（含相等）重声明允许（`_check_no_modifications_to_existing`） | D3（r1 M6 已对齐） |
| `merge`（union by label、patterns 去重保序） | D1（`identityId` 口径） |
| `suggest_extensions`/`_diff_ontologies`（只增；缺失≠删除） | D5 基线感知增量 |
| 采样 + 种子可复现 | D5（+`sampleFingerprint`） |
| prompt 注入 `entity_types`/`relation_patterns`/`attribute_block` | D2 |
| `_prune`（label 白名单 + 关系 pattern 匹配 + 方向反转警告 + 双向空 passthrough） | D4（明确谓词；"双向空 passthrough" = 无本体时不启用 validator）。**有意差异（r13）**：Python 方向反转 = 不匹配即删除 + 告警（`ingestion/pipeline.py:585-609`）；Java D1 谓词**允许反向端点对通过**（内核无向 + 端点对归并，见 D1 关系适配）——差异**有意**，非遗漏 |
| `from_file`/`save_to_file` JSON | L1 `OntologyJsonCodec` |

**（b）本轮纳入（SDK 缺口补齐）**

| graphrag-sdk 能力 | 落点 |
|---|---|
| `_normalize_type`：类型归一到 6 类、**默认 STRING**、未知拒绝 | L1 `OntologyPropertyType.normalize()`：**原样语义 = 缺省 STRING、`trim+大写`、仅认六类型常数**（Python 无别名表，`core/models.py:341-349`）；Java **扩展**别名表（`INT→INTEGER` 等，r13 标注为扩展、非原样对齐）；不可归一 → 拒绝 |
| `RESERVED_NODE_LABELS` + `reject_reserved_labels`（Chunk/Document） | L1 `ReservedLabels`：**跨后端语义保留词策略**——保留集 = 内核结构概念的 identityId（`entity/relation/chunk/document`），本体 label 命中 → **拒绝**；**不声称"六后端共同物理 label"**（r13：实际物理集随后端不同）；**后端实际物理标识集归 L5 `PhysicalGraphProfile`**（参数化传入），两处分开定义 |
| `_refuse_duplicate_sources`（同来源 → 两 label） | L1 `OntologyStructureValidator`：**数据库表身份规范化**（`schema.table`、trim/小写/去引号）后按 `SourceBinding` 签名判碰撞 → 拒绝；**不套用 Python 文件名"去扩展名"逻辑**（r13）；作用域 = 同一 `Ontology` 快照 |
| `_refuse_an_unconnected_new_label`（新 label 不连接 → 拒绝；standalone 逃生） | 同上：默认**警告**（`StructureValidationOptions.strict` 可配严格）；`standaloneLabels`（**options 载体的显式标记，模型不新增字段**，r13）命中跳过 |
| `_refuse_colliding_signatures`（签名碰撞） | 同上：拒绝 |
| `_warn_on_undeclared_pattern_labels`（pattern 引用未声明 label） | 同上：**仅警告**（不阻断） |
| relation_patterns **三态 STRICT/PREFER/OPEN** | D2 prompt 渲染强度（STRICT=仅声明模式 / PREFER=优先声明 / OPEN=不渲染模式）；**与过滤层正交**（三态管 prompt 措辞、D4 谓词管 drop 决策、"未知关键词策略"开关仍独立）；mode 入 `OntologyResolutionContext` 与 fingerprint |
| `extract_with_retry`（校验失败反馈重试、`max_retries+1` 总尝试、禁用 provider 级重试） | L4 `ProposalRetry`：JSON 解析失败/本地校验失败 → 以 user 消息回填反馈重试；限次后抛 `OntologyDiscoveryException` |
| `_validate_proposal`（返回 feedback list） | L4 提议校验器（保留 label/结构/矛盾 → 反馈文本） |
| `_per_source_rng`（`seed:源id` 独立 RNG） | L4 采样：**每文档独立 RNG**（seed+docId；单文档增删不扰动其他文档样本） |
| prompt 注入防护（`<<<UNTRUSTED INPUT>>>` 不可信输入分隔） | L4 prompt 模板内置；L2 抽取 prompt **可选**同步 |
| `_ensure_sdk_managed_attributes`（托管 `name: STRING` 注入） | **差异对齐**：Java 实体 `name` 是固有字段（非属性）——不注入托管属性，也不设保留属性名约束（属性暂不落图，见决策点 5） |

**（c）转平台落点（SDK 不实现，平台计划落实）**

| graphrag-sdk 能力 | 平台落点 |
|---|---|
| `mapping_proposal`（profile 列类型 → LLM 提议表→本体映射 → 预检 → `natural_mapping` 回退） | 平台 P6（数据模型来源）补链路 |
| `cypher_generation` 的 renderer/checker 消费 | 平台 **P7（新增）**：`graph_query` 动态 schema 提示 + 生成校验反馈；SDK 供 D6 纯函数 |
| `Catalog` grounding（DBpedia/schema.org 缓存、`discover_grounded`、`relations_among`） | **可选后续**：SDK 留 `OntologyGroundingCatalog` 接口位（**`link`/`lookup`/`relationsAmong`**，默认 Noop；r13 should-fix：`relationsAmong` 对齐 Python `Catalog.relations_among`，`discovery/catalog.py:47-71`，供发现补新旧类型间关系）；目录数据源（含缓存/网络）由宿主提供。**GLiNER 本地 NER 不纳入**（Java 无本地 NER；锚点用 LLM 或宿主提供） |

**（d）明确不纳入（含理由）**

| graphrag-sdk 能力 | 不纳入理由 |
|---|---|
| 本体持久化于独立图（FalkorDB `<graph>__ontology`） | 既有存储决策：宿主关系库 = source of truth；SDK 只出 `OntologyStore` 接口 |
| mutation APIs（drop/rename/add 级联、`delete_all` + 重注册） | Java 采用**不可变版本 + `EVOLUTION_REPLACE`**；rename 表达为删除+新增，无原地级联 mutation |
| 属性值 coerce（`_parse_step2_response` 按声明类型强转） | **依赖属性落图**；Java 记录无属性承载（见决策点 5），落图后再配套 |
| `EntityDeduplicator` 的 `declared_labels` 吸收 | Java 归并语义不同（`RelationCanonicalizer` 端点对归并 + 存储类型投票），无该去重机制；**同名异型处置口径（r13）**：一期无"结构化↔文本类型合并"入口（结构化表格入图见下行排除）；未来引入时须在合成/注册时**报告同名异型矛盾进审核**，不做 Python 吸收算法 |
| `backfill`（`extracted_ops` 幂等定向回填） | Java 以整库/按本体重抽为主（平台 D6 维护窗）；定向回填依赖属性与部分重抽，**后续评估** |
| `one-key-per-label`（Link target ≥2 key 列） | Python 防的是**多张表把同一目标 label 用不同 key 列标识**（`storage/ontology_store.py:400-434`）；Java `SourceBinding` **单条契约不能证明跨表安全**（r13 修正原理由）。一期**随结构化表格入图一并排除**（见下行）；引入时须补跨表 target-key 一致性校验 |
| **结构化表格入图**（`TableMapping` 驱动的行 / 引用节点 / 外键边 / 重同步写图；`core/tables.py:425-456`、`ingestion/structured_pipeline.py:446-520/619-625`） | **明确排除（一期，r13 M5）**：平台一期建图只从知识来源；表格入图属 data-qa 建图能力（依赖属性落图方案 B 与专用 pipeline），**另立项**。`SourceBinding` 元数据 **≠** 已覆盖该能力 |

**（e）已裁定/后续评估**：属性落图（**决策点 5 已裁定方案 A：只声明引导、不落图**）；定向回填（后续评估）；grounding 目录实现（L4 仅保留三原语）；结构化表格入图（独立立项）。

---

## 分期

| 期 | 内容 | 关键落点 | 依赖 | 验收 |
|---|---|---|---|---|
| **L1** | 本体模型（含 `sourceBinding` 契约 + **类型归一** + **保留标签/结构校验**）+ `OntologyGovernance`（子集含相等 / 矛盾校验）+ codec | 新包 `io.github.lightrag.ontology` | 无 | 单测：相等重放通过、增属性拒绝、改类型报错、无部分状态；**类型归一（默认 STRING + 扩展别名表）**；**语义保留词拒绝（`entity/relation/chunk/document`）**；**结构校验（表身份规范化后同来源/连接性/签名碰撞/未声明 pattern 引用；standalone 由 options 标记）**；codec round-trip + schemaVersion 拒绝；**归一先于 hash（golden 输入含大小写/空白变体）** |
| **L2** | 注入增强：`OntologyExtractionOverlay`（或 Options 兼容扩展）+ prompt 渲染（含**模式强度三态**） | `api/*`、`indexing/KnowledgeExtractor.java` | L1 | 单测：渲染含模式/属性；**STRICT/PREFER/OPEN 三态措辞**；既有 entityTypes 行为不变；**旧构造器兼容回归** |
| **L3** | `OntologyConformanceValidator` + **refinement 后最终校验挂点** | `io.github.lightrag.ontology` + `indexing/IndexingPipeline`、`indexing/GraphMaterializationPipeline` | L1 | 单测：本体外 drop、**`Other` 兜底保留**、悬空关系同删、未命中回调；集成：**refinement 泄漏用例**、两条建图链路 |
| **L4** | discovery → 草稿（含**反馈重试协议**、**每文档独立 RNG**、prompt 注入防护） | `io.github.lightrag.ontology.discovery` | L1 | 单测：采样（可复现种子 + `sampleFingerprint` 稳定 + 每文档 RNG 隔离）→提议校验→**失败反馈重试**→归一→草稿；**有基线时不重报基线已有项、矛盾项进 `conflicts[]`**；不写本体 |
| **L5** | 检索侧约束纯函数：`OntologySchemaRenderer` + `CypherOntologyChecker` + `PhysicalGraphProfile` | `io.github.lightrag.ontology` | L1 | 单测：渲染**仅可查询字段**（方案 A 不渲染声明未落图属性）；checker：只读、必须 RETURN、**按 profile 分节点/边物理 label**、业务类型值（**字面量 + `$param` 实值**）、**`unverified` 不计为 ok**；平台接线见平台 P7 |

**跨后端影响：无。** 本体模型与校验均不接触图存储；conform 在抽取期内存完成；L5 renderer/checker 为纯函数。**内核影响：非零**——L3 需在 refinement 合并后新增校验挂点并穿 `IndexingPipeline`/`GraphMaterializationPipeline` 两条链（见实现清单）。

> **发版门槛：L1 也需发版**（源自 r1 评审，问题 10/平台 S7）。平台 P1 消费 L1 的模型/治理/codec，故 L1 不能只"先行"而不发版；**L1–L5 每次交付均需走发版**（流程见既有 `lightrag-sdk-release-process`）；L1 与 L3 可分别开发，但**以"完整约束链路可用"为共同发布验收门槛**。

---

## 实现清单（文件级）

### L1 本体模型 + 治理 + 编解码 + 存储接口
新包 `io.github.lightrag.ontology`（`lightrag-core`）：

| 文件 | 内容 |
|---|---|
| `OntologyPropertyType.java` | enum `STRING/INTEGER/FLOAT/BOOLEAN/DATE/LIST`（对齐 `rdfs:Datatype`）；**`normalize(String)` 类型归一**：**原样语义 = 缺省 STRING、`trim+大写`、仅认六类型常数**（Python 无别名表）；Java **扩展**别名表（`INT→INTEGER` 等，r13 标注为扩展）；不可归一 → 拒绝 |
| `OntologyProperty.java` | record(name, type, description?, `SourceBinding sourceBinding?`) |
| `SourceBinding.java` | record(tableName, column, refTableName, refColumn, nullable)——**统一数据模型来源契约**：实体填 tableName、属性填 tableName+column、关系填全四项；文本来源 `null`（源自 r2 M7，跨仓同名） |
| `OntologyPattern.java` | record(sourceLabel, targetLabel)——**有向**，归属某 `OntologyRelationType` |
| `OntologyEntityType.java` | record(label, description?, List<OntologyProperty>, **`SourceBinding sourceBinding?`**)——实体绑定表身份 |
| `OntologyRelationType.java` | record(label, description?, List<OntologyPattern>, List<OntologyProperty>, **`SourceBinding sourceBinding?`**)——关系绑定外键四元组 |
| `Ontology.java` | record(entityTypes, relationTypes, version, metadata)；**所有索引、merge/diff、pattern 匹配、codec canonicalization 一律用 `Identity.identityId`（不用原始 `label`）**；`merge(other)` 供 discovery / 多来源合并 |
| `Identity.java` | `identityId(String)->String`：**跨仓唯一身份函数**（trim+空白折叠+NFC+小写）；`display_label` 保留原样（源自 r3 S1/Q3） |
| `ReservedLabels.java` | **跨后端语义保留词策略**：保留集 = 内核结构概念的 identityId（`entity/relation/chunk/document`）；本体实体/关系 label 命中 → **拒绝**（r13 对齐 `RESERVED_NODE_LABELS` + `reject_reserved_labels`；**不声称"六后端共同物理 label"**——后端实际物理标识集归 L5 `PhysicalGraphProfile`） |
| `OntologyStructureValidator.java` | 结构校验（r13 对齐 `core/models.py` 校验器，Java 语义重写）：**表身份规范化**（`schema.table`、trim/小写/去引号，不套用文件名"去扩展名"）后**同来源 → 两 label** 拒绝；**签名碰撞** → 拒绝；**新 label 不连接**任何已声明项 → 默认**警告**（`StructureValidationOptions.strict` 可配严格；`standaloneLabels` 跳过，**载体为 options、模型不新增字段**）；**pattern 引用未声明 label** → 警告；作用域 = 同一 `Ontology` 快照 |
| `OntologySchemaRenderer.java` | **L5**：本体 → schema 提示文本（**仅可查询字段**：实体类型值 + 关系关键词 + 有向模式行；`includeDeclaredProperties` 一期恒 false，方案 B 后置 true）；**纯逻辑段，不输出物理 label / `typePropertyName` 过滤注记**；物理结构段由平台按 `PhysicalGraphProfile` 拼接（D6 / r13 M2 / r15 N2） |
| `PhysicalGraphProfile.java` | **L5**：后端物理描述符 record(`Set<String> nodeLabels, Set<String> relationshipTypes, String typePropertyName`)；**平台按配置的图后端传入**（Neo4j/Memgraph=`{Entity}/{RELATION}/type`、FalkorDB/AGE=`{base}/{DIRECTED}/entity_type` 为经验值；实施时逐后端以适配器源码冻结） |
| `CypherOntologyChecker.java` | **L5**：`check(cypher, parameters, allowlist, profile) -> CheckResult{ok, unknownLabels[], unknownRelTypes[], unknownTypeValues[], unverified[], warnings[]}`；只读 + 必须 RETURN + **按位置分集**物理 label 白名单 + 业务类型值（**字面量与 `$param` 实值**）；**`unverified` 不计为 ok**（r13 M1/M3） |
| `OntologyGovernance.java` | 纯函数：**子集（含相等）判定**、增属性/加模式拒绝（受限语境）、类型矛盾检测；**`synthesizeCandidate(base, draft, mode)`** 合成完整候选（`REGISTER_INCREMENTAL` 合并保留未列出项 / `EVOLUTION_REPLACE` 完整替换）；算 `version`/**`candidateHash(mode, candidate)`**（SHA-256 over **canonical JSON**，**不含删除清单**）；`diff(before, after)`（**返回 typed deletions**，供平台 `deletion_manifest` **精确相等校验**）；**不含状态机**（冻结/审批/版本分配归宿主） |
| `DeletionManifest.java` | **typed 删除条目**（r5 M13）：`entity(identityId)` / `relation(identityId)` / `property(ownerKind, ownerIdentityId, propertyIdentityId)` / `pattern(ownerRelIdentityId, sourceIdentityId, targetIdentityId)`；**canonical 排序**；平台须校验 `manifest == diff(base, candidate).deletions`（规范化后**精确相等**），并定义级联删除规则 |
| `OntologyModificationNotAllowedException.java` / `OntologyContradictionException.java` | 治理异常（校验在**任何状态写入前**完成） |
| `OntologyJsonCodec.java` | Jackson 读写**类型化文档契约**（顶层 `{schemaVersion, kind, ...}`）；**schemaVersion 兼容矩阵 + 不支持版本显式失败 + `kind`/`source` 一致性校验**；canonical JSON 规则（键排序/空值/数字/Unicode）与宿主共用；内容哈希**排除版本号、哈希自身与易变审计字段** |
| `OntologyStore.java` | 接口 `load(key)` / `save`；默认 `InMemoryOntologyStore` + `NoopOntologyStore`；**不入图后端**（存储决策已定） |

> **跨仓契约（源自 r1 评审，问题 5；r3 收敛）**：SDK 定义**本体领域文档 + codec**，平台定义**资产/提案/绑定等数据库信封**；须补**跨仓库 golden fixtures** 防版本漂移——fixture **至少覆盖三种绑定**（实体`{tableName}` / 属性`{tableName,column}` / 关系`{tableName,column,refTableName,refColumn}`），**不能只验证 `SourceBinding.java` 自身序列化**。所有正文、示例与评审问题统一为**同一 JSON 字段路径**（实体/关系/属性各自的 `sourceBinding`，不再出现 `mapping.tableName`、`mapping.column` 等平行命名）。当前 SDK **尚无 `OntologyJsonCodec` 实现**，字段契约未闭合——L1 必须先冻结该契约。

### L2 注入增强

**推荐方案：独立 `OntologyExtractionOverlay`（不改 `GraphExtractionOptions` 组件数）**（源自 r1 评审 M1）。

| 文件 | 改动 |
|---|---|
| `io.github.lightrag.ontology.OntologyExtractionOverlay.java` | **新增** record(`Map<relLabel,List<OntologyPattern>>` patterns **带关系类型归属**、attributeBlock、**`OntologyConstraintMode constraintMode`**、...)；`KnowledgeExtractor` 增加**重载构造器**接收 overlay。宿主可用 `OntologyResolutionContext` 一次携带并贯穿（见契约节 M8） |
| `api/GraphExtractionOptions.java` | **若采用次选方案**：record **末尾**加两个可空组件 + **六处同步**（constructor、`defaults()`、`Builder`、`build()`、`mergeOver`、`resolved*`），并**显式保留旧 10 参数构造器**（委托新构造器）。⚠️ 内部 3 处调用点（`mergeOver:78` / `Builder.build():212` / `LightRag:190`）需更新；**消费者调用点无法保护**，故首选 overlay |
| `indexing/KnowledgeExtractor.java` | 构造器（`:285` 区）加字段并穿链；`renderGraphExtractionGuidance()`（`:830`）追加 patterns / attributes 渲染段（**软引导**）。**新增重载构造器**保留旧签名 |
| `indexing/IndexingPipeline.java` | `new KnowledgeExtractor(...)`（`:660`）**同步**穿入本体字段——**原计划遗漏** |
| `indexing/GraphMaterializationPipeline.java` | `new KnowledgeExtractor(...)`（`:420`）**同步**穿入本体字段——**原计划遗漏** |
| `api/LightRagBuilder.java` / `api/LightRag.java` | 把本体配置从 options/overlay 穿到 **两条 pipeline**（非仅 `:651-663`） |
| 兼容 | 旧 `entityTypes/relationTypes/examples` 语义**不变**；旧构造器/重载保留 |

### L3 一致性过滤

| 文件 | 内容 |
|---|---|
| `io.github.lightrag.ontology.OntologyConformanceValidator.java` | `implements KgExtractionValidator`（**主抽取**）；节点 label 未声明 → drop（**保留 `Other`**，非 `Unknown`）；关系按 D1 **明确谓词**校验（`T(kw)非空 ∧ ∀t∈T(kw)：patterns 为空 ∨ 完整端点对匹配`，正反容忍；**无"端点并集"**）；**悬空关系同删**；Builder 带 `Ontology` + `Consumer<UnmatchedLabel>`（**只回调，不聚合/不落库**） |
| `io.github.lightrag.ontology.UnmatchedLabel.java` | record(kind, rawLabel, chunkId, reason) |
| **refinement 后最终校验挂点** | 在 `IndexingPipeline.refineExtractions`（`:1232`；merge 产出 `:1247-1250`）与 `GraphMaterializationPipeline.refineExtractions`（`:931`；merge `:945`）**refinement 合并后**，对每个 `GraphAssembler.ChunkExtraction`（record 含 `ExtractionResult extraction`，`GraphAssembler.java:225`）调用**同一 `KgExtractionValidator`**（**零新接口**；过滤幂等，主抽取已滤结果再滤为 no-op），以校验后的 `ExtractionResult` 重建 record——**原计划遗漏**，不补则 refinement 泄漏非法结果 |
| **非零内核改动** | 平台经 `builder.kgExtractionValidator(...)`（`:463`）接线，但**需改两条 pipeline 的传参链**以覆盖 refinement 与 REBUILD 路径 |

### L4 发现

| 文件 | 内容 |
|---|---|
| `io.github.lightrag.ontology.discovery.OntologyDiscovery.java` | 编排：采样 → 逐 chunk 提议 → 合并 → 归一 → **增量草稿**（有基线时只提议基线外项、矛盾项进 `conflicts[]`） |
| `.../DiscoverySampleStrategy.java` | 采样策略（默认**分层采样**：按文档配额 + 内容去重 + `minDocuments` 覆盖下限 + **每文档独立 RNG（seed+docId，r13 对齐 `_per_source_rng`）**；可换热点策略） |
| `.../ProposalRetry.java` | **结构化输出重试协议（r13 对齐 `extract_with_retry`）**：JSON 解析失败/本地校验失败 → 以 user 消息回填反馈**重试**；`maxRetries` 限次（默认 3，总尝试 = maxRetries+1）；**禁用 provider 级重试**（避免双层重试放大）；耗尽抛 `OntologyDiscoveryException` |
| `.../OntologyGroundingCatalog.java` | **接口留位（可选，默认 Noop）**：**`link` / `lookup` / `relationsAmong`**（r13 should-fix：`relationsAmong` 对齐 Python `Catalog.relations_among`，供发现补新旧类型间关系）；目录数据源（含缓存/网络策略）由宿主提供，SDK 不内置网络实现 |
| `.../prompts/*` | 提议 / 归一 prompt 模板；**内置不可信输入分隔**（`<<<UNTRUSTED INPUT>>>` 标记，r13 对齐） |
| `.../DiscoveryRequest.java` / `DiscoveryResult.java` | 入参（含可选 `baseOntology`、`groundingCatalog?`、`rounds`/`hints` 预留位）/ 出参（= `Ontology` 增量草稿 + `conflicts[]` + `sampleFingerprint`，**不写**） |

### 发版
- **L1 也需发版**：平台 P1 消费 L1 的模型/治理/codec，不能只"先行"不发版（源码内自用 ≠ 已发布）。**L1/L2/L3/L4 每次交付均需走发版**（流程见既有 `lightrag-sdk-release-process`）；L1 与 L3 可分别开发，但**以"完整约束链路可用"为共同发布验收门槛**。

**改造文件总览（修正自 r1 评审 M5 / r5 M5 / r6 M4 / r8 M4；r13 补 L5）：** 改 **6 个既有文件**（`GraphExtractionOptions`／`LightRagBuilder`／`LightRag`／`KnowledgeExtractor`／**`IndexingPipeline`**／**`GraphMaterializationPipeline`**）+ 新增 1 包（模型/治理/编解码/存储接口/validator/discovery/**renderer/checker**）。**六个存储适配器零改动**——但**抽取内核非零改动**（L3 需在 refinement 合并后新增校验挂点并穿两条 pipeline 传参链）。**D6 隔离经 r6 M4 收敛为平台"KB 级维护窗 + 先删后建（无并发代）"、r8 M4 复核方向通过后，SDK 共享归并/查询层不再需要代际过滤改造**（见下"代际隔离对 SDK 的影响"）。"仅改 4 文件、零内核改动"的原声称不成立；"六存储适配器零改动"**有条件成立——限定于六个图/向量适配器本体**（`GraphStore.java:246/281`、`VectorStore.java:25` 保持原样），**快照 `ontologyFingerprint` 存储仍须改**（见下）。(r8)

> **代际隔离对 SDK 的影响（r5 M5 / r6 M4，已按平台 D6 收敛）**：r5 原拟的**版本化"文档贡献" + 按 `(documentId, generation)` 过滤**方案（要求实体/关系 record 携带贡献明细、向量带 metadata）**经 r6 M4 裁定不可表示**——`GraphStore` 实体/关系无 properties/贡献明细（`GraphStore.java:246/281`）、`VectorRecord` 只有 id/vector（`VectorStore.java:25`）。**平台 D6 已改选「KB 级维护窗 + 先删后建（无并发代）」**：KB 只持一份图、旧新代不并存、重建期读取被拒。→ **SDK 侧无需任何"代际过滤/共享归并层改造"**：图里始终只有一份（当前代的）产物，无旧代混入问题。**SDK 仅需**：(1) L3 校验挂点（与代际无关，本就要做）；(2) 快照 `ontologyFingerprint`（判断能否复用旧快照，见平台 D6 迁移条）。**六个存储适配器零改动成立**；"共享归并/查询层非零改动"这一 r5 结论**随之撤销**（不再需要按 generation 过滤）。

---

## 实现设计（r14 具体化；类签名与算法，避免过度设计）

以下为 L1–L5 新增面的**落地级设计**（既有已冻结设计 D1–D5 与契约节不复述）。总原则：**只补钩子、不造机制**——post-merge 校验复用 `KgExtractionValidator` 类型；renderer/checker/profile 为纯函数与纯数据。

### L1
- `Identity.identityId(s)`：`trim` → 内部空白折叠为单空格 → Unicode **NFC** → `toLowerCase(Locale.ROOT)`。全链唯一入口（merge/diff/codec/投影一致）。
- `OntologyPropertyType.normalize(raw)`：`null/blank → STRING`；`trim + toUpperCase(Locale.ROOT)` 命中六常量即返回；**扩展别名表**（`INT→INTEGER`、`DOUBLE/NUMBER/DECIMAL→FLOAT`、`BOOL→BOOLEAN`、`ARRAY→LIST`）为 Java 附加能力；未命中 → `IllegalArgumentException`（拒绝）。**归一先于编码**（见 D3）。
- `ReservedLabels`：常量 `Set<String>` = `{entity, relation, chunk, document}` 的 identityId 形式（**跨后端语义保留词**）；`check(String displayLabel)` 命中 → 抛 `OntologyStructureException`。
- `OntologyStructureValidator.validate(Ontology, StructureValidationOptions) -> StructureValidationResult{errors[], warnings[]}`；内嵌 `StructureValidationOptions(boolean strictUnconnected=false, Set<String> standaloneLabels=Set.of())`（**standalone 载体 = options，模型不新增字段**）。步骤：(1) 保留词 → error；(2) 表身份规范化（`tableName/refTableName`：trim、去引号、小写；`schema.table` 保留 schema 段，无 schema 即裸名——**不套用文件名逻辑**）；(3) 同签名不同 label → error；(4) 候选中未被任何 pattern 引用的 label → warning（strict 时 error；`standaloneLabels` 跳过）；(5) pattern 引用未声明 label → warning。作用域 = 单个 `Ontology` 快照。

### L2
- `OntologyExtractionOverlay(Map<String, List<OntologyPattern>> patternsByRelation /*key = rel identityId*/, String attributeBlock, OntologyConstraintMode constraintMode)`；`fromOntology(Ontology, mode)` 便利构造。
- `KnowledgeExtractor`：新构造器 = 现有 12 参 + `OntologyExtractionOverlay overlay`（可 null）；**全部旧构造器保留**（委托 null）。`renderGraphExtractionGuidance()`（`:830`）追加本体段：STRICT="Only the following relationship patterns are allowed; others will be discarded"；PREFER="Prefer the following …"（默认）；OPEN=不渲染模式段；`attributeBlock` 非空时追加属性引导段。**注意**：原 `:830` 处当 `relationTypes` 与 `examples` 均为空时提前返回，须改为"若 `relationTypes`/`examples`/`overlay` 三者皆空才提前返回"，否则仅有 overlay 时会跳过本体段。
- 穿链：`OntologyResolutionContext` 携带 overlay（`schemaVersion=1` 编码不变）；`newIndexingPipeline`（`LightRag.java:1246-1285`）与 `newGraphMaterializationPipeline`（`:1330-1368`）各取 context → overlay 传入 ctor（两条 pipeline 各一处）。

### L3
- `OntologyConformanceValidator implements KgExtractionValidator`，ctor `(Ontology ontology, OntologyFilterPolicy policy)`；`OntologyFilterPolicy(Set<String> fallbackLabels={Other}, UnknownKeywordPolicy unknownKeywordPolicy=KEEP_WITH_CALLBACK, Consumer<UnmatchedLabel> onUnmatched)`。`validate` 算法：(1) 实体 type identity ∉ 声明∪fallback → drop+回调；(2) 关系按 D1 全称谓词——`T(kw)=∅`（纯未知关键词）**或** `∃ t∈T(kw)` 谓词假（端点对不匹配）→ **无条件 drop+回调**；仅当 `T(kw)≠∅` 且所有命中类型谓词为真、但关键词中**混有未知词**时，才按 `UnknownKeywordPolicy` 决定保留+回调（KEEP）或 drop（DROP）；(3) drop 实体后其悬空关系同删+回调；(4) 原 warnings 透传。
- **post-merge 最终校验（具体接法）**：复用 `KgExtractionValidator` 类型本身（**零新接口**）——在 `IndexingPipeline.refineExtractions`（`:1232`；merge 产出 `:1247-1250`）与 `GraphMaterializationPipeline.refineExtractions`（`:931`；merge `:945`）中，对每个 `GraphAssembler.ChunkExtraction`（`(chunkId, ExtractionResult extraction, llmCacheIds, filePath)`，`GraphAssembler.java:225`）：`validator.validate(ce.chunkId(), textMap.get(ce.chunkId()), ce.extraction())` → 以返回的 `ExtractionResult` 重建 `ChunkExtraction`；validator 未注册则跳过。**过滤幂等**（主抽取已滤结果再滤为 no-op）；两处各约 5 行，覆盖常规 ingest 与 REBUILD 两条链。

### L4
- `ProposalRetry.propose(ChatModel, String systemPrompt, String userPayload, Function<String, List<String>> localValidator) -> OntologyDraft`：尝试 ≤ `maxRetries+1`（默认 4 次）；每次 `generate`（`ChatRequestOptions.JSON_OBJECT`）→ JSON 解析 → 本地校验（保留词/结构/与基线矛盾）→ 失败把**反馈文本**作为追加 user 消息重试；耗尽抛 `OntologyDiscoveryException`；**不叠加 provider 级重试**（宿主 ChatModel 配置域）。
- 每文档 RNG：`new Random(seed * 0x9E3779B97F4A7C15L ^ identityId(docId).hashCode())`——同 seed+docId 跨进程稳定；单文档增删不影响他文档样本。
- `OntologyGroundingCatalog`：`lookup(term)` / `link(label)` / **`relationsAmong(Collection<String> labels)`**；默认 `NoopGroundingCatalog` 全空。
- prompt 防护：user payload 包裹 `<<<UNTRUSTED INPUT>>> … <<<END UNTRUSTED INPUT>>>`。

### L5
- `PhysicalGraphProfile(Set<String> nodeLabels, Set<String> relationshipTypes, String typePropertyName)`；**平台按配置的图后端传入**；经验值见 D6（实施时逐后端以适配器源码冻结）。
- `OntologySchemaRenderer.render(Ontology, RenderOptions{includeDeclaredProperties=false})`：输出**纯逻辑 schema 段**（业务实体类型值列表 + 业务关系关键词列表 + 有向模式行）；**不渲染声明未落图属性**；**不输出物理 label/typePropertyName 注记**——这些由平台按 `PhysicalGraphProfile` 拼接。`RenderOptions.includeDeclaredProperties` 一期恒 `false`。
- `CypherOntologyChecker.check(String cypher, Map<String,Object> parameters, CypherOntologyAllowlist allowlist, PhysicalGraphProfile profile) -> CheckResult`：算法按 D6 五步（只读 → RETURN → 物理 label 按位置分集 → 类型属性谓词字面量/参数实值比对 → `unverified`/`warnings` 归集；`ok` 要求 `unverified` 为空）。词法实现：提取 `:Label`（跳过字符串字面量内）、`= '…'`、`IN [ … ]`、`$name` 并按属性前缀归属；**不做完整 Cypher 解析**（对齐"词法级、误报可控"）。

---

## 与宿主（aiplatform）的契约

SDK 只提供机制，宿主负责：
1. 把审核通过的 `Ontology` 转成 `GraphExtractionOptions`（entityTypes/relationTypes/patterns/attributes）——**注入**。
2. 实现并注册 `OntologyConformanceValidator`，把未命中回调落到审核队列——**过滤**。
3. **检索侧（r13/r14）**：平台 `graph_query` 在同一维护态闸门内消费 `OntologySchemaRenderer` 输出（拼接物理结构段）作为动态 schema 提示；生成的 Cypher 连同**解析后的 `parameters`** 与 `PhysicalGraphProfile` 交 `CypherOntologyChecker` 校验（未知物理 label / 未知类型值 / `unverified` → 拒绝并反馈重试）；**白名单与提示必须绑定 KB `applied_fingerprint` 对应快照**（图实际生效版本，r13 M4）；**无绑定 / 快照不可得 / checker 异常** → 回退现硬编码行为并显式降级（**不宣称白名单保证**）。

**版本语义（host 侧约定）：** `OntologyGovernance` 计算 `version`/`definitionHash`，每次 apply 产出**不可变**版本；宿主**钉版本**绑定 KB（KB → 具体 `version`，非"最新"），建图按钉定版本注入与过滤，未命中回调带版本信息供按版本对账。SDK 不参与"KB 绑哪个版本"的决策，只按传入的某一个版本工作。

> **多本体合并（源自 r1/r2 评审 M8）**：一个 KB 可绑多个本体（SKELETON + DOMAIN），单个 `(ontology_id, pinned_version)` 表达不了"实际生效的完整 schema"。宿主须为**每个抽取任务捕获一个不可变的合并本体快照**，prompt / validator / 回调 / `onto_*` **共用同一快照**；缓存键含**完整绑定版本集合 + priority + 合并算法版本**的 fingerprint，而非单键。
>
> **统一解析上下文（r2 要求，r3 补全任务契约）**：仅"补 fingerprint"不足以保证"一次捕获、处处一致"——options 在 pipeline 创建时解析、validator 来自 runtime 固定 config、异步提交元数据与执行时又**各自**调 provider（`LightRag.java:330/1252/1282/1462`）。故须定义**一个不可变 `OntologyResolutionContext`**，一次携带：生效 options/overlay、validator 所用 `Ontology`、完整绑定列表、**实际生效 schema 的 fingerprint**、以及上文的"未知关键词策略"开关。r3 补全的**任务级接线契约**：
>
> - **捕获时点**：宿主（aiplatform）**在平台任务提交时捕获一次**；其后 SDK 侧的抽取任务提交**不得再捕获**。
> - **载体（冻结，不再二选一）**：**选定 `TaskSubmitOptions` 作为唯一载体**——新增一个**可选组件** `ontologyResolutionContext`，并以**保留旧 `TaskSubmitOptions(List<...>)` 构造签名**（委托新构造器）保证二进制兼容；**不用**"独立参数"。凡需要本体约束的提交入口**全部**接受 `TaskSubmitOptions`——包括当前**缺失该参数**的 graph materialization 提交（`LightRag.java:750` 现无 task options）、document/chunk materialization、以及 resume/retry 入口，须补重载而非各走一套。
> - **`pipelineMetadata` 消费 + 编码**：metadata 当前是 `Map<String,String>`（`LightRag.java:1461`）。**冻结**：`pipelineMetadata` 中持久化 context 用**固定 key `lightrag.ontology.resolutionContext`**，值为 context 的 **JSON 字符串**（codec canonical 规则同 `OntologyJsonCodec`）；恢复执行只解出原 context，不重跑 provider（替换现有 `LightRag.java:1252/1338/1450` 的多次解析）。
> - **可恢复的序列化契约（冻结字面值）**：context `schemaVersion = 1`；codec = JSON（键排序/空值/数字/Unicode canonical）；**内容引用解析规则**——`context.bindingRefs` 为**内联内容**（`definitionSnapshot`）或**版本引用**（`ontologyId + version + definitionHash`）二者之一，**引用不可用时（版本被删/哈希不匹配）一律拒绝任务并显式报错**（不静默降级为无约束）；**有 key 但 JSON/版本非法一律拒绝**。
> - **legacy 行为（唯一）**：任务 metadata **无该 key** → 视为"无本体约束"（保持旧行为，不报错）；**不做**"强制新任务"分支。
> - **精确重载清单（r5/r6/r8/r9：列 Java 签名，与源码逐一核对，非"类别"）**：新增 `TaskSubmitOptions` 组件的**当前唯一构造器**为 `TaskSubmitOptions(List<...> listeners)`；保留该签名（委托新构造器）。**须覆盖每一个公共写入入口**（r8/r9 M1：原清单方法名有误、且同步入口与无 options 包装均漏列，按 `LightRag.java` 实际签名修订）：
>   - **异步 ingest（已带 options 的三个）**：`submitIngest(..., TaskSubmitOptions)` 六个重载 `LightRag.java:264/268/272/276/289/293`，其中 `:268`（`List<Document>`）、`:276`（`DocumentIngestRequest`）、`:293`（`PreChunkedIngestRequest`）**已带 `TaskSubmitOptions`**；
>   - **异步 graph materialization**：`submitDocumentGraphMaterialization(...)`（`LightRag.java:750`，现**无** options）与 `submitChunkGraphMaterialization(...)`（`LightRag.java:780`，现**无** options）——**各补 `TaskSubmitOptions` 重载**（r6 曾把 `:780` 误标为 document ingest resume；实为 chunk graph materialization）；
>   - **同步 document materialize**：`materializeDocumentGraph(...)`（`LightRag.java:692` 三参 + `:707` 四参 `CancellationCheckpoint`）——**禁止**直接新增四参 `TaskSubmitOptions` 重载（会使 `:697` 的 `materializeDocumentGraph(ws, doc, mode, null)` 因 `null` 同时匹配 `CancellationCheckpoint` 与 `TaskSubmitOptions` 产生**重载歧义**）；正确做法是**五参**（`(String, String, GraphMaterializationMode, CancellationCheckpoint, TaskSubmitOptions)`）或**另命名方法**；三参/四参旧签名保留不动；
>   - **同步 chunk resume/repair**：`resumeChunkGraph(...)`（`LightRag.java:732`）、`repairChunkGraph(...)`（`LightRag.java:741`）——**各补 `TaskSubmitOptions` 重载**；
>   - **同步 document resume**：`resumeDocumentIngest(...)`（`LightRag.java:544`，r8 补列）——**补 `TaskSubmitOptions` 重载**（或纳入下方"约束模式拒绝"）。
> - **同步 ingest 决策（r6 二选一定死；r8/r9/r10 补全范围，按 `LightRag.java` 实际签名逐行核对）**：同步 ingest **共 3 个 `ingest` 重载 + 1 个 `ingestSources` + 1 个 `ingestChunks`**（r10 更正：此前"4 个 `ingest` 重载"与源码不符，`ingestChunks` 行号亦误记为 `:255`）：`ingest(String, List<Document>)`（`:230`）、**`ingest(String, DocumentIngestRequest)`（`:234`，r9 指出原清单漏列）**、`ingest(String, PreChunkedIngestRequest)`（`:251`）、`ingestSources(String, List<RawDocumentSource>, DocumentIngestOptions)`（`:243`）、**`ingestChunks(String, List<PreChunkedChunk>)`（`:260`，经 `:251` 代理）**。**均不新增** context 重载；**当 KB 处于"本体约束模式"时显式拒绝**（抛 `IllegalStateException`，提示改用异步/带 context 入口）。**二选一固定为"拒绝"，不采用"静默无约束"**。
> - **无 options 的异步/导入/快照包装入口（r9 补列；r10 补 `submitIngestChunks`/`submitImport` 精确行号）**：以下入口现**隐式传默认 options**或直接落库，须显式处置——`submitIngest(String, List<Document>)`（`:264`）、`submitIngest(String, DocumentIngestRequest)`（`:272`）、`submitIngest(String, PreChunkedIngestRequest)`（`:289`）、**`submitIngestChunks(String, List<PreChunkedChunk>)`（`:309`，无 options；带 options 重载 `:313`）**、**`submitIngestSources(String, List<RawDocumentSource>, DocumentIngestOptions)`（`:344`，内部委派 `TaskSubmitOptions.defaults()`）**、**`submitRebuild(String)`（`:371`，委派 `defaults()`）**、`submitDeleteByDocumentId(String, String)`（`:389`，委派 `defaults()`）、**`submitImport(String, Path|byte[]|InputStream)`（`:913/933/947`，委派 `defaults()`；带 options 重载 `:924/938/952`）**，以及同步 **`restoreSnapshot(String, Path)`（`:829`）**、**`importData(...)`（`:864/876/885/890/899/904`）**。
> - **覆盖完备性总则（r8/r9 M1 收口；r10 更正"只有两个 pipeline"的错判）**：**不以"逐一枚举方法"为最终保证**，而以**实际写入汇合点**为准。**但 r10 核实：`IndexingPipeline`/`GraphMaterializationPipeline` 并非唯一汇合点**——手工图写走 `GraphManagementPipeline`（`createEntity:446`/`createRelation:451`/`editEntity:456`/`updateRelation:461`/`deleteRelation:469`/`mergeEntities:476`），删除与重建走 `DeletionPipeline`（`rebuildAllDocuments:384`、`deleteByDocumentId:412`、`deleteByEntity:516`、`deleteByRelation:527`、同步 `deleteByDocumentId:541`），**快照恢复与导入直接调用 `storageProvider.restore`**（`restoreSnapshot:829` 与 `importContent:984`，**不经任何 pipeline**）。因此**汇合点清单 = {`IndexingPipeline` 提交点, `GraphMaterializationPipeline` 提交点, `GraphManagementPipeline` 各写方法, `DeletionPipeline` 各写方法, `storageProvider.restore`（恢复/导入）}**（r11 补充：五类**并非严格互斥**——如 `DeletionPipeline.rebuildAllDocuments` 内部会调用 `IndexingPipeline.ingest`（`DeletionPipeline.java:290`），多个 pipeline 内部也会调用 `restore`；这不构成遗漏，**实施时按"实际经过的写阶段"执行相应规则**，不假定一条路径只命中一个汇合点）。**每个汇合点入口**必须执行三者之一：**(a) 携带已捕获 context**（`TaskSubmitOptions` 重载）；**(b) 约束模式下显式拒绝**；**(c) 非抽取写入则标记 **KB 级 dirty**（置 `consistency_state` 失效）并**禁止一致性报告**。**逐汇合点处置固定**：抽取类（两个 pipeline）→ (a) 或 (b)；手工图写 `GraphManagementPipeline`、`DeletionPipeline` 删除、`restore`/`import` → **(c) 标记 KB dirty**（恢复/导入为整体替换，须置 `applied_fingerprint` 失效）。实现阶段按汇合点逐项落地，并加**链接/编译回归测试**守护，避免"枚举遗漏 → 绕开快照"。
> - **兼容回归**：新增"已编译旧消费者链接测试"（旧 `TaskSubmitOptions(List)` 与新重载均可编译运行）。
> - **fingerprint 覆盖实际生效 schema**：不仅含绑定版本集合/priority/合并算法版本，**还须含 `extractConfig` 与本体的合并结果**（合并后的类型/关系/模式/属性全量），否则无法识别"配置合并结果变化"导致的 prompt 漂移。
> - **禁止各自重查**：元数据与两条 pipeline 一律消费**同一 context 对象**。`extractConfig.enabled` 与"绑定本体"的优先级及合并冲突规则须在此**同步定死**（见平台侧 D4）。

契约细节与平台侧落地见配套计划（aiplatform `docs/plans/2026-10-13-ontology-asset-and-governance-plan.md`）。

---

## 验证

- 单测：L1 governance（**相等重放通过**/增属性拒绝/改类型报错/无部分状态 + **类型归一（默认 STRING + 扩展别名表）/语义保留词拒绝/结构校验（表身份规范化）/归一先于 hash**）+ codec（round-trip、schemaVersion 拒绝）；L2 prompt 渲染（**含 STRICT/PREFER/OPEN 三态措辞**）+ **旧构造器兼容回归**；L3 validator（本体外 drop、**`Other` 保留**、**悬空关系同删**、未命中回调）；L4 discovery 草稿（可复现 + **反馈重试** + **每文档 RNG 隔离**）；**L5 renderer/checker（仅可查询字段渲染、只读校验、必须 RETURN、按 profile 分集物理 label、类型值字面量+`$param` 实值、`unverified` 不计为 ok）**。
- 契约：旧 `entityTypes/relationTypes/examples` 行为不变；`mergeOver` 语义保持；**旧构造器/重载可编译**。
- 集成：以宿主传入的本体跑一次 ingest，断言图内实体 label ⊆ 声明集合 ∪ `{Other}`；**并专测 refinement 泄漏**（refinement 不得重新引入被过滤的实体/关系）。
- 双链路：常规 ingest 与 graph REBUILD 两条路径均覆盖过滤。

## 风险

1. **prompt 注入的效果需实测**：软引导可能仍漏标（Python 亦如此，故需 prune）。
2. **本体存储不在 SDK**：若宿主希望 SDK 持久化本体（照搬 `__ontology` 独立图），需在六个图后端之上抽象——**本计划刻意不做**，避免多后端重复实现。
3. **discovery 是 LLM 密集操作**，按 KB 采样运行，需预算与限流。
4. **关系方向失真（M3）**：内核关系无向 + 端点对归并，本体有向模式在图中不保证保留；第一期按"容忍式校验"交付，若需方向强制须改内核（超出本计划）。
5. **refinement 泄漏（M2）**：不补 post-merge 校验，则本体约束只对主抽取生效，最终图仍可能含非法结果。
6. **兼容性（M1）**：并入 `GraphExtractionOptions` 组件会破坏已发布消费者；首选独立 overlay。
7. **属性"只声明不承载"（r13）**：方案 A 下 prompt 引导的属性与"图里可查属性"之间有空档——宿主若期待属性入图，须先另立项方案 B（已裁定阶段 1 不落地）。
8. **graph_query 提示动态化（r13）**：平台 graph_query 现为硬编码 schema 提示；接入本体动态渲染后，提示质量与稳定性须实测（渲染/解析失败须回退硬编码，见平台 P7 验收）。

## 决策点（已裁定）

> r14 已按"避免过度设计、具体实现优先"原则逐项裁定；本节为最终选择，交付范围不再二义。

1. **L2 attributes（属性块）**：**一期渲染**（`attributeBlock` 作为抽取软引导），但属性值**不落图**（见决策点 5）。历史备选：先只做 patterns，后补 attributes。
2. **本体 SDK 内持久化**：**否**。SDK 只出 `OntologyStore` 接口（默认 in-memory / No-op），本体 source of truth 在宿主关系库。
3. **L2 载体**：**独立 `OntologyExtractionOverlay`**（不改 `GraphExtractionOptions` 构造器签名，旧构造器零改动）。历史备选：并入 `GraphExtractionOptions` 并保留旧构造器。
4. **关系约束强度**：**已定（r2）**——采用"完整端点对匹配 + 正反两方向容忍 + 空 patterns 命中即通过"的明确谓词（见 D1）；保证范围**仅限抽取结果**，不承诺最终图端点类型。若需更强保证须另立。
5. **属性是否落图**：**方案 A（一期交付）**。属性只声明 + prompt 引导，**不落图**——保持“六存储适配器零改动”与内核边界不变；属性价值限于引导 LLM 把结构化信息写进 description。方案 B（属性入图）须独立立项，重评六后端改动。

**阶段 1 排除项（r14 裁定）**：回填（backfill）、结构化表格接入、`declared_labels` 同名异型吸收算法、change representation/diff UI 均不纳入阶段 1；保留 diff 纯函数供审核展示，结构化建图作为 data-qa 后续能力单独立项。

---

## r1 评审修订记录（2026-10-14）

Codex r1 评审（`review-plan-r1.md`）裁定 10 条核心声称：**4 条成立、3 条部分成立、3 条不成立**。本版已并入的修正：

- **M1** record 追加字段非兼容 → D2 改"首选独立 overlay / 次选保留旧构造器"；实现清单同步。
- **M2** refinement 绕过 validator → D4/分期/实现清单加"refinement 后最终校验挂点"。
- **M3** 关系无 `rel_type`/无向/端点排序 → D1 加"关系适配"；D4 改容忍式校验。
- **M4** 兜底 `Other` 非 `Unknown` + 悬空端点被补回 → D4/验证修正。
- **M5** 传参链漏 2 文件 → 现状/实现清单/改造总览修正为 6 文件。
- **M6** 真子集不允许相等重放 + 限制演进 → D3 改"子集含相等 + 受限 register vs 经批准演进"。
- **M7** mapping 契约缺失 → D0/D1 以 `SourceBinding` 显式定义。
- **M8** 单键表达不了多本体合并 → 契约节加"每任务合并快照 + fingerprint"。
- **L1 也需发版** → 分期/发版修正。
- **N1/N3/N4** 分期编号引用、表述失真 → 已改（"经 L1 治理"、"生产源码未找到运行时实现"）。

### r2 复评修订（2026-10-14）

r2 裁定：**M1/M2/M4/M5/M6/N1/N3/N4 已修复**；M3/M7/M8 部分修复，本轮进一步收敛：

- **M3** → D1 给出**明确校验谓词**（完整端点对匹配 + 正反容忍；空 patterns 命中即通过；未知关键词 drop）；明确**保证范围仅限抽取结果**（装配投票会改类型）；overlay 改为**带关系类型归属**的 patterns。D4/决策点同步。
- **M7** → `SourceBinding(tableName, column, refTableName, refColumn, nullable)` 统一契约，明确**实体/属性/关系**三级绑定位置；L1 单列 `SourceBinding.java`。
- **M8** → 契约节新增**不可变 `OntologyResolutionContext`**（一次捕获、贯穿 options/overlay/validator/元数据/两条 pipeline；重试沿用或新任务；enabled 与绑定优先级同处定死）。

### r3 复评修订（2026-10-14）

r3 裁定：M1/M2/M4/M5/M6 与 SDK 侧 M3/M7/M8 部分修复，本轮收敛：

- **M3（量词冲突）** → D1/D4/L3 统一为**全称谓词**：`T(kw) 非空 ∧ ∀t∈T(kw)：patterns 为空 ∨ 完整端点对匹配（正反容忍）`；删除 L3"端点并集"表述；补**混合"已知＋未知"关键词**语义（未知词默认回调不 drop，开关入 context 与 fingerprint）。
- **M7（交付清单漏字段）** → L1 的 `OntologyEntityType`/`OntologyRelationType` record **补 `SourceBinding sourceBinding?`**；跨仓契约明确 golden fixture **覆盖三种绑定**、统一 JSON 字段路径（删 `mapping.*` 平行命名）。
- **M8（任务契约）** → 契约节补**任务级接线**：宿主在**平台任务提交时**捕获一次、SDK 侧不得重捕；冻结**任务级 context 入参**（`TaskSubmitOptions`/两条 pipeline/validator 显式消费）；context **可持久化/可复原**、恢复不重查绑定；fingerprint **含 extractConfig 合并结果**。
- **新增 M13** → 见 D3：受限 register 的增量草稿须先与基线**合并成完整候选版本**再 freeze/hash/approve/apply（对齐平台侧）。

### r4 复评修订（2026-10-14）

r4 裁定：**M3 已修复**；M7/M8/M13 部分修复，本轮收敛：

- **M7-remain** → D0 删 `Ontology.tables/TableMapping/Attribute.structured`（Python 概念），改 `sourceBinding` 汇合；全文禁止把 `TableMapping` 描述为 Java 模型。
- **M8-remain** → 契约节**冻结载体**：唯一选 `TaskSubmitOptions`（新增可选组件 + 保留旧 `List` 构造签名）；graph materialization/document/chunk/resume 各入口**补重载**接受该参数；冻结 context `schemaVersion`+codec+metadata key+legacy 行为。
- **M13-remain** → D3 正文加 `synthesizeCandidate(base, draft, mode)` 纯函数（`REGISTER_INCREMENTAL` 合并保留未列出项 / `EVOLUTION_REPLACE` 完整替换）；`mode` 与候选入 **`candidateHash`（= `definitionHash`）**；L1 清单补 `Identity.java`。
- **S1/Q3-remain** → `Ontology` identity 改公共 `identityId`（trim+空白折叠+NFC+小写）；merge/diff/pattern 匹配/codec 全复用；L1 清单补 `Identity.java` 跨仓唯一身份函数。

### r5 复评修订（2026-10-14）

r5 裁定：**M7 已修复**；M8/S1-Q3/M13 部分修复，本轮收敛：

- **M8（任务契约冻结，本轮给字面值）** → metadata key 固定 `lightrag.ontology.resolutionContext`（值为 JSON 串）；context `schemaVersion=1`；引用规则内联/版本引用二选一、**引用不可用一律拒绝**；legacy 无 key = 唯一"无约束"行为；列**五处精确重载**（同步 document/clunk resume/异步 submit/graph materialization/document ingest resume）+ **旧消费者链接测试**。
- **S1/Q3** → L1 `Ontology.java` 行删"identity 按 label"，改"仅用 `Identity.identityId`"（消除正文/清单冲突）。
- **M13（跨仓 hash 拆分）** → SDK 只定义 **`candidateHash(mode, candidate)`**；新增 **typed `DeletionManifest`**；平台用 **`proposal_payload_hash`**（含 manifest+base_version）——同名不一致消除。
- **M5（六后端零改动声称修正）** → 明确"**六存储适配器零改动，但 SDK 共享归并/查询层非零改动**"（版本化文档贡献 + active-generation 过滤，与平台 D6 对齐）。

### r6 复评修订（2026-10-14）

r6 裁定：**S1/Q3 已修复**；M8/跨仓 M13 部分修复、M5 修复引入新问题，本轮收敛：

- **M8/M1（写入入口覆盖）** → 列 **Java 签名**（非"类别"）：补 **`submitChunkGraphMaterialization(..., TaskSubmitOptions)`**（`LightRag.java:780`，原误标并漏列）；补 graph document materialization（`:750`）与同步 document/chunk（`:692`/`:732`）重载；**同步普通 ingest（`:230`/`:251`）在"本体约束模式"下显式拒绝调用**（二选一定死为"拒绝"，不留绕开快照的入口）。
- **M2（hash 三义 + normal form）** → 冻结**三个 hash 名**：SDK `candidateHash` / `definitionHash`（已应用版本）/ 平台 `proposal_payload_hash`；外层 hash 用**域分隔 + 长度前缀 + 版本号** tuple；删除用**完全展开** normal form（owner 被删时 property/pattern 逐条列出）；RESISTER 下 manifest = 有序空列表。
- **M5/M4（六后端声称 + D6 物理模型）** → D6 物理模型本拟交 Codex r7 拍板，但**本会话 Codex 额度耗尽（`out of credits`）无法出评审**；由本会话临时拍定平台 **「KB 级维护窗 + 先删后建（无并发代）」**（见平台 D6）。→ **SDK 共享归并/查询层不再需要代际过滤改造**，"六存储适配器零改动"**成立**；原"共享归并/查询层非零改动"结论**撤销**（见"代际隔离对 SDK 的影响"）。**待 Codex 额度恢复后 r8 复核**。

---

## 历史评审问题（r1–r16 已逐轮闭合）

以下问题为 r1–r12 评审轮次提出的历史问题，**已在 r13–r16 修订与变更日志中逐项闭合**。保留此处仅作追溯，不再提交新评审。

1. **L2 扩展 `GraphExtractionOptions` 的兼容方式**：计划是在 record **末尾追加**两个可空字段 + 六处同步（constructor/defaults/Builder/build/mergeOver/resolved*）。这是否是正确的兼容姿势？既有 `new GraphExtractionOptions(...)` 调用点（请全仓 grep 计数）会否因参数位置变化而破坏？有无更安全的替代（如独立 `OntologyExtractionOverlay` 参数）？
2. **`mergeOver` 语义**：`GraphExtractionOptions.mergeOver`（`:76-90`）按 `x != null ? x : fallback.x()` 逐字段合并。新增 `ontologyPatterns`（List）与 `attributeBlock`（String）沿用该语义是否够用？空列表 vs null 的语义是否需要显式区分？
3. **`KgExtractionValidator` 能否覆盖节点/关系级过滤**：计划让 `OntologyConformanceValidator` 实现该接口做 prune。请核对 `KnowledgeExtractor.java:361-367` 的调用点与展开逻辑，确认 validator 返回的 `ExtractionResult` 是否**真的整体替换**结果、能否安全地 drop 单个节点/关系、是否在 gleaning（多轮）之后仍生效、以及 `Unknown` 保留是否会与下游图装配冲突。
4. **治理语义是否该完全交宿主**：计划把 `OntologyGovernance`（只增不改/冻结/矛盾校验）放 SDK。是否存在"SDK 不应承载治理语义"的理由（如它本质是审核工作流、与宿主 `km_ontology_proposal` 更贴近）？若保留在 SDK，纯函数无存储依赖的边界是否成立？
5. **`OntologyJsonCodec` 的落点**：计划让 SDK 提供**类型化文档契约**的 Jackson codec，宿主与其共用同一份编解码。SDK 与宿主是两个仓库，共享 codec 是否现实（版本漂移风险）？契约该由谁定义、如何版本化？
6. **包名与模块落点**：`io.github.lightrag.ontology`（`lightrag-core`）是否合适？是否应独立 `lightrag-ontology` 模块？是否触碰 `lightrag-core` 的对外 API 边界约定？
7. **`OntologyPattern` 有向 `(src,tgt)` 的表达力**：对齐 RDFS `domain/range` 的 `(srcLabel, tgtLabel)` 是否足够？是否需要通配（如 `(*, Person)`）或自反/传递等最小模式扩展？不引入推理的前提下，边界画在哪？
8. **discovery 的 LLM 抽象**：计划让 discovery"逐 chunk 让 LLM 提议"→合并→归一。请核对 lightrag-java 现有 LLM 调用抽象（`KnowledgeExtractor` 用的 provider / 接口），确认 discovery 复用该抽象是否可行、是否需要新增注入点；采样策略 `DiscoverySampleStrategy` 是否必要。
9. **"零内核改动 + 六后端零改动" 声称是否成立**：这是本计划的核心卖点。请核对 L1–L4 是否真的不需要动抽取内核与任一图后端；`GraphManagementPipeline` 是否真能支撑存量图治理（D6 平台侧）而无需 SDK 改动。
10. **分期依赖是否自洽**：L1→L2/L3/L4 的依赖是否合理？"L2/L3/L4 需发版后平台才能消费"是否被正确声明？有无被拆错的粒度（如 L3 validator 是否本可与 L1 合并）。

---

### r6 复评要点（2026-10-14）

**r6 复评**请：① 逐条核验本计划"r5 复评修订"里对 **M8（任务契约字面值）/S1-Q3（L1 清单）/跨仓 M13（hash 拆分+manifest）/M5（六后端声称）** 的**处置声称**——打开更新后的计划与源码，逐条裁定「已修复 / 部分修复 / 未修复 / 修复引入新问题」；② **只报新增或未处置的 must-fix**（已修复的不重报）；③ 重点复核：M8 五处精确重载 + metadata key 字面值 + `schemaVersion=1` + legacy 唯一行为 + 旧消费者链接测试是否闭合；`candidateHash` 与平台 `proposal_payload_hash` 拆分是否消除同名不一致；typed `DeletionManifest` 是否足以机器判定；"六存储适配器零改动但共享归并/查询层非零改动"是否成立。最后给**总体裁定（可合并 / 需再修 M 几 / 不可）**；若无 must-fix 明确给"可合并"。

### r7 复评要点（2026-10-14）

> **本轮未执行**：Codex workspace 额度耗尽（`out of credits`），r7 未产出评审。平台 D6 M4 由本会话临时拍板（平台计划 D6 物理模型）。**下列要点顺延为 r8**。

**r7 复评**请：① 逐条核验本计划"r6 复评修订"里对 **M8/M1（精确重载 + 同步 ingest 拒绝）/M2（三个 hash 名 + normal form）** 与 **M5** 的**处置声称**（打开更新后的计划与源码，逐条裁定「已修复 / 部分修复 / 未修复 / 修复引入新问题」）；② **只报新增或未处置的 must-fix**（已修复的不重报）；③ **核心任务：为平台 D6 的 M4 物理隔离模型拍板**——在用户约束"**不堆高兼容复杂度，该重建就重建**"下，请从平台候选方案中**择一**并给出跨 SDK 的可执行契约：`(documentId, generation)` 贡献如何物理承载、全读取路径（**含 raw Cypher**）如何按 active 指针快照、GC 如何按 reader watermark/安全窗口、删旧贡献时如何保留仍被其他 active 文档引用的实体/关系；并据此给出"六存储适配器零改动"的**最终取舍**（r6 已指出该目标在贡献模型闭合前不成立）。④ 复核 `submitChunkGraphMaterialization` 补列与"同步 ingest 拒绝"是否真正消除绕开快照的合法入口。最后给**总体裁定（可合并 / 需再修 M 几 / 不可）**；若无 must-fix 明确给"可合并"。

### r8 复评修订（2026-10-14）

r8 裁定：**平台 D4 fail-fast / D1.2 UK 可进入实现**；SDK M1 **部分修复且重载方案引入新问题**、跨仓 M2 **部分修复（hash 字节契约冲突）**、SDK M5（D6）**部分修复**。本轮收敛后 SDK 侧留 2 项（M1/M2）：

- **M1（写入入口覆盖不全 + 重载歧义）** → 按 `LightRag.java` 实际签名**逐一订正**：异步 ingest 入口名 **`submitDocument` → `submitIngest`**（六重载 `:264/268/272/276/289/293`，其中 `:268/:276/:293` 已带 options）；补 **同步 `ingestSources`（`:243`）** 与 **`resumeDocumentIngest`（`:544`）** 到覆盖清单（r8 指出原清单漏列）；`materializeDocumentGraph` 的 options 重载改为**五参（含 `CancellationCheckpoint` + `TaskSubmitOptions`）或另命名方法**，**禁止**四参 `TaskSubmitOptions`（与 `:697` 的 `null` 调用歧义）；新增**覆盖完备性总则**（无 options 入口要么补 context 要么约束模式拒绝）。
- **M2（hash 字节契约冲突）** → 删除正文残留的**裸拼接公式**（原 `hash(candidateHash ∥ manifest ∥ base_version)`）；**冻结唯一 canonical codec**（domain/字段顺序/长度单位/版本号）；**澄清 `definitionHash` = 被 apply 候选的 `candidateHash` 同字节别名**（取消"C/definitionHash 各自独立域名"的矛盾措辞，独立域名只在 `candidate` 与 `proposal_payload` 之间）。
- **M5（D6 贡献模型）** → r8 **复核认定「KB 级维护窗 + 先删后建（无并发代）」方向可接受**（无需重选物理模型），但**平台侧须补维护窗执行与完成契约**（见平台 D6）——SDK 侧无需代际过滤改造的结论保持。

### r9 复评修订（2026-10-14）

r9 裁定：SDK M1 / 跨仓 M2 **部分修复**、平台 D6 M4 **修复引入新问题**；仍判需再修 3 项。本轮闭合：

- **M1（写入口覆盖）** → ① 同步 ingest 订正为 **4 个 `ingest` 重载 + `ingestSources` + `ingestChunks`**（补 **`ingest(String, DocumentIngestRequest)`（`:234`）**、`ingestChunks`）；② 补列**无 options 的异步/导入/快照包装入口**（`submitIngestSources:344`、`submitRebuild:371`、`submitIngestChunks`、`submitDeleteByDocumentId`、`submitImport`、`restoreSnapshot:825`、`importData:829/864/913`）；③ 总则由"枚举方法"改为**以实际汇合点（`IndexingPipeline`/`GraphMaterializationPipeline` 提交点）为准**，每个汇合点执行"携带 context / 显式拒绝 / 非抽取写标记 KB dirty"**三选一**。
- **M2（hash 字节唯一）** → 删除"单字段/双字段"歧义：**`candidateHash = tuple(domain="candidate", v=1, fields=[("mode",mode),("candidate",canonical)])` 两字段**；`proposal_payload_hash` 三字段（candidate/manifest/base）；`definitionHash` 不单列 tuple（同字节别名）。新增**跨仓 golden fixture**（同输入 → 同 digest）。
- **D6 依赖（平台侧）** → 平台新增准入关闭 + 在途登记、读闸门含 `PARTIAL_FAILED`、逐项回执 + CAS 退出、文档+chunk 修订清单、专用按 KB 清空编排；SDK 侧无需代际过滤改造的结论保持（快照 `ontologyFingerprint` 存储仍须改）。

### r10 复评修订（2026-10-14）

r10 裁定：跨仓 M2 **本轮已修复**；SDK M1 **部分修复（写入口覆盖仍有缺口）**，平台 D6 M4 **部分修复**；仍判需再修 2 项 must-fix。本轮 SDK 侧闭合：

- **M1（写入口覆盖，r10 指出两处与源码不符 + 汇合点错判）** → ① **同步 ingest 订正为 3 个 `ingest` 重载**（`:230`/`:234`/`:251`；此前"4 个"有误）+ `ingestSources`（`:243`）+ `ingestChunks`（**`:260`**，非 `:255`）；② 补 `submitIngestChunks`（`:309`/`:313`）与 `submitImport`（`:913/933/947` + 选项 `:924/938/952`）精确行号；③ **推翻"所有写入汇合到两个 pipeline"**——核实手工图写走 `GraphManagementPipeline`（`:446`–`:476`）、删除/重建走 `DeletionPipeline`（`:384`/`:412`/`:516`/`:527`/`:541`）、恢复/导入**直接 `storageProvider.restore`**（`:829`/`:984`，不经 pipeline）；**汇合点清单扩展为 5 类**，并**逐类固定处置**（抽取类 → 携带 context 或拒绝；手工图写/删除/恢复/导入 → 标记 KB dirty）。

### r11 复评修订（2026-10-14）

r11 裁定：SDK M1 **已修复（可关闭）**；跨仓 M2 沿用已修复。SDK 侧仅作一处澄清（r11 指出五类汇合点**并非严格互斥**：`DeletionPipeline.rebuildAllDocuments` 内部会调 `IndexingPipeline.ingest`（`DeletionPipeline.java:290`），多个 pipeline 内部也会调 `restore`）：

- **M1（非互斥澄清）** → 汇合点清单处补注：五类**非严格互斥**、**不构成遗漏**；实施时按"**实际经过的写阶段**"执行相应规则，不假定一条路径只命中一个汇合点。

### graphrag-sdk 能力对齐扩展（2026-10-15；送 r13 评审）

按"参考 graphrag-sdk 把 lightrag-java 本体功能都规划出来"对 graphrag-sdk 全量本体能力面（`core/models.py`/`core/tables.py`/`storage/`/`discovery/`/`ingestion/`/`retrieval/`，约 12.6k 行盘点）逐项对齐：

- **新增 D6（检索侧本体约束）**：`OntologySchemaRenderer` + `CypherOntologyChecker` **纯函数**；平台 graph_query 接线落 **P7**。**事实核实（file:line）**：SDK 检索无 LLM-Cypher 链路、检索链路不用本体（`QueryMode.java:3-10`）；平台 graph_query 为"LLM 已生成 Cypher + 硬编码 schema 提示"（`GraphQueryBuiltinTool.java:266-297`、`CanonicalSystemPromptFactory.java:744-775`）。
- **新增 D7（覆盖矩阵）**：已覆盖 9 项 / 本轮纳入 12 项 / 转平台 3 项 / 不纳入 6 项（附理由）；grounding 目录、定向回填列后续评估。
- **L1 补**：`OntologyPropertyType.normalize()`（别名表 + 默认 STRING）；`ReservedLabels`（内核结构标签拒绝）；`OntologyStructureValidator`（同来源/连接性/签名碰撞/未声明 pattern 引用）。
- **L2 补**：`OntologyConstraintMode{STRICT,PREFER,OPEN}`（prompt 侧三态，与过滤层正交）。
- **L4 补**：`ProposalRetry` 反馈重试协议；每文档独立 RNG；prompt 不可信输入分隔；`OntologyGroundingCatalog` 接口留位（默认 Noop）。
- **L5 新增**：renderer/checker 纯函数；发版门槛更新为 **L1–L5**。
- **决策点 5（属性落图）**：Java 内核无属性承载（`ExtractedEntity.java:6`、`GraphStore.java:246-254`）——方案 A（只声明+引导，推荐/一期）vs 方案 B（全链落图，独立立项）。

### r13 评审修订（2026-10-15）

r13 裁定：**r12 两项已关闭**；判"需再修 5 项 must-fix"（M1–M5）。本轮闭合 + 具体实现设计（新增 `## 实现设计（r14 具体化）`）：

- **M1（checker 物理白名单与后端不匹配）** → 新增 `PhysicalGraphProfile`（节点标签集/边类型集/类型字段名，平台参数化传入）；checker **按位置分集**校验节点/边 label；`ReservedLabels` 拆分为 **L1 语义保留词**（`entity/relation/chunk/document`）与 **L5 后端物理集**（profile），不再声称"六后端共同物理 label"。
- **M2（renderer 展示未落图属性）** → renderer 一期**只渲染实际可查询字段**（方案 A：声明未落图属性不出现在 schema 段；`includeDeclaredProperties` 一期恒 false，方案 B 落地后置 true）。
- **M3（启发式 warnings 不足以支撑 P7 验收）** → checker 接收 `parameters` 并按实值解析 `$param`；可验证子集 = 类型属性上的等值/IN 谓词（字面量 + 参数）；不可静态判定 → `unverified`，**`ok` 要求 `unverified` 为空**；平台 P7 验收同步改为"可静态判定条件 ⊆ 白名单；unverified 不计通过"。
- **M4（P7 本体版本未闭合）** → 平台 P7 明确绑定 KB `applied_fingerprint` 对应快照（`applied_schema_snapshot`）；快照不可得 → 显式"未受本体约束"降级，**不宣称白名单保证**（平台计划同步）。
- **M5（D7 遗漏结构化表格入图）** → D7 (d) 单列该能力并**明确排除**（属 data-qa 建图能力，依赖属性落图方案 B，另立项）；`SourceBinding` 元数据 ≠ 已覆盖。
- **should-fix 全数并入**：grounding 补 `relationsAmong`；`_normalize_type` 措辞校正（默认 STRING、无别名表，Java 别名表为扩展）；`_prune` 方向反转标为**有意差异**；L1 结构校验改写为 Java 语义（DB 表身份规范化、standalone 载体 = options）；`one-key-per-label` 排除理由修正（跨表 key 一致性）并随结构化入图一并排除；`declared_labels` 补同名异型处置口径；**归一先于 canonical/hash、同一快照供渲染与校验**（D3 + 实现设计）。
- **实现设计（用户要求"避免过度设计 + 具体实现"）** → `## 实现设计（r14 具体化）`：L1–L5 类签名与算法；L3 post-merge 校验**复用 `KgExtractionValidator` + `GraphAssembler.ChunkExtraction`（`GraphAssembler.java:225`）重建，零新接口**。
- **定案程序（已完成）**：决策点 1/3/5 与各"排除/后续"确认已于 r14 由 Codex 裁定并回写。

### r14 评审修订（2026-10-15）

r14 裁定：**需再修 M4、N1、N2**；其余 r13 must-fix 已关闭，选项已裁定。

- **M4 闭合（P7 快照/指纹/缓存）**：平台 P7 实现设计明确——`applied_fingerprint` 沿用 D4 完整有效 schema context 算法；`applied_schema_snapshot` 持久化完整规范化输入并读取校验；`(kbId, applied_fingerprint)` 只缓存 applied 本体快照；`profile`/一致性状态每次从当前配置/状态解析；提示侧动态逻辑 schema 段每次运行时构造追加（或按 `(agent, fingerprint)` 短期缓存），静态物理段可继续原缓存。
- **N1 闭合（L3 算法放行非法关系）**：实现设计 L3 修正——`T(kw)=∅`（纯未知关键词）**或** `∃ t∈T(kw)` 谓词假（端点对不匹配）均**无条件 drop**；仅混合关键词且所有命中类型谓词为真时，才按 `UnknownKeywordPolicy` 决定保留或 drop。
- **N2 闭合（renderer 接口与输出责任）**：SDK `OntologySchemaRenderer` 只输出纯逻辑 schema 段（业务类型/关系关键词/模式行）；物理 label、`typePropertyName` 过滤说明由平台按 `PhysicalGraphProfile` 拼接。平台静态提示补充物理边 type（Neo4j/Memgraph 为 `RELATION`）。
- **L2 细节修正**：`KnowledgeExtractor` 新构造器 = 现有 **12 参** + overlay（非 11 参）；`renderGraphExtractionGuidance` 提前返回条件改为"`relationTypes`/`examples`/`overlay` 三者皆空"才返回；D2 旧表述"在 `GraphExtractionOptions` 增加字段"改为"独立 `OntologyExtractionOverlay`"。
- **选项裁定**：属性持久化 **方案 A**；L2 载体 **overlay**；阶段 1 渲染 `attributeBlock` **是**；grounding 仅保留 `lookup/link/relationsAmong`；回填、结构化表格接入、`declared_labels` 同名异型吸收、change representation/diff UI **均排除阶段 1**。

### r15 评审修订（2026-10-15）

r15 裁定：**需再修 N2、N3**；M4/N1 已闭合。

- **N2 最终闭合（renderer 输出责任一致性）**：
  - 实现清单 `OntologySchemaRenderer.java` 行删除"按 `profile.typePropertyName` 的过滤注记"，明确 renderer 只输出纯逻辑段，物理段由平台拼接。
  - 平台 P7 提示侧正文改为"静态物理段保留现有后端特定描述，并补充缺失的物理边 type 说明"，消除与 r14 修订记录的冲突。
- **N3 最终闭合（决策点去"待确认"）**：SDK 计划「## 决策点」标题改为「已裁定」，5 个决策点改为确定选择 + 历史备选；同时修正 item 1 对"决策点 5 方案 A"的引用。
- **L1 细节**：实现设计 `OntologyStructureValidator` 步骤 4 改为"候选中未被任何 pattern 引用的 label"，避免需要基线的歧义。
- **平台侧同步**：平台计划「## 决策点」同步改为「已裁定」，三项平台决策按推荐方案确定。

### r16 评审修订（2026-10-15）

r16 裁定：**N2 已闭合；N3 需再修**。本轮闭合 N3：

- 删除 D7(e) 中"属性落图（决策点 5，r14 交 Codex 定案）"的开放表述，改为"决策点 5 已裁定方案 A"。
- r13 变更日志"定案程序"改为"定案程序（已完成）"，明确 r14 已裁定并回写。
- 两份计划「## 待评审问题（交给 Codex）」改为「## 历史评审问题（r1–r16 已逐轮闭合）」，并加说明表示问题已闭合、仅作追溯。

### r17 评审修订（2026-10-15）

r17 裁定：**可合并**。未发现新增或未闭合的 must-fix。r13 M1–M5、r14 M4/N1、r15 N2、r16 N3 均已闭合，决策点已裁定，实现设计可执行。
