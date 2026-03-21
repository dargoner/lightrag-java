# Java LightRAG Ops Observability Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `lightrag-spring-boot-demo` 增加最小运维可观测性：`/actuator/health`、`/actuator/info` 以及面向 LightRag 的健康详情。

**Architecture:** 保持 demo 仍是轻量服务，不引入管理端 UI；直接复用 Spring Boot Actuator 的标准端点，再增加一个 `LightRagHealthIndicator` 和少量 `info` 元数据，暴露模型配置、storage 类型、async ingest 开关等最小排障信息。测试优先用 MockMvc / SpringBootTest 锁定 HTTP 契约，不做复杂指标采集。

**Tech Stack:** Java 17, Spring Boot 3.3, Actuator, Gradle, JUnit 5, MockMvc

---

## File Structure

- Modify: `lightrag-spring-boot-demo/build.gradle.kts`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/LightRagHealthIndicator.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/LightRagInfoContributor.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/ActuatorEndpointsTest.java`
- Modify: `README.md`
- Modify: `README_zh.md`

### Task 1: 用测试锁定 actuator 契约

**Files:**
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/ActuatorEndpointsTest.java`

- [ ] **Step 1: 写 health endpoint 失败测试**

覆盖：
- `GET /actuator/health` 返回 `200`
- `status = UP`
- 返回 `components.lightrag`

- [ ] **Step 2: 写 info endpoint 失败测试**

覆盖：
- `GET /actuator/info` 返回 `200`
- 暴露 `lightrag.storage.type`
- 暴露 `lightrag.demo.asyncIngestEnabled`

- [ ] **Step 3: 运行定向测试确认红灯**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.ActuatorEndpointsTest`
Expected: FAIL，提示 actuator 或 lightrag 组件尚未暴露

### Task 2: 最小实现 health/info

**Files:**
- Modify: `lightrag-spring-boot-demo/build.gradle.kts`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/LightRagHealthIndicator.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/LightRagInfoContributor.java`

- [ ] **Step 1: 引入 actuator 依赖**

依赖：
- `org.springframework.boot:spring-boot-starter-actuator`

- [ ] **Step 2: 暴露最小 management 端点**

配置：
- 暴露 `health`、`info`
- health details 设为 `always`

- [ ] **Step 3: 实现 `LightRagHealthIndicator`**

详情字段至少包括：
- `storageType`
- `asyncIngestEnabled`
- `chatModelConfigured`
- `embeddingModelConfigured`

- [ ] **Step 4: 实现 `LightRagInfoContributor`**

返回：
- `lightrag.storage.type`
- `lightrag.demo.asyncIngestEnabled`
- `lightrag.query.defaultMode`

- [ ] **Step 5: 跑定向测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.ActuatorEndpointsTest`
Expected: PASS

### Task 3: 做模块回归并更新文档

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: 更新 README**

补充：
- demo 现在提供 actuator health/info
- 端点地址
- 最小用途说明

- [ ] **Step 2: 跑 demo 模块全量测试**

Run: `./gradlew :lightrag-spring-boot-demo:test`
Expected: PASS

- [ ] **Step 3: 跑自检**

Run:
- `git diff --check`
- `git status --short`

Expected:
- 无空白错误
- 仅包含本批次相关文件
