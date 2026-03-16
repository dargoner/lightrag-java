# Java LightRAG Query Shortcut Controls Design

## Overview

This document defines the next upstream-capability alignment step for the Java LightRAG SDK: `bypass`, `only_need_context`, and `only_need_prompt`.

Upstream LightRAG exposes query-time shortcuts that let callers skip LLM generation after retrieval, inspect the final prompt before generation, or bypass retrieval entirely and talk directly to the underlying LLM. The current Java SDK does not expose these controls.

This phase adds those shortcuts while preserving the current Java `QueryResult(answer, contexts)` shape.

## Goals

- Add `BYPASS` to `QueryMode`.
- Add optional `onlyNeedContext` and `onlyNeedPrompt` to `QueryRequest`.
- Return assembled context text without LLM generation when `onlyNeedContext` is enabled.
- Return the complete prompt text without LLM generation when `onlyNeedPrompt` is enabled.
- Skip retrieval and query the chat model directly when `mode == BYPASS`.

## Non-Goals

- Do not redesign `QueryResult` into a richer upstream-style raw-data envelope in this phase.
- Do not change retrieval strategies beyond adding the bypass shortcut.
- Do not rework Java prompt ownership to exactly match upstream system/user prompt boundaries.
- Do not couple this phase to broader API compatibility refactors for public records.

## Upstream Behavior To Align

Relevant upstream query controls:

- `mode = "bypass"`
- `only_need_context`
- `only_need_prompt`

Observed upstream semantics:

- `only_need_context` returns the constructed retrieval context without calling the LLM.
- `only_need_prompt` returns the final prompt content without calling the LLM.
- `bypass` skips retrieval and sends the raw query plus conversation history directly to the LLM.

This phase aligns those visible behaviors while keeping Java's existing result envelope and prompt assembly boundary.

## Architectural Options Considered

### Option 1: Add new result types for context-only and prompt-only responses

Pros:

- closer to upstream's richer response model

Cons:

- expands public Java API surface immediately
- forces unrelated call-site churn
- makes this feature group larger than necessary

### Option 2: Reuse `QueryResult.answer` for shortcut payloads

Pros:

- smallest compatible API change
- keeps existing `LightRag.query(...)` return shape intact
- easy to document and test

Cons:

- `answer` becomes overloaded across normal, context-only, prompt-only, and bypass flows

## Recommendation

Adopt Option 2 for this phase.

Java will keep returning `QueryResult`. The shortcut payload is carried in `answer`, while `contexts` continues to hold the resolved retrieval contexts when retrieval happens.

## API Shape

### QueryMode

Add:

- `BYPASS`

### QueryRequest

Add:

- `boolean onlyNeedContext`
- `boolean onlyNeedPrompt`

Recommended defaults:

- `onlyNeedContext = false`
- `onlyNeedPrompt = false`

Compatibility requirements:

- preserve the existing 6-argument constructor by defaulting the new fields to `false`
- preserve the existing 8-argument constructor from the prior query-prompt phase by defaulting the new flags to `false`

Compatibility risk:

- `QueryRequest` is a public `record`, so adding new components still changes the canonical record shape
- even with delegating overloads, record-aware reflection, serialization, and equality/hash semantics are not fully source/binary/schema compatible in this phase

## Execution Semantics

### Standard retrieval modes

For `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`:

- retrieval and optional rerank run as they do today
- `contexts` still reflect the resolved final chunk contexts

Then:

- if `onlyNeedContext == true`, return assembled context text in `answer` and do not call the chat model
- otherwise build the final prompt
- if `onlyNeedPrompt == true`, return the complete prompt text in `answer` and do not call the chat model
- otherwise call the chat model and return the generated answer

`onlyNeedContext` takes precedence over `onlyNeedPrompt`, matching current upstream behavior.

### Bypass mode

For `BYPASS`:

- do not invoke any retrieval strategy
- do not assemble retrieval context
- build the current user message from the raw query plus optional `userPrompt`
- call the chat model with that bypass user message
- pass `conversationHistory` through unchanged
- return the chat model response in `answer`
- return an empty `contexts` list

Combination rules:

- if `mode == BYPASS` and `onlyNeedContext == true`, return an empty `answer`, skip the chat model, and return empty `contexts`
- if `mode == BYPASS` and `onlyNeedContext == false` and `onlyNeedPrompt == true`, return the complete bypass prompt payload without calling the chat model
- if `mode == BYPASS` and both flags are `true`, `onlyNeedContext` still wins

## Prompt Semantics

Java currently owns final prompt assembly inside `QueryEngine`. This phase keeps that boundary.

For prompt-only responses, Java should return a stable text form containing:

1. the final system prompt actually passed to the model
2. the final history messages actually passed to the model
2. the final current-turn user prompt actually passed to the model

This makes `onlyNeedPrompt` useful for inspection without requiring a new result object.

For bypass mode:

- no retrieval system instruction should be injected
- the OpenAI-compatible adapter should omit the system message entirely when `systemPrompt` is blank

## Testing Strategy

Required coverage:

- `QueryRequest` defaults the new flags to `false`
- the 6-argument `QueryRequest` constructor defaults the new flags to `false`
- the 8-argument `QueryRequest` constructor defaults the new flags to `false`
- `QueryMode` exposes `BYPASS`
- `QueryEngine` returns assembled context and skips the chat model for `onlyNeedContext`
- `QueryEngine` preserves resolved final `contexts` for `onlyNeedContext` and `onlyNeedPrompt`
- `QueryEngine` returns the complete prompt payload, including history, and skips the chat model for `onlyNeedPrompt`
- `onlyNeedContext` wins when both flags are `true`
- `QueryEngine` bypass mode skips retrieval, uses a blank system prompt, and returns direct chat-model output
- bypass combinations with `userPrompt`, `onlyNeedContext`, and `onlyNeedPrompt` follow the defined precedence rules
- OpenAI-compatible adapter omits the system message when `systemPrompt` is blank
- end-to-end `LightRag.query(...)` supports all three behaviors

Verification should include targeted tests plus full `./gradlew test`.
