# Java LightRAG Query Token Budgets Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: support query-time token budget controls for graph retrieval and final chunk context assembly.

Upstream `QueryParam` exposes `max_entity_tokens`, `max_relation_tokens`, and `max_total_tokens`. The current Java SDK limits retrieval only by `topK` and `chunkTopK`, so callers cannot bound how much entity, relation, or chunk context is retained when building prompts.

This phase adds the three token budget knobs and applies them in the same broad places as upstream:

- entity context is capped independently
- relation context is capped independently
- final chunk context is capped against the remaining total budget after merge and rerank

## Goals

- Add `maxEntityTokens`, `maxRelationTokens`, and `maxTotalTokens` to `QueryRequest`.
- Truncate matched entities in graph-aware strategies when their formatted context exceeds `maxEntityTokens`.
- Truncate matched relations in graph-aware strategies when their formatted context exceeds `maxRelationTokens`.
- Limit final matched chunks in graph and naive retrieval modes so assembled chunk context stays within `maxTotalTokens`.
- Preserve existing behavior when callers do not override budgets by using upstream-aligned defaults.

## Non-Goals

- Do not introduce an exact model-specific tokenizer dependency in this phase.
- Do not implement `includeReferences`, `stream`, `modelFunc`, or automatic keyword extraction here.
- Do not redesign prompt templates or retrieval ranking logic beyond budget enforcement.
- Do not change ingest-time chunk tokenization.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/base.py` and `/tmp/LightRAG-upstream/lightrag/operate.py` from upstream commit `e88d18949031f73c8eb7560fe28a3a859b3158e6`.

Relevant upstream behavior:

- `QueryParam` accepts `max_entity_tokens`, `max_relation_tokens`, and `max_total_tokens`.
- Entity and relation context lists are truncated independently by token count before prompt assembly.
- Final chunk selection is constrained by remaining total prompt budget after accounting for the system prompt, graph context, query text, and a fixed buffer.
- Naive retrieval also constrains chunk context against `max_total_tokens`, but without graph-context subtraction.

The Java SDK can align with this behavior without introducing a full tokenizer by using existing chunk token counts plus a consistent approximation for prompt, query, entity, and relation text.

## Architectural Options Considered

### Option 1: Add request fields and enforce budgets inside each query strategy

Pros:

- closest to upstream control points
- keeps retrieval and truncation logic near the data being selected
- avoids overloading `QueryEngine` with strategy-specific list manipulation

Cons:

- requires shared helper logic so token estimation does not diverge across strategies

### Option 2: Keep strategies unchanged and truncate only in `QueryEngine`

Pros:

- fewer strategy edits

Cons:

- `QueryEngine` would need to understand graph-vs-naive retrieval details
- harder to match upstream's separate entity/relation caps
- makes rerank/chunk expansion interactions less clear

### Option 3: Add a pluggable tokenizer abstraction first

Pros:

- more precise budgeting surface long term

Cons:

- significantly larger scope than the upstream-alignment gap being addressed
- introduces model/provider design questions unrelated to this feature

## Recommendation

Adopt Option 1 with a post-merge enforcement point.

Extend `QueryRequest` with:

- `int maxEntityTokens`
- `int maxRelationTokens`
- `int maxTotalTokens`

Add a shared query-budget helper used by composed strategies and `QueryEngine`:

- entity and relation token counts are approximated from the same formatted strings that appear in assembled context
- chunk token counts intentionally continue to use stored `Chunk.tokenCount()` for now, even though that is an ingest-time approximation rather than the same estimator used for other prompt text
- prompt/query overhead uses the same approximation utility so remaining-budget calculations are internally consistent for non-chunk text

Behavior:

- `LOCAL` and `GLOBAL` cap their own entity/relation lists before building context
- `HYBRID` and `MIX` re-apply entity/relation budgets after merging local/global results so composed modes do not effectively double-trim
- final chunk budgeting is applied to the final chunk set after merge and rerank ordering are complete
- `LOCAL`, `GLOBAL`, `HYBRID`, `MIX`, and `NAIVE` all respect `maxTotalTokens` on the final chunk set they expose
- `QueryEngine.expandChunkRequest(...)` preserves entity/relation budgets when rerank expands chunk retrieval breadth and intentionally relaxes the internal total chunk cap so final `maxTotalTokens` enforcement still happens after rerank

## Defaults And Compatibility

Use upstream-aligned defaults on `QueryRequest` so existing callers keep similar behavior without new configuration:

- `maxEntityTokens = 6_000`
- `maxRelationTokens = 8_000`
- `maxTotalTokens = 30_000`

Compatibility notes:

- builder-based callers remain source-compatible
- the public `QueryRequest` record shape changes because new components are added
- callers relying on the canonical record constructor or deconstruction need updates
- existing queries that do not override budgets keep the same prompt templates and ranking inputs, but very large contexts can now be truncated by the new default bounds

## Testing Strategy

Required coverage:

- `QueryRequest` exposes defaults and accepts explicit token budget overrides
- invalid non-positive token budgets are rejected
- `LocalQueryStrategy` truncates entity context to `maxEntityTokens`
- `GlobalQueryStrategy` truncates relation context to `maxRelationTokens`
- graph-aware retrieval caps chunk context by remaining `maxTotalTokens`
- naive retrieval caps chunk context by `maxTotalTokens`
- rerank expansion preserves entity/relation budgets and defers final total-budget enforcement until after rerank

Verification should use focused unit tests for affected strategies and `QueryEngine`. Full `./gradlew test` is still blocked by the pre-existing Neo4j Testcontainers hang and is not required for this scoped change.
