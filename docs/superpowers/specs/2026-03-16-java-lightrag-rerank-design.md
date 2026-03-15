# Java LightRAG Rerank Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: optional reranking of retrieved text chunks before final answer generation.

The upstream LightRAG README exposes rerank support through an instance-level rerank function plus a per-query `enable_rerank` switch. The Java SDK currently retrieves chunks from `local`, `global`, `hybrid`, and `mix` strategies, but it does not support a second-stage rerank model.

This phase adds a Java-first rerank SPI and query toggle without changing the existing storage SPI or query mode surface.

## Goals

- Add an optional rerank SPI to the Java SDK.
- Allow rerank to be configured once on `LightRagBuilder`.
- Allow rerank to be enabled or disabled per `QueryRequest`.
- Apply rerank to chunk candidates before final contexts are assembled and sent to the chat model.
- Keep all bundled storage providers compatible without schema changes.

## Non-Goals

- Do not add external rerank service adapters in this phase.
- Do not redesign graph retrieval or keyword extraction.
- Do not add new query modes such as `naive` in this phase.
- Do not add rerank result persistence or caching in this phase.

## Upstream Behavior To Align

According to the upstream LightRAG README:

- rerank is configured as an optional model/function on the LightRAG instance
- query execution has an `enable_rerank` flag
- rerank is used to improve chunk ordering before answer generation
- rerank is especially relevant for `mix`-style retrieval

The Java SDK can align with the visible behavior by:

- adding an optional `RerankModel`
- defaulting query requests to `enableRerank = true`
- skipping rerank when no model is configured
- reranking chunk candidates after retrieval and before `QueryResult.Context` assembly

Implementation note:

- the current Java strategies already honor `QueryRequest.chunkTopK`, so the final implementation only needed `QueryEngine` to pass an internal expanded request rather than changing each strategy's retrieval logic

The upstream README mentions warning when rerank is enabled but no reranker is configured. The Java SDK intentionally does not add a warning side channel in this phase. It will treat that case as a deterministic no-op so the public API stays small and the core query path remains logger-free.

## API Shape

Add a new model SPI:

- `RerankModel`

Recommended contract:

- `List<RerankResult> rerank(RerankRequest request)`

Public nested records are sufficient for the first phase:

- `RerankRequest`
- `RerankCandidate`
- `RerankResult`

Builder/config changes:

- `LightRagBuilder.rerankModel(RerankModel rerankModel)`
- `LightRagConfig` stores nullable `RerankModel`

Query API changes:

- add `boolean enableRerank` to `QueryRequest`
- default `enableRerank` to `true`

## Architectural Options Considered

### Option 1: Per-strategy rerank

Run rerank separately inside `LocalQueryStrategy`, `GlobalQueryStrategy`, `HybridQueryStrategy`, and `MixQueryStrategy`.

Pros:

- strategies stay self-contained

Cons:

- duplicated logic
- harder to keep behavior consistent
- awkward for `hybrid` and `mix`, which already compose other strategies

### Option 2: Central chunk rerank after retrieval

Let strategies continue retrieving candidate chunks, then rerank the final candidate list in one shared place before answer generation.

Pros:

- one implementation path
- preserves existing graph retrieval logic
- closest match to upstream “optional second-stage enhancement”

Cons:

- requires a query contract refactor so the engine can rerank before final context assembly

### Option 3: Storage/vector-layer rerank

Push rerank semantics into `VectorStore` or provider-specific query code.

Pros:

- none that matter for current Java shape

Cons:

- wrong abstraction boundary
- couples providers to model concerns
- complicates future database adapters

## Recommendation

Adopt Option 2.

Rerank should operate on retrieved chunk candidates after the active query strategy has done its graph/vector work, but before `ContextAssembler` converts them into final context text and `QueryResult.Context` records.

## Query Flow Changes

Current flow:

1. strategy retrieves entities, relations, and chunks
2. strategy assembles final context
3. `QueryEngine` prompts the chat model

New flow:

1. strategy retrieves entities, relations, and chunk candidates
2. `QueryEngine` optionally reranks chunk candidates
3. final chunk list is trimmed to `chunkTopK`
4. `ContextAssembler` assembles final context from the reranked chunk list
5. `QueryEngine` prompts the chat model

Because strategies currently return a fully assembled `QueryContext`, this phase must refactor the query contract slightly:

- strategies still return `QueryContext`
- but `assembledContext` becomes provisional until the engine finishes final chunk ordering
- `QueryEngine` becomes responsible for producing the final assembled context after rerank or fallback ordering is known

In the shipped implementation, this refactor stayed localized to `QueryEngine`: existing strategies continue to build provisional contexts, and the engine always rebuilds the final assembled context from the final chunk order.

## Candidate Set Semantics

If rerank is enabled and a rerank model is configured, the retrieval stage should provide a slightly larger chunk candidate set than the final `chunkTopK`, so rerank has meaningful choice.

For the first Java phase:

- use a deterministic candidate window derived from `chunkTopK`
- recommended rule: retrieve up to `max(chunkTopK * 2, chunkTopK)`

This candidate window must be applied consistently across all composed modes:

- `QueryEngine` derives an internal retrieval request with expanded `chunkTopK`
- `LocalQueryStrategy` and `GlobalQueryStrategy` use that expanded chunk limit directly
- `HybridQueryStrategy` merges already-expanded local/global chunk candidates without trimming back to the original final size
- `MixQueryStrategy` must also use the expanded limit for direct chunk-vector retrieval before final deduplication

The public `QueryRequest` still exposes only the final requested `chunkTopK`. The expanded candidate limit is an internal execution detail.

If rerank is disabled or no rerank model is configured:

- retain current behavior and limit directly to `chunkTopK`

## Failure Semantics

- If rerank is disabled, query proceeds exactly as it does today.
- If no rerank model is configured, query proceeds exactly as it does today.
- If rerank returns unknown chunk IDs, ignore those entries.
- If rerank omits some candidate IDs, append the remaining original candidates in existing score order so contexts stay populated.
- If rerank fails, surface the failure and fail the query rather than silently changing retrieval semantics.

Score semantics:

- `ScoredChunk.score` keeps the original retrieval score
- rerank changes chunk order only
- rerank scores are treated as transient orchestration data and are not exposed through `QueryContext` or prompt formatting in this phase

## Testing Strategy

Required coverage:

- `QueryRequest` defaults rerank to enabled
- query without configured rerank model preserves current ordering
- configured rerank model reorders final chunk contexts
- per-query `enableRerank(false)` bypasses rerank even when configured
- rerank fallback keeps omitted original candidates
- end-to-end query behavior remains correct with rerank enabled

Verification should include targeted query tests plus full `./gradlew test`.
