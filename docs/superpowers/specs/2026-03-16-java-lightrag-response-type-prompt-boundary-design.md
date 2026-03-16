# Java LightRAG Response Type And Prompt Boundary Alignment Design

## Overview

This document defines the next upstream-capability alignment step for the Java LightRAG SDK: make `responseType` participate in final prompt construction and move standard retrieval prompt shaping closer to upstream LightRAG's system-prompt boundary.

The current Java SDK already supports `userPrompt`, `conversationHistory`, and the query shortcut controls, but it still assembles retrieval context and question into the current-turn user message. As a result, `responseType` is not used in the actual model request, and Java's prompt boundary still diverges from upstream.

This phase keeps the existing public Java API and moves standard retrieval prompt shaping so the system prompt carries retrieval instructions, `responseType`, `userPrompt`, and assembled context, while the user message becomes the raw query text. It also corrects the remaining shortcut and default-value semantics that still diverge from upstream.

## Goals

- Make `QueryRequest.responseType` effective in the final model request.
- Move standard retrieval prompt shaping into `ChatModel.ChatRequest.systemPrompt`.
- Keep the current-turn `ChatModel.ChatRequest.userPrompt` equal to the raw query text for standard retrieval modes.
- Preserve the existing `conversationHistory` flow.
- Correct the remaining query shortcut precedence and prompt-default semantics that still diverge from upstream.

## Non-Goals

- Do not redesign `QueryRequest`, `QueryResult`, or `ChatModel.ChatRequest`.
- Do not redesign bypass into a richer upstream API-server concept beyond the current direct-LLM shortcut.
- Do not add reference/citation sections or fully replicate upstream prompt templates word-for-word.
- Do not introduce a separate prompt-template subsystem in this phase.

## Upstream Behavior To Align

Upstream LightRAG currently:

- builds a retrieval-oriented system prompt from `response_type`, `user_prompt`, and assembled context data
- sends the raw query text as the current user message
- passes conversation history separately to the model
- lets `only_need_prompt` win when both shortcut flags are enabled
- uses `n/a` when `user_prompt` is absent in the formatted system prompt
- falls back to `Multiple Paragraphs` when `response_type` is absent

This phase aligns Java to that prompt boundary and those related prompt semantics for standard retrieval modes while preserving Java's existing result envelope.

## Architectural Options Considered

### Option 1: Keep Java's current user-prompt boundary and only inject `responseType`

Pros:

- smallest code diff

Cons:

- keeps the main upstream divergence in place
- leaves the model-facing message split inconsistent with upstream

### Option 2: Move retrieval instructions and context into the system prompt

Pros:

- closest match to upstream's prompt boundary
- makes `responseType` naturally effective
- keeps `conversationHistory` behavior unchanged

Cons:

- updates multiple existing tests because model-facing prompt fields shift

### Option 3: Add a new prompt-template layer

Pros:

- more extensible for future template customization

Cons:

- more infrastructure than needed for this alignment step
- larger diff and higher regression surface

## Recommendation

Adopt Option 2.

Standard retrieval modes will now construct a retrieval system prompt from:

- a retrieval-oriented instruction scaffold
- effective `responseType`
- effective `userPrompt`
- assembled retrieval context

The current-turn user message becomes the raw query text. `conversationHistory` remains unchanged and continues to be sent as structured history through `ChatModel.ChatRequest`.

## Prompt Semantics

### Standard retrieval modes

For `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX`:

- retrieval and rerank behavior remain unchanged
- `contexts` in `QueryResult` remain unchanged
- `ChatRequest.systemPrompt` contains:
  - the retrieval instruction scaffold
  - the effective `responseType`
  - the effective `userPrompt`
  - the assembled retrieval context
- `ChatRequest.userPrompt` contains only the raw query text

For upstream alignment:

- if `responseType` is blank, treat the effective response type as `Multiple Paragraphs`
- if `userPrompt` is blank, treat the effective user prompt as `n/a`
- the additional-instructions section remains present in the system prompt because the template slot is always populated

### Shortcut modes

Shortcut semantics are corrected to match upstream:

- `onlyNeedContext` returns assembled context text in `QueryResult.answer` only when `onlyNeedPrompt == false`
- `onlyNeedPrompt` returns the final prompt payload in `QueryResult.answer`
- when both flags are `true`, `onlyNeedPrompt` wins
- `BYPASS` behavior otherwise remains as implemented in the prior phase

For prompt-only responses in standard retrieval modes, the returned prompt payload must now reflect upstream more closely:

- return the formatted system prompt
- append the raw query text as the user-query portion
- do not inline conversation history into the returned inspection text

## Compatibility

- Public API shape remains unchanged in this phase.
- Existing callers using `QueryRequest`, `QueryResult`, and `ChatModel.ChatRequest` keep the same types and constructors.
- Behavioral compatibility changes at the model-request boundary are intentional:
  - code or tests that assumed retrieval context lived in `ChatRequest.userPrompt` must be updated
  - code or tests that ignored `responseType` in the final prompt should now expect it to be present in `ChatRequest.systemPrompt`
  - code or tests that relied on the prior Java-only shortcut precedence or prompt-only inspection format must be updated

## Testing Strategy

Required coverage:

- `QueryEngine` standard retrieval requests now place context in `systemPrompt`
- `QueryEngine` standard retrieval requests now place the raw query in `userPrompt`
- `QueryEngine` uses a non-default `responseType` in the generated system prompt
- blank `responseType` falls back to `Multiple Paragraphs`
- `QueryEngine` includes `userPrompt` through the system-prompt additional-instructions slot
- blank `userPrompt` falls back to `n/a`
- `conversationHistory` remains unchanged and is not flattened into system or user prompt text
- rerank still affects the final context ordering, now visible in `systemPrompt`
- `onlyNeedPrompt` wins when both shortcut flags are `true`
- prompt-only responses reflect the upstream-like system-prompt-plus-query output and do not inline history
- end-to-end query behavior remains correct with the new prompt split

Verification should include targeted tests plus full `./gradlew test`.
