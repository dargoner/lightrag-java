# Java LightRAG Upstream Prompt Template Alignment Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align Java's standard retrieval prompt template content more closely with upstream LightRAG while preserving the already-aligned prompt boundary and current public API.

**Architecture:** Keep prompt assembly inside `QueryEngine`, but replace the short retrieval template with two richer upstream-style templates: one for graph-aware retrieval modes and one for `NAIVE`. The model-facing request shape stays the same: `systemPrompt` carries retrieval instructions plus assembled context, `userPrompt` stays the raw query text, and `conversationHistory` remains separate.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

## Chunk 1: Red Tests

### Task 1: Add failing tests for richer upstream-style prompt templates

**Files:**
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Add a failing graph-aware prompt-template test**

Cover:

- `LOCAL` retrieval system prompt contains `---Role---`, `---Goal---`, `---Instructions---`, and `---Context---`
- graph-aware prompt wording mentions both knowledge graph data and document chunks
- graph-aware prompt includes upstream-style grounding, language, and references guidance
- existing `responseType` and `userPrompt` assertions still hold inside the richer template

- [ ] **Step 2: Add a failing naive prompt-template test**

Cover:

- `NAIVE` retrieval system prompt contains the same section scaffold
- naive prompt wording refers to document chunks and does not require graph wording
- blank `responseType` still falls back to `Multiple Paragraphs`
- blank `userPrompt` still falls back to `n/a`

- [ ] **Step 3: Add a failing prompt-only rendering test**

Cover:

- prompt-only output includes the richer upstream-style system prompt
- prompt-only output still appends `---User Query---` and the raw query
- prompt-only output still excludes conversation history
- `onlyNeedPrompt` still wins when `onlyNeedContext(true)` is also set on standard retrieval modes

- [ ] **Step 4: Add a failing end-to-end assertion**

Cover:

- end-to-end retrieval still routes through the query path with the richer template
- the recorded query request now includes the upstream-style scaffold on `systemPrompt`
- the real query request still carries `conversationHistory` as structured history when provided

- [ ] **Step 5: Run targeted tests to verify they fail**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: FAIL because Java still uses the older short retrieval template.

## Chunk 2: Green Implementation

### Task 2: Implement richer upstream-style prompt templates in `QueryEngine`

**Files:**
- Modify: `src/main/java/io/github/lightragjava/query/QueryEngine.java`
- Modify: `src/test/java/io/github/lightragjava/query/QueryEngineTest.java`
- Modify: `src/test/java/io/github/lightragjava/E2ELightRagTest.java`

- [ ] **Step 1: Introduce template selection by query mode**

Requirements:

- use one upstream-style template for graph-aware retrieval modes
- use one upstream-style template for `NAIVE`
- keep `BYPASS` untouched

- [ ] **Step 2: Preserve current prompt-boundary semantics**

Requirements:

- `responseType` still defaults to `Multiple Paragraphs`
- `userPrompt` still defaults to `n/a`
- `conversationHistory` stays separate
- `userPrompt` in the chat request stays equal to the raw query text
- prompt-only output continues to render the formatted `systemPrompt` plus raw query text

- [ ] **Step 3: Run targeted tests**

Run:

- `./gradlew test --tests io.github.lightragjava.query.QueryEngineTest`
- `./gradlew test --tests io.github.lightragjava.E2ELightRagTest`

Expected: PASS.

## Chunk 3: Docs And Full Verification

### Task 3: Document the richer prompt-template behavior and verify the suite

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-03-16-java-lightrag-upstream-prompt-template-design.md`
- Modify: `docs/superpowers/plans/2026-03-16-java-lightrag-upstream-prompt-template.md`

- [ ] **Step 1: Update README prompt-control notes**

Include:

- standard retrieval now uses richer upstream-style prompt sections
- graph-aware and naive retrieval use different template wording
- prompt-only inspection returns that richer rendered system prompt

- [ ] **Step 2: Run full verification**

Run:

- `./gradlew test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect the final diff**

Run:

- `git status --short`

Expected: only prompt-template alignment files are modified before commit.
