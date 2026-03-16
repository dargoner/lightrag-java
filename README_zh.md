# lightrag-java

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

当前提供两个最小接口：

- `POST /documents/ingest`
- `POST /query`

适合用来验证：

- starter 自动装配是否生效
- 模型服务是否联通
- 基础 ingest / query 链路是否正常

Demo 默认配置文件：

- `lightrag-spring-boot-demo/src/main/resources/application.yml`

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
