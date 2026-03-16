# Java LightRAG Query Keyword Overrides Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: support manual query-time keyword overrides through `hlKeywords` and `llKeywords`.

Upstream `QueryParam` exposes `hl_keywords` and `ll_keywords` so callers can bypass automatic keyword extraction and steer retrieval explicitly. The Java SDK currently always embeds the raw query text for graph-aware retrieval modes, which means callers cannot independently steer entity-oriented and relation-oriented retrieval.

This phase adds only the manual override surface. It does not implement upstream's automatic keyword extraction path.

## Goals

- Add manual `hlKeywords` and `llKeywords` query-time controls to the Java SDK.
- Use `llKeywords` to steer entity-oriented retrieval in `LOCAL`, `HYBRID`, and `MIX`.
- Use `hlKeywords` to steer relation-oriented retrieval in `GLOBAL`, `HYBRID`, and `MIX`.
- Preserve existing behavior when no keyword overrides are provided by continuing to embed the raw query text.

## Non-Goals

- Do not implement automatic keyword extraction with an extra LLM call.
- Do not add `stream`, `modelFunc`, or `includeReferences` in this phase.
- Do not change `NAIVE` retrieval semantics.
- Do not redesign `QueryEngine`, prompt rendering, or rerank behavior.

## Upstream Behavior To Align

Pinned reference: `/tmp/LightRAG-upstream/lightrag/base.py` and `/tmp/LightRAG-upstream/lightrag/operate.py` from upstream commit `e88d18949031f73c8eb7560fe28a3a859b3158e6`.

Relevant upstream behavior:

- `QueryParam` accepts `hl_keywords` and `ll_keywords`.
- If the caller provides these values, upstream uses them directly and skips keyword extraction.
- `ll_keywords` steer local/entity-oriented retrieval.
- `hl_keywords` steer global/relation-oriented retrieval.
- `hybrid` and `mix` use both when present.

The Java SDK can align with the explicit-override portion without reproducing upstream's extraction path.

## Architectural Options Considered

### Option 1: Add only manual keyword override fields

Pros:

- smallest API and implementation change
- matches the explicit upstream override path
- avoids introducing a second LLM dependency into retrieval

Cons:

- still lacks upstream automatic keyword extraction

### Option 2: Add manual overrides plus automatic extraction fallback

Pros:

- broader upstream parity

Cons:

- much larger scope
- requires new model interfaces or reuse of chat model in retrieval
- adds more prompt, caching, and testing surface than needed for this step

### Option 3: Hide overrides inside strategy-specific config

Pros:

- no public API expansion

Cons:

- diverges from upstream's caller-visible query parameter surface
- harder for SDK users to discover and use

## Recommendation

Adopt Option 1.

Extend `QueryRequest` with:

- `List<String> hlKeywords`
- `List<String> llKeywords`

Strategy behavior:

- `LOCAL`: embed `llKeywords` when non-empty, else raw query
- `GLOBAL`: embed `hlKeywords` when non-empty, else raw query
- `HYBRID`: when manual keyword overrides are present, run only the graph branches whose keyword list is non-empty
- `MIX`: graph retrieval follows the same branch-suppression rule as `HYBRID`; direct chunk search still uses raw query
- `NAIVE`: unchanged, still uses raw query

Keyword lists should be normalized by trimming blanks and dropping empty strings while preserving order. For embedding/search, the normalized list should be rendered as a single comma-space joined string, matching upstream's `", ".join(...)` behavior.

## Compatibility

- Builder-based callers remain source-compatible.
- The public `QueryRequest` record shape changes because new components are added to the canonical record. Existing code that depends on the canonical constructor, record pattern shape, or exhaustive deconstruction may need updates.
- Existing callers that do not set keyword overrides see no behavior change.
- Existing rerank and prompt-shaping behavior remains unchanged.

## Testing Strategy

Required coverage:

- `LOCAL` uses `llKeywords` instead of raw query when provided
- `GLOBAL` uses `hlKeywords` instead of raw query when provided
- keyword lists are embedded as a comma-space joined string
- `HYBRID` respects both overrides through its child strategies
- `HYBRID` suppresses the opposite graph branch when only one keyword side is provided
- `MIX` uses keyword overrides for graph retrieval while keeping chunk-vector retrieval on raw query
- blank/whitespace keyword entries are ignored
- absent overrides preserve existing retrieval behavior
- `QueryRequest` builder preserves keyword overrides when rerank expands the internal request copy

Verification should include targeted query-strategy tests plus relevant `QueryEngine` tests.
