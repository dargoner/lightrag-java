# Java Model Capability Injection Design

## Background

当前 SDK 的模型装配入口主要聚焦在两个能力上：

1. `ChatModel`
2. `EmbeddingModel`

其中 `RerankModel` 已经存在，但仍然是可选补充能力，没有形成与其他模型能力一致的装配边界。与此同时，`ChatModel` 实际承担了多类职责：

1. 查询回答
2. 实体关系抽取
3. 摘要/归纳类处理

这会带来几个问题：

1. 不同阶段的模型能力被绑定在同一个通用 `chatModel` 上，无法独立替换。
2. 用户无法在 SDK 层明确表达“查询模型”和“摘要模型”不是同一个实现。
3. Spring Boot Starter 只能装配默认的 OpenAI-compatible `ChatModel` / `EmbeddingModel`，扩展路径不够清晰。
4. 后续接入网关模型、本地模型、便宜摘要模型或专用 rerank 引擎时，容易再次演变为固定组合装配。

## Goals

本次设计目标：

1. 在 SDK 层建立按能力拆分的模型注入边界。
2. 允许查询、抽取、摘要、嵌入、重排能力分别注入。
3. 保持现有 `chatModel(...)` / `embeddingModel(...)` 调用方式可兼容迁移。
4. 为 Spring Boot Starter 后续支持自定义模型 Bean 和声明式装配保留稳定扩展点。
5. 避免本轮把模型实现体系整体重写为全新 SPI。

## Non-Goals

本次不做以下事情：

1. 不重写 `ChatModel`、`EmbeddingModel`、`RerankModel` 的底层协议。
2. 不一次性引入新的远程模型协议标准或 provider registry。
3. 不在本轮实现完整的 Starter `type` 插件机制。
4. 不改变现有默认 OpenAI-compatible 模型的行为。
5. 不在本轮处理所有 indexing/query 内部调用点的架构重组，只做能力注入边界解耦。

## Recommended Architecture

推荐将“模型”从单一配置项改为“能力槽位”。

### 1. 通用默认模型槽位

保留现有：

1. `chatModel(ChatModel)`
2. `embeddingModel(EmbeddingModel)`
3. `rerankModel(RerankModel)`

作用：

1. 保持当前 SDK 使用方式不变。
2. 作为未显式配置专用模型时的默认回退来源。

### 2. 专用 Chat 能力槽位

新增 Builder 级别专用能力入口：

1. `queryModel(ChatModel)`
2. `extractionModel(ChatModel)`
3. `summaryModel(ChatModel)`

作用：

1. 查询回答走 `queryModel`。
2. 实体/关系抽取走 `extractionModel`。
3. 摘要、归纳、压缩类流程走 `summaryModel`。

### 3. 非 Chat 能力槽位

继续保留并强化以下能力为一等装配边界：

1. `embeddingModel(EmbeddingModel)`
2. `rerankModel(RerankModel)`

它们不与任意 `ChatModel` 绑定，不允许由固定组合类隐式派生。

## Resolution Rules

Builder 内部需要建立统一解析规则：

1. `queryModel` 已设置时，查询回答使用 `queryModel`。
2. `queryModel` 未设置时，回退到通用 `chatModel`。
3. `extractionModel` 已设置时，抽取流程使用 `extractionModel`。
4. `extractionModel` 未设置时，回退到通用 `chatModel`。
5. `summaryModel` 已设置时，摘要流程使用 `summaryModel`。
6. `summaryModel` 未设置时，回退到通用 `chatModel`。
7. `embeddingModel` 仍为必填能力，不设置则构建失败。
8. `rerankModel` 继续保持可选，不设置时按当前 no-op 语义处理。

这意味着：

1. 老代码只配置 `chatModel(...)` 时，行为保持不变。
2. 新代码可以逐步替换为更细粒度的专用模型装配。

## SDK Surface Changes

### Builder API

推荐新增如下 API：

```java
LightRag.builder()
    .chatModel(defaultChatModel)
    .queryModel(queryChatModel)
    .extractionModel(extractionChatModel)
    .summaryModel(summaryChatModel)
    .embeddingModel(embeddingModel)
    .rerankModel(rerankModel);
```

语义说明：

1. `chatModel(...)` 是通用默认值。
2. `queryModel(...)`、`extractionModel(...)`、`summaryModel(...)` 优先级高于通用 `chatModel(...)`。
3. `build()` 时统一校验：至少要能解析出查询/抽取/摘要所需的 `ChatModel` 默认来源。

### Config Object Changes

`LightRagConfig` 需要同步承载这些能力引用，而不是只保存一个 `chatModel`：

1. `defaultChatModel`
2. `queryModel`
3. `extractionModel`
4. `summaryModel`
5. `embeddingModel`
6. `rerankModel`

同时提供统一访问方法，例如：

1. `queryModel()`
2. `extractionModel()`
3. `summaryModel()`

这些访问方法内部负责执行回退逻辑，避免业务流程反复自行判断。

## Internal Usage Boundaries

### Query Pipeline

问答生成阶段只依赖：

1. `config.queryModel()`
2. `config.embeddingModel()`
3. `config.rerankModel()`

不能再直接假定“所有文本生成都来自同一个 `chatModel` 字段”。

### Indexing / Extraction Pipeline

实体关系抽取阶段只依赖：

1. `config.extractionModel()`
2. `config.embeddingModel()`

后续若有摘要压缩、分层摘要、文档摘要阶段，则统一依赖：

1. `config.summaryModel()`

### Defaulting Discipline

默认值仅发生在 SDK 模型解析层，不允许在各个业务组件里各自回退。

这样做的意义：

1. 避免多处散落的“如果 null 就用 chatModel”。
2. 让能力优先级规则只存在一处。
3. 后续若要继续拆出更多模型能力，只需要扩展统一解析层。

## Spring Boot Starter Direction

Starter 本轮不做完整 provider SPI，但要为后续扩展留出方向。

### Immediate Direction

Starter 最终应支持两类装配方式：

1. 用户直接声明 Bean：
   - `ChatModel`
   - `EmbeddingModel`
   - `RerankModel`
   - 以及后续专用命名 Bean / 限定符 Bean
2. 默认自动装配：
   - 继续创建 OpenAI-compatible 默认模型

### Recommended Follow-up

后续可以在 Starter 中增加更明确的模型能力装配边界，例如：

1. 默认 `ChatModel` Bean
2. `@Qualifier("queryModel")` 的 `ChatModel` Bean
3. `@Qualifier("extractionModel")` 的 `ChatModel` Bean
4. `@Qualifier("summaryModel")` 的 `ChatModel` Bean
5. 默认 `EmbeddingModel` Bean
6. 默认 `RerankModel` Bean

`LightRagAutoConfiguration` 在装配 `LightRag.builder()` 时，优先读取专用能力 Bean，缺省时回退到默认 `ChatModel` Bean。

## Compatibility Strategy

兼容策略如下：

1. 现有 `.chatModel(...)` + `.embeddingModel(...)` 代码无需修改。
2. 现有 Starter 默认配置无需修改。
3. 新增专用模型能力 API 只增强，不破坏已有调用。
4. 若用户同时配置默认 `chatModel` 和专用 `queryModel` / `summaryModel`，以专用模型优先。

## Testing Strategy

### SDK Tests

需要新增或补充以下测试：

1. 仅配置 `chatModel` 时，查询/抽取/摘要都回退到默认模型。
2. 配置 `queryModel` 时，查询流程优先使用专用模型。
3. 配置 `extractionModel` 时，抽取流程优先使用专用模型。
4. 配置 `summaryModel` 时，摘要流程优先使用专用模型。
5. 未配置 `embeddingModel` 时，`build()` 仍然失败。
6. `rerankModel` 未配置时保持当前 no-op 行为。

### Starter Tests

需要补充以下自动装配测试：

1. 用户只提供默认 `ChatModel` Bean 时，`LightRag` 正常装配。
2. 用户提供专用 `queryModel` / `extractionModel` / `summaryModel` Bean 时，Builder 收到对应能力。
3. 用户未提供专用能力 Bean 时，自动回退到默认 `ChatModel` Bean。
4. 默认 OpenAI-compatible 自动装配行为保持不变。

## Risks

### Risk 1: 只加 Builder 方法，不收敛内部访问

如果只是新增 `queryModel(...)` / `summaryModel(...)` 方法，但内部流程仍直接读旧 `chatModel` 字段，那么能力拆分会停留在表面 API。

缓解方式：

1. 将回退逻辑收敛到 `LightRagConfig` 或专门的模型解析层。
2. 内部流程统一改为依赖解析后的能力访问方法。

### Risk 2: Starter 先天只认默认 Bean

如果 Starter 仍然只向 Builder 传一个 `ChatModel`，SDK 的能力边界虽然存在，但 Spring 用户仍然无法真正使用。

缓解方式：

1. 本轮至少为 Starter 预留专用能力 Bean 装配入口。
2. 自动装配中明确优先级：专用 Bean > 默认 Bean > 默认 OpenAI-compatible 实现。

### Risk 3: 过早引入新的 provider SPI

如果这轮同时设计 provider registry、type 解析、动态发现，会把简单的能力注入问题扩大为完整插件系统问题。

缓解方式：

1. 本轮只做能力槽位解耦。
2. provider SPI 等下一阶段再做。

## Implementation Stages

### Current Status

截至 2026-04-09，当前代码已经完成：

1. SDK `LightRagBuilder` 已支持 `queryModel(...)`、`extractionModel(...)`、`summaryModel(...)` 独立注入。
2. `LightRagConfig` 已集中承载默认模型与专用能力模型，并统一处理回退语义。
3. 运行时查询阶段已切到 `queryModel()`，实体关系抽取阶段已切到 `extractionModel()`。
4. `IndexingPipeline` 已保留 `summaryModel` 能力边界，并兼容旧构造签名。
5. Spring Boot Starter 已支持 `queryModel`、`extractionModel`、`summaryModel` 专用 `ChatModel` Bean 注入。
6. Starter 默认 `chatModel` 解析已兼容命名默认 Bean、唯一自定义默认 Bean 以及 `@Primary` 默认 Bean。

### Stage 1

1. 在 SDK Builder 中新增 `queryModel(...)`、`extractionModel(...)`、`summaryModel(...)`。
2. 在 `LightRagConfig` 中新增对应能力字段与统一解析访问方法。
3. 将 query / extraction / summary 相关调用点切换到新能力访问方法。
4. 补齐 SDK 回退语义测试。

### Stage 2

1. 在 Starter 中支持专用模型能力 Bean 注入。
2. 保持默认 OpenAI-compatible 模型自动装配不变。
3. 补齐 Starter 自动装配测试。

### Stage 3

1. 评估是否需要正式引入模型 provider SPI。
2. 评估是否增加配置驱动的 `type` 扩展机制。

## Summary

这次设计的核心不是“再多一个模型配置项”，而是把模型从单点注入改成按能力注入：

1. 查询模型
2. 抽取模型
3. 摘要模型
4. 嵌入模型
5. 重排模型

推荐先在 SDK 和 Starter 装配边界上完成解耦，再逐步演进到底层 provider SPI，而不是一次性重写全部模型体系。
