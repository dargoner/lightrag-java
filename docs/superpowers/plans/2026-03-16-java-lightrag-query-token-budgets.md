# Java LightRAG Query Token Budgets Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned query token budget controls for entities, relations, and total chunk context.

**Architecture:** Extend `QueryRequest` with three budget fields, add a shared query-budget utility for approximate token counting and ordered truncation, enforce entity/relation budgets at both leaf and post-merge graph retrieval points, then enforce final chunk budgets only in `QueryEngine` after merge/rerank ordering is finalized. Preserve rerank behavior by copying the new fields through internal request expansion.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

## Chunk 1: Red Tests

### Task 1: Add failing tests for request and retrieval budgets

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Add failing `QueryRequest` budget tests**

Cover:

- default builder values for `maxEntityTokens`, `maxRelationTokens`, `maxTotalTokens`
- explicit builder overrides
- invalid zero or negative values rejected

- [ ] **Step 2: Add a failing local-strategy entity budget test**

Cover:

- retrieved entities exceed the budget when untrimmed
- `maxEntityTokens` drops trailing entities while keeping score order stable

- [ ] **Step 3: Add a failing global-strategy relation budget test**

Cover:

- retrieved relations exceed the budget when untrimmed
- `maxRelationTokens` drops trailing relations while keeping score order stable

- [ ] **Step 4: Add failing chunk budget tests**

Cover:

- `HYBRID` or `MIX` re-applies budgets after merging child results
- graph retrieval respects remaining `maxTotalTokens` after final ordering
- naive retrieval respects `maxTotalTokens`

- [ ] **Step 5: Add a failing rerank-copy preservation test**

Cover:

- `QueryEngine.expandChunkRequest(...)` preserves graph budgets and defers final total-budget enforcement until after rerank

- [ ] **Step 6: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.NaiveQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: FAIL because token budget fields and truncation logic do not exist yet.

## Chunk 2: Green Implementation

### Task 2: Implement token budget fields and truncation

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Create: `src/main/java/io/github/lightragjava/query/QueryBudgeting.java`
- Modify: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Extend `QueryRequest`**

Requirements:

- add `maxEntityTokens`, `maxRelationTokens`, and `maxTotalTokens`
- add builder defaults and setter methods
- validate each budget is positive
- keep convenience constructors usable and covered by tests

- [ ] **Step 2: Add shared query-budget helpers**

Requirements:

- provide approximate token counting for arbitrary text
- provide ordered truncation helpers for entity and relation lists
- provide total-budget chunk selection helpers that prefer stored chunk token counts

- [ ] **Step 3: Update retrieval strategies**

Requirements:

- `LocalQueryStrategy` enforces entity and final chunk budgets
- `GlobalQueryStrategy` enforces relation and final chunk budgets
- `HybridQueryStrategy` re-applies entity/relation budgets after merging child results
- `MixQueryStrategy` respects the same post-merge graph budgets after mixing direct chunk retrieval
- final chunk budgeting happens in `QueryEngine`, including `NAIVE`
- score ordering remains unchanged before truncation

- [ ] **Step 4: Preserve copied-request behavior in `QueryEngine`**

Requirements:

- `expandChunkRequest(...)` copies the three new fields
- `expandChunkRequest(...)` preserves entity/relation budgets while intentionally relaxing the internal total chunk cap for rerank candidate expansion
- remaining-budget calculation uses the same helper as strategies

- [ ] **Step 5: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.NaiveQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: PASS.

## Chunk 3: Docs And Integration

### Task 3: Document the new query budgets and verify branch state

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-query-token-budgets-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-query-token-budgets.md`

- [ ] **Step 1: Update README query parameter documentation**

Include:

- the three new token budget fields
- their defaults
- how graph and naive retrieval apply them
- that budgeting currently uses a shared approximation for non-chunk text

- [ ] **Step 2: Run final focused verification**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.query.MixQueryStrategyTest --tests io.github.lightragjava.query.NaiveQueryStrategyTest --tests io.github.lightragjava.query.QueryEngineTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only token-budget alignment files changed before commit.
