# SmartChunker Adaptive Chunking Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保留 `FINE / MEDIUM / COARSE` 基础粒度的前提下，为 `SmartChunker` 增加 paragraph 级别的自适应 chunk 能力，让连续正文更粗、标题密集区域更细，并保持 `LAW / QA / LIST / TABLE` 的边界稳定性。

**Architecture:** 继续以 `SmartChunkerConfig` 作为基础粒度来源，在 `SmartChunker` 的 paragraph 切分路径前增加一个轻量 `AdaptiveChunkSizingPolicy`。该策略根据文档类型、块类型、标题切换和句子统计返回当前 block 的有效 `target/max/overlap`，若 adaptive 不可用则直接回退到基础配置。

**Tech Stack:** Java 17, JUnit 5, AssertJ, Gradle, existing SmartChunker / ChunkingProfile infrastructure

---

## File Structure

**Create**

- `lightrag-core/src/main/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicy.java`
- `lightrag-core/src/test/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicyTest.java`

**Modify**

- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- `lightrag-core/src/test/java/dev/io/github/lightragjava/indexing/PublicDocumentSmartChunkerRealApiManualTest.java`
- `README.md`
- `README_zh.md`

**Keep Unchanged Intentionally**

- `StructuredDocumentParser.java`
- `RegexChunker.java`
- `ParentChildChunkBuilder.java`

原因：这次 adaptive 只落在 paragraph chunking，不扩展到 parser、regex、parent/child。

### Task 1: Add Adaptive Configuration And Policy

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicy.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicyTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java`

- [ ] **Step 1: Write the failing config and policy tests**

```java
@Test
void builderSupportsAdaptiveChunkingDefaults() {
    var config = SmartChunkerConfig.defaults();

    assertThat(config.adaptiveChunkingEnabled()).isTrue();
    assertThat(config.adaptiveMinTargetRatio()).isEqualTo(0.70d);
    assertThat(config.adaptiveMaxTargetRatio()).isEqualTo(1.35d);
    assertThat(config.adaptiveOverlapRatio()).isEqualTo(0.12d);
}

@Test
void policyExpandsStableLongParagraphsForGenericDocuments() {
    var policy = new AdaptiveChunkSizingPolicy();
    var base = SmartChunkerConfig.builder()
        .targetTokens(800)
        .maxTokens(1200)
        .overlapTokens(100)
        .build();

    var sizing = policy.resolve(
        base,
        DocumentType.GENERIC,
        StructuredBlock.Type.PARAGRAPH,
        false,
        8,
        120
    );

    assertThat(sizing.targetTokens()).isGreaterThan(base.targetTokens());
    assertThat(sizing.maxTokens()).isGreaterThan(base.maxTokens());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.AdaptiveChunkSizingPolicyTest"
```

Expected:
- FAIL because adaptive fields / policy class do not exist yet

- [ ] **Step 3: Implement minimal config and policy**

Add to `SmartChunkerConfig`:

```java
boolean adaptiveChunkingEnabled,
double adaptiveMinTargetRatio,
double adaptiveMaxTargetRatio,
double adaptiveOverlapRatio
```

Add nested value object in `AdaptiveChunkSizingPolicy`:

```java
record AdaptiveSizing(int targetTokens, int maxTokens, int overlapTokens) {}
```

Implement a conservative resolver:

```java
AdaptiveSizing resolve(
    SmartChunkerConfig base,
    DocumentType documentType,
    StructuredBlock.Type blockType,
    boolean headingTransition,
    int sentenceCount,
    int averageSentenceTokens
)
```

Rules:
- only `PARAGRAPH` adapts
- `GENERIC/BOOK` can expand more
- `LAW/QA` clamp close to base
- heading transition biases smaller target
- long stable paragraphs bias larger target

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.AdaptiveChunkSizingPolicyTest"
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker add \
  lightrag-core/src/main/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicy.java \
  lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java \
  lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingProfile.java \
  lightrag-core/src/test/java/io/github/lightragjava/indexing/AdaptiveChunkSizingPolicyTest.java
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker commit -m "feat: add adaptive chunk sizing policy"
```

### Task 2: Apply Adaptive Sizing To Paragraph Chunking

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`

- [ ] **Step 1: Write the failing paragraph adaptive tests**

```java
@Test
void adaptiveChunkingMakesLongGenericParagraphsCoarser() {
    var chunker = new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(80)
        .maxTokens(120)
        .overlapTokens(12)
        .adaptiveChunkingEnabled(true)
        .build());

    var fixed = new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(80)
        .maxTokens(120)
        .overlapTokens(12)
        .adaptiveChunkingEnabled(false)
        .build());

    assertThat(chunker.chunk(document)).hasSizeLessThan(fixed.chunk(document).size());
}

@Test
void adaptiveChunkingKeepsHeadingTransitionParagraphsFiner() {
    assertThat(firstChunkTokenCountAfterHeading).isLessThanOrEqualTo(baseTarget);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest.adaptiveChunkingMakesLongGenericParagraphsCoarser" \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest.adaptiveChunkingKeepsHeadingTransitionParagraphsFiner"
```

Expected:
- FAIL because paragraph path still uses static target/max/overlap

- [ ] **Step 3: Implement adaptive paragraph sizing**

Refactor `SmartChunker` paragraph path so that `buildParagraphDrafts(...)` computes per-block effective sizing before assembling chunks.

Add a helper similar to:

```java
private AdaptiveChunkSizingPolicy.AdaptiveSizing paragraphSizing(
    String content,
    String sectionPath,
    String previousSectionPath,
    DocumentType documentType
)
```

Use these values instead of raw config in:
- sentence accumulation stop condition
- max token guard
- overlap rewind logic
- fallback overlap calculation

Important:
- keep `maxTokens` as hard ceiling
- if adaptive disabled, return base config exactly

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest"
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker add \
  lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java \
  lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker commit -m "feat: apply adaptive sizing to smart chunker paragraphs"
```

### Task 3: Guardrails For LAW QA LIST TABLE And Ingest

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/ChunkingOrchestratorTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Write the failing guardrail tests**

Add assertions that:
- `LAW` does not become coarser across article boundaries
- `QA` still keeps question + answer pairing unchanged
- `LIST` and `TABLE` paths do not suddenly emit fewer oversized chunks
- ingest path still preserves adaptive metadata behavior for generic parsed documents

Example:

```java
assertThat(adaptiveLawChunks)
    .extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.SECTION_PATH))
    .containsExactly("第一条", "第二条");
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest"
```

Expected:
- FAIL if adaptive sizing leaks into protected paths incorrectly

- [ ] **Step 3: Implement guardrails**

Constrain adaptive policy usage so that:
- only paragraph path adapts
- `LAW` and `QA` clamp to near-base sizing
- list/table methods keep current static logic
- no metadata contract changes are required for stored chunks

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.StructuredDocumentParserTest" \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest"
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker add \
  lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/indexing/ChunkingOrchestratorTest.java \
  lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker commit -m "test: add adaptive chunking guardrails"
```

### Task 4: Real-Document Verification And Docs

**Files:**
- Modify: `lightrag-core/src/test/java/dev/io/github/lightragjava/indexing/PublicDocumentSmartChunkerRealApiManualTest.java`
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: Write the failing real-document comparison test**

Add a manual test that compares:
- static base sizing
- adaptive sizing

For the existing Chinese public PDF and prints:
- chunk count
- average token count
- max token count
- representative sections

Test skeleton:

```java
System.out.println("[ADAPTIVE_COMPARE] static_count=" + staticChunks.size());
System.out.println("[ADAPTIVE_COMPARE] adaptive_count=" + adaptiveChunks.size());
assertThat(adaptiveChunks.size()).isLessThan(staticChunks.size());
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
MINERU_API_KEY="$MINERU_API_KEY" GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "dev.io.github.lightragjava.indexing.PublicDocumentSmartChunkerRealApiManualTest.*Adaptive*" \
  --info
```

Expected:
- FAIL until manual comparison test exists

- [x] **Step 3: Implement docs and manual verification support**

Update README sections to explain:
- adaptive chunking is internal and automatic
- user still chooses only `FINE / MEDIUM / COARSE`
- paragraph path adapts while list/table remain conservative
- same-section short prose paragraphs may be regrouped before sentence splitting
- very short non-substantive snippets stay conservative and do not get blindly regrouped

Add the real-document comparison method to the manual test file.

- [x] **Step 4: Run verification and capture evidence**

Run:
```bash
GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "io.github.lightragjava.indexing.AdaptiveChunkSizingPolicyTest" \
  --tests "io.github.lightragjava.indexing.StructuredDocumentParserTest" \
  --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
  --tests "io.github.lightragjava.indexing.ChunkingOrchestratorTest" \
  --tests "io.github.lightragjava.indexing.DocumentIngestorTest"
```

Then run:
```bash
MINERU_API_KEY="$MINERU_API_KEY" GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test \
  --tests "dev.io.github.lightragjava.indexing.PublicDocumentSmartChunkerRealApiManualTest.comparesChineseGenericAdaptiveChunksAgainstStaticSizing" \
  --info
```

Expected:
- all targeted automated tests PASS
- manual output shows adaptive sizing improves `GENERIC` paragraph chunking without losing section metadata

Captured evidence:
- targeted automated tests passed
- real Chinese whitepaper comparison:
  - `static_count=252`
  - `adaptive_count=125`
  - `static_avg=172.70`
  - `adaptive_avg=350.19`

- [ ] **Step 5: Commit**

```bash
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker add \
  lightrag-core/src/test/java/dev/io/github/lightragjava/indexing/PublicDocumentSmartChunkerRealApiManualTest.java \
  README.md \
  README_zh.md
git -C /home/dargoner/work/lightrag-java/.worktrees/smart-chunker commit -m "docs: document adaptive smart chunking"
```

## Execution Notes

- Prefer implementing Task 1 and Task 2 before touching docs.
- Do not expand adaptive sizing to list/table in this plan.
- Keep fallback behavior: if adaptive sizing cannot compute a valid result, return the base config values.
- Real-document validation should reuse the existing CAC whitepaper public PDF and existing MinerU manual-test scaffolding.

## Review Constraint

The normal superpowers workflow expects a plan-review subagent here, but current tool policy in this session does not allow spawning subagents without explicit user direction. Perform a manual self-review before execution and surface any plan corrections in the execution session.
