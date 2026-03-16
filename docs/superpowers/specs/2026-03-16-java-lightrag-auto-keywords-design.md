# Java LightRAG Auto Keywords Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: automatic extraction of high-level and low-level query keywords when callers do not provide `hlKeywords` or `llKeywords`.

Upstream LightRAG resolves query keywords before graph-aware retrieval. If manual keywords are already present, it uses them directly. Otherwise it invokes the LLM once in keyword-extraction mode, parses JSON, and feeds the resulting keywords into local/global/hybrid/mix retrieval. The current Java SDK only supports manual keyword overrides; when both lists are empty it falls back directly to raw-query retrieval.

This phase adds automatic keyword extraction while preserving manual overrides as the highest-priority input.

## Goals

- Automatically derive `hlKeywords` and `llKeywords` for graph-aware query modes.
- Preserve manual `hlKeywords` / `llKeywords` overrides exactly as provided.
- Use the same selected query model for keyword extraction and final answer generation.
- Keep `NAIVE` and `BYPASS` behavior unchanged.
- Preserve current query result shapes and public query entry points.

## Non-Goals

- Do not add a new public keyword-extraction API.
- Do not change indexing-time extraction behavior.
- Do not redesign vector, graph, rerank, or reference logic.
- Do not add provider-specific JSON-mode APIs to `ChatModel` in this phase.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/operate.py` and `/tmp/LightRAG-upstream/lightrag/prompt.py`.

Relevant upstream behavior:

- If `hl_keywords` or `ll_keywords` are provided, use them directly.
- Otherwise, call the query model once with a keyword-extraction prompt.
- Parse JSON fields `high_level_keywords` and `low_level_keywords`.
- If extraction yields nothing, fall back to raw-query behavior by mode.

The Java SDK can align with this behavior by resolving keywords inside `QueryEngine` before it delegates to retrieval strategies.

## Architectural Options Considered

### Option 1: Add a keyword-resolution step in `QueryEngine`

Pros:

- centralizes upstream-like behavior in one place
- keeps retrieval strategies focused on retrieval
- easy to apply the same selected query model to extraction and answer generation

Cons:

- adds one more preprocessing step before retrieval

### Option 2: Teach each query strategy to auto-extract keywords

Pros:

- strategies own their own inputs

Cons:

- duplicates keyword-extraction logic across local/global/hybrid/mix
- harder to preserve consistent fallback semantics
- diverges from upstream's shared preprocessing step

### Option 3: Add automatic extraction in `LightRag.query(...)`

Pros:

- happens before `QueryEngine`

Cons:

- pushes retrieval-specific behavior into the public facade
- makes lower-level `QueryEngine` less upstream-aligned

## Recommendation

Adopt Option 1.

Introduce a small internal helper such as `QueryKeywordExtractor` that:

- returns manual keywords unchanged when either list is non-empty
- skips extraction for `NAIVE` and `BYPASS`
- otherwise prompts the selected query model for JSON keyword output
- normalizes parsed keywords using the same trimming rules as `QueryRequest`
- applies an upstream-like fallback when extraction returns nothing

Resolved keyword behavior:

- `LOCAL`: fallback to `llKeywords = [query]`
- `GLOBAL`: fallback to `hlKeywords = [query]`
- `HYBRID` / `MIX`: fallback to `llKeywords = [query]`, leaving `hlKeywords` empty

That fallback matches upstream more closely than the current Java behavior where both local and global branches may reuse the raw query when no keywords exist.

## Prompt And Parsing Semantics

The keyword extractor should use an internal prompt aligned with upstream's role/goal/instructions/examples structure and request strict JSON with:

- `high_level_keywords`
- `low_level_keywords`

Parsing rules:

- accept only textual array items
- trim blanks
- ignore empty or malformed entries
- if JSON parsing fails, treat extraction as empty and apply fallback

## Query Semantics

- Manual overrides continue to take precedence.
- Automatic extraction runs before retrieval, so it also affects `onlyNeedContext(true)` and `onlyNeedPrompt(true)` in graph-aware modes.
- `stream(true)` does not change extraction behavior; keyword extraction still occurs before retrieval.
- `modelFunc(...)` also controls keyword extraction, because upstream uses `query_param.model_func` for both extraction and final generation.

## Testing Strategy

Required coverage:

- graph-aware queries auto-populate keywords when both lists are empty
- manual keyword overrides bypass automatic extraction
- `NAIVE` and `BYPASS` skip automatic extraction
- extracted keywords are propagated into retrieval requests
- empty or malformed extraction output falls back by mode
- `modelFunc(...)` is also used for keyword extraction
- end-to-end graph-aware queries still succeed with automatic extraction enabled

Verification should use focused builder, query-engine, strategy, and end-to-end tests. Full `./gradlew test` remains optional as a secondary gate because unrelated Testcontainers cases can still be environment-sensitive.
