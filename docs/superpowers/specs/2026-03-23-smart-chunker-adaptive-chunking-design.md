# SmartChunker Adaptive Chunking Design

**Status:** Draft for review

**Goal**

在保留 `FINE / MEDIUM / COARSE` 用户心智的前提下，为 `SmartChunker` 增加基于基础粒度的自适应 chunk 能力，让连续正文更粗、标题密集区域更细，同时避免破坏 `LAW / QA / TABLE / LIST` 的边界稳定性。

## Problem

当前 `SmartChunker` 的 chunk 大小完全由静态配置控制：

- `targetTokens`
- `maxTokens`
- `overlapTokens`

这个模型足够稳定，但存在两个明显问题：

1. 同一文档不同区域的结构密度不同，固定大小会让正文偏碎、标题附近偏粗。
2. `GENERIC / BOOK` 这类长正文场景最适合动态调节，但现有实现无法表达“在安全区间内自动放缩”。

已有的结构增强、噪音清洗、弱结构复用已经改善了切片质量，下一步应该补上“局部自适应大小”，而不是推翻现有粒度体系。

## Non-Goals

本次不做：

- 完全无粒度配置的全智能切片
- OCR 深度纠错
- 语义合并策略重写
- 表格复杂跨页重建
- 图片视觉 embedding

## User Experience

用户界面继续只暴露：

- `FINE`
- `MEDIUM`
- `COARSE`

系统内部自动启用 adaptive chunking。也就是说，用户不需要新增开关，也不需要理解新的高级参数。

产品语义为：

- `FINE`：更细，但仍允许在局部略放粗
- `MEDIUM`：默认基准粒度，并允许自适应上下浮动
- `COARSE`：更粗，但在标题密集区仍会适当收细

## Design Options

### Option A: Conservative adaptive sizing

保留现有基础粒度参数，允许每个 block 在安全比例范围内动态调整。

优点：

- 改动最小
- 可回归性最好
- 不破坏现有 `ChunkGranularity` 语义

缺点：

- “智能程度”受限于安全边界，不会出现特别激进的放缩

### Option B: Fully dynamic sizing

忽略固定 `target/max/overlap`，运行时完全基于文档结构实时计算。

优点：

- 表达能力最强

缺点：

- 回归风险很高
- 很难对 `LAW / QA` 保持稳定
- 用户无法预期 `FINE / MEDIUM / COARSE` 的实际含义

### Option C: Two-pass regrouping

先按当前规则切，再做第二次 regroup，把过碎或过粗的 chunk 重组。

优点：

- 不侵入第一阶段切分

缺点：

- 实现复杂
- 容易和现有语义合并叠加，行为不透明

## Recommendation

采用 **Option A: Conservative adaptive sizing**，并在实现阶段补充一个更保守的 section 内短段 regroup。

这是最符合当前代码形态的方案。`SmartChunker` 已经有明确的 paragraph/list/table 分支和 `ChunkGranularity` 基础配置，只需要在 paragraph path 上引入轻量决策层，就能获得大部分收益。

不过真实文档验证表明，仅做“单段内 target/max/overlap 自适应”还不够。对于白皮书、报告这类连续正文文档，真正的收益点还包括：

- 同一 `section_path` 下多个相邻短正文段的预 regroup
- 但这个 regroup 必须非常保守，不能误合并页眉页脚、附件提示、页间噪声

## Architecture

新增一个轻量自适应策略对象，例如：

- `AdaptiveChunkSizingPolicy`

职责：

- 输入基础 `SmartChunkerConfig`
- 输入文档类型、块类型、句子统计和局部结构信号
- 输出当前 block 的有效 `target / max / overlap`

`SmartChunker` 继续负责：

- block 标准化
- paragraph run 级别的轻量预 regroup
- 句子切分
- 组装 `ChunkDraft`
- 语义合并

这样可以把“怎么决定大小”和“怎么按大小切分”分离开。

## Adaptive Signals

第一版只使用保守、可解释的信号：

1. **块类型**
   - `PARAGRAPH`：允许自适应
   - `LIST`：基本保持保守
   - `TABLE`：维持现有逻辑，不做 adaptive

2. **文档类型**
   - `GENERIC / BOOK`：自适应收益最大
   - `LAW`：只允许小幅调整
   - `QA`：问答配对优先，几乎不放粗

3. **句子统计**
   - 句子数
   - 平均句长
   - 是否是连续长正文

4. **结构密度**
   - 当前 block 是否处于标题切换后的短区域
   - 当前 block 是否明显偏短且节奏紧凑

5. **section 内连续性**
   - 当前 paragraph 是否与前后 paragraph 处于相同 `section_path`
   - 是否属于可安全 regroup 的连续正文 run
   - 是否更像正文而不是页眉页脚或短提示语

## Adaptive Rules

### Paragraph

`PARAGRAPH` 是第一版唯一真正启用 adaptive sizing 的路径。

规则建议：

- 长正文、句长稳定、非标题切换区域：
  - `target` 上浮
  - `max` 上浮
  - `overlap` 按比例小幅上浮

- 同一 `section_path` 下相邻多个短正文段：
  - 允许先做 paragraph regroup
  - regroup 后再进入按句切分
  - regroup 仍然受当前 adaptive `max` 约束

- 标题后首段、短段密集、句子数少：
  - `target` 下调
  - `max` 轻微下调
  - `overlap` 随之下调

- 过短且不像正文的段落（如页眉页脚、附件短句、页码附近噪声）：
  - 不进入 regroup
  - 继续按原 block 处理

### List

列表以边界稳定为主：

- 不主动放粗
- 维持现有列表切分逻辑

### Table

表格继续维持现有：

- 表头保留
- 按行拆分

不引入 adaptive，避免复杂表格回归。

### LAW

法律文本优先保持条款边界：

- 允许 very small adjustment
- 不允许因 adaptive 导致条款内部合并过粗

### QA

问答以 `Q/A` 配对优先：

- adaptive 只在问答正文内部生效
- 不改变问答配对边界

## Configuration Changes

在 `SmartChunkerConfig` 增加以下字段：

- `adaptiveChunkingEnabled`
- `adaptiveMinTargetRatio`
- `adaptiveMaxTargetRatio`
- `adaptiveOverlapRatio`

默认值建议：

- `adaptiveChunkingEnabled = true`
- `adaptiveMinTargetRatio = 0.70`
- `adaptiveMaxTargetRatio = 1.35`
- `adaptiveOverlapRatio = 0.12`

约束：

- `0 < adaptiveMinTargetRatio <= 1.0`
- `adaptiveMaxTargetRatio >= 1.0`
- `adaptiveMinTargetRatio <= adaptiveMaxTargetRatio`
- `adaptiveOverlapRatio >= 0`

## Code Changes

计划涉及文件：

- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicy.java`（新增）

可能补充文档：

- `README.md`
- `README_zh.md`

## Data Flow

```text
ParsedBlock / StructuredBlock
        ↓
SmartChunker paragraph path
        ↓
SentenceBoundaryAnalyzer.split(...)
        ↓
AdaptiveChunkSizingPolicy.resolve(...)
        ↓
effectiveTarget / effectiveMax / effectiveOverlap
        ↓
chunk assembly
        ↓
optional semantic merge
```

## Error Handling

如果 adaptive sizing 计算异常或命中非法值：

- 回退到基础 `SmartChunkerConfig`
- 不中断 chunking

也就是说，adaptive 是增强层，不应该成为主流程单点故障。

## Testing Strategy

### Unit tests

新增或扩展以下断言：

- 长正文在 `MEDIUM` 下启用 adaptive 后 chunk 数下降
- 标题后短段在 adaptive 下会更细
- `LIST / TABLE` 行为不被意外放粗
- `LAW / QA` 不跨边界失稳

### Regression tests

保留现有：

- `StructuredDocumentParserTest`
- `SmartChunkerTest`
- `ChunkingOrchestratorTest`
- `DocumentIngestorTest`

确保已有的弱结构复用、页眉去重、噪音清洗行为不被回退。

### Real document verification

继续使用现有中文白皮书对比：

- chunk count
- average token count
- section count
- representative chunks

## Rollout Plan

第一阶段：

- 只让 `PARAGRAPH` 使用 adaptive sizing
- `LIST / TABLE` 保持原样

第二阶段：

- 视真实文档效果，再考虑是否让 `BOOK` 的列表型正文也做轻微 adaptive

## Risks

1. **过粗**
   长正文若放大过度，会降低召回定位精度。

2. **过细**
   标题切换信号若过敏，会造成 chunk 数反弹。

3. **语义合并叠加**
   adaptive 放粗与语义合并叠加后，可能出现 chunk 过大。

对应策略：

- 先做保守比例边界
- 保持 `maxTokens` 为硬上限
- 用真实文档回归观察 `chunk_count / avg / max`

## Success Criteria

满足以下条件则视为成功：

- `GENERIC / BOOK` 长正文 chunk 数明显下降
- 标题附近 chunk 保持比正文更细
- `LAW / QA / TABLE / LIST` 没有明显行为回归
- 真实中文白皮书的 section 保留不下降
- 没有出现异常超大 chunk
