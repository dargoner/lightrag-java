# Workspace Isolation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 demo service 增加最小可用的 workspace 隔离能力，让导入、查询、图管理和状态查询都显式绑定 workspace，避免不同 workspace 之间数据串扰。

**Architecture:** 在 `starter` 中补齐 workspace 配置和可复用的 `LightRag` 工厂；在 `demo` 中通过 `X-Workspace-Id` 解析 workspace，并路由到按 workspace 缓存的 `LightRag` 实例。初版只保证 `IN_MEMORY` 和 `POSTGRES` 两类存储隔离；`POSTGRES_NEO4J` 保持现有默认 workspace 行为，但不支持非默认 workspace 扩展。

**Tech Stack:** Java 17, Spring Boot 3.3, Gradle, JUnit 5, MockMvc, Spring Boot Test, PostgreSQL

---

## Scope Guardrails

- 只做 **workspace 隔离最小闭环**，不做用户/权限系统
- 只支持基于请求头 `X-Workspace-Id` 的路由
- 允许缺省走 `default` workspace，但所有 demo controller 都必须显式解析 workspace
- 只保证 `IN_MEMORY` 与 `POSTGRES` 的隔离；`POSTGRES_NEO4J` 仅保留默认 workspace，非默认 workspace 明确返回未支持
- 不修改 `lightrag-core` 的 store 接口签名，不把 workspace 维度下沉到 core API

## File Structure

- Create:
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/WorkspaceResolver.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentControllerTest.java`
- Modify:
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
  - `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
  - `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
  - `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
  - `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`
  - `README.md`

### Task 1: 扩展 starter 的 workspace 配置与工厂

**Files:**
- Create: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: 先写 starter 配置绑定失败测试**

在 `LightRagAutoConfigurationTest` 补两个断言：
- `lightrag.workspace.header-name` 可绑定为自定义值
- 默认值包含 `headerName = X-Workspace-Id`、`defaultId = default`

- [ ] **Step 2: 运行 starter 测试，确认先红**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`
Expected: FAIL，提示缺少 `workspace` 配置字段或断言不成立

- [ ] **Step 3: 写最小 starter 配置实现**

在 `LightRagProperties` 新增：

```java
private final WorkspaceProperties workspace = new WorkspaceProperties();

public static class WorkspaceProperties {
    private String headerName = "X-Workspace-Id";
    private String defaultId = "default";
}
```

并补齐 getter/setter。

- [ ] **Step 4: 先写工厂行为测试**

在 `LightRagAutoConfigurationTest` 增加最小工厂断言：
- context 中存在 `WorkspaceLightRagFactory`
- `factory.get("alpha")` 与 `factory.get("beta")` 返回不同实例
- `factory.get("alpha")` 重复调用返回同一实例

- [ ] **Step 5: 运行 starter 测试，确认工厂测试先红**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest`
Expected: FAIL，提示缺少 `WorkspaceLightRagFactory` Bean

- [ ] **Step 6: 写最小工厂实现**

工厂职责：
- 规范化 `workspaceId`
- `ConcurrentHashMap` 缓存 `LightRag`
- `IN_MEMORY` 为每个 workspace 创建独立 `InMemoryStorageProvider`
- `POSTGRES` 为每个 workspace 派生独立 `tablePrefix`
- 若配置 `snapshotPath`，派生独立 snapshot 文件名
- `POSTGRES_NEO4J` 在默认 workspace 继续复用现状，非默认 workspace 抛出明确异常

建议派生规则：

```java
workspacePrefix = basePrefix + "ws_" + slug(workspaceId) + "_" + shortHash(workspaceId) + "_";
workspaceSnapshot = sibling(baseSnapshot, baseName + "-" + slug + "-" + shortHash + ext);
```

- [ ] **Step 7: 让默认 `LightRag` bean 复用工厂**

在 `LightRagAutoConfiguration` 中把单例 `LightRag` bean 改为：

```java
return workspaceLightRagFactory.get(properties.getWorkspace().getDefaultId());
```

这样 starter 仍保留默认单例语义，但 demo 可以额外做请求级路由。

- [ ] **Step 8: 运行 starter 测试并保持全绿**

Run: `./gradlew :lightrag-spring-boot-starter:test`
Expected: PASS

### Task 2: 在 demo 层引入 workspace 解析与路由

**Files:**
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/WorkspaceResolver.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java`
- Test: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java`

- [ ] **Step 1: 先写 query 的 failing test**

在 `QueryControllerTest` 改为 mock `WorkspaceLightRagFactory`：
- 带 `X-Workspace-Id: alpha` 时，验证 `factory.get("alpha").query(...)` 被调用
- 不带 header 时，验证走默认 workspace 或解析器给出的默认值

- [ ] **Step 2: 运行 query controller 测试，确认先红**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
Expected: FAIL，提示 controller 仍依赖单个 `LightRag`

- [ ] **Step 3: 写最小 `WorkspaceResolver`**

实现要点：
- 从 `HttpServletRequest` 读取 `properties.workspace.headerName`
- 为空时回退 `properties.workspace.defaultId`
- 对结果做 `strip()` 与非空校验
- 非法值抛 `IllegalArgumentException`

- [ ] **Step 4: 修改 `QueryController` 走 workspace 工厂**

形态类似：

```java
var workspaceId = workspaceResolver.resolve(request);
var lightRag = workspaceLightRagFactory.get(workspaceId);
```

- [ ] **Step 5: 跑 query controller 测试转绿**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
Expected: PASS

- [ ] **Step 6: 先写 ingest 和 graph 的 failing tests**

新增 `DocumentControllerTest`，以及在 `GraphControllerTest` 中增加断言：
- `POST /documents/ingest` 会把 resolved workspace 传入 `IngestJobService.submit(workspaceId, documents, async)`
- `POST /graph/entities` 使用 `factory.get("alpha")` 返回的实例执行业务

- [ ] **Step 7: 运行对应 controller 测试，确认先红**

Run:
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.GraphControllerTest`

Expected: FAIL，提示 service/controller 仍缺 workspace 参数

- [ ] **Step 8: 写最小路由实现**

修改：
- `DocumentController` 注入 `WorkspaceResolver`
- `IngestJobService.submit(...)` 增加 `workspaceId` 参数
- `GraphController` 与 `DocumentStatusController` 都改为按请求解析 workspace，再取对应 `LightRag`

- [ ] **Step 9: 让 job 状态也按 workspace 隔离**

`IngestJobService` 的 job state 增加 `workspaceId` 字段：
- `submit(workspaceId, documents, async)` 保存 workspace
- `runJob` 内部通过 `factory.get(workspaceId)` 执行
- `getJob(workspaceId, jobId)` 只返回当前 workspace 的 job

- [ ] **Step 10: 跑 controller 级测试并保持全绿**

Run:
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.QueryControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentControllerTest`
- `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.GraphControllerTest`

Expected: PASS

### Task 3: 用集成测试锁死跨 workspace 不串数据

**Files:**
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 先写 document status 的跨 workspace failing test**

在 `DocumentStatusControllerTest` 增加一个最小集成用例：
1. `ws-a` ingest 文档
2. 轮询 `ws-a` job 成功
3. `GET /documents/status` with `ws-a` 能看到 `doc-1`
4. `GET /documents/status` with `ws-b` 返回空列表
5. `GET /documents/jobs/{jobId}` with `ws-b` 返回 `404`

- [ ] **Step 2: 运行该集成测试，确认先红**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
Expected: FAIL，表明当前状态与 job 仍是全局共享

- [ ] **Step 3: 补齐 document status controller 的最小实现**

确保：
- `/documents/jobs/{jobId}` 按 workspace 查询 job
- `/documents/status` 与 `/documents/status/{documentId}` 读取当前 workspace 的 `LightRag`
- `DELETE /documents/{documentId}` 只影响当前 workspace

- [ ] **Step 4: 跑 document status 集成测试转绿**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DocumentStatusControllerTest`
Expected: PASS

- [ ] **Step 5: 补一条 end-to-end smoke**

在 `DemoApplicationTest` 增加最小断言：
- `ws-a` ingest 后，`ws-a` query 有答案
- `ws-b` query 的 `contexts` 或 `references` 为空，避免只看 chat stub 的静态答案

- [ ] **Step 6: 运行 smoke 测试并保持全绿**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: PASS

### Task 4: 文档与回归

**Files:**
- Modify: `README.md`
- Verify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Verify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/**`

- [ ] **Step 1: 更新 README**

补充：
- `X-Workspace-Id` 用法
- 默认 workspace 配置
- `IN_MEMORY` / `POSTGRES` 支持情况
- `POSTGRES_NEO4J` 当前限制

- [ ] **Step 2: 跑 demo 全量测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 3: 跑 starter + demo 联合回归**

Run: `./gradlew :lightrag-spring-boot-starter:test :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 4: 自查风险点**

确认：
- workspace id 规范化后不会生成非法 table prefix
- snapshotPath 派生不会覆盖默认 workspace 文件
- job 查询不会泄露其他 workspace 的 jobId

- [ ] **Step 5: 提交**

```bash
git add lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java \
  lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java \
  lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/WorkspaceLightRagFactory.java \
  lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/WorkspaceResolver.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentStatusController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/IngestJobService.java \
  lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DocumentStatusControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/GraphControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/QueryControllerTest.java \
  lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java \
  README.md
git commit -m "feat: add workspace-isolated demo routing"
```

## Acceptance Criteria

- `demo` 的导入、查询、图管理、状态查询全部按 workspace 路由
- `IN_MEMORY` 与 `POSTGRES` 下，不同 workspace 的文档与 job 状态互不污染
- starter 提供默认 workspace 配置与可复用工厂
- 缺省单 workspace 使用方式仍可工作
- `POSTGRES_NEO4J` 现有默认 workspace 行为不回归，非默认 workspace 有明确限制提示
- README 对新行为和限制有明确说明
