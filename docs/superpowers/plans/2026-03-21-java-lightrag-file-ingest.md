# Java LightRAG File Ingest Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `lightrag-spring-boot-demo` 增加基于 `multipart/form-data` 的文件上传导入入口，让调用方不再需要手写 JSON `Document` 载荷。

**Architecture:** 保持现有 `/documents/ingest` JSON 接口不变，新增独立上传 controller，把 `MultipartFile` 转成 `Document` 后复用 `IngestJobService`。第一阶段只支持 UTF-8 文本和 Markdown 文件，不引入 PDF/Office 解析器，也不新增独立任务系统。

**Tech Stack:** Java 17, Spring Boot 3.3, Spring MVC multipart, Gradle, JUnit 5, MockMvc

---

## File Structure

- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java`
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`
- Modify: `README.md`

## Scope Guardrails

- 本计划只支持文本类上传：`.txt`、`.md`、`.markdown`
- 文件内容按 UTF-8 读取，不引入 Apache Tika、PDFBox 等重型依赖
- 继续复用 `IngestJobService`，不新增 job 类型和状态机
- `/documents/ingest` 继续保留，上传接口作为更易接入的补充

## API Decision

推荐端点：

- `POST /documents/upload`
- `Content-Type: multipart/form-data`
- Part: `files`，支持 1 个或多个文件
- Optional query param: `async=true|false`

导入规则：

- `document.id` = 服务端生成的 URL-safe 标识，格式为 `<sanitized-basename>-<short-hash>`
- `document.title` = 保留 basename 后的原始文件名（允许空格和大小写）
- `document.content` = UTF-8 文本内容
- `document.metadata` 自动包含：
  - `source=upload`
  - `filename=<originalFilename>`
  - `contentType=<multipart content type or application/octet-stream>`

命名规则：

- 先把 `originalFilename` 规范化为 basename，去掉目录穿越片段
- basename 中不可打印字符和路径分隔符不进入 `document.id`
- `document.id` 的前缀取 basename 去扩展名后的小写 slug，只保留小写字母、数字和 `-`
- 连续非法字符折叠为单个 `-`，首尾 `-` 去掉
- slug 清洗后为空时，回退为 `document`
- hash 使用 `SHA-256`，输入为 `<normalized filename>\0<file bytes>`，十六进制编码后取前 12 位
- `documentIds` 返回顺序与上传的 `files` part 顺序一致
- 同一请求内若生成重复 `document.id`，直接返回 `400`

响应规则：

- 返回 `202`
- 响应体包含 `jobId`
- 同时返回 `documentIds`，便于后续调用 `/documents/status/{documentId}` 和 `DELETE /documents/{documentId}`

错误规则：

- 无文件：`400`
- 文件名为空：`400`
- 文件内容为空白：`400`
- 不支持的文件类型：`400`
- 扩展名匹配大小写不敏感
- UTF-8 解码后为空白内容返回 `400`
- multipart 解析失败或缺少 `files` part`：只保证 4xx 状态码，不强制统一 JSON 错误体`

### Task 1: 新增上传映射与 controller

**Files:**
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java`
- Create: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java`
- Create: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java`

- [ ] **Step 1: 先写失败测试，定义上传接口契约**

覆盖目标：
- 单文件上传返回 `202`
- 多文件上传同样返回 `202`
- `async=false` 能同步执行但仍返回统一 `jobId`
- 空文件、空白内容、不支持扩展名返回 `400`

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.UploadControllerTest`
Expected: FAIL，提示 controller 或 mapper 尚不存在

- [ ] **Step 3: 新建 `UploadedDocumentMapper`**

实现方向：

```java
final class UploadedDocumentMapper {
    List<Document> toDocuments(List<MultipartFile> files) { ... }
}
```

要求：
- 校验 `files` 非空
- 仅接受 `.txt/.md/.markdown`
- 使用 UTF-8 读取内容
- 拒绝空白内容
- 规范化文件名并生成稳定、URL-safe 的 `document.id`
- 统一生成 `Document`

- [ ] **Step 4: 新建 `UploadController`**

建议接口：

```java
@PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
ResponseEntity<UploadJobResponse> upload(
    @RequestPart("files") List<MultipartFile> files,
    @RequestParam(name = "async", required = false) Boolean async
) { ... }
```

要求：
- 复用 `UploadedDocumentMapper`
- 默认沿用 `LightRagProperties.demo.asyncIngestEnabled`
- 显式 `async` 参数优先于默认配置
- 返回 `jobId + documentIds`

- [ ] **Step 5: 跑上传 controller 测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.UploadControllerTest`
Expected: PASS

- [ ] **Step 6: 提交上传接口实现**

```bash
git add lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java \
        lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadedDocumentMapper.java \
        lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/UploadControllerTest.java
git commit -m "feat: add demo file upload ingest endpoint"
```

### Task 2: 增加 demo 端到端验证

**Files:**
- Modify: `lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java`

- [ ] **Step 1: 写失败集成测试，覆盖上传后查询**

目标：
- 通过 multipart 上传文本文件
- 复用现有 job 轮询
- 上传后 `/query` 能返回预期答案

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: FAIL，提示新端点或 ingest 路径未接通

- [ ] **Step 3: 最小修改测试桩或配置**

要求：
- 不改核心模型语义
- 只在测试里补充上传入口所需断言

- [ ] **Step 4: 跑集成测试**

Run: `./gradlew :lightrag-spring-boot-demo:test --tests io.github.lightragjava.demo.DemoApplicationTest`
Expected: PASS

- [ ] **Step 5: 提交集成测试**

```bash
git add lightrag-spring-boot-demo/src/test/java/io/github/lightragjava/demo/DemoApplicationTest.java
git commit -m "test: cover demo file upload ingest flow"
```

### Task 3: 文档与模块回归

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README 的 demo API 部分**

至少补充：
- `POST /documents/upload`
- 支持的文件类型
- `async` 参数语义
- 与 `/documents/ingest` 的区别

- [ ] **Step 2: 跑 demo 模块回归**

Run: `./gradlew :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 3: 跑 starter + demo 联合回归**

Run: `./gradlew :lightrag-spring-boot-starter:test :lightrag-spring-boot-demo:test --rerun-tasks`
Expected: PASS

- [ ] **Step 4: 最终提交**

```bash
git add README.md
git commit -m "docs: describe demo file upload ingest"
```

## Acceptance Criteria

- demo 新增 `multipart/form-data` 上传入口，不需要手写 JSON `Document`
- 单文件和多文件上传都能进入现有 ingest job 流程
- 不支持类型、空文件、空白内容有清晰 `400` 响应
- 端到端测试覆盖“上传 -> ingest -> 查询”
- README 明确说明上传入口和限制
