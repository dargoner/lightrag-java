# Model Capability Injection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 SDK 和 Spring Boot Starter 按能力独立注入查询、抽取、摘要、嵌入、重排模型，同时保持现有 `chatModel(...)` / `embeddingModel(...)` 调用兼容。

**Architecture:** 在 `LightRagBuilder` / `LightRagConfig` 增加模型能力槽位，并把回退规则收敛到配置层。运行时查询和索引流水线只读取解析后的能力访问方法。Starter 侧新增专用 `ChatModel` Bean 注入入口，优先使用专用能力 Bean，缺省回退到默认 `ChatModel` Bean。

**Tech Stack:** Java 17, Gradle, JUnit 5, Spring Boot auto-configuration, AssertJ

---

### Task 1: 在 SDK Builder 和 Config 中建立模型能力槽位

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: 在 Builder 测试中补一个失败用例，定义专用模型槽位与回退语义**

```java
@Test
void usesDedicatedCapabilityModelsWhenProvided() {
    var defaultChatModel = new FakeChatModel();
    var queryModel = new NamedChatModel("query");
    var extractionModel = new NamedChatModel("extract");
    var summaryModel = new NamedChatModel("summary");

    var rag = LightRag.builder()
        .chatModel(defaultChatModel)
        .queryModel(queryModel)
        .extractionModel(extractionModel)
        .summaryModel(summaryModel)
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .build();

    assertThat(rag.config().defaultChatModel()).isSameAs(defaultChatModel);
    assertThat(rag.config().queryModel()).isSameAs(queryModel);
    assertThat(rag.config().extractionModel()).isSameAs(extractionModel);
    assertThat(rag.config().summaryModel()).isSameAs(summaryModel);
}

@Test
void fallsBackDedicatedCapabilityModelsToDefaultChatModel() {
    var defaultChatModel = new FakeChatModel();

    var rag = LightRag.builder()
        .chatModel(defaultChatModel)
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .build();

    assertThat(rag.config().queryModel()).isSameAs(defaultChatModel);
    assertThat(rag.config().extractionModel()).isSameAs(defaultChatModel);
    assertThat(rag.config().summaryModel()).isSameAs(defaultChatModel);
}
```

- [ ] **Step 2: 先跑新增 Builder 测试，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest.usesDedicatedCapabilityModelsWhenProvided" --tests "io.github.lightrag.api.LightRagBuilderTest.fallsBackDedicatedCapabilityModelsToDefaultChatModel" --offline --rerun-tasks`

Expected: FAIL，报 `queryModel` / `extractionModel` / `summaryModel` 方法不存在，或 `LightRagConfig` 不含对应访问方法。

- [ ] **Step 3: 在 Builder 中新增专用模型槽位与 fluent API**

```java
private ChatModel chatModel;
private ChatModel queryModel;
private ChatModel extractionModel;
private ChatModel summaryModel;

public LightRagBuilder queryModel(ChatModel queryModel) {
    this.queryModel = Objects.requireNonNull(queryModel, "queryModel");
    return this;
}

public LightRagBuilder extractionModel(ChatModel extractionModel) {
    this.extractionModel = Objects.requireNonNull(extractionModel, "extractionModel");
    return this;
}

public LightRagBuilder summaryModel(ChatModel summaryModel) {
    this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
    return this;
}
```

- [ ] **Step 4: 在 Config 中承载能力字段并收敛回退逻辑**

```java
public record LightRagConfig(
    ChatModel defaultChatModel,
    ChatModel queryModel,
    ChatModel extractionModel,
    ChatModel summaryModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    DocumentStatusStore documentStatusStore,
    Path snapshotPath,
    RerankModel rerankModel,
    WorkspaceStorageProvider workspaceStorageProvider
) {
    public LightRagConfig {
        defaultChatModel = Objects.requireNonNull(defaultChatModel, "defaultChatModel");
        embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        workspaceStorageProvider = Objects.requireNonNull(workspaceStorageProvider, "workspaceStorageProvider");
    }

    public ChatModel queryModel() {
        return queryModel != null ? queryModel : defaultChatModel;
    }

    public ChatModel extractionModel() {
        return extractionModel != null ? extractionModel : defaultChatModel;
    }

    public ChatModel summaryModel() {
        return summaryModel != null ? summaryModel : defaultChatModel;
    }
}
```

- [ ] **Step 5: 调整 Builder 的 `build()`，把默认模型与专用模型一并写入 Config**

```java
return new LightRag(new LightRagConfig(
    chatModel,
    queryModel,
    extractionModel,
    summaryModel,
    embeddingModel,
    atomicStorageProvider,
    documentStatusStore,
    snapshotPath,
    rerankModel,
    resolvedWorkspaceStorageProvider
), ...);
```

- [ ] **Step 6: 跑 Builder 测试，确认能力槽位与回退语义通过**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest" --offline --rerun-tasks`

Expected: PASS，`LightRagBuilderTest` 全量通过。

- [ ] **Step 7: 提交 SDK Builder / Config 变更**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
  lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
  lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java
git commit -m "feat: add model capability slots to builder"
```

### Task 2: 切换运行时调用到解析后的能力模型

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`

- [ ] **Step 1: 在 E2E 测试中写失败用例，验证查询与抽取走不同模型**

```java
@Test
void usesDedicatedQueryAndExtractionModels() {
    var queryModel = new RecordingChatModel("query-answer");
    var extractionModel = new RecordingExtractionChatModel();

    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .queryModel(queryModel)
        .extractionModel(extractionModel)
        .embeddingModel(new FakeEmbeddingModel())
        .storage(InMemoryStorageProvider.create())
        .build();

    rag.ingest("default", List.of(new Document("doc-1", "Title", "Alice works at Acme.", Map.of())));
    var result = rag.query("default", new QueryRequest("Who works at Acme?"));

    assertThat(extractionModel.invocationCount()).isGreaterThan(0);
    assertThat(queryModel.lastPrompt()).contains("Who works at Acme?");
    assertThat(result.answer()).isEqualTo("query-answer");
}
```

- [ ] **Step 2: 先跑这个 E2E 用例，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.usesDedicatedQueryAndExtractionModels" --offline --rerun-tasks`

Expected: FAIL，因为查询和抽取仍然都依赖 `config.chatModel()`。

- [ ] **Step 3: 把索引与查询工厂改成读取解析后的能力方法**

```java
private IndexingPipeline newIndexingPipeline(AtomicStorageProvider storageProvider) {
    return new IndexingPipeline(
        config.extractionModel(),
        config.summaryModel(),
        config.embeddingModel(),
        storageProvider,
        config.snapshotPath(),
        ...
    );
}

private QueryEngine newQueryEngine(AtomicStorageProvider storageProvider) {
    return new QueryEngine(
        config.queryModel(),
        contextAssembler,
        strategies,
        config.rerankModel(),
        ...
    );
}
```

- [ ] **Step 4: 只在必要处扩展 IndexingPipeline 构造函数，避免把回退逻辑散出去**

```java
public IndexingPipeline(
    ChatModel extractionModel,
    ChatModel summaryModel,
    EmbeddingModel embeddingModel,
    AtomicStorageProvider storageProvider,
    Path snapshotPath,
    ...
) {
    this.knowledgeExtractor = new KnowledgeExtractor(
        Objects.requireNonNull(extractionModel, "extractionModel"),
        ...
    );
    this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
}
```

说明：如果本轮 `summaryModel` 暂无实际调用点，也先把能力参数贯通到 `IndexingPipeline`，并在字段/注释中明确保留给摘要阶段使用，避免再次退回“只有一个 chatModel”。

- [ ] **Step 5: 跑定向 E2E + Builder 回归，确认运行时切换生效**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.E2ELightRagTest.usesDedicatedQueryAndExtractionModels" --tests "io.github.lightrag.api.LightRagBuilderTest" --offline --rerun-tasks`

Expected: PASS，查询与抽取模型各走各的能力槽位。

- [ ] **Step 6: 提交运行时模型解析变更**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
  lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java
git commit -m "refactor: route runtime flows through capability models"
```

### Task 3: 在 Starter 中支持专用模型能力 Bean 注入

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: 在 Starter 测试中写失败用例，定义专用能力 Bean 优先级**

```java
@Test
void usesDedicatedCapabilityChatModelBeansWhenPresent() {
    contextRunner
        .withUserConfiguration(DedicatedCapabilityModelConfiguration.class)
        .run(context -> {
            var lightRag = context.getBean(LightRag.class);
            var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");

            assertThat(config.queryModel()).isSameAs(context.getBean("queryModel", ChatModel.class));
            assertThat(config.extractionModel()).isSameAs(context.getBean("extractionModel", ChatModel.class));
            assertThat(config.summaryModel()).isSameAs(context.getBean("summaryModel", ChatModel.class));
        });
}

@Test
void fallsBackDedicatedCapabilityBeansToDefaultChatModelBean() {
    contextRunner
        .withUserConfiguration(TestModelConfiguration.class)
        .run(context -> {
            var lightRag = context.getBean(LightRag.class);
            var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
            var defaultChatModel = context.getBean(ChatModel.class);

            assertThat(config.queryModel()).isSameAs(defaultChatModel);
            assertThat(config.extractionModel()).isSameAs(defaultChatModel);
            assertThat(config.summaryModel()).isSameAs(defaultChatModel);
        });
}
```

- [ ] **Step 2: 先跑这两个 Starter 用例，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest.usesDedicatedCapabilityChatModelBeansWhenPresent" --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest.fallsBackDedicatedCapabilityBeansToDefaultChatModelBean" --rerun-tasks`

Expected: FAIL，因为 `LightRagAutoConfiguration` 只向 Builder 传默认 `chatModel`。

- [ ] **Step 3: 在 AutoConfiguration 中通过限定名读取专用能力 Bean**

```java
@Bean
@ConditionalOnMissingBean
LightRag lightRag(
    ChatModel chatModel,
    EmbeddingModel embeddingModel,
    WorkspaceStorageProvider workspaceStorageProvider,
    ObjectProvider<@Qualifier("queryModel") ChatModel> queryModel,
    ObjectProvider<@Qualifier("extractionModel") ChatModel> extractionModel,
    ObjectProvider<@Qualifier("summaryModel") ChatModel> summaryModel,
    ObjectProvider<RerankModel> rerankModel,
    ...
) {
    var builder = LightRag.builder()
        .chatModel(chatModel)
        .embeddingModel(embeddingModel)
        .workspaceStorage(workspaceStorageProvider);

    var configuredQueryModel = queryModel.getIfAvailable();
    if (configuredQueryModel != null) {
        builder.queryModel(configuredQueryModel);
    }
    var configuredExtractionModel = extractionModel.getIfAvailable();
    if (configuredExtractionModel != null) {
        builder.extractionModel(configuredExtractionModel);
    }
    var configuredSummaryModel = summaryModel.getIfAvailable();
    if (configuredSummaryModel != null) {
        builder.summaryModel(configuredSummaryModel);
    }
    ...
}
```

- [ ] **Step 4: 在测试配置中增加专用能力 Bean**

```java
@Configuration(proxyBeanMethods = false)
static class DedicatedCapabilityModelConfiguration {
    @Bean
    ChatModel chatModel() {
        return request -> "default";
    }

    @Bean("queryModel")
    ChatModel queryModel() {
        return request -> "query";
    }

    @Bean("extractionModel")
    ChatModel extractionModel() {
        return request -> "{\"entities\":[],\"relations\":[]}";
    }

    @Bean("summaryModel")
    ChatModel summaryModel() {
        return request -> "summary";
    }

    @Bean
    EmbeddingModel embeddingModel() {
        return texts -> texts.stream().map(text -> List.of(1.0d)).toList();
    }
}
```

- [ ] **Step 5: 跑 Starter 全量回归，确认默认装配与专用能力装配都通过**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest" --rerun-tasks`

Expected: PASS，包含默认 OpenAI-compatible 模型装配与专用能力 Bean 覆盖两种场景。

- [ ] **Step 6: 提交 Starter 模型能力装配变更**

```bash
git add lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java \
  lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java
git commit -m "feat: wire dedicated model capability beans"
```

### Task 4: 做最终验证并同步文档

**Files:**
- Modify: `docs/superpowers/specs/2026-04-09-model-capability-injection-design.md`（如实现细节与 spec 有微调）
- Verify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
- Verify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
- Verify: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: 跑最终回归集，确认 SDK + Starter 行为收口**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest" --tests "io.github.lightrag.E2ELightRagTest" --offline --rerun-tasks`

Expected: PASS

- [ ] **Step 2: 串行跑 Starter 回归，避免测试结果写文件冲突**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest" --rerun-tasks`

Expected: PASS

- [ ] **Step 3: 若实现细节与 spec 有偏移，只同步已落地内容，不补未来承诺**

```markdown
### Current Status

截至实现完成，SDK 已支持：
1. `queryModel(ChatModel)`
2. `extractionModel(ChatModel)`
3. `summaryModel(ChatModel)`
4. `embeddingModel(EmbeddingModel)`
5. `rerankModel(RerankModel)`

Starter 已支持：
1. 默认 `ChatModel` / `EmbeddingModel` 自动装配
2. `queryModel` / `extractionModel` / `summaryModel` 专用 Bean 覆盖
```

- [ ] **Step 4: 复核工作树并提交收尾变更**

```bash
git status --short
git add docs/superpowers/specs/2026-04-09-model-capability-injection-design.md
git commit -m "docs: sync model capability injection status"
```

- [ ] **Step 5: 请求代码评审并在评审通过后准备合并**

Run: `git rev-parse HEAD~1 && git rev-parse HEAD`
Expected: 输出本轮评审的 base/head SHA，用于派发 code review。
