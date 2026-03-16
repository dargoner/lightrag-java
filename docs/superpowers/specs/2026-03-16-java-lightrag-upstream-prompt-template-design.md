# Java LightRAG Upstream Prompt Template Alignment Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: move the standard retrieval prompt template content closer to upstream LightRAG's `rag_response` and `naive_rag_response` templates while preserving the Java SDK's current public API and retrieval pipeline.

The previous phase already aligned the prompt boundary: standard retrieval now sends retrieval instructions, `responseType`, `userPrompt`, and assembled context through `systemPrompt`, while the current-turn user message remains the raw query text. The remaining gap is that Java still uses a very small custom template instead of upstream's richer `Role / Goal / Instructions / Context` scaffold.

This phase is pinned against the upstream prompt content in the local upstream checkout at `/tmp/LightRAG-upstream/lightrag/prompt.py` from commit `e88d18949031f73c8eb7560fe28a3a859b3158e6`, specifically `PROMPTS["rag_response"]` and `PROMPTS["naive_rag_response"]`.

## Goals

- Make standard retrieval prompt text materially closer to upstream LightRAG.
- Distinguish graph-aware retrieval prompt wording from `NAIVE` retrieval prompt wording.
- Preserve current prompt-boundary semantics:
  - `systemPrompt` contains retrieval instructions and context
  - `ChatModel.ChatRequest.userPrompt` contains only the raw query text
  - `conversationHistory` remains structured and separate
- Keep `onlyNeedPrompt` output aligned with the actual model-facing prompt.

## Non-Goals

- Do not change the public API surface of `LightRag`, `QueryRequest`, `QueryResult`, or `ChatModel`.
- Do not implement upstream's full reference-generation pipeline in this phase.
- Do not redesign the retrieval context payload structure or add citation metadata to `QueryResult`.
- Do not change `BYPASS` behavior.

## Current Gap

Upstream `prompt.py` currently provides richer retrieval prompts with:

- `---Role---`
- `---Goal---`
- `---Instructions---`
- explicit grounding rules
- explicit formatting and language rules
- explicit references-section instructions
- `Additional Instructions: {user_prompt}`
- `---Context---`

Java currently uses a short template that only states:

- answer using provided context
- response type
- additional instructions
- context

This means the prompt boundary is aligned, but the actual content still diverges noticeably from upstream.

## Architectural Options Considered

### Option 1: Expand the existing single template

Pros:

- smallest implementation diff
- minimal risk of regressions

Cons:

- still treats `NAIVE` and graph-aware retrieval as the same prompt family
- remains farther from upstream than necessary

### Option 2: Add separate upstream-style templates for graph-aware and naive retrieval

Pros:

- closest match to upstream's current prompt structure
- keeps the Java implementation simple and explicit
- lets Java preserve one prompt boundary while differentiating prompt wording by mode

Cons:

- requires more test updates because prompt assertions become more specific

### Option 3: Introduce a configurable prompt-template subsystem

Pros:

- more extensible for future customization

Cons:

- infrastructure-heavy for a small alignment step
- expands scope beyond this upstream-parity task

## Recommendation

Adopt Option 2.

Add two explicit template constants inside `QueryEngine`:

- one for graph-aware retrieval modes (`LOCAL`, `GLOBAL`, `HYBRID`, `MIX`)
- one for `NAIVE`

Both templates should preserve the already-aligned Java prompt boundary while making the content substantially closer to upstream's `rag_response` and `naive_rag_response`.

## Prompt Semantics

### Graph-aware retrieval modes

For `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`, the system prompt should include upstream-like sections describing:

- the assistant role
- the goal of generating a grounded answer from context
- instructions to use the knowledge graph and document chunks found in context
- grounding constraints
- formatting and language constraints
- references guidance, stated as instructions only
- additional instructions from `userPrompt`
- the assembled retrieval context

### Naive retrieval mode

For `NAIVE`, the system prompt should remain structurally similar but change the instructions so they refer only to document chunks rather than graph data.

### Shared semantics to preserve

- blank `responseType` still falls back to `Multiple Paragraphs`
- blank `userPrompt` still becomes `n/a`
- `onlyNeedPrompt` still wins over `onlyNeedContext` for standard retrieval modes
- prompt-only output still renders:
  - formatted `systemPrompt`
  - `---User Query---`
  - raw query text
- `conversationHistory` remains separate in the real model request and is not flattened into prompt-only output

### References guidance boundary

Java does not yet implement upstream's full reference-generation pipeline or reference metadata surface. To avoid encouraging fabricated citations, the aligned templates should keep the upstream-style references guidance in a softened form:

- instruct the model to emit a references section only when the context provides usable source metadata
- explicitly forbid inventing citations or references that are not grounded in the context
- avoid claiming that Java can currently correlate reference IDs to a reference document list

## Compatibility

- Public API shape stays unchanged.
- Retrieval, rerank, and context exposure stay unchanged.
- The intentional compatibility change is prompt text content only:
  - tests or integrations asserting the old short template must be updated
  - prompt-only inspection output will now include richer upstream-style sections

For prompt-only inspection, "aligned with the actual model-facing prompt" means:

- the rendered `systemPrompt` content is shown exactly
- the raw current-turn query is shown exactly
- structured `conversationHistory` is intentionally omitted because Java continues to pass it separately as structured history rather than flattening it into prompt text

## Testing Strategy

Required coverage:

- graph-aware standard retrieval renders `---Role---`, `---Goal---`, `---Instructions---`, and `---Context---`
- graph-aware standard retrieval mentions knowledge graph plus document chunks
- graph-aware standard retrieval includes grounding, language, and references guidance derived from upstream
- `NAIVE` standard retrieval renders the same section scaffold but refers only to document chunks
- `responseType` and `userPrompt` still flow through the richer template
- blank `userPrompt` still becomes `n/a`
- blank `responseType` still becomes `Multiple Paragraphs`
- `onlyNeedPrompt` still wins over `onlyNeedContext`
- prompt-only output mirrors the richer system prompt and still excludes conversation history
- the real chat-model request still receives `conversationHistory` as structured history
- end-to-end retrieval still works with the richer prompt content and existing fake chat model routing

Verification should include targeted tests plus full `./gradlew test`.
