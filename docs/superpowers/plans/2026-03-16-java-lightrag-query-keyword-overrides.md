# Java LightRAG Query Keyword Overrides Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned manual `hlKeywords` and `llKeywords` query parameters so callers can steer graph-aware retrieval explicitly.

**Architecture:** Extend `QueryRequest` with normalized keyword override lists, then make `LocalQueryStrategy` and `GlobalQueryStrategy` choose their embedding text from those lists when present. `HybridQueryStrategy` and `MixQueryStrategy` inherit that behavior compositionally, while `NAIVE` and direct chunk-vector retrieval continue using the raw query.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

## Chunk 1: Red Tests

### Task 1: Add failing tests for manual keyword override behavior

**Files:**
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Add a failing local-strategy override test**

Cover:

- raw query would miss the intended entity match
- provided `llKeywords` changes retrieval to the expected local/entity path
- blank keyword entries are ignored

- [ ] **Step 2: Add a failing global-strategy override test**

Cover:

- raw query would miss the intended relation match
- provided `hlKeywords` changes retrieval to the expected global/relation path

- [ ] **Step 3: Add failing hybrid and mix override tests**

Cover:

- `HYBRID` respects both `llKeywords` and `hlKeywords`
- `HYBRID` suppresses the opposite graph branch when only one keyword side is provided
- `MIX` uses keyword overrides for graph retrieval but still uses raw query for direct chunk search

- [ ] **Step 4: Add a failing `QueryEngine` rerank-copy preservation test**

Cover:

- when rerank expands the internal request, `hlKeywords` and `llKeywords` survive the copied request unchanged

- [ ] **Step 5: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: FAIL because strategies still always embed the raw query text.

## Chunk 2: Green Implementation

### Task 2: Implement keyword override support

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Extend `QueryRequest`**

Requirements:

- add `hlKeywords` and `llKeywords` fields
- add builder methods
- normalize by trimming blanks and dropping empty strings
- join normalized keyword lists with `", "` before embedding/search
- keep existing constructors/builders backward compatible

- [ ] **Step 2: Update graph-aware strategies**

Requirements:

- `LocalQueryStrategy` embeds `llKeywords` when present
- `GlobalQueryStrategy` embeds `hlKeywords` when present
- fallback remains the raw query for standalone `LOCAL` / `GLOBAL`
- in `HYBRID` / `MIX`, a branch with an empty keyword side is suppressed when manual keywords are present on the opposite side
- `HybridQueryStrategy` and `MixQueryStrategy` work through composition without extra branching where possible

- [ ] **Step 3: Preserve copied-request behavior in `QueryEngine`**

Requirements:

- `expandChunkRequest(...)` must copy the new keyword fields
- rerank behavior remains unchanged

- [ ] **Step 4: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: PASS.

## Chunk 3: Docs And Verification

### Task 3: Document keyword override support and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-query-keyword-overrides-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-query-keyword-overrides.md`

- [ ] **Step 1: Update README query parameter docs**

Include:

- what `hlKeywords` and `llKeywords` do
- which modes use them
- that Java only supports manual keyword overrides in this phase

- [ ] **Step 2: Run final verification**

Run:

- `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only keyword-override alignment files changed before commit.
