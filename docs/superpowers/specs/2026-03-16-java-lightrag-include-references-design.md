# Java LightRAG Include References Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: support query-time `includeReferences` so callers can receive a structured reference list alongside the generated answer and retrieved contexts.

Upstream `QueryParam` exposes `include_references` and the query result surface can include a structured references collection derived from retrieved chunks. The current Java SDK only exposes `answer` plus plain retrieved contexts, so callers cannot request a dedicated references field and cannot map retrieved chunks to stable reference identifiers.

This phase adds the caller-visible query flag and a Java-friendly structured result shape without attempting to reproduce upstream's larger `raw_data` envelope.

## Goals

- Add `includeReferences` to `QueryRequest`.
- Add structured references to `QueryResult`.
- Add per-context reference metadata so returned contexts can be associated with the emitted references list.
- Derive references from the final retrieval chunk set after rerank and token-budget trimming.
- Preserve existing behavior when `includeReferences` is not enabled.

## Non-Goals

- Do not add upstream's full `raw_data` / `metadata` response envelope in this phase.
- Do not redesign prompt templates or force the LLM to emit structured citations.
- Do not change retrieval ranking, rerank, or token-budget semantics beyond using the final chunk set as the references source.
- Do not require a real filesystem path in document metadata.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/base.py`, `/tmp/LightRAG-upstream/lightrag/lightrag.py`, and `/tmp/LightRAG-upstream/lightrag/utils.py` from upstream commit `e88d18949031f73c8eb7560fe28a3a859b3158e6`.

Relevant upstream behavior:

- `QueryParam` exposes `include_references: bool = False`.
- Query responses can include a structured `references` list.
- Reference IDs are assigned from final chunk sources after retrieval.
- Upstream prioritizes references by source occurrence frequency, then first appearance order.

The Java SDK can align with the visible capability by returning a structured references list and enriching returned contexts with reference identifiers.

## Architectural Options Considered

### Option 1: Add only a `references` list to `QueryResult`

Pros:

- smallest public API expansion
- low implementation cost

Cons:

- callers cannot map individual returned contexts to a reference entry
- weaker alignment with upstream chunk-level reference IDs

### Option 2: Add `references` plus per-context reference metadata

Pros:

- callers can map each context to a stable reference entry
- still much smaller than upstream's full `raw_data` response
- fits the existing Java `QueryResult.contexts()` surface naturally

Cons:

- changes the `QueryResult.Context` record shape

### Option 3: Reproduce upstream's full `raw_data` payload

Pros:

- closest byte-level alignment

Cons:

- much larger API change than needed for this step
- drags in upstream metadata structures not yet implemented elsewhere in Java

## Recommendation

Adopt Option 2.

Extend `QueryRequest` with:

- `boolean includeReferences`

Extend `QueryResult` with:

- `List<Reference> references`

Extend `QueryResult.Context` with:

- `String referenceId`
- `String source`

Compatibility approach:

- keep the existing two-argument `QueryResult(String answer, List<Context> contexts)` constructor and default `references` to an empty list
- keep the existing two-argument `QueryResult.Context(String sourceId, String text)` constructor and default `referenceId` / `source` to empty strings

## Reference Derivation Rules

References are derived from the final chunk list after:

- retrieval
- merge/composition
- rerank
- final token-budget trimming

Source resolution priority for each chunk:

1. `chunk.metadata().get("file_path")`
2. `chunk.metadata().get("source")`
3. `chunk.documentId()`

Reference ID assignment follows upstream's broad approach:

- count occurrences per resolved source
- sort sources by descending frequency
- break ties by first appearance in the final chunk list
- assign `"1"`, `"2"`, `"3"`, ...

If `includeReferences` is `false`:

- `QueryResult.references()` is empty
- `QueryResult.Context.referenceId()` is empty
- `QueryResult.Context.source()` is empty

If `includeReferences` is `true` but no sources can be resolved:

- `QueryResult.references()` is empty
- contexts still return with empty `referenceId` and `source`

## Query Mode Semantics

- `LOCAL`, `GLOBAL`, `HYBRID`, `MIX`, and `NAIVE` derive references from their final retrieved chunk contexts.
- `BYPASS` returns an empty references list because no retrieval occurs.
- `onlyNeedContext` and `onlyNeedPrompt` still return the same `answer` payload semantics as today, but may now also include structured references when enabled.

## Testing Strategy

Required coverage:

- `QueryRequest` defaults `includeReferences` to `false`
- `QueryRequest` accepts explicit `includeReferences(true)`
- `QueryResult` preserves backward-compatible convenience constructors
- `QueryEngine` returns empty references by default
- `QueryEngine` returns structured references and per-context reference metadata when enabled
- reference ordering follows frequency, then first appearance
- rerank-expanded internal requests preserve `includeReferences`
- `BYPASS` returns no references even when requested
- end-to-end `LightRag.query(...)` can emit references from document metadata using `source`

Verification should use focused builder, query-engine, and end-to-end tests. Full `./gradlew test` remains optional because the repository still has a pre-existing Neo4j Testcontainers hang unrelated to this feature.
