# Java LightRAG Query Shortcut Controls Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add upstream-capability-aligned `bypass`, `onlyNeedContext`, and `onlyNeedPrompt` query controls to the Java LightRAG SDK.

**Architecture:** Extend `QueryRequest` and `QueryMode` with the new shortcut controls, keep `QueryResult` unchanged, and centralize all branching behavior in `QueryEngine`. Standard modes still retrieve context first; `onlyNeedContext` and `onlyNeedPrompt` short-circuit before LLM generation, while `BYPASS` skips retrieval entirely and sends the raw query directly to the chat model.

**Tech Stack:** Java 17, existing LightRag query pipeline, JUnit 5, AssertJ, OkHttp MockWebServer

---

## Chunk 1: Shortcut Contract Red Phase

### Task 1: Add failing tests for query shortcut controls

**Files:**
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add failing `QueryRequest` and `QueryMode` tests**

Cover:

- `onlyNeedContext` defaults to `false`
- `onlyNeedPrompt` defaults to `false`
- the existing 6-argument constructor defaults the new flags to `false`
- the existing 8-argument constructor defaults the new flags to `false`
- `QueryMode.BYPASS` exists

- [ ] **Step 2: Add a failing `QueryEngine` context-only test**

Cover:

- assembled context is returned in `answer`
- chat model is not called
- `contexts` still contain the resolved final chunk contexts
- rerank, when enabled, still determines the returned context order before the shortcut return

- [ ] **Step 3: Add a failing `QueryEngine` prompt-only test**

Cover:

- complete prompt text is returned in `answer`
- the returned text contains the final system prompt, history messages, and current-turn user prompt
- chat model is not called
- `onlyNeedContext` wins when both shortcut flags are `true`
- `contexts` still contain the resolved final chunk contexts
- rerank, when enabled, still determines the returned context order before the shortcut return

- [ ] **Step 4: Add a failing `QueryEngine` bypass test**

Cover:

- retrieval strategy is not called
- chat model receives a blank system prompt, the bypass user message, and conversation history
- returned contexts are empty
- bypass plus `onlyNeedContext` returns an empty answer without calling the chat model
- bypass plus `onlyNeedPrompt` returns the complete bypass prompt payload without calling the chat model

- [ ] **Step 5: Add a failing OpenAI adapter blank-system test**

Cover:

- blank `systemPrompt` omits the system message and only sends history plus current user message

- [ ] **Step 6: Add failing end-to-end tests**

Cover:

- `LightRag.query(...)` returns assembled context for `onlyNeedContext`
- `LightRag.query(...)` returns complete prompt for `onlyNeedPrompt`
- `LightRag.query(...)` supports `BYPASS`
- bypass coverage uses a fixture that can record raw-query chat requests instead of only question-formatted retrieval prompts

- [ ] **Step 7: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because the new shortcut flags and bypass mode do not exist yet.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG query shortcut controls"
```

## Chunk 2: Query Shortcut Implementation

### Task 2: Implement bypass and shortcut query controls

**Files:**
- Modify: `src/main/java/io/github/lightragjava/api/QueryMode.java`
- Modify: `src/main/java/io/github/lightragjava/api/QueryRequest.java`
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`
- Modify: `src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Extend `QueryMode` and `QueryRequest`**

Requirements:

- add `BYPASS`
- add `onlyNeedContext`
- add `onlyNeedPrompt`
- default the new flags to `false`
- preserve the existing 6-argument constructor by delegating with `false` defaults
- preserve the existing 8-argument constructor from the query-prompt phase by delegating with `false` defaults

- [ ] **Step 2: Update `QueryEngine` branching**

Requirements:

- bypass mode skips retrieval entirely
- `onlyNeedContext` returns assembled context without calling the chat model
- `onlyNeedPrompt` returns complete prompt text without calling the chat model
- prompt-only text contains the final system prompt, history messages, and the final current-turn user prompt
- `onlyNeedContext` takes precedence over `onlyNeedPrompt`
- standard retrieval behavior and rerank behavior stay unchanged
- shortcut returns preserve the final `contexts` payload exactly as standard retrieval would
- bypass mode passes a blank system prompt to the chat model
- bypass mode reuses `userPrompt` by appending it to the raw query as additional instructions
- bypass combinations follow the defined shortcut precedence rules

- [ ] **Step 3: Update OpenAI-compatible adapter**

Requirements:

- omit the system message when `systemPrompt` is blank
- preserve current order for non-blank system prompts

- [ ] **Step 4: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.model.openai.OpenAiCompatibleChatModelTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/api/QueryMode.java src/main/java/io/github/lightragjava/api/QueryRequest.java src/main/java/io/github/lightragjava/query/QueryEngine.java src/main/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModel.java src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/model/openai/OpenAiCompatibleChatModelTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: align LightRAG query shortcut controls"
```

## Chunk 3: Documentation And Verification

### Task 3: Document and verify query shortcut support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-query-shortcuts-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-query-shortcuts.md`

- [ ] **Step 1: Document shortcut controls in README**

Include:

- how to use `mode(BYPASS)`
- how to use `onlyNeedContext`
- how to use `onlyNeedPrompt`
- what is returned in `QueryResult.answer`

- [ ] **Step 2: Keep spec and plan aligned with implementation**

Update docs to reflect the implemented shortcut precedence and compatibility rules. Do not silently redefine the spec to excuse drift.

- [ ] **Step 3: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run:

- `git status --short`

Expected: only query-shortcut-related files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-16-java-lightrag-query-shortcuts-design.md docs/superpowers/plans/2026-03-16-java-lightrag-query-shortcuts.md
git commit -m "docs: finalize LightRAG query shortcut alignment"
```
