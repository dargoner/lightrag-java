# Java LightRAG Query Metadata Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `metadataFilters` and `metadataConditions` to Java LightRAG query APIs, then enforce shared metadata filtering semantics across query strategies with early application and post-filter correctness fallback.

**Architecture:** Extend the public `QueryRequest` API with normalized metadata filter inputs, introduce a small shared query-layer filtering core (`MetadataFilterNormalizer`, `MetadataMatcher`, `MetadataPushdownPlanner`, and a thin strategy helper), and wire `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, `MIX`, `MULTI_HOP`, and `QueryEngine` through the same filter plan. Keep storage adapters unchanged in this phase and treat query-layer early filtering plus unified fallback matching as the compatibility baseline.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing `QueryRequest`, `QueryEngine`, query strategies in `lightrag-core`, and the current in-memory/query test suite.

---

## File Structure

**Create**

- `lightrag-core/src/main/java/io/github/lightrag/api/MetadataCondition.java`
- `lightrag-core/src/main/java/io/github/lightrag/api/MetadataOperator.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterPlan.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterNormalizer.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MetadataMatcher.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MetadataPushdownPlanner.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryMetadataFilterSupport.java`
- `lightrag-core/src/test/java/io/github/lightrag/api/QueryRequestTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/MetadataFilterNormalizerTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/MetadataMatcherTest.java`

**Modify**

- `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/NaiveQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/MultiHopQueryStrategy.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/NaiveQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/GlobalQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/HybridQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/MixQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/MultiHopQueryStrategyTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

**Reference During Implementation**

- `docs/superpowers/specs/2026-04-14-java-lightrag-query-metadata-filtering-design.md`
- `lightrag-core/src/main/java/io/github/lightrag/storage/ChunkStore.java`
- `lightrag-core/src/main/java/io/github/lightrag/types/Chunk.java`

## Task 1: Extend QueryRequest With Public Metadata Filter APIs

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/MetadataCondition.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/MetadataOperator.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/api/QueryRequestTest.java`

- [ ] **Step 1: Write the failing API tests**

```java
@Test
void builderDefaultsMetadataFiltersAndConditionsToEmptyCollections() {
    var request = QueryRequest.builder()
        .query("metadata aware search")
        .build();

    assertThat(request.metadataFilters()).isEmpty();
    assertThat(request.metadataConditions()).isEmpty();
}

@Test
void builderNormalizesScalarAndCollectionMetadataFilters() {
    var request = QueryRequest.builder()
        .query("metadata aware search")
        .metadataFilters(Map.of(
            "region", List.of("shanghai", "beijing", "shanghai"),
            "docType", "policy"
        ))
        .build();

    assertThat(request.metadataFilters())
        .containsEntry("region", List.of("shanghai", "beijing"))
        .containsEntry("docType", List.of("policy"));
}

@Test
void builderRejectsInvalidMetadataConditionPayload() {
    assertThatThrownBy(() -> QueryRequest.builder()
        .query("metadata aware search")
        .metadataConditions(List.of(new MetadataCondition("publishDate", MetadataOperator.BEFORE, Map.of("bad", "value"))))
        .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("publishDate");
}
```

- [ ] **Step 2: Run the focused API test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.QueryRequestTest"`

Expected: FAIL because `QueryRequest`, `MetadataCondition`, and `MetadataOperator` do not yet expose metadata filter fields.

- [ ] **Step 3: Implement the public API additions with normalized defaults**

```java
// MetadataOperator.java
public enum MetadataOperator {
    EQ,
    IN,
    GT,
    GTE,
    LT,
    LTE,
    BEFORE,
    AFTER
}
```

```java
// MetadataCondition.java
public record MetadataCondition(
    String field,
    MetadataOperator operator,
    Object value
) {
    public MetadataCondition {
        field = Objects.requireNonNull(field, "field").trim();
        operator = Objects.requireNonNull(operator, "operator");
        value = Objects.requireNonNull(value, "value");
        if (field.isEmpty()) {
            throw new IllegalArgumentException("field must not be blank");
        }
    }
}
```

```java
// QueryRequest.java builder additions
private Map<String, List<String>> metadataFilters = Map.of();
private List<MetadataCondition> metadataConditions = List.of();

public Builder metadataFilters(Map<String, ?> metadataFilters) {
    this.metadataFilters = MetadataFilterNormalizer.normalizeFilters(metadataFilters);
    return this;
}

public Builder metadataConditions(List<MetadataCondition> metadataConditions) {
    this.metadataConditions = MetadataFilterNormalizer.normalizeConditions(metadataConditions);
    return this;
}
```

- [ ] **Step 4: Re-run the focused API test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.QueryRequestTest"`

Expected: PASS with normalized filter defaults and public metadata condition types in place.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/api/MetadataCondition.java \
  lightrag-core/src/main/java/io/github/lightrag/api/MetadataOperator.java \
  lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java \
  lightrag-core/src/test/java/io/github/lightrag/api/QueryRequestTest.java
git commit -m "feat: add query metadata filter request api"
```

## Task 2: Add Shared Normalization, Planning, And Matching Semantics

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterPlan.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterNormalizer.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/MetadataMatcher.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/MetadataPushdownPlanner.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/MetadataFilterNormalizerTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/MetadataMatcherTest.java`

- [ ] **Step 1: Write the failing normalization and matcher tests**

```java
@Test
void normalizerBuildsPlanFromFiltersAndConditions() {
    var plan = MetadataFilterNormalizer.normalize(
        Map.of("region", List.of("shanghai", "beijing"), "docType", "policy"),
        List.of(new MetadataCondition("score", MetadataOperator.GTE, 80))
    );

    assertThat(plan.normalizedFilters())
        .containsEntry("region", List.of("shanghai", "beijing"))
        .containsEntry("docType", List.of("policy"));
    assertThat(plan.normalizedConditions()).hasSize(1);
}

@Test
void matcherSupportsNumericAndDateComparisons() {
    var plan = MetadataFilterNormalizer.normalize(
        Map.of("region", "shanghai"),
        List.of(
            new MetadataCondition("score", MetadataOperator.GTE, 80),
            new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01")
        )
    );

    assertThat(MetadataMatcher.matches(plan, Map.of(
        "region", "shanghai",
        "score", "88.5",
        "publishDate", "2024-06-01"
    ))).isTrue();
}

@Test
void matcherTreatsBadStoredValuesAsNonMatchesInsteadOfFailingQuery() {
    var plan = MetadataFilterNormalizer.normalize(
        Map.of(),
        List.of(new MetadataCondition("score", MetadataOperator.GT, 10))
    );

    assertThat(MetadataMatcher.matches(plan, Map.of("score", "not-a-number"))).isFalse();
}
```

- [ ] **Step 2: Run the focused helper tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.MetadataFilterNormalizerTest" --tests "io.github.lightrag.query.MetadataMatcherTest"`

Expected: FAIL because the shared filter plan, normalizer, and matcher do not exist yet.

- [ ] **Step 3: Implement the shared filter plan, normalization, and matcher**

```java
// MetadataFilterPlan.java
public record MetadataFilterPlan(
    Map<String, List<String>> normalizedFilters,
    List<NormalizedMetadataCondition> normalizedConditions,
    boolean hasEarlyApplicableFilters
) {
    public boolean isEmpty() {
        return normalizedFilters.isEmpty() && normalizedConditions.isEmpty();
    }
}
```

```java
// MetadataFilterNormalizer.java
static MetadataFilterPlan normalize(Map<String, ?> filters, List<MetadataCondition> conditions) {
    var normalizedFilters = normalizeFilters(filters);
    var normalizedConditions = normalizeConditions(conditions).stream()
        .map(NormalizedMetadataCondition::from)
        .toList();
    return new MetadataFilterPlan(normalizedFilters, normalizedConditions,
        !normalizedFilters.isEmpty() || normalizedConditions.stream().allMatch(NormalizedMetadataCondition::supportsEarlyApplication));
}
```

```java
// MetadataMatcher.java
static boolean matches(MetadataFilterPlan plan, Map<String, String> metadata) {
    if (plan == null || plan.isEmpty()) {
        return true;
    }
    var resolvedMetadata = metadata == null ? Map.<String, String>of() : metadata;
    for (var filter : plan.normalizedFilters().entrySet()) {
        var actual = resolvedMetadata.get(filter.getKey());
        if (actual == null || filter.getValue().stream().noneMatch(candidate -> candidate.equals(actual.trim()))) {
            return false;
        }
    }
    for (var condition : plan.normalizedConditions()) {
        if (!condition.matches(resolvedMetadata.get(condition.field()))) {
            return false;
        }
    }
    return true;
}
```

- [ ] **Step 4: Re-run the focused helper tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.MetadataFilterNormalizerTest" --tests "io.github.lightrag.query.MetadataMatcherTest"`

Expected: PASS with stable normalization rules and shared matching semantics for `EQ`, `IN`, numeric, and date operators.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterPlan.java \
  lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterNormalizer.java \
  lightrag-core/src/main/java/io/github/lightrag/query/MetadataMatcher.java \
  lightrag-core/src/main/java/io/github/lightrag/query/MetadataPushdownPlanner.java \
  lightrag-core/src/test/java/io/github/lightrag/query/MetadataFilterNormalizerTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/MetadataMatcherTest.java
git commit -m "feat: add shared query metadata filter semantics"
```

## Task 3: Apply Metadata Filters In Naive, Local, And Global Retrieval

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/QueryMetadataFilterSupport.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/NaiveQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/NaiveQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/GlobalQueryStrategyTest.java`

- [ ] **Step 1: Add failing strategy tests for chunk-level metadata filtering**

```java
@Test
void naiveStrategyDropsChunksThatDoNotMatchMetadataFilters() {
    var context = strategy.retrieve(QueryRequest.builder()
        .query("policy")
        .chunkTopK(5)
        .metadataFilters(Map.of("region", "shanghai"))
        .build());

    assertThat(context.matchedChunks())
        .extracting(chunk -> chunk.chunk().id())
        .containsExactly("chunk-shanghai");
}

@Test
void localStrategyKeepsOnlyChunksMatchingStructuredConditions() {
    var context = strategy.retrieve(QueryRequest.builder()
        .query("latest score policy")
        .metadataConditions(List.of(new MetadataCondition("score", MetadataOperator.GTE, 90)))
        .build());

    assertThat(context.matchedChunks())
        .allSatisfy(chunk -> assertThat(chunk.chunk().metadata()).containsEntry("score", "90"));
}
```

- [ ] **Step 2: Run the focused strategy tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.NaiveQueryStrategyTest" --tests "io.github.lightrag.query.LocalQueryStrategyTest" --tests "io.github.lightrag.query.GlobalQueryStrategyTest"`

Expected: FAIL because the strategies do not yet consult metadata filter plans.

- [ ] **Step 3: Implement shared strategy support and early filtering in the three base strategies**

```java
// QueryMetadataFilterSupport.java
final class QueryMetadataFilterSupport {
    MetadataFilterPlan plan(QueryRequest request) {
        return MetadataFilterNormalizer.from(request);
    }

    List<ScoredChunk> filterChunks(QueryRequest request, List<ScoredChunk> chunks) {
        var plan = plan(request);
        if (plan.isEmpty()) {
            return chunks;
        }
        return chunks.stream()
            .filter(chunk -> MetadataMatcher.matches(plan, chunk.chunk().metadata()))
            .toList();
    }
}
```

```java
// NaiveQueryStrategy.java
var matchedChunks = VectorSearches.search(...)
    .stream()
    .map(...)
    .filter(Objects::nonNull)
    .sorted(scoreOrder())
    .toList();
var filteredChunks = metadataFilterSupport.filterChunks(query, matchedChunks);
var expandedChunks = parentChunkExpander.expand(filteredChunks, query.chunkTopK());
```

```java
// LocalQueryStrategy.java and GlobalQueryStrategy.java
var matchedChunks = parentChunkExpander.expand(
    metadataFilterSupport.filterChunks(query, collectChunks(...)),
    query.chunkTopK()
);
```

- [ ] **Step 4: Re-run the focused strategy tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.NaiveQueryStrategyTest" --tests "io.github.lightrag.query.LocalQueryStrategyTest" --tests "io.github.lightrag.query.GlobalQueryStrategyTest"`

Expected: PASS with chunk-level metadata filtering applied before final context assembly in the three core retrieval strategies.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/QueryMetadataFilterSupport.java \
  lightrag-core/src/main/java/io/github/lightrag/query/NaiveQueryStrategy.java \
  lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java \
  lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java \
  lightrag-core/src/test/java/io/github/lightrag/query/NaiveQueryStrategyTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/GlobalQueryStrategyTest.java
git commit -m "feat: apply metadata filters in core query strategies"
```

## Task 4: Preserve Filtering Through Hybrid, Mix, Multi-Hop, And QueryEngine

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/MultiHopQueryStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/HybridQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/MixQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/MultiHopQueryStrategyTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write failing tests for merged modes and end-to-end query execution**

```java
@Test
void hybridStrategyDoesNotReintroduceChunksFilteredOutByMetadata() {
    var context = strategy.retrieve(QueryRequest.builder()
        .query("regional policy")
        .metadataFilters(Map.of("region", "shanghai"))
        .build());

    assertThat(context.matchedChunks())
        .allSatisfy(chunk -> assertThat(chunk.chunk().metadata()).containsEntry("region", "shanghai"));
}

@Test
void queryEngineHonorsMetadataConditionsWithoutBreakingReferences() {
    var result = engine.query(QueryRequest.builder()
        .query("latest policy")
        .includeReferences(true)
        .metadataConditions(List.of(new MetadataCondition("publishDate", MetadataOperator.AFTER, "2024-01-01")))
        .build());

    assertThat(result.contexts()).allSatisfy(context -> assertThat(context.referenceId()).isNotBlank());
    assertThat(result.contexts()).allSatisfy(context -> assertThat(context.text()).isNotBlank());
}
```

- [ ] **Step 2: Run the merged-mode and engine tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.HybridQueryStrategyTest" --tests "io.github.lightrag.query.MixQueryStrategyTest" --tests "io.github.lightrag.query.MultiHopQueryStrategyTest" --tests "io.github.lightrag.query.QueryEngineTest"`

Expected: FAIL because merged strategies and `QueryEngine` do not yet guarantee shared metadata filtering behavior.

- [ ] **Step 3: Implement final-plan propagation and fallback matching for merged modes**

```java
// HybridQueryStrategy.java / MixQueryStrategy.java
var mergedChunks = mergedChunks.values().stream()
    .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
    .toList();
var filteredChunks = metadataFilterSupport.filterChunks(request, mergedChunks);
```

```java
// MultiHopQueryStrategy.java
var seedContext = baseStrategy.retrieve(request);
var filteredChunks = metadataFilterSupport.filterChunks(request, seedContext.matchedChunks());
return new QueryContext(seedContext.matchedEntities(), seedContext.matchedRelations(), filteredChunks, assembledContext);
```

```java
// QueryEngine.java
var filteredContext = new QueryContext(
    context.matchedEntities(),
    context.matchedRelations(),
    metadataFilterSupport.filterChunks(resolvedQuery, context.matchedChunks()),
    context.contextText()
);
```

- [ ] **Step 4: Re-run the merged-mode and engine tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.HybridQueryStrategyTest" --tests "io.github.lightrag.query.MixQueryStrategyTest" --tests "io.github.lightrag.query.MultiHopQueryStrategyTest" --tests "io.github.lightrag.query.QueryEngineTest"`

Expected: PASS with metadata filters preserved across merged retrieval modes and end-to-end query flows.

- [ ] **Step 5: Run the broader core regression set and verify it stays green**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.QueryRequestTest" --tests "io.github.lightrag.query.MetadataFilterNormalizerTest" --tests "io.github.lightrag.query.MetadataMatcherTest" --tests "io.github.lightrag.query.NaiveQueryStrategyTest" --tests "io.github.lightrag.query.LocalQueryStrategyTest" --tests "io.github.lightrag.query.GlobalQueryStrategyTest" --tests "io.github.lightrag.query.HybridQueryStrategyTest" --tests "io.github.lightrag.query.MixQueryStrategyTest" --tests "io.github.lightrag.query.MultiHopQueryStrategyTest" --tests "io.github.lightrag.query.QueryEngineTest"`

Expected: PASS across the metadata filter API, matcher semantics, query strategies, and query engine regression coverage.

- [ ] **Step 6: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java \
  lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java \
  lightrag-core/src/main/java/io/github/lightrag/query/MultiHopQueryStrategy.java \
  lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
  lightrag-core/src/test/java/io/github/lightrag/query/HybridQueryStrategyTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/MixQueryStrategyTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/MultiHopQueryStrategyTest.java \
  lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: enforce metadata filters across merged query modes"
```

## Self-Review

### Spec coverage

- Public API additions in the spec are covered by Task 1.
- Shared normalization, typed comparison rules, and fallback semantics are covered by Task 2.
- Early application in `NAIVE`, `LOCAL`, and `GLOBAL` is covered by Task 3.
- Consistent behavior in `HYBRID`, `MIX`, `MULTI_HOP`, and `QueryEngine` is covered by Task 4.
- TDD order in the spec is preserved across the four tasks.

### Placeholder scan

- No `TODO`, `TBD`, or deferred implementation placeholders remain.
- Every task includes explicit files, tests, commands, expected failures, implementation snippets, and commit commands.

### Type consistency

- Public API names match the spec: `metadataFilters`, `metadataConditions`, `MetadataCondition`, and `MetadataOperator`.
- Shared helper names are consistent across tasks: `MetadataFilterNormalizer`, `MetadataMatcher`, `MetadataPushdownPlanner`, and `QueryMetadataFilterSupport`.
- Query strategy integration consistently filters `ScoredChunk` results using chunk metadata.
