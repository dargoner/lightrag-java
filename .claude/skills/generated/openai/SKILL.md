---
name: openai
description: "Skill for the Openai area of lightrag-java. 42 symbols across 4 files."
---

# Openai

42 symbols | 4 files | Cohesion: 75%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how embedAll, toModelException, isTimeout work
- Modifying openai-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | generate, extractContent, stream, buildHttpRequest, buildPayload (+12) |
| `lightrag-core/src/test/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModelTest.java` | chatAdapterSendsOpenAiCompatibleRequestPayload, chatAdapterSendsConversationHistoryBeforeCurrentUserMessage, chatAdapterOmitsBlankSystemMessage, chatAdapterParsesResponseContent, non2xxResponsesRaiseModelException (+7) |
| `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | embedAll, toModelException, isTimeout, compactResponseBody, extractEmbeddings (+2) |
| `lightrag-core/src/test/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModelTest.java` | embeddingAdapterSendsOpenAiCompatibleRequestPayload, embeddingAdapterParsesEmbeddingVectors, non2xxResponsesRaiseModelException, malformedJsonOrMissingRequiredFieldsRaiseModelException, embeddingAdapterSupportsCustomRequestTimeout (+1) |

## Entry Points

Start here when exploring this area:

- **`embedAll`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:47`
- **`toModelException`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:89`
- **`isTimeout`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:96`
- **`compactResponseBody`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:107`
- **`extractEmbeddings`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:118`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `embedAll` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 47 |
| `toModelException` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 89 |
| `isTimeout` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 96 |
| `compactResponseBody` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 107 |
| `extractEmbeddings` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 118 |
| `generate` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 50 |
| `extractContent` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 80 |
| `stream` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 64 |
| `buildHttpRequest` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 88 |
| `buildPayload` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 97 |
| `execute` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 115 |
| `compactResponseBody` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 151 |
| `toModelException` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 133 |
| `isTimeout` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 140 |
| `hasNext` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 190 |
| `next` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 202 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 212 |
| `loadNext` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 223 |
| `readNextEventData` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 247 |
| `extractDeltaContent` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 273 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `SaveEntityVectors → Execute` | cross_community | 5 |
| `SaveEntityVectors → ModelException` | cross_community | 5 |
| `SaveEntityVectors → CompactResponseBody` | cross_community | 5 |
| `SaveEntityVectors → ModelException` | cross_community | 5 |
| `SaveRelationVectors → Execute` | cross_community | 5 |
| `SaveRelationVectors → ModelException` | cross_community | 5 |
| `SaveRelationVectors → CompactResponseBody` | cross_community | 5 |
| `GenerateTwoStageAnswer → ModelException` | cross_community | 4 |
| `GenerateTwoStageAnswer → CompactResponseBody` | cross_community | 4 |
| `GenerateTwoStageAnswer → BuildPayload` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Indexing | 2 calls |

## How to Explore

1. `gitnexus_context({name: "embedAll"})` — see callers and callees
2. `gitnexus_query({query: "openai"})` — find related execution flows
3. Read key files listed above for implementation details
