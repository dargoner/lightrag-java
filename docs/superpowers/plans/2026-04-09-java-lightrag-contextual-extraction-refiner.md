# Contextual Extraction Refiner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `lightrag-core` 增加 Stage 1 版按需二次抽取修复链路，在细粒度 chunk 关系断裂时使用相邻窗口补边，并把可归因结果稳定回写到细 chunk。

**Architecture:** 保持现有 `IndexingPipeline -> KnowledgeExtractor -> GraphAssembler` 主干不变，在一次抽取之后增加 `refinement` 子包下的检测、窗口、归因、merge 编排层。Stage 1 只支持相邻窗口、静态 builder 开关、默认关闭；不做 section 扩窗、动态热切换和模糊归因算法。

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing `lightrag-core` indexing/storage stack

---

## Scope Guardrails

- 本计划只实现 spec 的 **Stage 1**
- 只支持 `previous + current + next` 相邻窗口
- `enabled` 与 `allowDeterministicAttributionFallback` 仅做 **SDK/库侧静态配置**，不实现动态配置热切换体系
- deterministic 归因兜底默认关闭，且本轮只实现严格子串规则，不做句级打分或 embedding 归因
- `KnowledgeExtractor.extractWindow(...)` 复用现有抽取 JSON schema，额外增加 `supportingChunkIndexes`，但不重写一次抽取 prompt
- 本轮不改查询链路，不改 GraphStore / VectorStore schema

## File Structure

- Create:
  - `docs/superpowers/plans/2026-04-09-java-lightrag-contextual-extraction-refiner.md`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/PrimaryChunkExtraction.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/GapAssessment.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementScope.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindow.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowEntityCandidate.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowRelationCandidate.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowExtractionResponse.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedEntityPatch.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedRelationPatch.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedWindowExtraction.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ChunkExtractionPatch.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionGapDetector.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetector.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindowResolver.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/AttributionResolver.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionMergePolicy.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementOptions.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetectorTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolverTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicyTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipelineTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineRefinementIntegrationTest.java`
- Modify:
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java`
  - `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
  - `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
  - `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
  - `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`
  - `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

## Task 1: 建立窗口抽取协议与 Stage 1 开关

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowEntityCandidate.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowRelationCandidate.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowExtractionResponse.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementOptions.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: 在 `KnowledgeExtractorTest` 中先写 failing tests，锁定窗口抽取原始 DTO 与校验行为**

```java
@Test
void extractsWindowRelationsWithSupportingChunkIndexes() {
    var extractor = new KnowledgeExtractor(new StubChatModel("""
        {
          "entities": [],
          "relations": [
            {
              "sourceEntityName": "订单系统",
              "targetEntityName": "PostgreSQL",
              "type": "依赖",
              "description": "订单系统依赖 PostgreSQL 进行事务存储",
              "weight": 1.0,
              "supportingChunkIndexes": [0, 1, 1]
            }
          ],
          "warnings": []
        }
        """));
    var window = new RefinementWindow(
        "doc-1",
        List.of(chunk("chunk-1", "订单系统依赖"), chunk("chunk-2", "PostgreSQL 进行事务存储")),
        0,
        RefinementScope.ADJACENT,
        16
    );

    var result = extractor.extractWindow(window);

    assertThat(result.relationPatches()).containsExactly(
        new RefinedRelationPatch(
            new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
            List.of("chunk-1", "chunk-2")
        )
    );
}

@Test
void dropsWindowRelationsWhenSupportingChunkIndexesAreOutOfRange() {
    var extractor = new KnowledgeExtractor(new StubChatModel("""
        {
          "entities": [],
          "relations": [
            {
              "sourceEntityName": "订单系统",
              "targetEntityName": "PostgreSQL",
              "type": "依赖",
              "description": "bad indexes",
              "weight": 1.0,
              "supportingChunkIndexes": [3]
            }
          ],
          "warnings": []
        }
        """));
    var window = new RefinementWindow(
        "doc-1",
        List.of(chunk("chunk-1", "订单系统依赖"), chunk("chunk-2", "PostgreSQL 进行事务存储")),
        0,
        RefinementScope.ADJACENT,
        16
    );

    var result = extractor.extractWindow(window);

    assertThat(result.relationPatches()).isEmpty();
    assertThat(result.warnings()).contains("dropped relation candidate because supportingChunkIndexes were invalid");
}
```

- [ ] **Step 2: 在 `LightRagBuilderTest` 中补失败用例，锁定 Stage 1 静态开关语义**

```java
@Test
void keepsContextualExtractionRefinementDisabledByDefault() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .build();

    assertThat(rag.contextualExtractionRefinementEnabled()).isFalse();
    assertThat(rag.allowDeterministicAttributionFallback()).isFalse();
}

@Test
void enablesContextualExtractionRefinementWhenConfigured() {
    var rag = LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .contextualExtractionRefinement(true)
        .allowDeterministicAttributionFallback(true)
        .build();

    assertThat(rag.contextualExtractionRefinementEnabled()).isTrue();
    assertThat(rag.allowDeterministicAttributionFallback()).isTrue();
}
```

- [ ] **Step 3: 先跑窗口抽取和 builder 定向测试，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest.extractsWindowRelationsWithSupportingChunkIndexes" --tests "io.github.lightrag.indexing.KnowledgeExtractorTest.dropsWindowRelationsWhenSupportingChunkIndexesAreOutOfRange" --tests "io.github.lightrag.api.LightRagBuilderTest.keepsContextualExtractionRefinementDisabledByDefault" --tests "io.github.lightrag.api.LightRagBuilderTest.enablesContextualExtractionRefinementWhenConfigured" --offline --rerun-tasks`

Expected: FAIL，报 `extractWindow(...)`、refinement 相关 record、builder API 或 `LightRag` 访问方法不存在。

- [ ] **Step 4: 新增窗口抽取原始 DTO 与 refinement 选项对象**

```java
public record WindowEntityCandidate(
    String name,
    String type,
    String description,
    List<String> aliases,
    List<Integer> supportingChunkIndexes
) {}

public record WindowRelationCandidate(
    String sourceEntityName,
    String targetEntityName,
    String type,
    String description,
    Double weight,
    List<Integer> supportingChunkIndexes
) {}

public record WindowExtractionResponse(
    List<WindowEntityCandidate> entities,
    List<WindowRelationCandidate> relations,
    List<String> warnings
) {}

public record ExtractionRefinementOptions(
    boolean enabled,
    boolean allowDeterministicAttributionFallback,
    int maxWindowChunks,
    int maxWindowTokens,
    int maxRefinementPerDocument,
    int minPrescreenSignals,
    int minQualitySignals
) {
    public static ExtractionRefinementOptions disabled() {
        return new ExtractionRefinementOptions(false, false, 3, 1200, 1, 1, 1);
    }
}
```

- [ ] **Step 5: 在 `KnowledgeExtractor` 增加窗口抽取入口，并把 index 解析成 `RefinedWindowExtraction`**

```java
public RefinedWindowExtraction extractWindow(RefinementWindow window) {
    var response = chatModel.generate(new ChatRequest(buildSystemPrompt(), buildWindowPrompt(window)));
    var parsed = parseWindowExtractionResponse(response);
    var relationPatches = parsed.relations().stream()
        .map(candidate -> toRelationPatch(candidate, window))
        .flatMap(Optional::stream)
        .toList();
    var entityPatches = parsed.entities().stream()
        .map(candidate -> toEntityPatch(candidate, window))
        .flatMap(Optional::stream)
        .toList();
    return new RefinedWindowExtraction(
        entityPatches,
        relationPatches,
        parsed.warnings(),
        !relationPatches.isEmpty()
    );
}

private Optional<RefinedRelationPatch> toRelationPatch(WindowRelationCandidate candidate, RefinementWindow window) {
    var supportingChunkIds = resolveSupportingChunkIds(candidate.supportingChunkIndexes(), window);
    if (supportingChunkIds.isEmpty()) {
        return Optional.empty();
    }
    return Optional.of(new RefinedRelationPatch(
        new ExtractedRelation(
            candidate.sourceEntityName(),
            candidate.targetEntityName(),
            candidate.type(),
            candidate.description(),
            candidate.weight()
        ),
        supportingChunkIds
    ));
}
```

- [ ] **Step 6: 在 `LightRagBuilder` / `LightRag` 增加 Stage 1 静态开关并贯通到字段**

```java
private boolean contextualExtractionRefinementEnabled;
private boolean allowDeterministicAttributionFallback;

public LightRagBuilder contextualExtractionRefinement(boolean enabled) {
    this.contextualExtractionRefinementEnabled = enabled;
    return this;
}

public LightRagBuilder allowDeterministicAttributionFallback(boolean enabled) {
    this.allowDeterministicAttributionFallback = enabled;
    return this;
}
```

```java
private final boolean contextualExtractionRefinementEnabled;
private final boolean allowDeterministicAttributionFallback;

boolean contextualExtractionRefinementEnabled() {
    return contextualExtractionRefinementEnabled;
}

boolean allowDeterministicAttributionFallback() {
    return allowDeterministicAttributionFallback;
}
```

- [ ] **Step 7: 跑窗口抽取与 builder 回归，确认协议和开关通过**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest" --tests "io.github.lightrag.api.LightRagBuilderTest" --offline --rerun-tasks`

Expected: PASS，窗口抽取解析与 builder 静态开关测试通过，现有 `KnowledgeExtractorTest` / `LightRagBuilderTest` 不回归。

- [ ] **Step 8: 提交窗口协议与开关基础设施**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowEntityCandidate.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowRelationCandidate.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/WindowExtractionResponse.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementOptions.java \
  lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
  lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java \
  lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java
git commit -m "feat: add contextual extraction protocol and toggles"
```

### Task 2: 实现 gap detection、归因和 per-chunk merge

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/PrimaryChunkExtraction.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/GapAssessment.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementScope.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindow.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedEntityPatch.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedRelationPatch.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinedWindowExtraction.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ChunkExtractionPatch.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionGapDetector.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetector.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindowResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/AttributionResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionMergePolicy.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetectorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolverTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicyTest.java`

- [ ] **Step 1: 先写 gap detector 的 failing tests，锁定 `1 + 1` 触发规则与相邻窗口范围**

```java
@Test
void requestsAdjacentRefinementWhenPrescreenAndQualitySignalsBothMatch() {
    var detector = new DefaultExtractionGapDetector();
    var extractions = List.of(
        primary("chunk-1", "订单系统依赖", List.of(
            new ExtractedEntity("订单系统", "", "", List.of()),
            new ExtractedEntity("PostgreSQL", "", "", List.of())
        ), List.of())
    );

    var assessment = detector.assess(extractions, 0);

    assertThat(assessment.requiresRefinement()).isTrue();
    assertThat(assessment.recommendedScope()).isEqualTo(RefinementScope.ADJACENT);
    assertThat(assessment.prescreenSignals()).isNotEmpty();
    assertThat(assessment.qualitySignals()).contains("entities_without_relations");
}

@Test
void skipsRefinementWhenOnlyPrescreenMatches() {
    var detector = new DefaultExtractionGapDetector();
    var extractions = List.of(
        primary("chunk-1", "订单系统依赖 PostgreSQL", List.of(
            new ExtractedEntity("订单系统", "", "", List.of()),
            new ExtractedEntity("PostgreSQL", "", "", List.of())
        ), List.of(new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "完整关系", 1.0d)))
    );

    assertThat(detector.assess(extractions, 0).requiresRefinement()).isFalse();
}
```

- [ ] **Step 2: 写 attribution 和 merge 的 failing tests，锁定可归因 patch、端点闭合和确定性合并**

```java
@Test
void distributesRelationPatchToAllSupportingChunks() {
    var resolver = new DefaultAttributionResolver(false);
    var window = window("doc-1", chunk("chunk-1", "订单系统依赖"), chunk("chunk-2", "PostgreSQL 事务"));
    var refined = new RefinedWindowExtraction(
        List.of(),
        List.of(new RefinedRelationPatch(
            new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
            List.of("chunk-1", "chunk-2")
        )),
        List.of(),
        true
    );

    var patches = resolver.distribute(refined, window);

    assertThat(patches).hasSize(2);
    assertThat(patches).extracting(ChunkExtractionPatch::chunkId).containsExactly("chunk-1", "chunk-2");
}

@Test
void injectsMinimalEndpointEntitiesWhenChunkReceivesRelationPatch() {
    var mergePolicy = new DefaultExtractionMergePolicy();
    var primary = primary("chunk-1", "订单系统依赖", List.of(), List.of());
    var patch = new ChunkExtractionPatch(
        "chunk-1",
        List.of(),
        List.of(new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL", 1.0d))
    );

    var merged = mergePolicy.merge(primary, List.of(patch));

    assertThat(merged.extraction().entities()).contains(
        new ExtractedEntity("订单系统", "", "", List.of()),
        new ExtractedEntity("PostgreSQL", "", "", List.of())
    );
}
```

- [ ] **Step 3: 先跑 detector / attribution / merge 定向测试，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.refinement.DefaultExtractionGapDetectorTest" --tests "io.github.lightrag.indexing.refinement.DefaultAttributionResolverTest" --tests "io.github.lightrag.indexing.refinement.DefaultExtractionMergePolicyTest" --offline --rerun-tasks`

Expected: FAIL，报 refinement 子包类型和实现缺失。

- [ ] **Step 4: 实现最小 refinement 类型与默认 detector / window resolver**

```java
public record PrimaryChunkExtraction(Chunk chunk, ExtractionResult extraction) {}

public record GapAssessment(
    boolean requiresRefinement,
    List<String> prescreenSignals,
    List<String> qualitySignals,
    RefinementScope recommendedScope
) {}

public enum RefinementScope {
    NONE,
    ADJACENT,
    SECTION
}
```

```java
public final class DefaultExtractionGapDetector implements ExtractionGapDetector {
    @Override
    public GapAssessment assess(List<PrimaryChunkExtraction> extractions, int index) {
        var primary = extractions.get(index);
        var prescreenSignals = new ArrayList<String>();
        if (primary.chunk().text().strip().matches(".*(依赖|通过|使用|调用|用于|先|再)$")) {
            prescreenSignals.add("trailing_relation_keyword");
        }
        var qualitySignals = new ArrayList<String>();
        if (primary.extraction().entities().size() >= 2 && primary.extraction().relations().isEmpty()) {
            qualitySignals.add("entities_without_relations");
        }
        var enabled = !prescreenSignals.isEmpty() && !qualitySignals.isEmpty();
        return new GapAssessment(enabled, prescreenSignals, qualitySignals, enabled ? RefinementScope.ADJACENT : RefinementScope.NONE);
    }
}
```

```java
public final class DefaultRefinementWindowResolver implements RefinementWindowResolver {
    @Override
    public Optional<RefinementWindow> resolve(List<PrimaryChunkExtraction> extractions, int index, GapAssessment assessment) {
        if (!assessment.requiresRefinement()) {
            return Optional.empty();
        }
        int start = Math.max(0, index - 1);
        int end = Math.min(extractions.size() - 1, index + 1);
        var chunks = extractions.subList(start, end + 1).stream().map(PrimaryChunkExtraction::chunk).toList();
        return Optional.of(new RefinementWindow(chunks.get(0).documentId(), chunks, index - start, RefinementScope.ADJACENT, estimateTokens(chunks)));
    }
}
```

- [ ] **Step 5: 实现 attribution resolver 和 merge policy，保证 relation 分发与端点闭合**

```java
public final class DefaultAttributionResolver implements AttributionResolver {
    private final boolean allowDeterministicFallback;

    public DefaultAttributionResolver(boolean allowDeterministicFallback) {
        this.allowDeterministicFallback = allowDeterministicFallback;
    }

    @Override
    public List<ChunkExtractionPatch> distribute(RefinedWindowExtraction refinedWindow, RefinementWindow window) {
        var patches = new LinkedHashMap<String, ChunkExtractionPatch>();
        for (var relationPatch : refinedWindow.relationPatches()) {
            if (relationPatch.supportingChunkIds().isEmpty()) {
                continue;
            }
            for (var chunkId : relationPatch.supportingChunkIds()) {
                patches.compute(chunkId, (ignored, current) -> appendRelation(current, chunkId, relationPatch.relation()));
            }
        }
        return List.copyOf(patches.values());
    }
}
```

```java
public final class DefaultExtractionMergePolicy implements ExtractionMergePolicy {
    @Override
    public GraphAssembler.ChunkExtraction merge(PrimaryChunkExtraction primary, List<ChunkExtractionPatch> patchesForChunk) {
        var entities = new LinkedHashMap<String, ExtractedEntity>();
        primary.extraction().entities().forEach(entity -> entities.put(normalize(entity.name()), entity));
        var relations = new LinkedHashMap<String, ExtractedRelation>();
        primary.extraction().relations().forEach(relation -> relations.put(relationKey(relation), relation));
        for (var patch : patchesForChunk) {
            for (var relation : patch.relations()) {
                relations.merge(relationKey(relation), relation, this::mergeRelation);
                ensureEntityPresent(entities, relation.sourceEntityName());
                ensureEntityPresent(entities, relation.targetEntityName());
            }
        }
        return new GraphAssembler.ChunkExtraction(
            primary.chunk().id(),
            new ExtractionResult(List.copyOf(entities.values()), List.copyOf(relations.values()), primary.extraction().warnings())
        );
    }
}
```

- [ ] **Step 6: 跑 refinement core tests，确认触发、归因和 merge 通过**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.refinement.DefaultExtractionGapDetectorTest" --tests "io.github.lightrag.indexing.refinement.DefaultAttributionResolverTest" --tests "io.github.lightrag.indexing.refinement.DefaultExtractionMergePolicyTest" --offline --rerun-tasks`

Expected: PASS，Stage 1 规则、relation 分发、最小实体注入和确定性合并通过。

- [ ] **Step 7: 提交 refinement core 组件**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/refinement \
  lightrag-core/src/test/java/io/github/lightrag/indexing/refinement
git commit -m "feat: add contextual extraction refinement core"
```

### Task 3: 接入 `ExtractionRefinementPipeline` 到索引主干

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipelineTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineRefinementIntegrationTest.java`

- [ ] **Step 1: 写 failing pipeline tests，锁定“默认关闭不回归，开启后补边”**

```java
@Test
void returnsPrimaryExtractionsUnchangedWhenRefinementIsDisabled() {
    var pipeline = new ExtractionRefinementPipeline(
        ExtractionRefinementOptions.disabled(),
        new DefaultExtractionGapDetector(),
        new DefaultRefinementWindowResolver(),
        (window, extractions) -> { throw new AssertionError("should not refine"); },
        new DefaultAttributionResolver(false),
        new DefaultExtractionMergePolicy()
    );
    var primary = List.of(primary("chunk-1", "订单系统依赖", List.of(
        new ExtractedEntity("订单系统", "", "", List.of()),
        new ExtractedEntity("PostgreSQL", "", "", List.of())
    ), List.of()));

    var result = pipeline.refine(primary);

    assertThat(result).containsExactly(
        new GraphAssembler.ChunkExtraction("chunk-1", primary.get(0).extraction())
    );
}

@Test
void augmentsPrimaryExtractionsWhenAdjacentWindowProducesAttributedRelation() {
    var primary = List.of(
        primary("chunk-1", "订单系统依赖", List.of(new ExtractedEntity("订单系统", "", "", List.of())), List.of()),
        primary("chunk-2", "PostgreSQL 进行事务存储", List.of(new ExtractedEntity("PostgreSQL", "", "", List.of())), List.of())
    );
    var pipeline = new ExtractionRefinementPipeline(
        new ExtractionRefinementOptions(true, false, 3, 1200, 1, 1, 1),
        new DefaultExtractionGapDetector(),
        new DefaultRefinementWindowResolver(),
        (window, ignored) -> new RefinedWindowExtraction(
            List.of(),
            List.of(new RefinedRelationPatch(
                new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL 进行事务存储", 1.0d),
                List.of("chunk-1", "chunk-2")
            )),
            List.of(),
            true
        ),
        new DefaultAttributionResolver(false),
        new DefaultExtractionMergePolicy()
    );

    var result = pipeline.refine(primary);

    assertThat(result).allSatisfy(extraction -> assertThat(extraction.extraction().relations()).hasSize(1));
}
```

- [ ] **Step 2: 先跑 pipeline 定向测试，确认当前实现失败**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.refinement.ExtractionRefinementPipelineTest" --offline --rerun-tasks`

Expected: FAIL，报 `ExtractionRefinementPipeline` 缺失。

- [ ] **Step 3: 实现 pipeline 编排，并保持单文档内串行 refinement**

```java
public final class ExtractionRefinementPipeline {
    public List<GraphAssembler.ChunkExtraction> refine(List<PrimaryChunkExtraction> primaryExtractions) {
        if (!options.enabled()) {
            return primaryExtractions.stream()
                .map(primary -> new GraphAssembler.ChunkExtraction(primary.chunk().id(), primary.extraction()))
                .toList();
        }
        var patchesByChunkId = new LinkedHashMap<String, List<ChunkExtractionPatch>>();
        int refinements = 0;
        for (int index = 0; index < primaryExtractions.size() && refinements < options.maxRefinementPerDocument(); index++) {
            var assessment = detector.assess(primaryExtractions, index);
            var window = windowResolver.resolve(primaryExtractions, index, assessment).orElse(null);
            if (window == null) {
                continue;
            }
            var refined = refiner.refine(window, primaryExtractions);
            for (var patch : attributionResolver.distribute(refined, window)) {
                patchesByChunkId.computeIfAbsent(patch.chunkId(), ignored -> new ArrayList<>()).add(patch);
            }
            refinements++;
        }
        return primaryExtractions.stream()
            .map(primary -> mergePolicy.merge(primary, patchesByChunkId.getOrDefault(primary.chunk().id(), List.of())))
            .toList();
    }
}
```

- [ ] **Step 4: 在 `IndexingPipeline.computeDocument(...)` 中接入 refinement pipeline**

```java
var primaryExtractions = chunks.stream()
    .map(chunk -> new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
    .toList();
var graph = graphAssembler.assemble(extractionRefinementPipeline.refine(primaryExtractions));
```

```java
this.extractionRefinementPipeline = new ExtractionRefinementPipeline(
    contextualExtractionRefinementEnabled
        ? new ExtractionRefinementOptions(true, allowDeterministicAttributionFallback, 3, 1200, 1, 1, 1)
        : ExtractionRefinementOptions.disabled(),
    new DefaultExtractionGapDetector(),
    new DefaultRefinementWindowResolver(),
    knowledgeExtractor::extractWindow,
    new DefaultAttributionResolver(allowDeterministicAttributionFallback),
    new DefaultExtractionMergePolicy()
);
```

- [ ] **Step 5: 写并跑 `IndexingPipelineRefinementIntegrationTest`，验证完整索引链路能补边**

```java
@Test
void addsRelationVectorsAfterRefinementFillsBrokenEdge() {
    var extractionModel = new SequentialChatModel(
        """
        {"entities":[{"name":"订单系统","type":"system","description":"","aliases":[]}],"relations":[]}
        """,
        """
        {"entities":[{"name":"PostgreSQL","type":"database","description":"","aliases":[]}],"relations":[]}
        """,
        """
        {
          "entities": [],
          "relations": [
            {
              "sourceEntityName": "订单系统",
              "targetEntityName": "PostgreSQL",
              "type": "依赖",
              "description": "订单系统依赖 PostgreSQL 进行事务存储",
              "weight": 1.0,
              "supportingChunkIndexes": [0, 1]
            }
          ],
          "warnings": []
        }
        """
    );
    var rag = LightRag.builder()
        .chatModel(extractionModel)
        .embeddingModel(new FakeEmbeddingModel())
        .storage(InMemoryStorageProvider.create())
        .contextualExtractionRefinement(true)
        .build();

    rag.ingest("default", List.of(new Document("doc-1", "title", "订单系统依赖 PostgreSQL 进行事务存储", Map.of())));

    assertThat(rag.config().workspaceStorageProvider().forWorkspace(new WorkspaceScope("default")).graphStore().allRelations())
        .extracting(GraphStore.RelationRecord::type)
        .contains("依赖");
}
```

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.refinement.ExtractionRefinementPipelineTest" --tests "io.github.lightrag.indexing.IndexingPipelineRefinementIntegrationTest" --offline --rerun-tasks`

Expected: PASS，默认关闭无回归，开启时完整索引链路能把断裂关系补出来。

- [ ] **Step 6: 提交 refinement pipeline 和索引接入**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
  lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipelineTest.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineRefinementIntegrationTest.java
git commit -m "feat: integrate contextual extraction refinement into indexing"
```

### Task 4: 收尾回归、默认关闭验证和最小文档说明

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`
- Modify: `docs/superpowers/specs/2026-04-09-java-lightrag-contextual-extraction-refiner-design.md`

- [ ] **Step 1: 增加 regression tests，确认 deterministic fallback 默认关闭且不会隐式启用**

```java
@Test
void doesNotUseDeterministicAttributionFallbackByDefault() {
    var resolver = new DefaultAttributionResolver(false);
    var window = window("doc-1", chunk("chunk-1", "订单系统依赖"));
    var refined = new RefinedWindowExtraction(
        List.of(),
        List.of(new RefinedRelationPatch(
            new ExtractedRelation("订单系统", "PostgreSQL", "依赖", "订单系统依赖 PostgreSQL", 1.0d),
            List.of()
        )),
        List.of(),
        true
    );

    assertThat(resolver.distribute(refined, window)).isEmpty();
}
```

- [ ] **Step 2: 跑 Stage 1 相关全量测试集合**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest" --tests "io.github.lightrag.indexing.GraphAssemblerTest" --tests "io.github.lightrag.indexing.refinement.*" --tests "io.github.lightrag.indexing.IndexingPipelineRefinementIntegrationTest" --tests "io.github.lightrag.api.LightRagBuilderTest" --offline --rerun-tasks`

Expected: PASS，KnowledgeExtractor / GraphAssembler / refinement / builder 回归全部通过。

- [ ] **Step 3: 跑 `lightrag-core` 受影响模块回归**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --offline --rerun-tasks`

Expected: PASS，`lightrag-core:test` 全量通过；如果有手工或网络依赖测试，明确记录并排除。

- [ ] **Step 4: 在 spec 文件末尾补一段实现状态备注，标记 Stage 1 已落地范围**

```markdown
## Implementation Status

- Stage 1 implements adjacent-window refinement only.
- Section expansion remains deferred.
- Dynamic hot-switching remains out of scope for the library layer.
- Deterministic attribution fallback is implemented but disabled by default.
```

- [ ] **Step 5: 提交回归与状态备注**

```bash
git add lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java \
  lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java \
  docs/superpowers/specs/2026-04-09-java-lightrag-contextual-extraction-refiner-design.md
git commit -m "test: finalize contextual extraction refinement coverage"
```

## Self-Review

### Spec Coverage Check

- “规则预筛 + 质量复判” -> Task 2
- “相邻窗口优先” -> Task 2 / Task 3
- “窗口结果回写细 chunk” -> Task 2 / Task 3
- “最小 DTO / schema / 校验” -> Task 1
- “与 GraphAssembler 契约对齐” -> Task 2 / Task 3
- “默认关闭、成本上限、静态配置” -> Task 1 / Task 3
- “分层测试” -> Task 1 / Task 2 / Task 3 / Task 4

未覆盖项：
- section 扩窗、动态热切换、复杂 deterministic 归因算法

这些都属于本计划明确排除的 Stage 2/3 范围，不是遗漏。

### Placeholder Scan

已检查：
- 无 `TODO / TBD / implement later`
- 每个任务都包含具体文件、代码片段、命令和预期结果
- 没有 “类似 Task N” 这类跨任务省略写法

### Type Consistency Check

- `WindowExtractionResponse` -> `RefinedWindowExtraction` -> `ChunkExtractionPatch` -> `GraphAssembler.ChunkExtraction` 链路已在 Task 1-3 中一致命名
- builder 开关名统一为：
  - `contextualExtractionRefinement(boolean)`
  - `allowDeterministicAttributionFallback(boolean)`
- pipeline 入口统一为：
  - `ExtractionRefinementPipeline.refine(List<PrimaryChunkExtraction>)`

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-09-java-lightrag-contextual-extraction-refiner.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
