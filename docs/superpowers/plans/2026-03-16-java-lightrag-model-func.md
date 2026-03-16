# Java LightRAG ModelFunc Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned query-time `modelFunc` overrides so one `LightRag` instance can use different chat models per query.

**Architecture:** Extend `QueryRequest` with an optional `ChatModel modelFunc`, keep the builder-level `chatModel` as the default fallback, and teach `QueryEngine` to use the request override for both buffered and streaming generation paths.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

## Chunk 1: Red Tests

### Task 1: Add failing model override tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Add failing request-shape tests**

Cover:

- `modelFunc` defaults to `null`
- `modelFunc(...)` is accepted by the builder
- legacy `QueryRequest` convenience constructors still default `modelFunc` to `null`

- [x] **Step 2: Add failing query-engine override tests**

Cover:

- standard retrieval uses `request.modelFunc()` instead of the default engine model
- `BYPASS` uses `request.modelFunc()`
- `onlyNeedContext` and `onlyNeedPrompt` do not call either model
- rerank-expanded internal requests preserve `modelFunc`

- [x] **Step 3: Add failing streaming override tests**

Cover:

- `stream(true) + modelFunc(...)` uses the override model's `stream(...)`

- [x] **Step 4: Add failing end-to-end override tests**

Cover:

- `LightRag.query(...)` overrides the default query model for one request
- `LightRag.query(...)` can stream through the override model

- [x] **Step 5: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because `modelFunc` does not exist yet.

## Chunk 2: Green Implementation

### Task 2: Implement query-time model overrides

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Extend `QueryRequest`**

Requirements:

- add nullable `ChatModel modelFunc`
- default to `null`
- preserve convenience constructors
- add builder setter

- [x] **Step 2: Update `QueryEngine` model selection**

Requirements:

- select `request.modelFunc()` when provided
- otherwise use the existing default engine model
- apply the selection consistently to buffered, streaming, and `BYPASS` paths
- preserve `modelFunc` in rerank-expanded requests

- [x] **Step 3: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

## Chunk 3: Docs And Verification

### Task 3: Document model override behavior and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-model-func-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-model-func.md`

- [x] **Step 1: Update README query docs**

Include:

- how to override the default chat model per query
- how `modelFunc` interacts with `stream`, `BYPASS`, `onlyNeedContext`, and `onlyNeedPrompt`

- [x] **Step 2: Run final focused verification**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only model-func-related files changed before commit.
