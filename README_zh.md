# lightrag-java

![Java 17](https://img.shields.io/badge/Java-17-437291)
![Gradle 8](https://img.shields.io/badge/Gradle-8.14.3-02303A)
![Spring Boot Starter](https://img.shields.io/badge/Spring_Boot-Starter-6DB33F)
![RAGAS Eval](https://img.shields.io/badge/Evaluation-RAGAS-7B61FF)

`lightrag-java` 是一个面向 Java 17 的 LightRAG 风格 SDK，支持文档 ingest、图检索、向量检索、结构化引用、流式查询、RAGAS 评测，以及 Spring Boot Starter / Demo 接入。

如果你主要是想快速判断这个仓库能做什么，可以先看这几项：

- 核心 SDK：`lightrag-core`
- Spring Boot Starter：`lightrag-spring-boot-starter`
- Demo 应用：`lightrag-spring-boot-demo`
- 评测脚本：`evaluation/ragas/`

完整英文文档仍然保留在 [README.md](./README.md)。

## 环境要求

- JDK 17
- Gradle Wrapper 已内置
- 如果使用 PostgreSQL / Neo4j / Testcontainers 相关功能，需要本机有对应服务或 Docker 环境

## 仓库结构

- `lightrag-core`
  - 核心 SDK，包含模型适配、存储、索引、查询、评测 runner 等基础能力
- `lightrag-spring-boot-starter`
  - Spring Boot 自动装配模块
- `lightrag-spring-boot-demo`
  - 最小可运行 Demo，提供 ingest / query REST 接口
- `evaluation/ragas`
  - upstream 风格 RAGAS 评测脚本、样例数据和说明
- `docs/superpowers`
  - 本仓库的设计文档与实现计划

## 当前能力

### 查询模式

支持以下查询模式：

- `NAIVE`
- `LOCAL`
- `GLOBAL`
- `HYBRID`
- `MIX`
- `BYPASS`

### 查询增强能力

支持：

- `userPrompt`
- `conversationHistory`
- `includeReferences`
- `stream`
- `modelFunc`
- 自动关键词提取
- token budget 控制
- rerank

### 存储后端

当前支持：

- `in-memory`
- `PostgresStorageProvider`
- `PostgresNeo4jStorageProvider`

## 快速开始

下面是最小 Java 用法：

```java
var storage = InMemoryStorageProvider.create();
var rag = LightRag.builder()
    .chatModel(new OpenAiCompatibleChatModel(
        "https://api.openai.com/v1/",
        "gpt-4o-mini",
        System.getenv("OPENAI_API_KEY")
    ))
    .embeddingModel(new OpenAiCompatibleEmbeddingModel(
        "https://api.openai.com/v1/",
        "text-embedding-3-small",
        System.getenv("OPENAI_API_KEY")
    ))
    .storage(storage)
    .build();

rag.ingest(List.of(
    new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "demo"))
));

var result = rag.query(QueryRequest.builder()
    .query("Who works with Bob?")
    .build());

System.out.println(result.answer());
```

## Spring Boot Starter

仓库已经内置 Spring Boot Starter，可以直接在 Spring 项目里自动装配 `LightRag`。

### Starter 模块

- `lightrag-spring-boot-starter`

它会自动注册：

- `ChatModel`
- `EmbeddingModel`
- `Chunker`
- `StorageProvider`
- `LightRag`

### 支持的 storage type

- `in-memory`
- `postgres`
- `postgres-neo4j`

### 最小内存版配置

```yaml
lightrag:
  chat:
    base-url: http://localhost:11434/v1/
    model: qwen2.5:7b
    api-key: dummy
  embedding:
    base-url: http://localhost:11434/v1/
    model: nomic-embed-text
    api-key: dummy
  storage:
    type: in-memory
```

### 自定义分块

Java SDK 现在支持在 builder 上覆盖 ingest 阶段的 `Chunker`，不再只能使用内置的 `FixedWindowChunker(1000, 100)`。

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(document -> List.of(
        new Chunk(document.id() + ":0", document.id(), document.content(), document.content().length(), 0, document.metadata())
    ))
    .build();
```

如果你想直接使用结构感知分块，可以通过同一个 `.chunker(...)` 入口接入 `SmartChunker`：

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(800)
        .maxTokens(1200)
        .overlapTokens(100)
        .semanticMergeEnabled(true)
        .semanticMergeThreshold(0.80d)
        .build()))
    .build();
```

`SmartChunker` 目前分三层能力：

- V1：按句子边界切分，overlap 也尽量落在完整句子上
- V2：识别 markdown 风格标题、列表、表格，并写入 `smart_chunker.section_path` 等字符串 metadata
- V3：可选的相邻 chunk 语义合并，以及反射式 `LangChain4jChunkAdapter`

从这版开始，`SmartChunker` 还会在内部默认开启 **adaptive paragraph chunking**：

- 用户仍然只需要选择 `FINE / MEDIUM / COARSE`
- 连续长正文会在该粒度范围内自动放粗
- 同一 section 下相邻的短正文段，会先尝试 regroup，再进入按句切分
- 标题切换后的首段会自动收细
- 很短的页眉页脚、附件短句、非正文碎片会继续保持保守，不会被强行合并
- `LIST / TABLE` 仍保持保守，不做激进自适应
- `LAW / QA` 只允许很小范围的动态调整，优先保证模板边界稳定

独立调用 `SmartChunker.chunk(...)` 仍然是 **model-free** 的。它的 V3 语义合并继续使用 `SmartChunkerConfig` 里的本地启发式相似度，不依赖 `EmbeddingModel`。

如果你想启用 **embedding 驱动** 的语义合并，请在 `LightRag` 的 ingest 链路上打开，而不是指望 standalone chunker 自动切到 embedding 模式：

```java
var rag = LightRag.builder()
    .chatModel(chatModel)
    .embeddingModel(embeddingModel)
    .storage(storage)
    .chunker(new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(800)
        .maxTokens(1200)
        .overlapTokens(100)
        .build()))
    .enableEmbeddingSemanticMerge(true)
    .embeddingSemanticMergeThreshold(0.80d)
    .build();
```

这条 ingest-only 路径会先取 `SmartChunker.chunkStructural(...)` 的结构分片，再用当前 `EmbeddingModel` 计算语义相似度，最后只在 `section_path` 和 `content_type` 允许的情况下做相邻 chunk 合并。query 阶段和 standalone `SmartChunker.chunk(...)` 的行为都不会因此改变。

这些新增 metadata 仍然保持 `Map<String, String>` 形态，所以和当前 query、快照、PostgreSQL 持久化链路兼容。

如果你要把 chunk 转成 LangChain4j 的 `TextSegment`，可以显式调用 adapter：

```java
var chunker = new SmartChunker(SmartChunkerConfig.defaults());
var chunks = chunker.chunk(new Document("doc-1", "Guide", "# Policies\nCarry your passport.", Map.of()));
var adapter = new LangChain4jChunkAdapter();
var segments = adapter.toTextSegments(chunks);
```

如果你希望 SDK 直接接收文件，而不是自己先把所有内容转成 `Document`，可以使用 raw-source ingest 入口：

```java
var source = RawDocumentSource.bytes(
    "contract.docx",
    Files.readAllBytes(Path.of("contract.docx")),
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
);

rag.ingestSources(
    List.of(source),
    new DocumentIngestOptions(DocumentTypeHint.LAW, ChunkGranularity.MEDIUM)
);
```

raw-source ingest 会把解析后端选择继续藏在 SDK 内部：

- `.txt`、`.md`、`.markdown`：直接按 UTF-8 纯文本解析
- `.pdf`、`.doc`、`.docx`、`.ppt`、`.pptx`、`.html`、`.htm`：优先走 `MinerU`，不可用时再按配置降级到 `Tika`
- `.png`、`.jpg`、`.jpeg`、`.webp`、`.gif`、`.bmp`：只走 `MinerU`，不会在 SDK 内再伪造 OCR 回退
- 文档里的图片/图表会先在 `MinerU` 解析阶段转成 OCR 文本、图注或版面文本块，再交给 `SmartChunker`；当前不会对纯视觉内容单独生成图片 embedding

如果你用的是托管版 `mineru.net` API，还必须在 metadata 里提供一个公网可访问的文件 URL，例如 `sourceUrl`。
托管版 API 不能直接接收本地文件字节，所以本地上传的 raw bytes 目前仍然需要二选一：

- 使用 self-hosted MinerU
- 或者先经过对象存储/临时中转，把上传文件变成可访问 URL，再交给托管版 MinerU

切片策略同样保持业务语义：

- 默认 `AUTO`：如果传了正则规则，就走手工/regex 分片；否则走 `SmartChunker`
- 也可以通过 `DocumentIngestOptions` 强制指定 `SMART`、`REGEX` 或 `FIXED`
- 需要父子分片检索时，可以打开 parent/child，让 child 命中后再向上扩回 parent 上下文

如果使用 Spring Boot Starter，也可以直接在 `application.yml` 里调整固定窗口分块参数；如果应用自己声明了 `Chunker` Bean，starter 会自动让位。

```yaml
lightrag:
  indexing:
    chunking:
      window-size: 1200
      overlap: 150
    ingest:
      preset: GENERAL
      parent-child-window-size: 400
      parent-child-overlap: 40
    parsing:
      tika-fallback-enabled: true
      mineru:
        enabled: false
        mode: DISABLED
        base-url: http://127.0.0.1:8000
        api-key: ${MINERU_API_KEY:}
    embedding-batch-size: 32
    max-parallel-insert: 4
    entity-extract-max-gleaning: 1
    max-extract-input-tokens: 20480
```

`ingest.preset` 现在是默认推荐的产品化配置入口，支持 `GENERAL`、`LAW`、`BOOK`、`QA`、`FIGURE`。

为了兼容旧项目，下面三项旧配置仍然保留可用：

- `lightrag.indexing.ingest.document-type`
- `lightrag.indexing.ingest.chunk-granularity`
- `lightrag.indexing.ingest.parent-child-enabled`

如果请求级别没有显式传 `preset`，这些旧配置仍会覆盖 `preset` 推导出来的默认值。

`embedding-batch-size` 用来控制 ingest 阶段每次 embedding 请求最多发送多少段文本。保持未配置或设为 `0`，就会继续沿用当前的单批次行为。
`max-parallel-insert` 用来控制 ingest 阶段最多同时处理多少个文档，默认值是 `1`，也就是不显式开启时仍按串行执行。
`entity-extract-max-gleaning` 用来控制每个 chunk 在首次抽取之后还能继续做多少轮补抽。
`max-extract-input-tokens` 用来限制补抽前允许的估算上下文预算，超过后会跳过该轮补抽。
当 `max-parallel-insert` 大于 `1` 时，自定义 `Chunker`、`ChatModel`、`EmbeddingModel` 实现需要具备并发安全性。

如果不配置这两个字段，starter 默认仍然使用 `window-size=1000`、`overlap=100`。

### PostgreSQL 配置示例

```yaml
lightrag:
  chat:
    base-url: https://api.openai.com/v1/
    model: gpt-4o-mini
    api-key: ${OPENAI_API_KEY}
  embedding:
    base-url: https://api.openai.com/v1/
    model: text-embedding-3-small
    api-key: ${OPENAI_API_KEY}
  storage:
    type: postgres
    postgres:
      jdbc-url: jdbc:postgresql://localhost:5432/lightrag
      username: postgres
      password: postgres
      schema: lightrag
      vector-dimensions: 1536
      table-prefix: rag_
```

### Postgres + Neo4j 配置示例

```yaml
lightrag:
  chat:
    base-url: https://api.openai.com/v1/
    model: gpt-4o-mini
    api-key: ${OPENAI_API_KEY}
  embedding:
    base-url: https://api.openai.com/v1/
    model: text-embedding-3-small
    api-key: ${OPENAI_API_KEY}
  storage:
    type: postgres-neo4j
    postgres:
      jdbc-url: jdbc:postgresql://localhost:5432/lightrag
      username: postgres
      password: postgres
      schema: lightrag
      vector-dimensions: 1536
      table-prefix: rag_
    neo4j:
      uri: bolt://localhost:7687
      username: neo4j
      password: password
      database: neo4j
```

## Demo 应用

最小 Demo 位于：

- `lightrag-spring-boot-demo`

启动命令：

```bash
./gradlew :lightrag-spring-boot-demo:bootRun
```

当前 demo 提供这些最小接口：

- `POST /documents/ingest`
- `GET /documents/jobs?page=0&size=20`
- `GET /documents/jobs/{jobId}`
- `POST /documents/jobs/{jobId}/cancel`
- `POST /documents/jobs/{jobId}/retry`
- `GET /documents/status`
- `GET /documents/status/{documentId}`
- `DELETE /documents/{documentId}`
- `POST /query`
- `GET /actuator/health`
- `GET /actuator/info`

适合用来验证：

- starter 自动装配是否生效
- 模型服务是否联通
- 基础 ingest / query 链路是否正常
- 服务基础探活和运行配置是否正常暴露

`POST /documents/ingest` 适合已经有结构化 `Document` JSON 的场景。
`POST /documents/upload` 适合直接上传原始文件字节，让 SDK 自动选择 `plain -> MinerU -> Tika` 解析路径。

upload 接口接收 `multipart/form-data`，支持一个或多个 `files`，并带可选 `async=true|false` 和 `preset=GENERAL|LAW|BOOK|QA|FIGURE` 参数。当前支持：

- `.txt`
- `.md`
- `.markdown`
- `.pdf`
- `.doc`
- `.docx`
- `.ppt`
- `.pptx`
- `.html`
- `.htm`
- `.png`
- `.jpg`
- `.jpeg`
- `.webp`
- `.gif`
- `.bmp`

当前 demo upload 限制：

- 单次请求最多 `20` 个文件
- 单文件最大 `1 MiB`
- 整个请求最大 `4 MiB`
- `.txt` / `.md` / `.markdown` 必须是合法 UTF-8
- office、pdf、html、image 会保留原始字节，延后到 ingest 阶段再解析

upload 解析规则：

- office、pdf、html 文件优先走 `MinerU`，不可用时按配置降级到 `Tika`
- 图片文件必须依赖 `MinerU`；如果 `MinerU` 不可用或解析失败，job 会失败，不会伪造空 OCR 成功
- 切片仍然跟随 `DocumentIngestOptions`，默认是智能分片，也可以在 SDK 集成里切到 regex 手工分片

每个上传文件会先变成一个 `RawDocumentSource`，包含：

- 自动生成的 URL-safe `sourceId`
- 原始文件名与 media type
- `source=upload`、`fileName`、`contentType` 等 metadata

其中 actuator 端点提供最小运维信息：

- `/actuator/health`：返回应用整体健康状态，以及 `lightrag` 组件的 storage type / async ingest 配置
- `/actuator/info`：返回 storage type、async ingest 开关、默认 query mode

其中 job 查询接口额外暴露了最小可观测字段：

- `documentCount`
- `createdAt` / `startedAt` / `finishedAt`
- `errorMessage`
- `cancellable` / `retriable` / `retriedFromJobId` / `attempt`

当前阶段的 cancel / retry 语义：

- `PENDING` job 取消后会直接进入 `CANCELLED`
- `RUNNING` job 取消是 best-effort，状态会先进入 `CANCELLING`
- retry 只会重提还没到 `PROCESSED` 的文档，避免部分成功批次直接撞上重复 `documentId`

Demo 默认配置文件：

- `lightrag-spring-boot-demo/src/main/resources/application.yml`

Starter 还额外暴露了几项 pipeline 配置：

- `lightrag.indexing.chunking.window-size`
- `lightrag.indexing.chunking.overlap`
- `lightrag.indexing.ingest.preset`
- `lightrag.indexing.ingest.parent-child-window-size`
- `lightrag.indexing.ingest.parent-child-overlap`
- 兼容旧配置：`lightrag.indexing.ingest.document-type`
- 兼容旧配置：`lightrag.indexing.ingest.chunk-granularity`
- 兼容旧配置：`lightrag.indexing.ingest.parent-child-enabled`
- `lightrag.indexing.parsing.tika-fallback-enabled`
- `lightrag.indexing.parsing.mineru.enabled`
- `lightrag.indexing.parsing.mineru.mode`
- `lightrag.indexing.parsing.mineru.base-url`
- `lightrag.indexing.parsing.mineru.api-key`
- `lightrag.indexing.embedding-batch-size`
- `lightrag.indexing.max-parallel-insert`
- `lightrag.indexing.entity-extract-max-gleaning`
- `lightrag.indexing.max-extract-input-tokens`
- `lightrag.query.automatic-keyword-extraction`
- `lightrag.query.rerank-candidate-multiplier`

模型超时也可以直接通过 Starter 配置：

- `lightrag.chat.timeout`
- `lightrag.embedding.timeout`

两者都使用 ISO-8601 `Duration` 格式，默认值是 `PT30S`，例如：

```yaml
lightrag:
  chat:
    timeout: PT45S
  embedding:
    timeout: PT15S
```

## RAGAS 评测

仓库内已经集成 upstream 风格的 RAGAS 评测：

- `evaluation/ragas/eval_rag_quality_java.py`

支持两种评测 profile：

- `in-memory`
- `postgres-neo4j-testcontainers`

常见入口：

```bash
python3 evaluation/ragas/eval_rag_quality_java.py
```

如果你要把当前结果固化成 baseline：

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --run-label baseline \
  --baseline-name sample-default \
  --update-baseline
```

如果你要跑候选配置并和 baseline 比较：

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --run-label candidate-rerank-4 \
  --baseline-name sample-default
```

兼容性说明：

- `eval_rag_quality_java.py` 同时兼容旧版 batch `list` 输出和新版 `{request, summary, results}` envelope
- wrapper 会自动把结构化 `contexts` 归一化成 RAGAS 需要的字符串列表；如果你直接消费 Java batch runner 原始 JSON，再读取 `context.text()`
- 如果存在 `evaluation/ragas/baselines/<name>.json`，脚本会自动比较平均分，并在退化超过阈值时返回非 0 退出码

如果要使用 PG/Neo4j Testcontainers 评测，需要：

- Docker 可用
- 模型与 embedding 服务可访问

评测说明见：

- `evaluation/ragas/README.md`

## 推荐阅读顺序

如果你是第一次接入，建议按这个顺序：

1. `README_zh.md`
2. `README.md`
3. `lightrag-spring-boot-demo/src/main/resources/application.yml`
4. `evaluation/ragas/README.md`

## 说明

这份中文 README 目前是高信息密度版本，重点覆盖：

- 项目定位
- 模块结构
- 快速开始
- Spring Boot Starter / Demo
- 评测入口

更细的查询参数、存储说明、测试与评测细节，请继续参考英文版 [README.md](./README.md)。
