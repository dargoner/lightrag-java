# Java LightRAG Streaming HTTP Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `lightrag-spring-boot-demo` 增加正式的流式查询 HTTP 端点，把 `lightrag-core` 已支持的 `QueryRequest.stream(true)` 暴露给外部客户端。

**Architecture:** 保持 `lightrag-core` 不改 HTTP 语义，流式协议只落在 demo 层。先把 `/query` 的请求 DTO、校验和 `QueryRequest` 映射抽成共享组件，再新增独立的 `POST /query/stream` SSE 端点，避免把 buffered 和 streaming 逻辑揉进同一个 controller。SSE 响应统一发 `meta`、`chunk`、`complete`、`error` 事件，并在 buffered fallback 场景发单次 `answer` 事件。

**Tech Stack:** Java 17, Spring Boot 3.3, Spring MVC `SseEmitter`, Gradle, JUnit 5, AssertJ, MockMvc

---

## File Structure

- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryRequestMapper.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryStreamService.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`
- Modify: `README.md`

## Scope Guardrails

- 本计划只做 demo HTTP 流式协议，不修改 `lightrag-core` 的 `QueryEngine`、`QueryResult` 或模型适配器
- 只支持 `text/event-stream`，不同时引入 `NDJSON` 和 WebSocket
- 保持现有 buffered `/query` 行为不变，不把流式逻辑塞回 `/query`
- `onlyNeedContext` / `onlyNeedPrompt` 继续沿用 core 现有语义，即使在 `/query/stream` 上也允许走 buffered fallback

## Protocol Decision

推荐端点：

- `POST /query/stream`
- `Content-Type: application/json`
- `Accept: text/event-stream`

推荐事件格式：

```text
event: meta
data: {"streaming":true,"contexts":[...],"references":[...]}

event: chunk
data: {"text":"Alice "}

event: chunk
data: {"text":"works with Bob."}

event: complete
data: {"done":true}
```

Buffered fallback 示例：

```text
event: meta
data: {"streaming":false,"contexts":[...],"references":[...]}

event: answer
data: {"answer":"assembled prompt or context"}

event: complete
data: {"done":true}
```

错误示例：

```text
event: error
data: {"message":"streaming query failed"}
```

### Task 1: 抽取共享查询映射层

**Files:**
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryRequestMapper.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`

- [ ] **Step 1: 先写失败测试，约束 `/query` 仍走共享映射层**

测试目标：
- 现有 `/query` 语义不回退
- 新 mapper 能复用当前 `QueryPayload`、`ConversationMessagePayload`、校验逻辑

建议新增断言：

```java
assertThat(request.stream()).isFalse();
assertThat(request.onlyNeedContext()).isTrue();
assertThat(request.includeReferences()).isTrue();
```

- [ ] **Step 2: 运行失败测试确认抽取前无共享组件**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
Expected: FAIL，提示新 mapper 或新结构尚不存在

- [ ] **Step 3: 新建 `QueryRequestMapper` 并迁移 DTO/校验**

实现方向：

```java
final class QueryRequestMapper {
    QueryRequest toBufferedRequest(QueryPayload payload) { ... }
    QueryRequest toStreamingRequest(QueryPayload payload) { ... }
    void validate(QueryPayload payload, boolean streamingEndpoint) { ... }

    record QueryPayload(...) {}
    record ConversationMessagePayload(...) {}
}
```

要求：
- 共享 `QueryPayload` 和 `ConversationMessagePayload`
- `/query` 调用 `toBufferedRequest(...)`
- `/query/stream` 调用 `toStreamingRequest(...)`
- 除 `stream` 默认值外，其余字段映射规则保持一致

- [ ] **Step 4: 最小修改 `QueryController`**

要求：
- `QueryController` 只负责接收请求、调用 mapper、调用 `lightRag.query(...)`
- 保持当前 JSON 响应结构不变
- 继续拒绝 `/query` 上的 `stream=true`

- [ ] **Step 5: 跑测试确认 buffered 行为未回归**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
Expected: PASS

- [ ] **Step 6: 提交共享映射重构**

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryRequestMapper.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java
git commit -m "refactor: share demo query request mapping"
```

### Task 2: 增加 SSE 流式查询端点

**Files:**
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryStreamService.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java`

- [ ] **Step 1: 写失败测试，定义 SSE 协议**

最小测试目标：
- `POST /query/stream` 返回 `200`
- `Content-Type` 是 `text/event-stream`
- 第一个事件是 `meta`
- 流式结果返回至少一个 `chunk` 事件和一个 `complete` 事件

示例断言方向：

```java
mockMvc.perform(post("/query/stream")
        .contentType(APPLICATION_JSON)
        .accept(TEXT_EVENT_STREAM)
        .content("{\"query\":\"Who works with Bob?\",\"mode\":\"MIX\"}"))
    .andExpect(request().asyncStarted());
```

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.StreamingQueryControllerTest`
Expected: FAIL，提示无映射或返回的不是异步 SSE

- [ ] **Step 3: 新建 `QueryStreamService`，封装 emitter 生命周期**

建议职责：

```java
final class QueryStreamService {
    SseEmitter stream(QueryResult result) { ... }
}
```

要求：
- 发送 `meta` 事件时携带 `contexts`、`references`、`streaming`
- `QueryResult.streaming() == true` 时循环 `answerStream()`
- `QueryResult.streaming() == false` 时发送单个 `answer` 事件
- 始终显式关闭 `QueryResult`
- 流中抛异常时发送 `error` 事件并完成 emitter

- [ ] **Step 4: 新建 `StreamingQueryController`**

建议接口：

```java
@PostMapping(path = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter stream(@RequestBody QueryPayload payload) { ... }
```

要求：
- 使用 `QueryRequestMapper.toStreamingRequest(...)`
- 启动前的校验异常仍交给 `ApiExceptionHandler`
- 只处理 HTTP/SSE 编排，不复制 query 构建细节

- [ ] **Step 5: 补 buffered fallback 测试**

覆盖：
- `onlyNeedContext(true)` 在 `/query/stream` 上返回 `meta + answer + complete`
- `onlyNeedPrompt(true)` 同样走单次 answer 事件
- `BYPASS + stream(true)` 仍按 chunk 事件输出

- [ ] **Step 6: 跑流式 controller 测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.StreamingQueryControllerTest`
Expected: PASS

- [ ] **Step 7: 提交流式端点实现**

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryStreamService.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/StreamingQueryControllerTest.java
git commit -m "feat: add demo streaming query endpoint"
```

### Task 3: 增加 demo 集成验证

**Files:**
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 写失败集成测试，覆盖 ingest 后流式查询**

目标：
- 复用现有 ingest job 成功链路
- 增加 `/query/stream` 端到端断言

建议测试方向：

```java
var mvcResult = mockMvc.perform(post("/query/stream")
        .contentType(APPLICATION_JSON)
        .accept(TEXT_EVENT_STREAM)
        .content("{\"query\":\"Who works with Bob?\",\"mode\":\"MIX\"}"))
    .andExpect(request().asyncStarted())
    .andReturn();
```

- [ ] **Step 2: 给测试 ChatModel 增加 `stream(...)` 实现**

实现方向：

```java
@Bean
ChatModel chatModel() {
    return new ChatModel() {
        @Override
        public String generate(ChatRequest request) { ... }

        @Override
        public CloseableIterator<String> stream(ChatRequest request) {
            return CloseableIterator.of(List.of("Alice ", "works with Bob."));
        }
    };
}
```

- [ ] **Step 3: 断言事件内容**

至少验证：
- 存在 `event: meta`
- 存在 `event: chunk`
- 存在 `event: complete`
- chunk 文本能拼出期望答案

- [ ] **Step 4: 跑 demo 集成测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: PASS

- [ ] **Step 5: 提交集成测试**

```bash
git add lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java
git commit -m "test: cover demo streaming query flow"
```

### Task 4: 文档与最终验证

**Files:**
- Modify: `README.md`
- Optional Modify: `docs/superpowers/plans/2026-03-21-java-lightrag-feature-roadmap.md`

- [ ] **Step 1: 更新 README 的 demo API 部分**

至少补充：
- `POST /query/stream`
- SSE 事件类型：`meta`、`chunk`、`answer`、`complete`、`error`
- `/query` 继续只支持 buffered
- `/query/stream` 对 `onlyNeedContext` / `onlyNeedPrompt` 的 fallback 语义

- [ ] **Step 2: 跑模块回归**

Run: `./gradlew :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 3: 跑 starter + demo 回归**

Run: `./gradlew :lightrag-spring-boot-starter:test :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 4: 人工 smoke check**

建议手工验证：

```bash
curl -N -X POST http://127.0.0.1:8080/query/stream \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"query":"Who works with Bob?","mode":"MIX"}'
```

Expected:
- 首先输出 `event: meta`
- 然后输出一个或多个 `event: chunk`
- 最后输出 `event: complete`

- [ ] **Step 5: 最终提交**

```bash
git add README.md \
        docs/superpowers/plans/2026-03-21-java-lightrag-streaming-http.md
git commit -m "docs: document demo streaming query plan"
```

## Acceptance Criteria

- demo 层新增独立 `POST /query/stream` SSE 端点
- `/query` 保持 buffered-only，不与 streaming 语义混淆
- 请求映射和校验逻辑在 buffered 与 streaming 两端复用
- `/query/stream` 能稳定输出 `meta/chunk/complete` 事件，并在 buffered fallback 时输出 `answer`
- README 与测试都覆盖新的 demo 流式链路
