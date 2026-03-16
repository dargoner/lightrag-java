# Java LightRAG Include References Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-aligned `includeReferences` query support so Java callers can request structured references from final retrieval results.

**Architecture:** Extend `QueryRequest` with a boolean flag, widen `QueryResult` and `QueryResult.Context` with backward-compatible constructors, and derive reference IDs from the final chunk list in `QueryEngine` using metadata source resolution. Keep prompt generation and retrieval logic unchanged apart from preserving the new flag through rerank request expansion.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

## Chunk 1: Red Tests

### Task 1: Add failing tests for reference-aware query results

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add failing request/result compatibility tests**

Cover:

- `includeReferences` defaults to `false`
- `includeReferences(true)` is accepted
- legacy `QueryResult` and `QueryResult.Context` constructors still work

- [ ] **Step 2: Add a failing engine references-off test**

Cover:

- default query results still return an empty structured references list

- [ ] **Step 3: Add a failing engine references-on test**

Cover:

- `includeReferences(true)` returns structured references
- returned contexts carry `referenceId` and `source`
- ordering follows frequency then first appearance

- [ ] **Step 4: Add a failing rerank-copy preservation test**

Cover:

- `QueryEngine.expandChunkRequest(...)` preserves `includeReferences`

- [ ] **Step 5: Add a failing bypass / end-to-end test**

Cover:

- `BYPASS` returns no references
- `LightRag.query(...)` can emit references from chunk/document metadata `source`

- [ ] **Step 6: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because `includeReferences` and structured references do not exist yet.

## Chunk 2: Green Implementation

### Task 2: Implement structured references support

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/api/QueryResult.java`
- Create: `src/main/java/io/github/lightragjava/query/QueryReferences.java`
- Modify: `src/main/java/io/github/lightragjava/query/ContextAssembler.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Extend `QueryRequest`**

Requirements:

- add `includeReferences`
- default it to `false`
- preserve convenience constructors

- [ ] **Step 2: Extend `QueryResult`**

Requirements:

- add `references`
- widen `Context` with `referenceId` and `source`
- keep legacy convenience constructors usable
- defensively copy the new list

- [ ] **Step 3: Add reference derivation helper**

Requirements:

- resolve source by `file_path`, then `source`, then `documentId`
- assign reference IDs by frequency, then first appearance
- enrich contexts and build the deduplicated references list

- [ ] **Step 4: Update `QueryEngine`**

Requirements:

- derive references from the final chunk set only
- preserve `includeReferences` when rerank expands the internal request
- keep `BYPASS` returning empty references

- [ ] **Step 5: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

## Chunk 3: Docs And Verification

### Task 3: Document includeReferences behavior and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-include-references-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-include-references.md`

- [ ] **Step 1: Update README query docs**

Include:

- how to enable `includeReferences`
- what structured fields are returned
- source resolution priority and fallback behavior

- [ ] **Step 2: Run final focused verification**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest --tests io.github.lightragjava.query.QueryEngineTest --tests io.github.lightragjava.E2ELightRagTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect final diff**

Run:

- `git status --short`

Expected: only include-references-related files changed before commit.
