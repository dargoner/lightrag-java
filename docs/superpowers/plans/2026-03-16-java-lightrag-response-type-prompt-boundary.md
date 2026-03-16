# Java LightRAG Response Type And Prompt Boundary Alignment Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `responseType` effective in final prompt construction and align Java's standard retrieval prompt boundary and related prompt semantics with upstream LightRAG.

**Architecture:** Keep the current public API and change only the model-facing prompt assembly inside `QueryEngine`. Standard retrieval modes will now emit retrieval instructions, effective `responseType`, effective `userPrompt`, and assembled context in `ChatRequest.systemPrompt`, while the current-turn `userPrompt` becomes the raw query text. This phase also corrects remaining shortcut and default-value semantics so prompt rendering is closer to upstream instead of preserving earlier Java-specific behavior.

**Tech Stack:** Java 17, existing LightRag query pipeline, JUnit 5, AssertJ, OkHttp MockWebServer

---

## Chunk 1: Prompt Boundary Red Phase

### Task 1: Add failing tests for response type and prompt-boundary alignment

**Files:**
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add a failing `QueryEngine` system-prompt test**

Cover:

- assembled retrieval context is moved into `systemPrompt`
- a non-default `responseType` appears in `systemPrompt`
- blank `responseType` falls back to `Multiple Paragraphs`
- current-turn `userPrompt` is the raw query text

- [ ] **Step 2: Add a failing `QueryEngine` additional-instructions test**

Cover:

- non-blank `userPrompt` appears in `systemPrompt` as an additional-instructions block
- blank `userPrompt` falls back to `n/a` inside that block
- conversation history remains separate and is not flattened into `systemPrompt` or current-turn `userPrompt`

- [ ] **Step 3: Add a failing rerank-path test**

Cover:

- reranked chunk order is now reflected in `systemPrompt`
- current-turn `userPrompt` still stays equal to the raw query text
- a non-default `responseType` still survives the rerank path

- [ ] **Step 4: Add a failing prompt-only test for the new boundary**

Cover:

- prompt-only output now shows the new system/history/user split
- standard retrieval prompt-only output contains `responseType`
- prompt-only output matches the upstream-like system-prompt-plus-query shape and does not inline history

- [ ] **Step 5: Add a failing shortcut-precedence test**

Cover:

- when both `onlyNeedContext` and `onlyNeedPrompt` are `true`, prompt-only wins

- [ ] **Step 6: Add failing end-to-end tests**

Cover:

- `LightRag.query(...)` still answers correctly after the prompt-boundary shift
- recorded query requests now show context in `systemPrompt`
- recorded query requests now show the raw query text in `userPrompt`
- the E2E fake chat-model harness is updated so query-vs-extraction detection no longer depends on the old `userPrompt.contains("Question:")` boundary
- at least one multi-mode check confirms the new boundary is used beyond the `LOCAL` unit-test helper path

- [ ] **Step 7: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because Java still puts retrieval context in the current-turn user message and does not include `responseType` in the final prompt.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "test: cover LightRAG response type prompt alignment"
```

## Chunk 2: Prompt Assembly Implementation

### Task 2: Implement response type and prompt-boundary alignment

**Files:**
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Update `QueryEngine` standard retrieval prompt assembly**

Requirements:

- build a retrieval system prompt for standard retrieval modes
- include `responseType` in that system prompt
- fall back to `Multiple Paragraphs` when `responseType` is blank
- include assembled context in that system prompt
- include `userPrompt` through the additional-instructions slot in that system prompt
- fall back to `n/a` when `userPrompt` is blank
- set current-turn `userPrompt` to the raw query text
- keep conversation history separate from both prompt strings

- [ ] **Step 2: Preserve existing non-boundary behavior**

Requirements:

- do not change retrieval strategy selection
- do not change rerank behavior
- do not change bypass behavior
- change shortcut precedence so `onlyNeedPrompt` wins when both flags are `true`
- update the E2E fake chat-model detection logic so retrieval queries are recognized from the new prompt boundary instead of the old `Question:` marker in `userPrompt`

- [ ] **Step 3: Ensure prompt-only output reflects the new boundary**

Requirements:

- standard retrieval prompt-only output reflects the updated `systemPrompt`
- raw query appears after the formatted system prompt
- history is not inlined into the prompt-only inspection text
- non-default `responseType` is visible in prompt-only output

- [ ] **Step 4: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/lightragjava/query/QueryEngine.java src/test/java/io/github/lightragjava/query/QueryEngineTest.java src/test/java/io/github/lightragjava/E2ELightRagTest.java
git commit -m "feat: align LightRAG response type prompt boundary"
```

## Chunk 3: Documentation And Verification

### Task 3: Document and verify prompt-boundary support

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-response-type-prompt-boundary-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-response-type-prompt-boundary.md`

- [ ] **Step 1: Document prompt-boundary behavior in README**

Include:

- that `responseType` now affects final prompt construction
- that standard retrieval modes send context through `systemPrompt`
- that the current-turn user message remains the raw query text

- [ ] **Step 2: Keep spec and plan aligned with implementation**

Update docs to reflect the final system/history/user prompt split. Do not silently redefine the spec to excuse drift.

- [ ] **Step 3: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect git diff**

Run:

- `git status --short`

Expected: only response-type prompt-boundary files changed.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/superpowers/specs/2026-03-16-java-lightrag-response-type-prompt-boundary-design.md docs/superpowers/plans/2026-03-16-java-lightrag-response-type-prompt-boundary.md
git commit -m "docs: finalize LightRAG response type prompt alignment"
```
