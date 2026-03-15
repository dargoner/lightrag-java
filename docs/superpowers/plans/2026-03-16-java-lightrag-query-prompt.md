# Java LightRAG Query Prompt Alignment Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-capability-aligned query-time `userPrompt` and `conversationHistory` support to the Java LightRAG SDK.

**Architecture:** Extend `QueryRequest` with optional prompt-level controls and widen `ChatModel.ChatRequest` to carry structured conversation history. `QueryEngine` remains responsible for final current-turn prompt assembly, while the OpenAI-compatible adapter serializes system, history, and current user messages into the outgoing `messages` payload. This plan aligns the Java query parameter surface with upstream without rewriting Java's existing prompt boundary.

**Tech Stack:** Java 17, existing LightRag query pipeline, JUnit 5, AssertJ, OkHttp MockWebServer

---

## Chunk 1: Prompt Contract Red Phase

### Task 1: Add failing query-prompt tests

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add a failing `QueryRequest` default-and-copy test**

Cover:

- `userPrompt` defaults to empty
- `conversationHistory` defaults to empty
- conversation history is defensively copied

- [ ] **Step 2: Add a failing `QueryEngine` prompt-assembly test**

Cover:

- non-blank `userPrompt` is appended to the final current-turn user prompt

- [ ] **Step 3: Add a failing `QueryEngine` history-forwarding test**

Cover:

- `conversationHistory` is forwarded unchanged into `ChatModel.ChatRequest`

- [ ] **Step 4: Add a failing OpenAI adapter payload test**

Cover:

- payload order is system, history messages, then current-turn user message

- [ ] **Step 5: Add a failing end-to-end query prompt customization test**

Cover:

- `LightRag.query(...)` supports both `userPrompt` and `conversationHistory`

- [ ] **Step 6: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because the prompt customization fields and message history contract do not exist yet.

- [ ] **Step 7: Commit**

```bash
git add src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG query prompt customization"
```

## Chunk 2: Query And Model Contract Implementation

### Task 2: Implement query prompt customization

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/model/ChatModel.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Extend `QueryRequest`**

Requirements:

- add `userPrompt`
- add `conversationHistory`
- default to empty string and empty list
- defensively copy the history list

- [ ] **Step 2: Extend `ChatModel.ChatRequest`**

Requirements:

- add `conversationHistory`
- add nested `ConversationMessage`
- defensively copy history
- preserve the existing two-argument constructor for current non-query call sites such as indexing

- [ ] **Step 3: Update `QueryEngine` prompt composition**

Requirements:

- keep existing context + question format
- append `userPrompt` only when non-blank
- pass `conversationHistory` through unchanged
- preserve both new fields when rerank expands the retrieval request

- [ ] **Step 4: Update OpenAI-compatible adapter**

Requirements:

- serialize system message first
- serialize history messages in input order
- serialize current-turn user message last

- [ ] **Step 5: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/QueryRequest.java src/main/java/io/github/lightragjava/model/ChatModel.java src/main/java/io/github/lightragjava/query/QueryEngine.java src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: align LightRAG query prompt controls"
```

## Chunk 3: Documentation And Verification

### Task 3: Document and verify query prompt support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-query-prompt-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-query-prompt.md`

- [ ] **Step 1: Document query prompt customization in README**

Include:

- how to set `userPrompt`
- how to pass `conversationHistory`
- that retrieval behavior is unchanged

- [ ] **Step 2: Keep spec and plan aligned with implementation**

Update docs to reflect the implemented compatibility behavior and validation rules. Do not silently redefine the spec to excuse implementation drift.

- [ ] **Step 3: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run:

- `git status --short`

Expected: only query-prompt-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-16-java-lightrag-query-prompt-design.md docs/superpowers/plans/2026-03-16-java-lightrag-query-prompt.md
git commit -m "docs: finalize LightRAG query prompt alignment"
```
