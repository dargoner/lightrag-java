# Java LightRAG Naive Mode Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned `NAIVE` query mode to the Java LightRAG SDK.

**Architecture:** Introduce a dedicated `NaiveQueryStrategy` that performs direct chunk-vector retrieval without graph expansion. Wire the new mode into `QueryMode` and `LightRag`, keep `MIX` as the default request mode, and let the existing `QueryEngine` continue owning optional rerank and final context assembly.

**Tech Stack:** Java 17, existing LightRag query pipeline, JUnit 5, AssertJ

---

## Chunk 1: Public Mode Surface And Strategy Behavior

### Task 1: Add failing naive-mode tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Create: `src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add a failing public-surface test for `QueryMode.NAIVE`**

Cover:

- `QueryMode` contains `NAIVE`
- `QueryRequest.builder().query(...).mode(QueryMode.NAIVE).build()` is accepted
- `topK` is documented as irrelevant for `NAIVE` while `chunkTopK` remains effective

- [ ] **Step 2: Add a failing `NaiveQueryStrategy` chunk-only retrieval test**

Cover:

- direct chunk-vector retrieval returns chunk matches
- matched entities and relations are empty

- [ ] **Step 3: Add a failing `NaiveQueryStrategy` `chunkTopK` trimming test**

Cover:

- only the requested number of chunks are returned

- [ ] **Step 4: Add a failing `NaiveQueryStrategy` contract test for ignored `topK` and deterministic tie-breaks**

Cover:

- `topK` does not affect `NAIVE` retrieval size
- equal chunk scores are ordered by chunk ID

- [ ] **Step 5: Add failing end-to-end naive-mode tests**

Cover:

- `mode(NAIVE)` returns non-empty contexts for ingested documents
- configured rerank can reorder final `NAIVE` chunk contexts
- provider-specific PostgreSQL and PostgreSQL+Neo4j query-mode matrices include `NAIVE`

- [ ] **Step 6: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.NaiveQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because `NAIVE` strategy does not exist yet.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG naive query mode"
```

### Task 2: Implement naive query mode

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryMode.java`
- Create: `src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java`
- Modify: `src/main/java/io/github/lightragjava/api/LightRag.java`
- Modify: `src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add `NAIVE` to the public enum**

Requirement:

- keep existing modes unchanged
- keep `QueryRequest.DEFAULT_MODE = MIX`

- [ ] **Step 2: Implement `NaiveQueryStrategy`**

Requirements:

- search the `chunks` vector namespace directly
- load chunk records from `ChunkStore`
- return empty entities and relations
- sort by score descending, then chunk ID
- build provisional context through `ContextAssembler`
- ignore `topK`; use `chunkTopK` as the effective retrieval control

- [ ] **Step 3: Wire `NAIVE` into `LightRag`**

Requirement:

- register `NaiveQueryStrategy` in the strategy map so `LightRag.query(...)` supports the new mode

- [ ] **Step 4: Run targeted naive-mode tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.NaiveQueryStrategyTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/QueryMode.java src/main/java/io/github/lightragjava/query/NaiveQueryStrategy.java src/main/java/io/github/lightragjava/api/LightRag.java src/test/java/io/github/lightragjava/query/NaiveQueryStrategyTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: add LightRAG naive query mode"
```

## Chunk 2: Document And Verify Naive-Mode Support

### Task 3: Document and verify naive-mode support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-naive-mode-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-naive-mode.md`

- [ ] **Step 1: Document `NAIVE` mode in README**

Include:

- what `NAIVE` does
- when to use it versus `MIX`
- how it interacts with rerank
- that `topK` is ignored and `chunkTopK` is the effective limit

- [ ] **Step 2: Keep spec and plan aligned with implementation**

Update docs if class names or test locations differ.

- [ ] **Step 3: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run:

- `git status --short`

Expected: only naive-mode-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-16-java-lightrag-naive-mode-design.md docs/superpowers/plans/2026-03-16-java-lightrag-naive-mode.md
git commit -m "docs: finalize LightRAG naive mode alignment"
```
