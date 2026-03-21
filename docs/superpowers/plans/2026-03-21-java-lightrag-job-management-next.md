# Job Management Next Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 demo ingest job 补齐 `cancel / retry` 闭环，并保持现有异步 ingest 协议兼容。

**Architecture:** 在 `IngestJobService` 内补一层可重试、可取消的作业状态机，controller 只负责 HTTP 映射与错误翻译。取消优先支持 `PENDING/RUNNING`，重试只允许失败或已取消任务复用原始文档重新生成新 job。

**Tech Stack:** Java 17, Spring Boot 3.3, JUnit 5, MockMvc

---

### Task 1: 扩展作业状态模型

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`

- [ ] **Step 1: 先写失败测试，覆盖 cancel/retry 状态边界**
- [ ] **Step 2: 扩展 `JobState`，保存原始 `documents`、时间戳与终止原因**
- [ ] **Step 3: 新增 `CANCELLED` 状态和取消原因，保证状态流转幂等**
- [ ] **Step 4: 为 retry 生成新 job，并保留 `retriedFromJobId` 关联**
- [ ] **Step 5: 跑定向测试，确认状态机通过**

### Task 2: 暴露 HTTP 接口

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/ApiExceptionHandler.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`

- [ ] **Step 1: 新增 `POST /documents/jobs/{jobId}/cancel`**
- [ ] **Step 2: 新增 `POST /documents/jobs/{jobId}/retry`**
- [ ] **Step 3: 非法状态返回 `409 Conflict`，缺失 job 返回 `404`**
- [ ] **Step 4: 在 job 详情和列表响应中补 `cancellable/retriable/retriedFromJobId`**
- [ ] **Step 5: 跑 controller 测试验证协议**

### Task 3: 文档与回归

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: 更新 demo API 列表与示例请求**
- [ ] **Step 2: 补充 cancel/retry 的语义说明**
- [ ] **Step 3: 运行 `./gradlew :lightrag-spring-boot-demo:test`**
- [ ] **Step 4: 运行 `./gradlew test` 做全量回归**
