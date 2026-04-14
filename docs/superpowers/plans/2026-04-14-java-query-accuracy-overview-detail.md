# Query Accuracy For Overview And Detail Questions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve query accuracy for overview and detail questions by classifying broad summary-style prompts separately from detail prompts, then shaping answer wording with a lightweight evidence-confidence gate.

**Architecture:** Keep the current `QueryEngine` retrieval pipeline intact and add only three thin layers: a clearer `QueryIntent` split, an internal `EvidenceCoverageAdvisor`, and prompt shaping that produces summary/category/guidance answers for overview questions while making detail answers refuse unsupported guesses. This plan explicitly avoids planners, topic graphs, storage changes, and public API changes.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, existing `QueryEngine`, `RuleBasedQueryIntentClassifier`, `ContextAssembler`, and query test suite in `lightrag-core`.

---

## File Structure

**Create**

- `lightrag-core/src/main/java/io/github/lightrag/query/EvidenceCoverageAdvisor.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/EvidenceCoverageAdvisorTest.java`

**Modify**

- `lightrag-core/src/main/java/io/github/lightrag/query/QueryIntent.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/RuleBasedQueryIntentClassifierTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

**Reference During Implementation**

- `docs/superpowers/specs/2026-04-14-java-query-accuracy-overview-detail-design.md`
- `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java`

## Task 1: Split Query Intents Into Overview And Detail

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryIntent.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/RuleBasedQueryIntentClassifierTest.java`

- [ ] **Step 1: Extend the classifier test with overview and detail expectations**

```java
@Test
void classifiesOverviewQuestionAsOverview() {
    var classifier = new RuleBasedQueryIntentClassifier();

    var intent = classifier.classify(QueryRequest.builder()
        .query("这个系统支持哪些数据库？")
        .build());

    assertThat(intent).isEqualTo(QueryIntent.OVERVIEW);
}

@Test
void classifiesEnglishOverviewQuestionAsOverview() {
    var classifier = new RuleBasedQueryIntentClassifier();

    var intent = classifier.classify(QueryRequest.builder()
        .query("What capabilities does this system support?")
        .build());

    assertThat(intent).isEqualTo(QueryIntent.OVERVIEW);
}

@Test
void defaultsToDetailForSimpleQuestion() {
    var classifier = new RuleBasedQueryIntentClassifier();

    var intent = classifier.classify(QueryRequest.builder()
        .query("谁负责 GraphStore？")
        .build());

    assertThat(intent).isEqualTo(QueryIntent.DETAIL);
}
```

- [ ] **Step 2: Run the classifier test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.RuleBasedQueryIntentClassifierTest"`

Expected: FAIL because `QueryIntent` does not yet contain `OVERVIEW` or `DETAIL`, and the classifier still returns `FACT`.

- [ ] **Step 3: Update the intent enum and rule-based classifier with minimal overview rules**

```java
// QueryIntent.java
public enum QueryIntent {
    OVERVIEW,
    DETAIL,
    RELATION,
    MULTI_HOP
}
```

```java
// RuleBasedQueryIntentClassifier.java
private static final List<String> OVERVIEW_SIGNALS = List.of(
    "有哪些",
    "哪几类",
    "分为哪些",
    "支持哪些",
    "主要模块",
    "核心能力",
    "总体上",
    "what types",
    "what categories",
    "what modules",
    "what capabilities",
    "supported"
);

@Override
public QueryIntent classify(QueryRequest request) {
    var normalized = Objects.requireNonNull(request, "request").query().strip().toLowerCase(Locale.ROOT);
    if (normalized.contains("通过")
        || normalized.contains("经过")
        || normalized.contains("间接")
        || normalized.contains("多跳")
        || normalized.contains("through")
        || normalized.contains("via")
        || normalized.contains("indirect")
        || normalized.contains("multi-hop")
        || normalized.contains("multihop")
        || (normalized.contains("先") && normalized.contains("再"))
        || (normalized.contains("first") && normalized.contains("then"))) {
        return QueryIntent.MULTI_HOP;
    }
    if (normalized.contains("关系") || normalized.contains("relationship")) {
        return QueryIntent.RELATION;
    }
    if (OVERVIEW_SIGNALS.stream().anyMatch(normalized::contains)) {
        return QueryIntent.OVERVIEW;
    }
    return QueryIntent.DETAIL;
}
```

- [ ] **Step 4: Re-run the classifier test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.RuleBasedQueryIntentClassifierTest"`

Expected: PASS with overview questions classified as `OVERVIEW` and simple factual questions classified as `DETAIL`.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/QueryIntent.java \
  lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java \
  lightrag-core/src/test/java/io/github/lightrag/query/RuleBasedQueryIntentClassifierTest.java
git commit -m "feat: distinguish overview and detail query intents"
```

## Task 2: Add A Lightweight Evidence Coverage Advisor

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/EvidenceCoverageAdvisor.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/EvidenceCoverageAdvisorTest.java`

- [ ] **Step 1: Write failing tests for overview partial coverage and detail abstention**

```java
@Test
void overviewWithSingleSourceIsMarkedAsPartialCoverage() {
    var advisor = new EvidenceCoverageAdvisor();

    var coverage = advisor.assess(
        QueryIntent.OVERVIEW,
        new QueryContext(
            List.of(),
            List.of(),
            List.of(new ScoredChunk(
                "chunk-1",
                new Chunk("chunk-1", "doc-1", "Postgres storage support", 3, 0, Map.of("source", "guide-a")),
                0.9d
            )),
            ""
        )
    );

    assertThat(coverage.level()).isEqualTo(EvidenceCoverageAdvisor.ConfidenceLevel.PARTIAL_COVERAGE);
    assertThat(coverage.overviewGuidanceEnabled()).isTrue();
}

@Test
void detailWithoutAnyGroundedEvidenceIsMarkedAsInsufficient() {
    var advisor = new EvidenceCoverageAdvisor();

    var coverage = advisor.assess(QueryIntent.DETAIL, new QueryContext(List.of(), List.of(), List.of(), ""));

    assertThat(coverage.level()).isEqualTo(EvidenceCoverageAdvisor.ConfidenceLevel.INSUFFICIENT_EVIDENCE);
}
```

- [ ] **Step 2: Run the focused advisor test to verify it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.EvidenceCoverageAdvisorTest"`

Expected: FAIL because the advisor class does not exist yet.

- [ ] **Step 3: Implement the minimal advisor and confidence model**

```java
package io.github.lightrag.query;

import io.github.lightrag.types.QueryContext;

import java.util.Locale;
import java.util.Objects;

public final class EvidenceCoverageAdvisor {
    public EvidenceCoverage assess(QueryIntent intent, QueryContext context) {
        var resolvedIntent = Objects.requireNonNull(intent, "intent");
        var resolvedContext = Objects.requireNonNull(context, "context");
        return switch (resolvedIntent) {
            case OVERVIEW -> assessOverview(resolvedContext);
            case DETAIL -> assessDetail(resolvedContext);
            case RELATION -> assessRelation(resolvedContext);
            case MULTI_HOP -> new EvidenceCoverage(ConfidenceLevel.HIGH_CONFIDENCE, false);
        };
    }

    private static EvidenceCoverage assessOverview(QueryContext context) {
        var distinctSources = context.matchedChunks().stream()
            .map(chunk -> chunk.chunk().metadata().getOrDefault("source", chunk.chunk().documentId()).toLowerCase(Locale.ROOT))
            .distinct()
            .count();
        if (context.matchedChunks().size() < 2 || distinctSources < 2) {
            return new EvidenceCoverage(ConfidenceLevel.PARTIAL_COVERAGE, true);
        }
        return new EvidenceCoverage(ConfidenceLevel.HIGH_CONFIDENCE, true);
    }

    private static EvidenceCoverage assessDetail(QueryContext context) {
        if (context.matchedChunks().isEmpty() && context.matchedEntities().isEmpty() && context.matchedRelations().isEmpty()) {
            return new EvidenceCoverage(ConfidenceLevel.INSUFFICIENT_EVIDENCE, false);
        }
        return new EvidenceCoverage(ConfidenceLevel.HIGH_CONFIDENCE, false);
    }

    private static EvidenceCoverage assessRelation(QueryContext context) {
        if (context.matchedRelations().isEmpty() && context.matchedEntities().size() < 2) {
            return new EvidenceCoverage(ConfidenceLevel.INSUFFICIENT_EVIDENCE, false);
        }
        return new EvidenceCoverage(ConfidenceLevel.HIGH_CONFIDENCE, false);
    }

    public record EvidenceCoverage(ConfidenceLevel level, boolean overviewGuidanceEnabled) {
        public EvidenceCoverage {
            level = Objects.requireNonNull(level, "level");
        }
    }

    public enum ConfidenceLevel {
        HIGH_CONFIDENCE,
        PARTIAL_COVERAGE,
        INSUFFICIENT_EVIDENCE
    }
}
```

- [ ] **Step 4: Run the focused advisor test to verify it passes**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.EvidenceCoverageAdvisorTest"`

Expected: PASS with overview partial coverage and detail insufficient evidence both covered.

- [ ] **Step 5: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/EvidenceCoverageAdvisor.java \
  lightrag-core/src/test/java/io/github/lightrag/query/EvidenceCoverageAdvisorTest.java
git commit -m "feat: add query evidence coverage advisor"
```

## Task 3: Inject Overview Summary Guidance And Detail Abstention Into QueryEngine

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write failing query engine tests for overview guidance and detail abstention**

```java
@Test
void overviewQuestionsUseSummaryGroupingAndExpansionGuidance() {
    var chatModel = new RecordingChatModel();
    var strategy = new RecordingQueryStrategy(new QueryContext(
        List.of(),
        List.of(),
        List.of(
            scoredChunk("chunk-1", "PostgreSQL storage support", 0.95d),
            scoredChunk("chunk-2", "Neo4j graph support", 0.90d)
        ),
        ""
    ));
    var engine = new QueryEngine(
        chatModel,
        new ContextAssembler(),
        strategiesReturning(strategy),
        null,
        false,
        2,
        new RuleBasedQueryIntentClassifier(),
        null,
        new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
    );

    engine.query(QueryRequest.builder()
        .query("这个系统支持哪些数据库？")
        .mode(QueryMode.MIX)
        .build());

    assertThat(chatModel.lastRequest().systemPrompt())
        .contains("start with a short high-level summary")
        .contains("organize the answer into 2-5 grouped categories")
        .contains("continue by module")
        .doesNotContain("基于当前命中结果")
        .doesNotContain("可能未覆盖全部");
}

@Test
void detailQuestionsWithInsufficientEvidenceMustRefuseToGuess() {
    var chatModel = new RecordingChatModel();
    var strategy = new RecordingQueryStrategy(new QueryContext(List.of(), List.of(), List.of(), ""));
    var engine = new QueryEngine(
        chatModel,
        new ContextAssembler(),
        strategiesReturning(strategy),
        null,
        false,
        2,
        new RuleBasedQueryIntentClassifier(),
        null,
        new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
    );

    engine.query(QueryRequest.builder()
        .query("GraphStore 具体怎么初始化？")
        .mode(QueryMode.LOCAL)
        .build());

    assertThat(chatModel.lastRequest().systemPrompt())
        .contains("explicitly say that the context is insufficient")
        .contains("do not guess");
}
```

- [ ] **Step 2: Run the focused query engine tests to verify they fail**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`

Expected: FAIL because `QueryEngine` does not yet inject overview/detail-specific prompt instructions.

- [ ] **Step 3: Implement minimal prompt shaping in QueryEngine**

```java
private static final String OVERVIEW_GUIDANCE_INSTRUCTIONS = """

    6. Overview Answer Instructions:
      - Start with a short high-level summary.
      - Organize the answer into 2-5 grouped categories.
      - Only include categories that are grounded in the provided context.
      - End with a short sentence that guides the user to continue by module, workflow, or implementation detail.
      - Avoid absolute completeness wording such as 全部, 完整, 所有, exhaustive, or fully covered.
    """;

private static final String DETAIL_EVIDENCE_GUARD_INSTRUCTIONS = """

    6. Detail Accuracy Instructions:
      - State a direct conclusion only when it is clearly supported by the context.
      - If the context is insufficient, explicitly say that the context is insufficient to confirm the answer.
      - Do not guess and do not turn weak associations into facts.
    """;

private final EvidenceCoverageAdvisor evidenceCoverageAdvisor;
```

```java
// in constructor
this.evidenceCoverageAdvisor = new EvidenceCoverageAdvisor();
```

```java
// in query(...)
var intent = queryIntentClassifier == null ? QueryIntent.DETAIL : queryIntentClassifier.classify(resolvedQuery);
var coverage = evidenceCoverageAdvisor.assess(intent, assembledQueryContext);
var chatRequest = new ChatModel.ChatRequest(
    buildSystemPrompt(resolvedQuery, assembledContext, intent, coverage),
    resolvedQuery.query(),
    resolvedQuery.conversationHistory()
);
```

```java
private String buildSystemPrompt(
    QueryRequest query,
    String assembledContext,
    QueryIntent intent,
    EvidenceCoverageAdvisor.EvidenceCoverage coverage
) {
    var prompt = systemPromptTemplate(query.mode()).formatted(
        effectiveResponseType(query.responseType()),
        effectiveUserPrompt(query.userPrompt()),
        assembledContext
    );
    prompt = appendQueryAccuracyInstructions(prompt, intent, coverage);
    return pathAwareAnswerSynthesizer.injectContext("%s", query, prompt);
}

private static String appendQueryAccuracyInstructions(
    String prompt,
    QueryIntent intent,
    EvidenceCoverageAdvisor.EvidenceCoverage coverage
) {
    var addition = switch (intent) {
        case OVERVIEW -> OVERVIEW_GUIDANCE_INSTRUCTIONS;
        case DETAIL, RELATION -> coverage.level() == EvidenceCoverageAdvisor.ConfidenceLevel.INSUFFICIENT_EVIDENCE
            ? DETAIL_EVIDENCE_GUARD_INSTRUCTIONS
            : "";
        case MULTI_HOP -> "";
    };
    if (addition.isBlank()) {
        return prompt;
    }
    var marker = "\n---Context---\n";
    var insertionPoint = prompt.indexOf(marker);
    if (insertionPoint < 0) {
        return prompt + addition;
    }
    return prompt.substring(0, insertionPoint) + addition + prompt.substring(insertionPoint);
}
```

- [ ] **Step 4: Run the focused query engine tests to verify they pass**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`

Expected: PASS with overview prompts containing summary/grouping/guidance instructions and weak detail prompts containing abstention instructions.

- [ ] **Step 5: Run the combined focused regression suite**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.RuleBasedQueryIntentClassifierTest" --tests "io.github.lightrag.query.EvidenceCoverageAdvisorTest" --tests "io.github.lightrag.query.QueryEngineTest" --tests "io.github.lightrag.query.MultiHopQueryStrategyTest"`

Expected: PASS so the new intent split and evidence gate do not regress multi-hop query behavior.

- [ ] **Step 6: Commit**

```bash
git add \
  lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
  lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: shape overview and detail query answers by evidence"
```

## Self-Review

### Spec Coverage

- query intent split: covered by Task 1
- evidence strength estimation: covered by Task 2
- overview summary/grouping/guidance wording: covered by Task 3
- detail abstention when evidence is weak: covered by Task 3
- no planner / no topic graph / no public API churn: preserved by all tasks because no storage, API, or planner files are in scope

### Placeholder Scan

This plan intentionally avoids placeholders such as `TBD`, `TODO`, or "implement later". Every code step contains explicit file paths, commands, and code snippets.

### Type Consistency

- final intent set is `OVERVIEW`, `DETAIL`, `RELATION`, `MULTI_HOP`
- advisor types are `EvidenceCoverageAdvisor`, `EvidenceCoverage`, and `ConfidenceLevel`
- `QueryEngine` calls the new `buildSystemPrompt(..., intent, coverage)` overload consistently

