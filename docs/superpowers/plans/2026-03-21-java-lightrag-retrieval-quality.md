# Retrieval Quality Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `lightrag-core` 建立最小可控的 retrieval-quality 演进面，让 chunking、keyword extraction、rerank 和评测基线能被显式配置和回归验证。

**Architecture:** 先不重写整条查询链路，而是在现有 `QueryEngine`、indexing pipeline 和 evaluation CLI 周围补齐几个稳定的配置接点，再用固定数据集和 RAGAS batch CLI 形成可重复的质量基线。第一阶段优先解决“能配置、能评估、能比较”，不做过深算法重构。

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing RAGAS evaluation CLI, lightrag-core

---

## Scope Guardrails

- 不引入新的在线服务或前端
- 不改 public query API 的核心语义
- 先做可配置和可评估，再做质量优化策略本身
- 每一项质量开关都要有对应测试或基线验证

## File Structure

- Create:
  - `docs/superpowers/plans/2026-03-21-java-lightrag-retrieval-quality.md`
- Modify later:
  - `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
  - `lightrag-core/src/main/java/io/github/lightragjava/query/**`
  - `lightrag-core/src/main/java/io/github/lightragjava/indexing/**`
  - `lightrag-core/src/main/java/io/github/lightragjava/evaluation/**`
  - `lightrag-core/src/test/java/io/github/lightragjava/**`
  - `README.md`

### Task 1: 盘点质量瓶颈并锁定最小配置面

**Files:**
- Inspect: `lightrag-core/src/main/java/io/github/lightragjava/query/**`
- Inspect: `lightrag-core/src/main/java/io/github/lightragjava/indexing/**`
- Inspect: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/**`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/**`

- [ ] **Step 1: 盘点当前质量敏感点**

重点记录：
- chunk 切分策略
- keyword extraction 触发条件
- rerank 开关与候选窗口
- entity/relation merge 的确定性规则
- evaluation CLI 当前输入输出格式

- [ ] **Step 2: 写一份 focused checklist**

输出一个最小 checklist：
- 哪些点已经可配
- 哪些点写死在实现里
- 哪些点最值得先暴露为配置

### Task 2: 补齐 pipeline 配置入口

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/query/**`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/**`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/**`

- [ ] **Step 1: 先写 failing tests**

至少覆盖：
- 自定义 chunk 策略或 chunk size 参数能进配置
- keyword extraction 可禁用或替换
- rerank candidate window 可配置

- [ ] **Step 2: 最小实现**

原则：
- builder 增加少量明确配置项
- config 只承载参数，不承载复杂策略编排
- 默认值保持现有行为不回归

- [ ] **Step 3: 回归核心 query/indexing 测试**

Run: `./gradlew :lightrag-core:test --tests io.github.lightragjava.query.* --tests io.github.lightragjava.indexing.*`
Expected: PASS

### Task 3: 建立固定质量基线

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationCli.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/**`
- Modify: `README.md`

- [ ] **Step 1: 固定一个最小评测数据集入口**

要求：
- 能在本地重复跑
- 输出 answer、contexts、references 与关键参数
- 能区分 baseline 与 candidate run

- [ ] **Step 2: 写最小回归验证**

至少验证：
- CLI 输出结构稳定
- 参数变化会反映到输出 metadata
- 默认配置下结果可重复

- [ ] **Step 3: 文档化评测流程**

README 补充：
- 如何跑单条 evaluation
- 如何跑 batch evaluation
- 如何比较参数调整前后的结果

## Acceptance Criteria

- retrieval pipeline 至少有 2-3 个关键质量旋钮可以显式配置
- 默认行为不回归
- 有一条固定的 evaluation/batch evaluation 回归路径
- README 能指导下一轮质量优化工作直接落地
