# Java LightRAG Rerank Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned optional chunk reranking to the Java LightRAG SDK.

**Architecture:** Introduce a new optional `RerankModel` SPI plus a per-query `enableRerank` flag. `QueryEngine` will derive an internal expanded retrieval request for rerank-capable queries, let strategies return chunk candidates using that larger window, then own the shared rerank step and final context assembly so behavior stays consistent across `local`, `global`, `hybrid`, and `mix`. Rerank changes chunk ordering only; exposed chunk scores remain the original retrieval scores.

**Implementation note:** During execution, existing strategies already proved compatible with the internal expanded `chunkTopK` request. The shipped code therefore localized the behavior change to `QueryEngine` instead of editing every strategy class.

**Tech Stack:** Java 17, existing LightRag builder/config/query pipeline, JUnit 5, AssertJ

---

## Chunk 1: Public Surface And Core Query Behavior

### Task 1: Add failing rerank API and engine tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Create or Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Add a failing builder/config test for optional rerank model**

Cover:

- builder accepts a configured `RerankModel`
- `QueryRequest.builder().query(...).build()` defaults `enableRerank` to `true`

- [ ] **Step 2: Add a failing query-engine rerank ordering test**

Use a fake rerank model to prove final contexts are reordered away from original retrieval score order.

- [ ] **Step 3: Add a failing query-engine no-model fallback test**

Use the same retrieval setup without a configured rerank model and assert current ordering is preserved.

- [ ] **Step 4: Add a failing query-engine bypass test**

Use the same setup but set `enableRerank(false)` and assert original order is preserved.

- [ ] **Step 5: Add a failing query-engine omitted-candidate fallback test**

Use a fake rerank model that returns only part of the original chunk ID set and assert the omitted candidates are appended in original order.

- [ ] **Step 6: Add a failing query-engine rerank failure propagation test**

Use a fake rerank model that throws and assert query execution fails with the rerank error instead of silently falling back.

- [ ] **Step 7: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`

Expected: FAIL because rerank SPI and query flag do not exist yet.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java
git commit -m "test: cover LightRAG rerank query behavior"
```

### Task 2: Add rerank SPI and wire it through builder/config

**Files:**
- Create: `src/main/java/io/github/lightragjava/model/RerankModel.java`
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `src/main/java/io/github/lightragjava/config/LightRagConfig.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`

- [ ] **Step 1: Add `RerankModel` SPI**

Include nested records for:

- `RerankRequest`
- `RerankCandidate`
- `RerankResult`

- [ ] **Step 2: Extend `QueryRequest` with `enableRerank`**

Requirements:

- default `true`
- builder setter `enableRerank(boolean enableRerank)`

- [ ] **Step 3: Extend builder/config with optional rerank model**

Requirements:

- builder setter `rerankModel(...)`
- `LightRagConfig` stores nullable rerank model

- [ ] **Step 4: Instantiate `QueryEngine` with rerank model support**

Pass the optional rerank model from config into query orchestration.

- [ ] **Step 5: Document the no-model no-op behavior in code comments or Javadoc where appropriate**

Make it explicit that Java intentionally skips upstream-style warnings in this phase.

- [ ] **Step 6: Run targeted public-surface tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/lightragjava/model/RerankModel.java src/main/java/io/github/lightragjava/api/QueryRequest.java src/main/java/io/github/lightragjava/api/LightRagBuilder.java src/main/java/io/github/lightragjava/config/LightRagConfig.java src/main/java/io/github/lightragjava/api/LightRag.java
git commit -m "feat: add LightRAG rerank API surface"
```

## Chunk 2: Retrieval Candidate Expansion And Shared Rerank Logic

### Task 3: Implement shared rerank orchestration in query flow

**Files:**
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/MixQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Modify: `src/main/java/io/github/lightragjava/types/QueryContext.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`

- [ ] **Step 1: Refactor strategies to return chunk candidates without final rerank trimming assumptions**

Requirements:

- preserve existing entity and relation scoring
- make `QueryEngine` derive an internal retrieval request with candidate chunk limit `max(chunkTopK * 2, chunkTopK)` when rerank is enabled and a rerank model is configured
- keep the public request unchanged
- keep direct `chunkTopK` limiting when rerank is disabled or no rerank model is configured
- ensure `HybridQueryStrategy` does not prematurely trim expanded candidates
- ensure `MixQueryStrategy` uses the expanded candidate limit for direct chunk-vector retrieval too

- [ ] **Step 2: Implement rerank in `QueryEngine`**

Requirements:

- if `enableRerank` is false, preserve current order
- if no rerank model is configured, preserve current order
- rerank only chunk candidates
- ignore unknown rerank IDs
- append omitted original candidates after returned rerank IDs
- propagate rerank failures instead of silently falling back
- trim final contexts to `chunkTopK`
- preserve original retrieval scores on `ScoredChunk`

- [ ] **Step 3: Rebuild assembled context after final chunk order is known**

Keep `QueryResult.Context` ordering aligned with reranked chunks.

- [ ] **Step 4: Run targeted query tests**

Run:

- `./gradlew test --tests io.github.lightragjava.query.LocalQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.query.GlobalQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.query.HybridQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.query.MixQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/query/QueryEngine.java src/main/java/io/github/lightragjava/query/LocalQueryStrategy.java src/main/java/io/github/lightragjava/query/GlobalQueryStrategy.java src/main/java/io/github/lightragjava/query/HybridQueryStrategy.java src/main/java/io/github/lightragjava/query/MixQueryStrategy.java src/main/java/io/github/lightragjava/query/ContextAssembler.java src/main/java/io/github/lightragjava/types/QueryContext.java src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java src/test/java/io/github/lightragjava/query/MixQueryStrategyTest.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java
git commit -m "feat: add shared LightRAG rerank query flow"
```

## Chunk 3: End-To-End Coverage And Documentation

### Task 4: Add end-to-end rerank coverage

**Files:**
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add an end-to-end rerank test**

Cover:

- configured rerank model changes final context order in a real `LightRag.query(...)`

- [ ] **Step 2: Add an end-to-end rerank disable test**

Cover:

- configured rerank model is bypassed when `enableRerank(false)` is set on the request

- [ ] **Step 3: Run targeted end-to-end tests**

Run:

- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG rerank end-to-end flow"
```

### Task 5: Document and verify rerank support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-rerank-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-rerank.md`

- [ ] **Step 1: Document the rerank API in README**

Include:

- how to configure `RerankModel`
- how to disable rerank per request
- that rerank reorders chunk contexts before answer generation

- [ ] **Step 2: Keep spec and plan aligned with final implementation**

Update docs if actual class names or candidate-window rules differ.

- [ ] **Step 3: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run:

- `git status --short`

Expected: only rerank-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-16-java-lightrag-rerank-design.md docs/superpowers/plans/2026-03-16-java-lightrag-rerank.md
git commit -m "docs: finalize LightRAG rerank alignment"
```
