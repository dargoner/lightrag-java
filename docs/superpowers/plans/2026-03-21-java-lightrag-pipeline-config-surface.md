# Pipeline Config Surface Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 扩大 SDK 与 starter 对 pipeline 的可配置面，优先外提 chunking、自动关键词提取与 rerank 候选扩展参数。

**Architecture:** 保持 `LightRagConfig` 记录型配置为内部汇总对象，不破坏已有公开构造形状；对外统一通过 `LightRagBuilder` 与 starter `LightRagProperties` 暴露新参数，再由 `LightRag` 组装进 indexing/query pipeline。

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5

---

### Task 1: 扩展 core builder

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: 先补 builder 测试，覆盖 chunker / keyword extraction / rerank multiplier**
- [ ] **Step 2: 给 builder 增加 `chunker(...)`、`automaticQueryKeywordExtraction(...)`、`rerankCandidateMultiplier(...)`**
- [ ] **Step 3: 把参数接线到 `LightRagConfig`、`IndexingPipeline`、`QueryEngine`**
- [ ] **Step 4: 继续保持默认行为与当前 `main` 一致**

### Task 2: 扩展 starter 配置

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: 暴露 `lightrag.indexing.chunking.*`**
- [ ] **Step 2: 暴露 `lightrag.query.automatic-keyword-extraction` 与 `lightrag.query.rerank-candidate-multiplier`**
- [ ] **Step 3: 在自动装配中注入 `Chunker` 和 query 开关**
- [ ] **Step 4: 跑 starter 定向测试**

### Task 3: 文档与回归

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/query/QueryKeywordExtractorTest.java`

- [ ] **Step 1: 更新 SDK/starter 配置示例**
- [ ] **Step 2: 补充默认值和兼容性说明**
- [ ] **Step 3: 运行 `./gradlew :lightrag-core:test :lightrag-spring-boot-starter:test`**
