# Java LightRAG Job Management Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `lightrag-spring-boot-demo` 补齐最小 ingest job 可观察性闭环：分页列表、时间线字段和失败详情。

**Architecture:** 保持 job 状态仍由 demo 内存服务 `IngestJobService` 承担，不引入持久化队列；控制器只负责请求映射和错误翻译，业务时间线、失败信息和分页视图都收敛在 `IngestJobService`。新增 API 与现有 `/documents/ingest`、`/documents/jobs/{jobId}` 风格保持一致，先覆盖 demo service 的接入和排障需要，不伪造当前实现无法可靠支持的 cancel/retry 语义。

**Tech Stack:** Java 17, Spring Boot 3.3, Gradle, MockMvc, JUnit 5

---

## File Structure

- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
- Modify: `README.md`
- Modify: `README_zh.md`
- Create later if needed: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/IngestJobServiceTest.java`

### Task 1: 定义 job observability API 红灯

**Files:**
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`

- [ ] **Step 1: 写分页列表接口失败测试**

覆盖：
- `GET /documents/jobs?page=0&size=2`
- 返回 `items`, `page`, `size`, `total`
- 最新 job 优先
- 列表项包含 `createdAt`, `startedAt`, `finishedAt`

- [ ] **Step 2: 运行列表测试并确认红灯**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
Expected: FAIL，提示缺少 `/documents/jobs` 或响应结构不匹配

- [ ] **Step 3: 写 failure detail 失败测试**

覆盖：
- 失败 job 的 `errorMessage` 出现在单 job 查询和列表项里
- `FAILED` job 的时间线字段合理返回

- [ ] **Step 4: 再次运行测试并确认仍是预期红灯**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
Expected: FAIL，且失败点只来自新增生命周期断言

### Task 2: 在服务层实现 job 状态机

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Create if needed: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/IngestJobServiceTest.java`

- [ ] **Step 1: 为 JobSnapshot 增加最小业务字段**

字段：
- `documentCount`
- `createdAt`
- `startedAt`
- `finishedAt`
- `errorMessage`
- 必要的内部排序键

- [ ] **Step 2: 实现列表查询**

要求：
- 支持 `page`、`size`
- 稳定排序，最新 job 优先

- [ ] **Step 3: 补齐时间线与失败详情写入**

要求：
- `createdAt` 在 submit 时写入
- `startedAt` 在真正执行前写入
- `finishedAt` 在 `SUCCEEDED` / `FAILED` 时写入
- 失败消息保持 strip 后输出

- [ ] **Step 4: 运行定向测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
Expected: PASS

### Task 3: 暴露 REST API 与错误语义

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`

- [ ] **Step 1: 增加分页列表 endpoint**

接口：
- `GET /documents/jobs?page=0&size=20`

- [ ] **Step 2: 扩展单 job 响应字段**

字段：
- `documentCount`
- `createdAt`
- `startedAt`
- `finishedAt`

- [ ] **Step 3: 保持错误映射一致**

要求：
- job 不存在返回 `404`
- 参数非法返回 `400`

- [ ] **Step 4: 运行定向测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: PASS

### Task 4: 更新文档并做回归验证

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: 更新 demo service API 文档**

补充：
- job list 接口
- 分页参数
- 时间线字段
- failure detail 语义

- [ ] **Step 2: 运行完整 demo 模块验证**

Run: `./gradlew :lightrag-spring-boot-demo:test`
Expected: PASS

- [ ] **Step 3: 运行 diff/check 自检**

Run:
- `git diff --check`
- `git status --short`

Expected:
- 无空白错误
- 仅包含本批次相关文件
