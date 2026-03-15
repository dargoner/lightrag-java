# Java LightRAG Naive Mode Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: add `naive` query mode.

Upstream LightRAG exposes a `naive` mode alongside graph-aware retrieval modes. The current Java SDK supports `local`, `global`, `hybrid`, and `mix`, but it does not expose a chunk-only retrieval mode.

This phase adds a Java `NAIVE` query mode that retrieves contexts directly from chunk-vector similarity without graph expansion.

## Goals

- Add `NAIVE` to the public `QueryMode` surface.
- Allow `QueryRequest.mode(NAIVE)` to execute successfully through `LightRag.query(...)`.
- Retrieve contexts using only chunk-vector similarity from the `chunks` namespace.
- Keep rerank support compatible with `NAIVE` through the existing `QueryEngine` orchestration.

## Non-Goals

- Do not change the default query mode. It remains `MIX`.
- Do not redesign `mix`, `local`, `global`, or `hybrid`.
- Do not add upstream query flags beyond the mode itself in this phase.
- Do not persist additional query metadata.

## Upstream Behavior To Align

The upstream LightRAG README exposes a `naive` query mode as a direct retrieval path that does not depend on graph traversal.

The Java SDK can align with that visible behavior by:

- adding `NAIVE` to `QueryMode`
- wiring a dedicated `NaiveQueryStrategy`
- returning chunk contexts from direct chunk-vector matches
- leaving matched entities and relations empty

## Recommended API Shape

Public surface changes:

- add `NAIVE` to `QueryMode`

No `QueryRequest` shape changes are required beyond accepting the new enum value.

## Architecture

Add a dedicated `NaiveQueryStrategy` that mirrors the direct chunk-vector retrieval portion of `MixQueryStrategy`, but without invoking `HybridQueryStrategy`.

Recommended responsibilities:

- embed the query with the configured `EmbeddingModel`
- search the chunk vector namespace with `chunkTopK`
- load chunk records from `ChunkStore`
- map results to `ScoredChunk`
- sort by retrieval score descending, then chunk ID
- return provisional assembled context through `ContextAssembler`

The resulting `QueryContext` should contain:

- `matchedEntities = []`
- `matchedRelations = []`
- `matchedChunks = direct chunk vector matches`
- `assembledContext = provisional context assembled from the strategy chunk list`

As with the current rerank architecture, `QueryEngine` still owns the final assembled prompt context after optional rerank and final trimming. `NaiveQueryStrategy` should follow the same provisional-context contract as the other strategies rather than trying to own final prompt assembly itself.

`QueryRequest.topK` remains part of the public request shape, but it is ignored for `NAIVE`. The effective retrieval control is `chunkTopK`.

## Rerank Semantics

`NAIVE` should integrate with the existing rerank behavior exactly like the other modes:

- if rerank is enabled and a rerank model is configured, `QueryEngine` expands the internal chunk candidate window and reranks the naive chunk candidates
- if rerank is disabled or no rerank model is configured, `NAIVE` preserves direct retrieval order
- rerank still changes order only, not exposed scores

No special naive-specific rerank code is needed beyond honoring the `QueryRequest.chunkTopK` value passed into the strategy.

## Testing Strategy

Required coverage:

- `QueryMode` exposes `NAIVE`
- `LightRag` wires `NAIVE` into the strategy map
- `NaiveQueryStrategy` returns only chunk contexts and no entities/relations
- `NaiveQueryStrategy` ignores `topK` and uses `chunkTopK`
- `NaiveQueryStrategy` breaks equal chunk scores by chunk ID
- `NAIVE` respects `chunkTopK`
- end-to-end `LightRag.query(...)` works with `mode(NAIVE)`
- rerank can reorder `NAIVE` chunk results through the shared engine path
- PostgreSQL and PostgreSQL+Neo4j query-mode matrices include `NAIVE`

Verification should include targeted unit tests plus full `./gradlew test`.
