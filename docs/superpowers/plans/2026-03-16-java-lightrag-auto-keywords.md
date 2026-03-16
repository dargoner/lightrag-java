# Java LightRAG Auto Keywords Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned automatic keyword extraction for graph-aware queries when callers do not provide manual keyword overrides.

**Architecture:** Add a shared query-keyword resolution helper inside the query layer. `QueryEngine` will resolve manual or automatic keywords before retrieval, then pass the enriched `QueryRequest` into the existing strategies without changing their public contracts.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, Jackson

---

## Chunk 1: Red Tests

### Task 1: Add failing automatic-keyword tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Add failing query-engine keyword-resolution tests**

Cover:

- auto extraction runs for graph-aware modes when both keyword lists are empty
- manual overrides bypass extraction
- `NAIVE` and `BYPASS` skip extraction
- `modelFunc(...)` is used for keyword extraction

- [x] **Step 2: Add failing fallback tests**

Cover:

- empty or malformed extraction output falls back by mode
- `HYBRID` and `MIX` use upstream-style low-level fallback only

- [x] **Step 3: Add failing end-to-end tests**

Cover:

- graph-aware queries still succeed with automatic extraction
- manual override behavior remains unchanged

- [x] **Step 4: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because automatic keyword extraction does not exist yet.

## Chunk 2: Green Implementation

### Task 2: Implement keyword resolution

**Files:**
- Create: `src/main/java/io/github/lightragjava/query/QueryKeywordExtractor.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/LocalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/GlobalQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/HybridQueryStrategyTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Add internal keyword extractor**

Requirements:

- build an upstream-aligned keyword-extraction prompt
- call the selected query model
- parse JSON into normalized high-level and low-level keyword lists
- gracefully handle malformed output

- [x] **Step 2: Resolve keywords before retrieval**

Requirements:

- preserve manual overrides exactly
- skip extraction for `NAIVE` and `BYPASS`
- use upstream-like fallback when extraction yields nothing
- preserve resolved keywords when rerank expands internal requests

- [x] **Step 3: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

## Chunk 3: Docs And Verification

### Task 3: Document auto-keyword behavior and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-auto-keywords-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-auto-keywords.md`

- [x] **Step 1: Update README query docs**

Include:

- automatic keyword extraction behavior
- precedence between manual overrides and automatic extraction
- how `modelFunc(...)`, `stream(...)`, and shortcut modes interact with extraction

- [x] **Step 2: Run final focused verification**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.query.LocalQueryStrategyTest --tests io.github.lightragjava.query.GlobalQueryStrategyTest --tests io.github.lightragjava.query.HybridQueryStrategyTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only auto-keyword-related files changed before commit.
