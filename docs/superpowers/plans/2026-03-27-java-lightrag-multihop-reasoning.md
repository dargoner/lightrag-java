# Multi-Hop Reasoning Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `lightrag-core` 增加一条最小可用的多跳检索与答案合成链路，让 2-hop 问题能够输出结构化路径，而不是仅平铺多条证据。

**Architecture:** 保持现有 `QueryEngine + QueryStrategy + ContextAssembler` 主干不变，在查询入口前增加问题意图识别，在 `hybrid/mix` 种子召回之后增加路径扩展与路径评分，在最终回答阶段增加按 hop 组织证据的合成器。第一阶段只支持 1-hop/2-hop，复用现有 `GraphStore`、`VectorStore`、`sourceChunkIds`，不引入社区摘要和重型图算法。

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing `lightrag-core` query/indexing/storage stack

---

## Scope Guardrails

- 第一阶段只做 `FACT / RELATION / MULTI_HOP` 三类问题识别，不做 `GLOBAL_SUMMARY` 社区级实现
- 第一阶段只支持 `maxHop <= 2`
- 不重写现有 `LocalQueryStrategy`、`GlobalQueryStrategy`、`HybridQueryStrategy` 的核心语义
- 不修改存储后端 schema；路径证据先复用现有 `sourceChunkIds`
- 如果路径不完整，答案必须允许“不足以确认”，不能强行合成多跳结论
- 第一阶段必须显式覆盖 `QueryRequest` 的所有复制/透传链路，以及 `LightRag.newQueryEngine(...)` 的装配链路，避免“类已写完但系统未接入”

## File Structure

- Create:
  - `docs/superpowers/plans/2026-03-27-java-lightrag-multihop-reasoning.md`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/QueryIntent.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/QueryIntentClassifier.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/RuleBasedQueryIntentClassifier.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/MultiHopQueryStrategy.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/SeedContextRetriever.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/PathRetriever.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/DefaultPathRetriever.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/PathScorer.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/DefaultPathScorer.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/ReasoningContextAssembler.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/ReasoningPath.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/PathRetrievalResult.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/HopEvidence.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/ReasoningBundle.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/synthesis/PathAwareAnswerSynthesizer.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/query/RuleBasedQueryIntentClassifierTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/query/DefaultPathRetrieverTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/query/DefaultPathScorerTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/query/MultiHopQueryStrategyTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/synthesis/PathAwareAnswerSynthesizerTest.java`
  - `lightrag-core/src/test/java/io/github/lightragjava/query/MultiHopQueryEngineIntegrationTest.java`
- Modify:
  - `lightrag-core/src/main/java/io/github/lightragjava/api/QueryRequest.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/QueryKeywordExtractor.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/QueryEngine.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java` or `lightrag-core/src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/ContextAssembler.java` or keep as-is and route multi-hop through `ReasoningContextAssembler`
  - `README.md`

### Task 1: 锁定查询入口和配置面

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryKeywordExtractor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryIntent.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryIntentClassifier.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/RuleBasedQueryIntentClassifier.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/RuleBasedQueryIntentClassifierTest.java`

- [ ] **Step 1: 写 failing tests，锁定意图分类和多跳参数默认值**

覆盖点：
- “A 通过谁影响 B” 识别为 `MULTI_HOP`
- “A 和 B 什么关系” 识别为 `RELATION`
- 普通事实题保持 `FACT`
- `QueryRequest` 新增 `maxHop / pathTopK / multiHopEnabled` 后默认值不改变现有查询行为

- [ ] **Step 2: 在 `QueryRequest` 增加最小多跳参数**

新增字段建议：
- `int maxHop`
- `int pathTopK`
- `boolean multiHopEnabled`

要求：
- 默认值分别为 `2 / 3 / true`
- builder 和 record 校验保持清晰
- 不修改现有模式枚举的语义

- [ ] **Step 3: 补齐 `QueryRequest` 的复制与透传链路**

至少检查并修正：
- `QueryEngine.expandChunkRequest(...)`
- `QueryKeywordExtractor` 中对 query 的复制/增强逻辑
- 所有直接 `new QueryRequest(...)` 的调用点

要求：
- 新增字段不会在 rerank、keyword extraction、prompt-only、context-only、stream 模式下丢失
- 为每个复制链路补一条 focused 测试或断言

- [ ] **Step 4: 实现规则版问题分类器**

规则保持简单可解释：
- 含“通过 / 经过 / 间接 / 多跳 / 先…再…” 等模式 -> `MULTI_HOP`
- 含“关系” -> `RELATION`
- 其他 -> `FACT`

- [ ] **Step 5: 运行分类器与 `QueryRequest` 透传测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.RuleBasedQueryIntentClassifierTest" --tests "io.github.lightragjava.query.*Query*"`
Expected: PASS

### Task 2: 定义多跳路径数据对象

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/ReasoningPath.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/PathRetrievalResult.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/HopEvidence.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/types/reasoning/ReasoningBundle.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/DefaultPathRetrieverTest.java`

- [ ] **Step 1: 先定义 record 和不变量**

要求：
- `ReasoningPath` 至少包含 `entityIds / relationIds / supportingChunkIds / hopCount / score`
- hop 数与 `relationIds.size()` 一致
- 不允许空路径或负分异常值

- [ ] **Step 2: 给对象补最小构造校验测试**

至少验证：
- 非法 hop/空列表会抛异常
- 合法 1-hop / 2-hop 路径可以构造

- [ ] **Step 3: 运行对象层测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.DefaultPathRetrieverTest"`
Expected: compile + object assertions PASS

### Task 3: 实现 2-hop 路径检索器

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/SeedContextRetriever.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/PathRetriever.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/DefaultPathRetriever.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/DefaultPathRetrieverTest.java`

- [ ] **Step 1: 写 failing tests，固定 1-hop / 2-hop 扩展行为**

测试图至少包含：
- `Atlas -> depends_on -> GraphStore`
- `GraphStore -> owned_by -> KnowledgeGraphTeam`

断言：
- 能从 `Atlas` 找到 1-hop
- 能从 `Atlas` 找到 2-hop
- 简单回环不会重复进入结果
- 每层扩展 obey limit

- [ ] **Step 2: 实现种子上下文提取器**

要求：
- 先复用 `HybridQueryStrategy` 或 `MixQueryStrategy`
- 输出 `matchedEntities / matchedRelations / matchedChunks`
- 不在这里做路径推理

- [ ] **Step 3: 实现 `DefaultPathRetriever`**

行为要求：
- 从 topN 种子实体起步
- 使用 `GraphStore.findRelations(entityId)` 做邻接扩展
- 支持 1-hop 与 2-hop
- 去掉重复 relation 和简单循环
- 汇总所有涉及的 `supportingChunkIds`

- [ ] **Step 4: 运行路径检索测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.DefaultPathRetrieverTest"`
Expected: PASS

### Task 4: 实现路径评分器

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/PathScorer.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/DefaultPathScorer.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/DefaultPathScorerTest.java`

- [ ] **Step 1: 写 failing tests，锁定最小排序规则**

至少验证：
- 同 hop 下，证据更完整的路径得分更高
- 2-hop 路径有轻微长度惩罚，但不会把强证据路径永远压到 1-hop 后面
- 含重复节点的路径会降分

- [ ] **Step 2: 实现最小评分公式**

建议先用：
- 种子实体分数
- 关系分数均值
- `supportingChunkIds` 覆盖度
- hop 惩罚
- 环路惩罚

- [ ] **Step 3: 运行评分器测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.DefaultPathScorerTest"`
Expected: PASS

### Task 5: 实现多跳查询策略

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/MultiHopQueryStrategy.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/query/ReasoningContextAssembler.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/MultiHopQueryStrategyTest.java`

- [ ] **Step 1: 写 failing tests，固定多跳查询输出**

断言：
- `MULTI_HOP` 问题会触发路径检索
- 返回 context 中含有 `Reasoning Path 1 / Hop 1 / Hop 2`
- 仍然保留 fallback chunks，避免路径不完整时完全无上下文

- [ ] **Step 2: 实现 `ReasoningContextAssembler`**

输出结构固定为：
- Question
- Reasoning Path N
- Hop 1 / Hop 2
- 每跳 evidence
- Candidate Conclusion

- [ ] **Step 3: 实现 `MultiHopQueryStrategy`**

要求：
- 先拿 seed context
- 再做 path retrieve + rerank
- 只选前 `pathTopK` 条路径
- 返回一个兼容 `QueryContext` 的最终对象
- 明确把多跳结构写入 `QueryContext.assembledContext`
- 不假设 `QueryEngine` 会保留策略内部组装结果，后续还要配合引擎层保留 assembled context

- [ ] **Step 4: 运行策略测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.MultiHopQueryStrategyTest"`
Expected: PASS

### Task 6: 接入 QueryEngine 调度

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/MultiHopQueryEngineIntegrationTest.java`

- [ ] **Step 1: 写 failing integration test**

至少验证：
- `QueryIntentClassifier` 返回 `MULTI_HOP` 时，`QueryEngine` 走 `MultiHopQueryStrategy`
- 普通问题仍走现有策略
- 默认配置下旧测试不回归

- [ ] **Step 2: 修正 `QueryEngine` 对 assembled context 的覆盖逻辑**

要求：
- 第一阶段不扩展 `QueryContext` 结构；判定规则直接固定为：
  `resolvedQuery.multiHopEnabled() == true` 且当前命中的是 `MultiHopQueryStrategy`，并且 `retrievedContext.assembledContext()` 非空时，
  `QueryEngine` 直接保留这份 assembled context，不再用默认 `ContextAssembler` 覆盖
- 仍保留现有普通查询的 `ContextAssembler` 路径
- `onlyNeedContext / onlyNeedPrompt / stream / references` 四条分支都使用同一份最终上下文

- [ ] **Step 3: 在 `QueryEngine` 以最小改动接入多跳调度**

要求：
- 不破坏现有 mode 分流
- 仅在 `multiHopEnabled=true` 且分类命中时切入新策略
- 无法形成有效路径时 graceful fallback 到现有 context

- [ ] **Step 4: 在 `LightRag.newQueryEngine(...)` 完成装配**

明确创建与注入：
- `RuleBasedQueryIntentClassifier`
- `SeedContextRetriever`
- `DefaultPathRetriever`
- `DefaultPathScorer`
- `MultiHopQueryStrategy`
- 普通 `ContextAssembler`

要求：
- 装配后的 `QueryEngine` 既支持现有策略，也支持多跳策略
- 不把多跳依赖散落在外部调用方

- [ ] **Step 5: 跑 `QueryEngine` 相关测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.MultiHopQueryEngineIntegrationTest" --tests "io.github.lightragjava.query.*Query*"`
Expected: PASS

### Task 7: 实现按 hop 的答案合成器

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/synthesis/PathAwareAnswerSynthesizer.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/synthesis/PathAwareAnswerSynthesizerTest.java`

- [ ] **Step 1: 写 failing tests，锁定 prompt 结构**

至少验证 prompt/输入中包含：
- 用户问题
- Path 1
- Hop 1 / Hop 2
- 每跳 evidence
- “insufficient evidence” 弱结论分支

- [ ] **Step 2: 明确 `PathAwareAnswerSynthesizer` 的职责边界并写测试**

职责固定为：
- 输入：最终 `QueryRequest` + `ReasoningBundle` 或最终 reasoning context
- 输出：供 `QueryEngine` 使用的 system prompt context 段或完整 prompt 片段
- 不直接负责模型调用
- 不替代 `QueryResult`、`references`、`stream` 流程

测试必须覆盖：
- `onlyNeedPrompt` 分支能够拿到多跳 prompt
- `onlyNeedContext` 不会错误触发模型调用

- [ ] **Step 3: 实现最小 synthesizer**

要求：
- 优先使用结构化路径
- 路径缺口明显时显式降级
- 不能把无证据 hop 写成确定事实
- 在 `QueryEngine.buildSystemPrompt(...)` 路径中接入，而不是新增一个孤立未调用的类

- [ ] **Step 4: 运行合成器测试**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.synthesis.PathAwareAnswerSynthesizerTest"`
Expected: PASS

### Task 8: 跑完整回归并补文档

**Files:**
- Modify: `README.md`
- Inspect: `lightrag-core/src/test/java/io/github/lightragjava/**`

- [ ] **Step 1: 跑多跳相关目标测试集**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.RuleBasedQueryIntentClassifierTest" --tests "io.github.lightragjava.query.DefaultPathRetrieverTest" --tests "io.github.lightragjava.query.DefaultPathScorerTest" --tests "io.github.lightragjava.query.MultiHopQueryStrategyTest" --tests "io.github.lightragjava.query.MultiHopQueryEngineIntegrationTest" --tests "io.github.lightragjava.synthesis.PathAwareAnswerSynthesizerTest"`
Expected: PASS

- [ ] **Step 2: 跑一轮 query/indexing 回归**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.query.*" --tests "io.github.lightragjava.indexing.*"`
Expected: PASS

- [ ] **Step 3: README 补充多跳能力说明**

至少写清：
- 多跳问题如何识别
- 新增配置项是什么意思
- 当前只支持到 2-hop
- 路径不足时的降级行为

- [ ] **Step 4: 分批提交**

建议提交粒度：
- `feat(query): add query intent classification for multi-hop routing`
- `feat(query): add two-hop path retrieval and scoring`
- `feat(query): add path-aware multi-hop synthesis`

## Acceptance Criteria

- `QueryEngine` 能区分普通问题与多跳问题
- 2-hop 图路径可以从现有 `GraphStore` 里稳定检索出来
- 最终上下文包含显式 `Hop 1 / Hop 2` 结构，而不是散乱证据
- 无完整路径时会保守降级，不会硬编造两跳结论
- 新增测试全部通过，既有 query/indexing 测试不回归
