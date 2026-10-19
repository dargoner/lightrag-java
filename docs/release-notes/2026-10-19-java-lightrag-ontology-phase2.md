# lightrag-java 本体二期发行说明（2026-10-19）

本说明覆盖本体二期（2A 属性物化与校验、2B 确定性 bootstrap、2C OWL/Turtle/SHACL 互操作）。
发布形态为一次 minor 版本；`lightrag-rdf` 为可选模块，与 core 同版本发布。

## 升级前必读

### 1. 既有图不会自动获得属性，需要 REBUILD

属性物化只作用于 SDK 的**当前图**。升级 SDK 后，既有图仍可按空属性正常读取，但不会自动补齐
属性；要让已声明属性出现在图上，必须由宿主在维护窗内对相关文档执行 **REBUILD/重抽**。

- REBUILD 会**原地覆盖当前图与 chunk 快照**。
- REBUILD **不修改**宿主 `km_ontology_version`/旧投影；pin 旧版本后重建是"按旧本体重抽当前图"，
  不是恢复历史图快照。
- SDK 不提供历史图归档；**版本回滚 ≠ 图回滚**。
- SDK 不删除宿主旧本体版本或旧投影；版本存储保持追加式、**永久保留**，不引入 TTL、定时 prune
  或后台清理。

建议顺序：升级 SDK → 升级/冻结本体版本 → 逐文档 REBUILD → 校验属性与关系保留。

### 2. 快照不支持降级

属性随快照一起持久化。**旧 SDK 读取含非空属性的新快照会失败**，且没有降级读取模式；
升级与回退必须协调进行，不能只回退 SDK 版本而保留新快照。

### 3. 关系校验的 ∀ 行为变化（单列）

`OntologyConformanceValidator` 修正了关系谓词从 ∃ 到 ∀ 的实现偏差：一个关键词命中多个受约束
关系类型时，现在要求**每个**受约束命中类型都完整匹配端点对。行为矩阵：

| 模式 | 行为变化 |
|---|---|
| STRICT | 不满足 ∀ 的关系被丢弃 |
| PREFER | 关系保留并回调 `UnmatchedLabel`，不从图中移除 |
| OPEN | 行为不变 |

该收紧只影响"多命中类型且仅部分类型匹配"的关系；单命中类型的既有行为不变。

## 2A 属性物化与属性级校验

- 实体/关系新增 `attributes`。存储与快照中的属性为规范 JSON，空属性袋为 `{}`；API JSON 的空属性
  字段省略，与 Phase 1 一致。属性覆盖六个存储后端、文件/数据库快照、查询视图与手工治理 API。
- 属性值域：`String`、`Long`、`Double`、`Boolean`、`List`；DATE 使用 ISO 字符串；数值统一规范为
  `Long`/`Double`（整数值不保留小数形式）。
- 上限：每条记录 64 键、canonical JSON 16 KiB UTF-8、LIST 64 元素、字符串 4096 字符、元素 1024 字符。
- 抽取期按最终实体类型/关键词绑定属性；未声明键、类型冲突、非法值只丢属性并产生诊断，
  **不丢实体或关系**。人工写入与合并同样执行上限检查。
- 投影在**合并存量之后**执行：普通写入会裁剪被本次写入触及的记录中"最终类型未声明"的键；
  这不是"删除声明即全量迁移"，未被本次写入触及的记录保持原样。
- 无本体时不产生属性声明、prompt 与 Phase 1 字节一致，解析出的属性按空 schema 丢弃。

## 2B 确定性 bootstrap

- 新增 `io.github.lightrag.ontology.bootstrap`：从强类型样本归纳实体/关系/属性候选，
  结果确定、可复现，`draft=True` 且 `version=null`。
- **不调用 LLM、不读存储、不写版本、不自动应用**；有 base 时输出完整候选，只能通过宿主
  `EVOLUTION_REPLACE` 审批路径进入版本库。
- fingerprint 覆盖算法版本、规范化后的完整 base（含 version/metadata）与 canonical 样本。

## 2C OWL/Turtle/SHACL 互操作

- core 新增零依赖 Turtle/OWL/SHACL 导出；SHACL 对实体属性输出 `sh:maxCount 1`，
  LIST 属性省略 datatype。
- 新增可选模块 `lightrag-rdf`（RDF4J 5.3.2）：支持 Turtle、RDF/XML、JSON-LD 导入子集。
- 导入为**离线、fail-closed**：拒绝全部远程/本地 context 文档加载；`owl:imports` 默认配置下作为
  未知公理产生确定性警告（关闭 `allowUnknownAxioms` 时只计数、不警告），两种情况下都绝不跟随；
  三元组、literal/IRI 字节、深度与节点数限额在处理中生效；同一主体的标签/注释/range 冲突直接
  失败而不猜测；导入只产草稿，不写版本。

## 兼容性

- record 扩展保留旧 arity 构造器，旧二进制消费者仍可链接；反序列化时空属性与 Phase 1 字节形状一致。
- 旧构造器仅允许已审阅的空占位或 PREFER 显式清空；仓库内由 `LegacyConstructorAllowlistTest` 门禁
  （源码扫描 + 显式 allowlist），新调用点必须显式传 attributes。
