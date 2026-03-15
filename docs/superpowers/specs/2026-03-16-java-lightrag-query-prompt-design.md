# Java LightRAG Query Prompt Alignment Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: query-time prompt customization through `user_prompt` and `conversation_history`.

The upstream LightRAG query parameter surface includes extra prompt-shaping inputs that affect answer generation without changing retrieval itself. The current Java SDK only accepts the query text plus retrieval settings, so downstream callers cannot inject an additional user instruction or structured conversation history.

This phase aligns the Java SDK with the upstream query parameter surface while keeping the existing retrieval pipeline and Java prompt-assembly boundary intact.

## Goals

- Add optional `userPrompt` to `QueryRequest`.
- Add optional structured `conversationHistory` to `QueryRequest`.
- Pass conversation history through the chat-model abstraction instead of flattening it into one large string.
- Keep retrieval, rerank, and context assembly behavior unchanged.

## Non-Goals

- Do not add `bypass`, `only_need_context`, or `only_need_prompt` in this phase.
- Do not redesign storage or retrieval strategies.
- Do not add provider-specific chat adapters beyond the existing OpenAI-compatible adapter.

## Upstream Behavior To Align

Upstream LightRAG exposes query-time prompt modifiers including:

- `user_prompt`
- `conversation_history`

The Java SDK can align with the visible behavior by:

- allowing a caller-supplied extra user instruction
- allowing a caller-supplied list of prior conversation messages
- preserving the existing retrieved context as the basis for the final answer prompt

This phase aligns the exposed capability, not a byte-for-byte reproduction of upstream prompt assembly. Upstream currently places more answer-shaping material into the system prompt; Java keeps its existing `QueryEngine` ownership of the current-turn prompt and adds the new caller controls within that boundary.

## API Shape

### QueryRequest

Add:

- `String userPrompt`
- `List<ChatModel.ChatRequest.ConversationMessage> conversationHistory`

Recommended defaults:

- `userPrompt = ""`
- `conversationHistory = []`

Compatibility note:

- this phase reuses `ChatModel.ChatRequest.ConversationMessage` as the public history entry type to minimize API surface churn while the Java SDK is still converging on upstream query features

### ChatModel

Extend `ChatModel.ChatRequest` to carry structured history:

- `String systemPrompt`
- `String userPrompt`
- `List<ConversationMessage> conversationHistory`

Add a nested record:

- `ConversationMessage(String role, String content)`

The Java phase should accept string roles directly so the abstraction stays close to OpenAI-compatible payloads and remains simple for custom adapters.

Validation and compatibility requirements:

- `conversationHistory` must be defensively copied in both `QueryRequest` and `ChatModel.ChatRequest`
- `ConversationMessage.role` must be non-null and non-blank
- `ConversationMessage.content` must be non-null
- keep a two-argument `ChatModel.ChatRequest(String systemPrompt, String userPrompt)` constructor so existing non-query call sites continue to compile unchanged in this phase

## Architectural Options Considered

### Option 1: Flatten everything into one user prompt string

Pros:

- smallest code diff

Cons:

- loses message structure
- weak match for OpenAI-compatible adapters
- makes future multi-message adapters harder

### Option 2: Carry structured history through ChatModel

Pros:

- closest match to upstream multi-message behavior without rewriting the Java prompt boundary
- clean boundary between retrieval prompt assembly and model transport
- allows OpenAI-compatible adapter to emit multiple messages naturally

Cons:

- requires widening the `ChatModel.ChatRequest` contract

## Recommendation

Adopt Option 2.

`QueryEngine` should continue to own final answer prompt assembly. It will:

- retrieve and optionally rerank context as it does today
- build the final current-turn user prompt from context, question, and optional `userPrompt`
- pass optional structured `conversationHistory` through `ChatModel.ChatRequest`

Retrieval strategies remain unaffected.

## Prompt Composition Semantics

The final current-turn prompt should keep the existing structure:

- `Context: ...`
- `Question: ...`

If `userPrompt` is provided and non-blank, append it as an additional instruction block after the question, for example:

- `Additional Instructions: ...`

Conversation history is not duplicated inside that string. It is carried separately in `ChatModel.ChatRequest`.

When `userPrompt` is blank, Java must not append an `Additional Instructions` block.

## OpenAI-Compatible Adapter Semantics

`OpenAiCompatibleChatModel` should send messages in this order:

1. system message from `systemPrompt`
2. zero or more caller-supplied history messages in original order
3. current-turn user message built by `QueryEngine`

This keeps the existing adapter behavior for callers that do not provide history while enabling a clean upgrade path for multi-turn requests.

## Testing Strategy

Required coverage:

- `QueryRequest` defaults `userPrompt` to empty and `conversationHistory` to empty
- `QueryRequest` copies conversation history defensively
- `ChatModel.ChatRequest` copies conversation history defensively and preserves the two-argument constructor path
- `QueryEngine` appends `userPrompt` into the final current-turn prompt
- `QueryEngine` forwards `conversationHistory` unchanged into `ChatModel.ChatRequest`
- `QueryEngine` preserves `userPrompt` and `conversationHistory` when rerank expands the retrieval request
- `QueryEngine` does not append `Additional Instructions` when `userPrompt` is blank and does not flatten conversation history into the current-turn prompt
- OpenAI-compatible adapter emits system + history + current user messages in order
- end-to-end `LightRag.query(...)` supports both fields together

Verification should include targeted tests plus full `./gradlew test`.
