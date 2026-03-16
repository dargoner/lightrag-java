# Java LightRAG ModelFunc Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: support query-time `model_func` overrides so callers can switch the LLM used for one specific query without rebuilding the whole `LightRag` instance.

Upstream `QueryParam` exposes `model_func`, and query execution prefers that override over the globally configured `llm_model_func`. The current Java SDK only supports the builder-level `chatModel`, so every query in one `LightRag` instance is forced through the same model.

This phase adds a Java-native per-query override while preserving the existing global builder model as the default.

## Goals

- Add `modelFunc` to `QueryRequest`.
- Use `QueryRequest.modelFunc()` as a per-query `ChatModel` override.
- Preserve the builder-level `chatModel` as the default fallback.
- Apply the override to both buffered and streaming query generation.
- Preserve all current retrieval, rerank, reference, and token-budget behavior.

## Non-Goals

- Do not change indexing-time extraction to use query-level model overrides.
- Do not replace the builder-level `chatModel`; it remains required.
- Do not redesign `ChatModel` itself beyond reusing the existing abstraction.
- Do not implement automatic keyword extraction in this phase.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/lightrag.py` and `/tmp/LightRAG-upstream/lightrag/operate.py`.

Relevant upstream behavior:

- `QueryParam` exposes `model_func`.
- Query execution uses `query_param.model_func` when provided.
- Otherwise it falls back to the global `llm_model_func`.

The Java SDK can align with this behavior by exposing `ChatModel modelFunc` on `QueryRequest` and choosing that model inside `QueryEngine`.

## Architectural Options Considered

### Option 1: Add `ChatModel modelFunc` to `QueryRequest`

Pros:

- maps directly to upstream `QueryParam.model_func`
- keeps the override scoped to one query
- reuses the existing `ChatModel` abstraction for both buffered and streaming paths

Cons:

- expands the public `QueryRequest` shape

### Option 2: Add a second query API that accepts a `ChatModel`

Pros:

- keeps `QueryRequest` smaller

Cons:

- duplicates the query surface
- diverges from upstream's `QueryParam` model

### Option 3: Make `LightRag` mutable and allow swapping the global model before each query

Pros:

- no `QueryRequest` change

Cons:

- unsafe for concurrent callers
- changes global instance behavior for a local need
- diverges from upstream

## Recommendation

Adopt Option 1.

Extend `QueryRequest` with:

- `ChatModel modelFunc`

Behavior:

- if `request.modelFunc()` is non-null, `QueryEngine` uses it for generation
- otherwise `QueryEngine` uses the builder-configured default `chatModel`
- `stream(true)` uses the same selected model's `stream(...)`

Compatibility approach:

- keep current builder-level `chatModel` required on `LightRagBuilder`
- default `modelFunc` to `null`
- preserve convenience constructors by defaulting `modelFunc` to `null`

## Query Semantics

- Standard retrieval modes should use the selected model only for final answer generation.
- `BYPASS` should use the selected model directly.
- `onlyNeedContext(true)` and `onlyNeedPrompt(true)` should not call either the override model or the default model.
- When rerank expands the internal request, the expanded `QueryRequest` must preserve `modelFunc`.

## Testing Strategy

Required coverage:

- `QueryRequest` defaults `modelFunc` to `null`
- `QueryRequest` accepts `modelFunc(...)`
- `QueryEngine` uses the request override instead of the default model
- `QueryEngine` preserves `modelFunc` when rerank expands internal requests
- `QueryEngine` still bypasses model invocation for `onlyNeedContext` and `onlyNeedPrompt`
- `QueryEngine` uses the override model for `BYPASS`
- streaming queries use `modelFunc.stream(...)` when both `stream(true)` and `modelFunc(...)` are set
- end-to-end `LightRag.query(...)` can override the global model for a single request

Verification should use focused builder, query-engine, and end-to-end tests. The repository's full `./gradlew test` remains acceptable but not required as the primary gate because unrelated Testcontainers cases are known to be environment-sensitive.
