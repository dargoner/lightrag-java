# Java LightRAG Query Stream Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: support query-time `stream` so callers can consume generated output incrementally instead of waiting for the full response body.

Upstream `QueryParam` exposes `stream: bool = False`, and query execution can return either a complete text response or a streaming iterator. The current Java SDK only supports fully buffered `String` responses from `ChatModel.generate(...)`, so query callers cannot consume generation incrementally.

This phase adds a Java-native streaming path while preserving the existing non-streaming APIs.

## Goals

- Add `stream` to `QueryRequest`.
- Add a streaming generation method to `ChatModel`.
- Extend `QueryResult` so it can carry either a complete answer or a streaming response handle.
- Support OpenAI-compatible streaming via SSE.
- Preserve all existing non-streaming behavior when `stream` is not enabled.

## Non-Goals

- Do not redesign retrieval, rerank, include-references, or token-budget behavior.
- Do not add reactive-library dependencies such as Reactor or RxJava in this phase.
- Do not implement streaming for every future provider abstraction; only the existing OpenAI-compatible chat adapter must support it now.
- Do not change indexing-time model calls to use streaming.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/base.py` and `/tmp/LightRAG-upstream/lightrag/operate.py` from upstream commit `e88d18949031f73c8eb7560fe28a3a859b3158e6`.

Relevant upstream behavior:

- `QueryParam` exposes `stream`.
- Query execution can return either full `content` or a `response_iterator`.
- Structured retrieval data remains available regardless of whether generation is buffered or streamed.

The Java SDK can align with this behavior by returning a streaming handle plus the same structured retrieval metadata already exposed on `QueryResult`.

## Architectural Options Considered

### Option 1: Return `Stream<String>` directly from `LightRag.query(...)`

Pros:

- simple for callers

Cons:

- breaks the existing `QueryResult` contract
- loses structured retrieval metadata unless Java adds a second parallel query API

### Option 2: Add a streaming handle to `QueryResult`

Pros:

- preserves one query entry point
- matches upstream's "content or iterator" dual-shape result
- allows callers to access contexts/references even when output is streamed

Cons:

- expands the public `QueryResult` shape
- needs careful resource-management semantics

### Option 3: Introduce a separate `QueryStreamResult` type

Pros:

- keeps `QueryResult` simpler

Cons:

- duplicates a large part of the query API surface
- diverges from upstream's unified result concept

## Recommendation

Adopt Option 2.

Extend `QueryRequest` with:

- `boolean stream`

Extend `ChatModel` with:

- `CloseableIterator<String> stream(ChatRequest request)`

Extend `QueryResult` with:

- `CloseableIterator<String> answerStream`
- `boolean streaming`

Compatibility approach:

- keep existing constructors that produce non-streaming results
- keep `answer` as the non-streaming content field; for streaming results it should be the empty string
- keep `contexts` and `references` populated from retrieval regardless of buffered vs streaming generation

## Streaming Abstraction

Introduce a small Java-native abstraction:

- `CloseableIterator<T>` extends `Iterator<T>` and `AutoCloseable`

This avoids pulling in reactive dependencies while still making resource lifetime explicit. Callers can use:

```java
try (var stream = result.answerStream()) {
    while (stream.hasNext()) {
        var token = stream.next();
    }
}
```

For non-streaming results, `answerStream()` returns an empty closeable iterator and `streaming()` is `false`.

## Query Semantics

- `onlyNeedContext(true)` still returns assembled context text immediately and does not stream.
- `onlyNeedPrompt(true)` still returns prompt text immediately and does not stream.
- `BYPASS + stream(true)` streams directly from the chat model with empty retrieval metadata.
- standard retrieval modes derive contexts/references first, then stream final generation.

## OpenAI-Compatible Adapter Semantics

`OpenAiCompatibleChatModel.stream(...)` should:

- send the same message payload as buffered generation
- add `"stream": true` to the request body
- parse SSE lines from `/chat/completions`
- emit only non-empty `choices[0].delta.content` fragments
- stop on `[DONE]`
- close the underlying HTTP response body when iteration completes or the caller closes early

## Testing Strategy

Required coverage:

- `QueryRequest` defaults `stream` to `false`
- `QueryRequest` accepts `stream(true)`
- `QueryResult` preserves backward-compatible constructors
- `QueryEngine` returns buffered answers by default and streaming handles when requested
- `QueryEngine` bypasses streaming when `onlyNeedContext` or `onlyNeedPrompt` is enabled
- `QueryEngine` preserves `stream` when rerank expands internal requests
- `OpenAiCompatibleChatModel` sends `"stream": true` for streaming requests
- `OpenAiCompatibleChatModel` yields SSE content fragments in order and closes cleanly
- end-to-end `LightRag.query(...)` supports streaming for both standard retrieval and `BYPASS`

Verification should use focused query-engine, adapter, and end-to-end tests. Full `./gradlew test` remains optional because of the repository's existing unrelated Neo4j Testcontainers hang.
