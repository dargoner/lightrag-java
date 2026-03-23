# Java LightRAG P0 Service Parity Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有 `lightrag-java` 的核心 SDK 能力补齐到一个可直接作为轻量服务端使用的 P0 水平，覆盖完整查询参数、图管理接口、文档状态/删除接口，以及对应的 Spring Boot 配置与测试。

**Architecture:** 保持 `lightrag-core` 继续做纯 SDK，所有 P0 服务化工作收敛在 `lightrag-spring-boot-demo` 与 `lightrag-spring-boot-starter`。HTTP 层只做参数映射、错误转换和异步编排，不把业务逻辑复制进 Controller；复用 `LightRag` 已有的查询、图管理、删除、状态查询 API。

**Tech Stack:** Java 17, Spring Boot 3.3, Gradle, JUnit 5, AssertJ, MockMvc

---

## File Structure

- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/ApiExceptionHandler.java`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Modify: `README.md`

## Scope Guardrails

- 本计划只做 **P0 服务层补齐**，不包含文件上传、目录扫描、WebUI、workspace、多存储扩展、Ollama 兼容 API。
- 不在 `lightrag-core` 中重复实现 HTTP 语义；若发现 SDK 已缺字段或返回结构，再最小增补 core。
- 所有新增接口先写 MockMvc 测试，再写实现。

### Task 1: 补齐 Query API 参数映射与返回形态

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 写失败测试，覆盖 P0 查询参数**

```java
mockMvc.perform(post("/query")
        .contentType(APPLICATION_JSON)
        .content("""
            {
              "query": "Who works with Bob?",
              "mode": "MIX",
              "topK": 5,
              "chunkTopK": 8,
              "responseType": "Bullet Points",
              "enableRerank": false,
              "includeReferences": true,
              "onlyNeedContext": false,
              "onlyNeedPrompt": false,
              "stream": false,
              "userPrompt": "Answer briefly.",
              "hlKeywords": ["organization"],
              "llKeywords": ["Alice"],
              "conversationHistory": [{"role": "user", "content": "Earlier"}]
            }
            """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.references").isArray());
```

- [ ] **Step 2: 运行失败测试确认当前 demo 没暴露这些字段**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`  
Expected: FAIL，提示请求字段未映射或响应结构不满足断言

- [ ] **Step 3: 扩展 `QueryPayload` 与 `QueryResponse`**

实现方向：

```java
record QueryPayload(
    String query,
    QueryMode mode,
    Integer topK,
    Integer chunkTopK,
    Integer maxEntityTokens,
    Integer maxRelationTokens,
    Integer maxTotalTokens,
    String responseType,
    Boolean enableRerank,
    Boolean onlyNeedContext,
    Boolean onlyNeedPrompt,
    Boolean includeReferences,
    Boolean stream,
    String userPrompt,
    List<String> hlKeywords,
    List<String> llKeywords,
    List<ConversationMessagePayload> conversationHistory
) {}
```

- [ ] **Step 4: 把 HTTP 请求完整映射到 `QueryRequest.builder()`**

要求：
- 所有可选值都回退到 `QueryRequest` 默认值
- `conversationHistory` 转成 `ChatModel.ChatRequest.ConversationMessage`
- `stream=true` 时先返回 400 或显式说明“当前 demo 仅支持 buffered `/query`”，不要伪造流式
- `onlyNeedContext` / `onlyNeedPrompt` 沿用 core 现有语义

- [ ] **Step 5: 增加参数校验与错误响应测试**

重点覆盖：
- `query` 为空
- `topK/chunkTopK/max*Tokens <= 0`
- `stream=true` 的拒绝路径

- [ ] **Step 6: 跑测试并提交**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`  
Expected: PASS

Commit:

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java
git commit -m "feat: expand demo query api surface"
```

### Task 2: 暴露图管理 API

**Files:**
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`
- Optional Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 写失败测试，覆盖 create/edit/merge/delete 流程**

```java
mockMvc.perform(post("/graph/entities").contentType(APPLICATION_JSON).content("""
    {"name":"Tesla","type":"ORGANIZATION","description":"EV maker","aliases":["Tesla Inc."]}
    """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.name").value("Tesla"));
```

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.GraphControllerTest`  
Expected: FAIL，提示无映射或返回 404

- [ ] **Step 3: 新建 `GraphController`，只做 DTO -> core request 映射**

建议接口：
- `POST /graph/entities`
- `PUT /graph/entities`
- `POST /graph/entities/merge`
- `DELETE /graph/entities/{entityName}`
- `POST /graph/relations`
- `PUT /graph/relations`
- `DELETE /graph/relations`

DTO 直接映射到 core：
- `CreateEntityRequest`
- `EditEntityRequest`
- `MergeEntitiesRequest`
- `CreateRelationRequest`
- `EditRelationRequest`

- [ ] **Step 4: 为 `IllegalArgumentException` / `NoSuchElementException` 建立统一错误转换**

目标：
- 参数不合法 -> `400`
- 目标不存在 -> `404`
- 未知异常 -> `500`

- [ ] **Step 5: 增加关系重命名、实体合并、文档删除后图删除的测试用例**

至少验证：
- 合并后旧实体不可再查
- 删除实体后相关关系消失
- 删除关系不会删除源文档

- [ ] **Step 6: 跑测试并提交**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.GraphControllerTest`  
Expected: PASS

Commit:

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/ApiExceptionHandler.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java
git commit -m "feat: add demo graph management api"
```

### Task 3: 补齐文档状态、删除与异步 ingest 编排

**Files:**
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 写失败测试，定义 P0 文档接口**

目标接口：
- `POST /documents/ingest` -> `202 Accepted`，返回 `jobId`
- `GET /documents/jobs/{jobId}` -> 返回任务状态
- `GET /documents/status` -> 列出所有文档状态
- `GET /documents/status/{documentId}` -> 返回单文档状态
- `DELETE /documents/{documentId}` -> 删除文档并触发 core 重建

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`  
Expected: FAIL，提示接口缺失

- [ ] **Step 3: 引入最小异步编排服务 `IngestJobService`**

实现边界：
- 使用单机内存 `ConcurrentHashMap<String, JobState>`
- `CompletableFuture.runAsync(...)` 或 Spring `TaskExecutor`
- job 只跟踪 demo 层任务状态：`PENDING/RUNNING/SUCCEEDED/FAILED`
- 文档级状态仍以 core 的 `listDocumentStatuses()` / `getDocumentStatus()` 为准

```java
record IngestJobStatus(String jobId, String status, String errorMessage) {}
```

- [ ] **Step 4: 修改 `DocumentController` 返回异步任务响应**

要求：
- 不再同步阻塞直到 ingest 完成
- 接口校验空文档列表
- 保留现有请求体结构，避免破坏最小 demo 使用方式

- [ ] **Step 5: 增加状态与删除控制器**

要求：
- `GET /documents/status` 直接返回 `LightRag.listDocumentStatuses()`
- `GET /documents/status/{documentId}` 直接返回 `LightRag.getDocumentStatus(documentId)`
- `DELETE /documents/{documentId}` 调用 `LightRag.deleteByDocumentId(documentId)`

- [ ] **Step 6: 追加失败场景测试**

覆盖：
- 不存在的 `jobId`
- 不存在的 `documentId`
- ingest 后能查询到 `PROCESSED`
- 删除后文档状态查询返回 `404`

- [ ] **Step 7: 跑测试并提交**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`  
Expected: PASS

Commit:

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java
git commit -m "feat: add demo document status and async ingest api"
```

### Task 4: 补齐 starter 配置项、Demo 默认配置与文档

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml`
- Modify: `README.md`

- [ ] **Step 1: 写失败测试，定义 starter 需要暴露的 P0 配置**

建议新增配置：
- `lightrag.query.default-mode`
- `lightrag.query.default-top-k`
- `lightrag.query.default-chunk-top-k`
- `lightrag.query.default-response-type`
- `lightrag.demo.async-ingest-enabled`

测试目标：
- context 能正常绑定这些属性
- 未配置时有明确默认值

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`  
Expected: FAIL，提示属性缺失或未绑定

- [ ] **Step 3: 扩展 `LightRagProperties`，仅加入 P0 需要的配置**

注意：
- 不要把上游 `.env` 体系完整照搬
- 保持 starter API 精简，先服务 demo/controller 默认行为

- [ ] **Step 4: 在 `application.yml` 和 `README.md` 更新新的 demo API 面**

README 至少补充：
- 新增图管理接口
- 新增文档状态与删除接口
- `/query` 支持的字段
- 明确 `stream=true` 尚未在 demo controller 中落地专门流式端点

- [ ] **Step 5: 跑模块测试与全量回归**

Run: `./gradlew :lightrag-spring-boot-starter:test :lightrag-spring-boot-demo:test`  
Expected: PASS

- [ ] **Step 6: 最终提交**

```bash
git add lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java \
        lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java \
        lightrag-spring-boot-demo/src/main/resources/application.yml \
        README.md
git commit -m "docs: document p0 demo service api"
```

## Final Verification Checklist

- [ ] `./gradlew :lightrag-spring-boot-starter:test`
- [ ] `./gradlew :lightrag-spring-boot-demo:test`
- [ ] `./gradlew test`
- [ ] 手工验证 `POST /query`
- [ ] 手工验证图管理接口
- [ ] 手工验证异步 ingest + 文档状态 + 删除链路

## Acceptance Criteria

- Demo HTTP 层能覆盖 core 已有的 P0 查询能力，不再只暴露最小 `query/mode/topK/chunkTopK`
- 图管理 API 可完成实体/关系创建、编辑、合并、删除
- 文档状态与文档删除能力可通过 HTTP 访问
- ingest 改为异步任务编排，客户端可以轮询任务状态
- starter 与 README 能说明并支撑这些新增能力

