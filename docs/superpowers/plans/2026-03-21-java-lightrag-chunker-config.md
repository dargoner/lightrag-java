# Chunker Config Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Java SDK 和 Spring Boot Starter 支持可配置的 chunker，而不是把固定窗口分块参数写死在索引流水线里。

**Architecture:** 在 `lightrag-core` 暴露 builder 级 `Chunker` 扩展点，并保持默认实现仍然是 `FixedWindowChunker(1000, 100)`。在 starter 中新增 `lightrag.indexing.chunking.*` 配置，自动装配默认 fixed-window chunker，demo 和下游应用都复用同一入口。

**Tech Stack:** Java 17, Gradle, JUnit 5, Spring Boot 3.3

---

### Task 1: Builder 级 chunker 扩展点

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] Step 1: 接手并整理现有 `LightRagBuilderTest` 里的 chunker 失败测试
- [ ] Step 2: 运行 `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`，确认当前因为 `builder.chunker(...)` 缺失而失败
- [ ] Step 3: 最小实现 `LightRagBuilder -> LightRagConfig -> LightRag -> IndexingPipeline` 的 `Chunker` 透传
- [ ] Step 4: 再次运行 `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`，确认自定义分块和 `chunker(null)` 校验通过
- [ ] Step 5: 保持默认行为回退到 `FixedWindowChunker(1000, 100)`，不改变未配置场景

### Task 2: Starter 固定窗口分块配置

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] Step 1: 接手并整理现有 `LightRagAutoConfigurationTest` 里的 chunker 失败测试
- [ ] Step 2: 运行 `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`，确认当前没有 `Chunker` bean 和 chunking 绑定
- [ ] Step 3: 增加 `lightrag.indexing.chunking.window-size` / `overlap`，自动装配默认 `FixedWindowChunker`
- [ ] Step 4: 确保用户自定义 `Chunker` bean 可覆盖 starter 默认 bean
- [ ] Step 5: 覆盖非法参数启动失败场景，保证 `overlap >= window-size` 时快速报错
- [ ] Step 6: 再次运行 `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`，确认属性绑定、默认值和覆盖规则

### Task 3: 文档与回归

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`

- [ ] Step 1: 更新 SDK builder、starter YAML 和 demo 默认配置示例
- [ ] Step 2: 运行 `./gradlew :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`
- [ ] Step 3: 运行 `git diff --check`
- [ ] Step 4: 提交 `feat: add configurable chunker support`
