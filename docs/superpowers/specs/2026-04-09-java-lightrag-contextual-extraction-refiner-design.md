# Java LightRAG Contextual Extraction Refiner Design

## Background

当前 `LightRAG` 的知识抽取链路是逐 chunk 执行的：

1. `IndexingPipeline` 对文档切块。
2. `KnowledgeExtractor.extract(chunk)` 对每个 chunk 单独抽取实体和关系。
3. `GraphAssembler` 将每个 chunk 的 `ExtractionResult` 合并成最终图。

当前实现入口见：

- [IndexingPipeline.computeDocument](../../../lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java)
- [KnowledgeExtractor.extract](../../../lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java)
- [GraphAssembler.ChunkExtraction](../../../lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java)

这条链路吞吐简单直接，但有一个明显弱点：当 chunk 粒度偏细时，实体通常还能被抽到，关系却容易被切断。

典型问题：

- 句子前半段在当前 chunk，后半段在下一个 chunk，导致只抽到实体，抽不到完整关系。
- 一个 chunk 只包含“依赖 / 通过 / 用于 / 先 / 再”等关系提示词，但关系两端不在同一个 chunk 内。
- 配置块和解释文本被拆开，单个 chunk 既无法稳定抽关系，也难以提供高质量证据。

这会直接影响：

1. 图谱断边，`GLOBAL` / `HYBRID` / `MIX` 的关系侧召回变弱。
2. 多跳路径不完整，`MULTI_HOP` 更容易回退到 seed context。
3. `sourceChunkIds` 不完整，命中关系后缺少理想证据块。

## Goals

本次设计目标：

1. 在不放弃细粒度 chunk 证据单元的前提下，补齐因切块过细导致的关系断裂。
2. 采用按需触发的二次抽取，而不是对所有 chunk 全量重跑。
3. 优先补 relation 和 `sourceChunkIds`，必要时再补少量缺失实体。
4. 保持现有 `KnowledgeExtractor` 和 `GraphAssembler` 的主干能力可复用。
5. 为后续外部分片接入提供稳定的抽取修复能力。

## Non-Goals

本次不做以下事情：

1. 不替换现有 chunker，也不强制修改默认切块策略。
2. 不对所有文档执行全量窗口抽取。
3. 不重写现有 `KnowledgeExtractor` prompt 语义，只增加局部聚合抽取入口。
4. 不在这一阶段引入复杂学习型质量判别模型。
5. 不改变查询侧 `QueryStrategy` 的行为。

## Problem Statement

当前抽取模型默认假设“一个 chunk 内已经包含完整局部事实”。

这个假设在以下场景容易失效：

1. 技术文档被切到句中或跨句依赖位置。
2. 配置说明和代码示例相邻但分开。
3. 同一个 section 内，实体和关系描述跨多个短段落展开。
4. 外部分片策略偏向极细粒度，优先保留证据精度而不是抽取完整性。

结果就是：

```text
chunk 很细 -> entity 还在，relation 丢失 -> 图谱断边 -> 召回和多跳下降
```

因此需要增加一层“关系断裂补全抽取”。

## Recommended Approach

推荐采用：

1. **规则预筛**：根据 chunk 文本结构和相邻 chunk 关系，筛出疑似断裂区域。
2. **质量复判**：结合当前 chunk 的一次抽取结果，确认是否真的存在关系断裂风险。
3. **局部聚合二次抽取**：仅对命中风险的 chunk 窗口做 contextual extraction。
4. **结果合并**：将补充结果以“只补不推翻”的方式并入一次抽取结果。

核心原则：

- 保留细 chunk 作为最终证据单位。
- 使用小窗口作为抽取修复单位。
- 优先补图边，其次补证据，最后才补实体。

## Proposed Architecture

### 1. `ExtractionGapDetector`

职责：

1. 判断当前 chunk 是否存在关系断裂风险。
2. 给出推荐的聚合窗口范围。
3. 保持轻量，不调用大模型。

输入：

- 当前 chunk
- 相邻 chunk 元数据与文本
- 一次抽取结果 `ExtractionResult`
- 可选 section 信息或 parent/child chunk 元数据

输出：

- `GapAssessment`
  - `requiresRefinement`
  - `signals`
  - `windowStart`
  - `windowEnd`

#### Recommended Signals

**规则预筛信号**：

1. 当前 chunk 以关系提示词或连接词结尾，例如：`依赖`、`通过`、`使用`、`调用`、`用于`、`先`、`再`。
2. 当前 chunk 与相邻 chunk 存在明显实体词重叠。
3. 当前 chunk 位于同一 section 的连续说明段中，且长度偏短。
4. 当前 chunk 仅包含代码/配置或仅包含解释文本，语义明显未闭合。

**质量复判信号**：

1. 一次抽取得到多个实体但没有关系。
2. 抽出的关系数量偏少，且文本中存在明显关系动词。
3. 关系两端实体名空洞或过于泛化。
4. warnings 指示上下文不充分或抽取不稳定。

推荐默认策略：**规则预筛命中至少 1 条，且质量复判命中至少 1 条，才触发二次抽取。**

### 2. `RefinementWindowResolver`

职责：

1. 将 `GapAssessment` 转成实际窗口。
2. 先按相邻 chunk 收敛，再按 section 内扩展。

推荐两层窗口：

1. **第一层**：`previous + current + next`
2. **第二层**：若第一层仍不足，则在同一 section 内扩到最多 5 个 chunk

窗口控制原则：

1. 不跨 document。
2. 默认不跨 section。
3. 总 token 受控，避免把二次抽取做成小型全文抽取。

### 3. `ContextualExtractionRefiner`

职责：

1. 对窗口内多个 chunk 的聚合文本做一次局部抽取。
2. 产出补充实体与关系。
3. 保留每条补充结果与原始 chunk 的映射关系。

输入：

- `RefinementWindow`
- 一次抽取结果集合
- 聚合文本

输出：

- `RefinedExtractionResult`
  - `entities`
  - `relations`
  - `coveredChunkIds`
  - `signals`

这里建议仍复用 `KnowledgeExtractor` 的 prompt/schema，但要增加一个面向窗口的入口，例如：

- `KnowledgeExtractor.extractWindow(ExtractionWindow window)`

它的语义不是重新做全文抽取，而是：

- 给定一组连续 chunk
- 只补足跨 chunk 的局部事实
- 输出与当前 JSON schema 兼容的结果

### 4. `ExtractionMergePolicy`

职责：

1. 合并一次抽取与二次抽取结果。
2. 确保“补洞”而不是“推翻”。
3. 统一补齐 `sourceChunkIds`。

合并原则：

#### Entity Merge

按规范化名称或既有 merge key 去重：

1. 已存在实体：补 `aliases`、描述、`sourceChunkIds`
2. 缺失实体：允许新增，但需要至少被一个新 relation 引用，避免引入无关噪音

#### Relation Merge

按现有 `GraphAssembler` 的关系主键语义去重：

- `sourceEntity + canonicalType + targetEntity`

策略：

1. 已存在关系：合并描述和 `sourceChunkIds`
2. 不存在关系：新增
3. 对称关系仍沿用 `GraphAssembler` 的 canonical 规则

### 5. `RefinedChunkExtractionPlan`

为保持与现有 `GraphAssembler.ChunkExtraction` 兼容，推荐引入一个中间计划对象，承载：

1. 原始 chunk 的一次抽取结果
2. 参与 refinement 的窗口信息
3. 最终合并后的 `ExtractionResult`
4. 归属到各 chunk 的补充 `sourceChunkIds`

最终 `GraphAssembler` 仍消费 “每个 chunk 一个 extraction result”，但这个结果已经过 refinement merge。

## Data Flow

推荐数据流：

```text
chunks
  -> primary extraction per chunk
  -> gap detection
  -> refinement window selection
  -> contextual extraction for selected windows
  -> merge primary + refined results
  -> GraphAssembler
  -> entity/relation vectors
```

在 `IndexingPipeline` 中，推荐把现有：

```java
chunks.stream()
    .map(chunk -> new GraphAssembler.ChunkExtraction(chunk.id(), knowledgeExtractor.extract(chunk)))
```

改造成：

```java
var primaryExtractions = ...;
var refinedExtractions = extractionRefinementPipeline.refine(chunks, primaryExtractions);
var graph = graphAssembler.assemble(refinedExtractions);
```

这样不会影响下游 `graphAssembler.assemble(...)` 的签名方向，只是在它之前补了一层 refinement pipeline。

## Integration Strategy

### Current Integration Points

现有链路主要落点：

1. `IndexingPipeline.computeDocument(...)`
2. `KnowledgeExtractor.extract(Chunk chunk)`
3. `GraphAssembler.assemble(List<ChunkExtraction>)`

### Recommended New Integration

推荐新增：

1. `ExtractionRefinementPipeline`
2. `ExtractionGapDetector`
3. `RefinementWindowResolver`
4. `ContextualExtractionRefiner`
5. `ExtractionMergePolicy`

其中：

- `IndexingPipeline` 只负责编排。
- `KnowledgeExtractor` 负责一次抽取与窗口抽取。
- `GraphAssembler` 不承担断裂检测职责。

这是为了避免：

- 在 `GraphAssembler` 里混入文本级规则判断
- 在 `KnowledgeExtractor` 里硬编码窗口决策
- 在 `IndexingPipeline` 里堆积太多策略逻辑

## Detailed Behavioral Rules

### Trigger Policy

默认触发规则：

1. 若 chunk 未命中任何预筛信号，则不做 refinement。
2. 若命中预筛信号，但一次抽取质量正常，则不做 refinement。
3. 只有“预筛命中 + 质量复判命中”才做 refinement。
4. 同一个 chunk 最多参与一次一级窗口 refinement 和一次 section 级升级，不允许无限扩窗。

### Window Upgrade Policy

1. 优先尝试相邻窗口。
2. 若相邻窗口抽取后仍没有新增有效 relation，则可升级到 section 窗口。
3. 若 section 窗口仍无收益，则保留一次抽取结果，不再继续放大窗口。

### Merge Policy

1. refinement 结果不能删除一次抽取已有 entity/relation。
2. refinement 结果可以新增 relation。
3. refinement 结果可以补齐已有 relation 的 `sourceChunkIds`。
4. refinement 结果新增实体时，必须和新增 relation 连通。
5. 同一 relation 的多个 `sourceChunkIds` 保留去重后的并集。

## API Direction

建议新增以下类型：

```java
public interface ExtractionGapDetector {
    GapAssessment assess(List<Chunk> chunks, int index, ExtractionResult primaryExtraction);
}

public interface ContextualExtractionRefiner {
    RefinedExtractionResult refine(RefinementWindow window, List<PrimaryChunkExtraction> primaryExtractions);
}

public interface ExtractionMergePolicy {
    ExtractionResult merge(ExtractionResult primary, RefinedExtractionResult refined, String chunkId);
}
```

编排层：

```java
public final class ExtractionRefinementPipeline {
    List<GraphAssembler.ChunkExtraction> refine(List<Chunk> chunks, List<ExtractionResult> primaryExtractions)
}
```

这些类型都应放在 `indexing` 包下，保持和现有知识抽取链路同域。

## Configuration Strategy

建议增加轻量配置，而不是一开始暴露大量参数。

### Recommended Initial Config

1. `enabled`，默认开启
2. `maxWindowChunks`，默认 5
3. `maxRefinementPerDocument`，默认按文档上限控制
4. `sectionExpansionEnabled`，默认开启
5. `requiredSignals`，默认 `1 + 1` 规则

可以先由 `LightRagBuilder` 提供高级开关，细参数先保持内部默认。

## Testing Strategy

测试必须覆盖以下场景：

1. **关系被切断**
   - 一次抽取无 relation
   - refinement 后新增 relation

2. **正常 chunk 不应被触发**
   - 预筛不命中
   - 或抽取质量正常

3. **窗口升级策略生效**
   - 相邻窗口失败
   - section 窗口成功

4. **合并不覆盖原结果**
   - 一次抽取已有 relation
   - refinement 只补 `sourceChunkIds` 或新增边

5. **新增实体必须连通**
   - 防止孤立实体被引入

6. **GraphAssembler 兼容性**
   - refinement 后输出仍可被现有 `GraphAssembler` 正常消费

7. **向量构建兼容性**
   - refinement 补出的 relation 能继续构建 relation vectors

## Risks

### 1. 误触发导致成本升高

如果预筛规则太宽，会让大量 chunk 进入 refinement，导致抽取成本明显上升。

### 2. 窗口过大导致误连边

如果 section 扩窗过大，容易把不相关实体硬连到同一个局部上下文里。

### 3. 合并策略过强导致结果不稳定

如果 refinement 可以覆盖一次抽取，就会让抽取结果在调试时难以解释。

### 4. 外部分片质量差异大

外部传入的 chunk 如果完全不保留顺序、section 和结构边界，会削弱本方案效果。

## Mitigations

1. 默认采用保守触发策略。
2. 默认小窗口优先，section 扩窗设严格上限。
3. merge 只补不删。
4. 对外部分片要求保留：`documentId`、`order`、可选 `section` metadata。
5. 为 refinement 增加可观测日志和命中统计，便于后续调优。

## Rollout Plan

### Stage 1

1. 新增 `ExtractionRefinementPipeline` 与基础 detector / merge policy。
2. 仅支持相邻窗口 refinement。
3. 补充单元测试，验证断裂关系补全。

### Stage 2

1. 增加 section 窗口升级策略。
2. 引入轻量配置。
3. 增加命中统计与调优日志。

### Stage 3

1. 评估是否对外开放外部分片配套 metadata 约束。
2. 评估是否为不同文档类型增加定制 detector 规则。

## Recommendation

推荐按本设计落地。

原因：

1. 它直接解决细粒度 chunk 导致的“图断边”问题。
2. 它不要求放大默认 chunk，也不会牺牲证据颗粒度。
3. 它可以复用现有 `KnowledgeExtractor` 与 `GraphAssembler`，改动边界清楚。
4. 它对外部分片场景尤其有价值，能够提升 LightRAG 对细分片输入的容错性。

最终要达到的效果不是“让 chunk 变大”，而是：

**保留细粒度证据单元，同时用局部上下文补齐跨 chunk 的实体关系。**
