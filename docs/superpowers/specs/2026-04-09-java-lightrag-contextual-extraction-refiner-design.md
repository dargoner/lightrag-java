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

## Verified Current Contracts

在设计 refinement 之前，必须先对齐现有代码契约。

### Existing Extraction Model

当前抽取数据模型是：

- `ExtractionResult(entities, relations, warnings)`
- `ExtractedEntity(name, type, description, aliases)`
- `ExtractedRelation(sourceEntityName, targetEntityName, type, description, weight)`

也就是说，当前一次抽取结果里：

1. **没有** per-entity 的 `sourceChunkIds`。
2. **没有** per-relation 的 `sourceChunkIds`。
3. 只有 chunk 级 `warnings`。

### Existing GraphAssembler Behavior

`GraphAssembler` 的关键语义：

1. 输入是 `ChunkExtraction(chunkId, extraction)`。
2. 每个 `ExtractedEntity` / `ExtractedRelation` 都通过所属 `chunkId` 贡献 `sourceChunkIds`。
3. entity 去重键基于 `name + aliases` 的规范化结果。
4. relation 去重键基于：
   - source entity id
   - canonical relation type
   - target entity id
5. 对称关系类型当前只包含：
   - `related_to`
   - `works_with`

这意味着：

```text
GraphAssembler 不理解“窗口级 relation 证据”
GraphAssembler 只理解“这条 extraction 属于哪个 chunk”
```

因此，refinement 不能只产生“窗口级抽取结果”，还必须把补充结果**重新分配回具体 chunk**，否则无法正确生成 `sourceChunkIds`。

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
4. **证据归因与分配**：将窗口级补充结果映射回具体 supporting chunks。
5. **结果合并**：将补充结果以“只补不推翻”的方式并入一次抽取结果。

核心原则：

- 保留细 chunk 作为最终证据单位。
- 使用小窗口作为抽取修复单位。
- 优先补图边，其次补证据，最后才补实体。
- **没有明确证据归因的新增 relation，不允许写回图。**

## Proposed Architecture

### 1. `ExtractionGapDetector`

职责：

1. 判断当前 chunk 是否存在关系断裂风险。
2. 输出推荐的 refinement 范围级别，而不是直接给出最终窗口。
3. 保持轻量，不调用大模型。

输入：

- 当前 chunk
- 相邻 chunk 元数据与文本
- 当前 chunk 的一次抽取结果 `ExtractionResult`
- 可选 section 信息或 parent/child chunk 元数据

输出：

- `GapAssessment`
  - `requiresRefinement`
  - `prescreenSignals`
  - `qualitySignals`
  - `recommendedScope`，取值为 `NONE` / `ADJACENT` / `SECTION`

#### Recommended Signals

**规则预筛信号**：

1. 当前 chunk 以关系提示词或连接词结尾，例如：`依赖`、`通过`、`使用`、`调用`、`用于`、`先`、`再`。
2. 当前 chunk 与相邻 chunk 存在明显实体词重叠。
3. 当前 chunk 位于同一 section 的连续说明段中，且长度偏短。
4. 当前 chunk 仅包含代码/配置或仅包含解释文本，语义明显未闭合。

**质量复判信号**：

1. 一次抽取得到多个实体但没有关系。
2. 抽出的关系数量偏少，且文本中存在明显关系动词。
3. 关系两端实体名过于泛化，例如“系统”“模块”“服务”，但 chunk 中存在更具体名词。
4. `warnings` 非空，且说明一次抽取可能受上下文不完整影响。

推荐默认策略：

- `minPrescreenSignals = 1`
- `minQualitySignals = 1`

只有两类信号都达到阈值才触发 refinement。

### 2. `RefinementWindowResolver`

职责：

1. 根据 `GapAssessment` 生成实际窗口。
2. 先按相邻 chunk 收敛，再按 section 内扩展。
3. 统一负责预算裁剪，避免窗口来源不唯一。

推荐两层窗口：

1. **第一层**：`previous + current + next`
2. **第二层**：若一级窗口无收益，则在同一 section 内扩到最多 5 个 chunk

窗口控制原则：

1. 不跨 document。
2. 默认不跨 section。
3. `maxWindowChunks` 默认 5。
4. `maxWindowTokens` 默认 1200，超限时优先保留：
   - 当前 chunk
   - 相邻 chunk
   - 含候选实体重叠的句子

### 3. `ContextualExtractionRefiner`

职责：

1. 对窗口内多个 chunk 的聚合文本做一次局部抽取。
2. 产出窗口级补充实体与关系。
3. 同时给出每条补充结果的 supporting chunks，供后续分配。

输入：

- `RefinementWindow`
- 窗口内的一次抽取结果集合
- 聚合文本

输出：

- `RefinedWindowExtraction`
  - `entityPatches`
  - `relationPatches`
  - `warnings`
  - `qualitySummary`

这里建议仍复用 `KnowledgeExtractor` 的 prompt/schema，但要增加一个面向窗口的入口，例如：

- `KnowledgeExtractor.extractWindow(RefinementWindow window)`

它的语义不是重新做全文抽取，而是：

- 给定一组连续 chunk
- 只补足跨 chunk 的局部事实
- 输出与当前 JSON schema 兼容的结果
- 同时要求模型或后处理器给出 supporting chunk attribution

### 4. `AttributionResolver`

职责：

1. 将窗口级补充结果映射回具体 chunk。
2. 只为可归因的补充关系生成 per-chunk patch。
3. 避免把窗口关系错误归因到单个 chunk。

这是本次设计新增的关键组件。

#### Attribution Rules

对于每条 `RefinedRelationPatch`：

1. 必须至少有一个 supporting chunk id。
2. 若 supporting chunk ids 为空，则该 relation **不得新增入图**。
3. 若 relation 覆盖多个 chunk，则将同一 relation patch 分发到这些 supporting chunks。
4. `GraphAssembler` 后续会用重复出现的同一 relation 自动合并，并将 `sourceChunkIds` 做并集，因此这是当前代码语义下最稳定的实现方式。

对于每条 `RefinedEntityPatch`：

1. 只有在它被至少一个可归因 relation 引用时，才允许写回。
2. supporting chunk ids 采用：
   - 被引用 relation 的 supporting chunk ids 并集
   - 或窗口内显式命中的 chunk ids
3. 不允许引入没有 supporting chunk 的孤立实体。

### 5. `ExtractionMergePolicy`

职责：

1. 将一次抽取结果和 refinement patch 合并成最终的 per-chunk `ExtractionResult`。
2. 确保“补洞”而不是“推翻”。
3. 保持输出仍然兼容 `GraphAssembler.ChunkExtraction`。

合并原则：

#### Entity Merge

按当前 `GraphAssembler` 的规范化语义对齐：

1. 规范化键使用：`name + aliases`
2. 已存在实体：补 `aliases`、`description`
3. 缺失实体：只在它被至少一个新增 relation 引用时写入对应 chunks

#### Relation Merge

按当前 `GraphAssembler` 的关系主键语义对齐：

- `sourceEntityId + canonicalRelationType + targetEntityId`

这里的 `canonicalRelationType` 与当前实现一致：

- 小写化
- 将空格、`_`、`-` 统一归一为 `_`

策略：

1. 已存在关系：补 `description`、`weight`
2. 不存在关系：新增
3. 同一 relation 若分发到多个 supporting chunks，则允许它在多个 chunk 的 `ExtractionResult` 中重复出现
4. `GraphAssembler` 负责最终去重并聚合 `sourceChunkIds`

### 6. `RefinedChunkExtractionPlan`

为保持与现有 `GraphAssembler.ChunkExtraction` 兼容，推荐引入一个中间计划对象，承载：

1. 原始 `PrimaryChunkExtraction`
2. `GapAssessment`
3. `RefinementWindow`
4. `RefinedWindowExtraction`
5. `ChunkExtractionPatch` 列表
6. 合并后的最终 `GraphAssembler.ChunkExtraction`

这个对象只存在于 refinement pipeline 内部，不进入公开 API。

## Minimal Type Definitions

推荐在 spec 中先固定最小类型契约：

```java
record PrimaryChunkExtraction(
    Chunk chunk,
    ExtractionResult extraction
) {}

record GapAssessment(
    boolean requiresRefinement,
    List<String> prescreenSignals,
    List<String> qualitySignals,
    RefinementScope recommendedScope
) {}

enum RefinementScope {
    NONE,
    ADJACENT,
    SECTION
}

record RefinementWindow(
    String documentId,
    List<Chunk> chunks,
    int anchorIndex,
    RefinementScope scope,
    int estimatedTokens
) {}

record RefinedEntityPatch(
    ExtractedEntity entity,
    List<String> supportingChunkIds
) {}

record RefinedRelationPatch(
    ExtractedRelation relation,
    List<String> supportingChunkIds
) {}

record RefinedWindowExtraction(
    List<RefinedEntityPatch> entityPatches,
    List<RefinedRelationPatch> relationPatches,
    List<String> warnings,
    boolean producedUsefulRelations
) {}

record ChunkExtractionPatch(
    String chunkId,
    List<ExtractedEntity> entities,
    List<ExtractedRelation> relations
) {}
```

约束：

1. `PrimaryChunkExtraction` 的稳定主键是 `chunk.id()`。
2. `RefinementWindow.chunks` 保持文档原始顺序。
3. `ChunkExtractionPatch.chunkId` 必须引用窗口内 chunk。
4. `RefinedRelationPatch.supportingChunkIds` 不允许为空；为空则该 patch 被丢弃。

## GraphAssembler Contract

必须明确 refinement 与当前 `GraphAssembler` 的契约：

1. `GraphAssembler` 只消费 `ChunkExtraction(chunkId, extraction)`。
2. `GraphAssembler` 不理解窗口，也不理解 per-relation attribution。
3. `GraphAssembler` 通过“同一 relation 在多个 chunk extraction 中重复出现”来汇总 `sourceChunkIds`。
4. 因此，refinement pipeline 的最终职责是生成**per-chunk extraction patches**，而不是把窗口结果直接喂给 `GraphAssembler`。

这一定义也意味着：

- `sourceChunkIds` 的合并发生在 `GraphAssembler` 阶段。
- `description` / `weight` 的补充允许通过重复 relation merge 生效。
- relation attribution 必须在 assembler 之前完成。

## Data Flow

推荐数据流：

```text
chunks
  -> primary extraction per chunk
  -> gap detection
  -> refinement window selection
  -> contextual extraction for selected windows
  -> attribution resolution
  -> per-chunk patch merge
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
var primaryExtractions = chunks.stream()
    .map(chunk -> new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
    .toList();
var refinedExtractions = extractionRefinementPipeline.refine(primaryExtractions);
var graph = graphAssembler.assemble(refinedExtractions);
```

这里 `refinedExtractions` 的最终类型应为：

```java
List<GraphAssembler.ChunkExtraction>
```

而不是窗口级抽取结果。

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
5. `AttributionResolver`
6. `ExtractionMergePolicy`

其中：

- `IndexingPipeline` 只负责编排。
- `KnowledgeExtractor` 负责一次抽取与窗口抽取。
- `GraphAssembler` 不承担断裂检测职责。
- `AttributionResolver` 负责把窗口结果落回细 chunk。

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
2. 若相邻窗口没有产出**可归因**的新 relation，则可升级到 section 窗口。
3. “可归因 relation”的定义：
   - supporting chunk ids 非空
   - 两端实体名在窗口文本中可定位
4. 若 section 窗口仍无收益，则保留一次抽取结果，不再继续放大窗口。

### Attribution Policy

1. refinement 新增 relation 时，必须分配到至少一个 supporting chunk。
2. 一个 relation 若支持证据跨多个 chunk，则向多个 chunk 分发同一 patch。
3. 无法确定 supporting chunks 的 relation 不得写回。
4. 新增实体必须附着于至少一个新增 relation 的 supporting chunks。

### Merge Policy

1. refinement 结果不能删除一次抽取已有 entity/relation。
2. refinement 结果可以新增 relation。
3. refinement 结果可以补齐已有 relation 的描述和 weight。
4. `sourceChunkIds` 不在 merge 层直接维护，而由 `GraphAssembler` 通过分发后的重复 relation 汇总得到。
5. 同一个 chunk 上的 patch 去重遵循现有 entity/relation 规范化规则。

## API Direction

建议新增以下类型：

```java
public interface ExtractionGapDetector {
    GapAssessment assess(List<PrimaryChunkExtraction> extractions, int index);
}

public interface ContextualExtractionRefiner {
    RefinedWindowExtraction refine(RefinementWindow window, List<PrimaryChunkExtraction> extractions);
}

public interface AttributionResolver {
    List<ChunkExtractionPatch> distribute(RefinedWindowExtraction refinedWindow, RefinementWindow window);
}

public interface ExtractionMergePolicy {
    GraphAssembler.ChunkExtraction merge(
        PrimaryChunkExtraction primary,
        List<ChunkExtractionPatch> patchesForChunk
    );
}
```

编排层：

```java
public final class ExtractionRefinementPipeline {
    List<GraphAssembler.ChunkExtraction> refine(List<PrimaryChunkExtraction> primaryExtractions)
}
```

这些类型都应放在 `indexing` 包下，保持和现有知识抽取链路同域。

## Configuration Strategy

建议增加轻量配置，但默认保持保守。

### Recommended Initial Config

1. `enabled`，**默认关闭**
2. `maxWindowChunks`，默认 5
3. `maxWindowTokens`，默认 1200
4. `maxRefinementPerDocument`，默认 `min(8, max(1, chunks.size() / 10))`
5. `sectionExpansionEnabled`，默认开启
6. `minPrescreenSignals`，默认 1
7. `minQualitySignals`，默认 1

### Cost and Backpressure Rules

1. refinement 与一次抽取共用同一个执行器和限流器，避免额外线程池放大并发。
2. 每文档达到 `maxRefinementPerDocument` 后，剩余 chunk 一律跳过 refinement。
3. 窗口 token 超限时，不升级窗口，只保留一级窗口。
4. 若系统处于高负载，可通过配置直接关闭 refinement。

## Testing Strategy

测试必须拆成可执行分层，而不是只列覆盖点。

### 1. Pure Unit Tests

覆盖：

- `ExtractionGapDetector`
- `RefinementWindowResolver`
- `AttributionResolver`
- `ExtractionMergePolicy`

要求：

- 不依赖 LLM
- 使用固定 chunk 文本和固定一次抽取结果
- 断言信号命中、窗口选择、patch 分发和 merge 结果

### 2. Component Integration Tests

覆盖：

- `ContextualExtractionRefiner + mock KnowledgeExtractor`
- `ExtractionRefinementPipeline`

要求：

- 使用固定窗口输入
- mock 出窗口级抽取结果
- 验证 relation 是否正确分发到 supporting chunks
- 验证无归因 relation 被丢弃

### 3. End-to-End Golden Tests

覆盖：

1. 关系被切断：一次抽取无 relation，refinement 后新增 relation
2. 正常 chunk：不触发 refinement
3. 一级窗口失败，section 窗口成功
4. refinement 只补不覆盖 primary
5. 新增实体必须连通
6. refinement 后结果仍可被现有 `GraphAssembler` 正常消费
7. refinement 后 relation 仍可构建 relation vectors

### 4. Performance Guards

增加以下断言：

1. 每文档 refinement 次数不超过上限
2. 单窗口 chunk 数不超过上限
3. 单窗口 token 不超过上限
4. refinement 关闭时，链路退化回当前实现

## Risks

### 1. 误触发导致成本升高

如果预筛规则太宽，会让大量 chunk 进入 refinement，导致抽取成本明显上升。

### 2. 窗口过大导致误连边

如果 section 扩窗过大，容易把不相关实体硬连到同一个局部上下文里。

### 3. 归因不准导致证据污染

如果窗口 relation 被错误分发到无关 chunk，会直接污染 `sourceChunkIds`。

### 4. 外部分片质量差异大

外部传入的 chunk 如果完全不保留顺序、section 和结构边界，会削弱本方案效果。

## Mitigations

1. 默认采用保守触发策略。
2. 默认小窗口优先，section 扩窗设严格上限。
3. 无法归因的 relation 不入图。
4. 对外部分片要求保留：`documentId`、`order`、可选 `section` metadata。
5. 为 refinement 增加可观测日志和命中统计，便于后续调优。

## Rollout Plan

### Stage 1

1. 新增 `ExtractionRefinementPipeline`、`ExtractionGapDetector`、`AttributionResolver` 与基础 merge policy。
2. 仅支持相邻窗口 refinement。
3. `enabled` 默认关闭，仅在内部基准、demo 或指定文档类型启用。
4. 补充单元测试和 golden tests，验证断裂关系补全。

### Stage 2

1. 增加 section 窗口升级策略。
2. 引入轻量配置。
3. 增加命中统计与调优日志。
4. 观察以下指标：
   - refinement 命中率
   - 每文档额外调用数
   - relation 增量
   - 误连边抽查率
   - 端到端耗时增量

### Stage 3

1. 只有当 Stage 2 指标稳定时，才考虑扩大默认启用范围。
2. 明确回滚阈值与热切换配置。
3. 评估是否对外开放外部分片配套 metadata 约束。
4. 评估是否为不同文档类型增加定制 detector 规则。

## Recommendation

推荐按本设计落地。

原因：

1. 它直接解决细粒度 chunk 导致的“图断边”问题。
2. 它不要求放大默认 chunk，也不会牺牲证据颗粒度。
3. 它可以复用现有 `KnowledgeExtractor` 与 `GraphAssembler`，改动边界清楚。
4. 它对外部分片场景尤其有价值，能够提升 LightRAG 对细分片输入的容错性。
5. 它把最关键的正确性问题写死了：**窗口结果必须先做证据归因，再回写到细 chunk。**

最终要达到的效果不是“让 chunk 变大”，而是：

**保留细粒度证据单元，同时用局部上下文补齐跨 chunk 的实体关系。**
