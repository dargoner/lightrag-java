# Java LightRAG Query Stream Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned streaming query output so Java callers can consume generation incrementally while retaining structured retrieval metadata.

**Architecture:** Extend `QueryRequest`, `ChatModel`, and `QueryResult` with a minimal streaming abstraction based on a closeable iterator. Keep retrieval assembly unchanged, and add OpenAI-compatible SSE support for the existing chat adapter.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ, OkHttp MockWebServer

---

## Chunk 1: Red Tests

### Task 1: Add failing streaming tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Add failing request/result streaming-shape tests**

Cover:

- `stream` defaults to `false`
- `stream(true)` is accepted
- legacy `QueryResult` constructors still produce non-streaming results

- [x] **Step 2: Add failing query-engine streaming tests**

Cover:

- standard retrieval returns `streaming=true` plus a readable stream when requested
- `onlyNeedContext` and `onlyNeedPrompt` still bypass streaming
- rerank-expanded internal requests preserve `stream`

- [x] **Step 3: Add failing bypass streaming test**

Cover:

- `BYPASS + stream(true)` returns a streaming handle with empty retrieval metadata

- [x] **Step 4: Add failing OpenAI adapter SSE tests**

Cover:

- request payload includes `"stream": true`
- SSE response fragments are emitted in order
- early close cleans up without requiring full consumption

- [x] **Step 5: Add failing end-to-end streaming tests**

Cover:

- `LightRag.query(...)` streams for a normal retrieval query
- `LightRag.query(...)` streams for `BYPASS`

- [x] **Step 6: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because the streaming contracts do not exist yet.

## Chunk 2: Green Implementation

### Task 2: Implement streaming query support

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/api/QueryResult.java`
- Create: `src/main/java/io/github/lightragjava/model/CloseableIterator.java`
- Modify: `src/main/java/io/github/lightragjava/model/ChatModel.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [x] **Step 1: Extend `QueryRequest`**

Requirements:

- add `stream`
- default to `false`
- preserve convenience constructors

- [x] **Step 2: Add the streaming abstraction**

Requirements:

- add `CloseableIterator<T>`
- provide a reusable empty iterator for non-streaming cases

- [x] **Step 3: Extend `QueryResult` and `ChatModel`**

Requirements:

- `QueryResult` can carry `answerStream` and `streaming`
- `ChatModel` exposes a streaming method
- existing buffered generation paths remain source-compatible

- [x] **Step 4: Update `QueryEngine`**

Requirements:

- use streaming only when `stream=true` and neither prompt/context shortcut is enabled
- preserve retrieval metadata in both buffered and streaming modes
- preserve `stream` when rerank expands internal requests

- [x] **Step 5: Add OpenAI-compatible SSE support**

Requirements:

- send `"stream": true`
- parse SSE delta content fragments
- stop on `[DONE]`
- close response resources correctly

- [x] **Step 6: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

## Chunk 3: Docs And Verification

### Task 3: Document streaming behavior and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-query-stream-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-query-stream.md`

- [x] **Step 1: Update README query docs**

Include:

- how to request streaming
- how to consume and close the returned stream
- how streaming interacts with `onlyNeedContext`, `onlyNeedPrompt`, and `BYPASS`

- [x] **Step 2: Run final focused verification**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only stream-related files changed before commit.
