---
name: lightrag
description: "Skill for the Lightrag area of lightrag-java. 19 symbols across 13 files."
---

# Lightrag

19 symbols | 13 files | Cohesion: 64%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how OpenAiCompatibleChatModel, ChatModel work
- Modifying lightrag-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | DefaultChatModel, QueryOnlyChatModel, CountingExtractionChatModel, OverrideChatModel, SelectiveFailingExtractionChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | SelectiveFailingIngestionChatModel, CoordinatedFailingIngestionChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java` | RecordingChatModel, SequencedChatModel |
| `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java` | ChatModel |
| `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | OpenAiCompatibleChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/OfflineRetrievalAccuracyTest.java` | NoOpChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/ParentChildOfflineRetrievalEvaluationTest.java` | NoOpChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java` | NoOpChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java` | FakeChatModel |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java` | FailingConcurrentChatModel |

## Entry Points

Start here when exploring this area:

- **`OpenAiCompatibleChatModel`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java:24`
- **`ChatModel`** (Interface) — `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java:5`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `OpenAiCompatibleChatModel` | Class | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` | 24 |
| `ChatModel` | Interface | `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java` | 5 |
| `DefaultChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | 3140 |
| `QueryOnlyChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | 3156 |
| `CountingExtractionChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | 3181 |
| `OverrideChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | 3198 |
| `SelectiveFailingExtractionChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | 3423 |
| `SelectiveFailingIngestionChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | 1246 |
| `CoordinatedFailingIngestionChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | 1267 |
| `NoOpChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/evaluation/OfflineRetrievalAccuracyTest.java` | 150 |
| `NoOpChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/evaluation/ParentChildOfflineRetrievalEvaluationTest.java` | 242 |
| `NoOpChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java` | 246 |
| `FakeChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineBatchGraphPersistenceTest.java` | 66 |
| `FailingConcurrentChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineChunkExtractionConcurrencyTest.java` | 180 |
| `SequentialChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/indexing/IndexingPipelineRefinementIntegrationTest.java` | 67 |
| `RecordingChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java` | 763 |
| `RecordingChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java` | 1378 |
| `SequencedChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java` | 1476 |
| `CountingKeywordChatModel` | Class | `lightrag-core/src/test/java/io/github/lightrag/query/QueryKeywordExtractorTest.java` | 286 |

## How to Explore

1. `gitnexus_context({name: "OpenAiCompatibleChatModel"})` — see callers and callees
2. `gitnexus_query({query: "lightrag"})` — find related execution flows
3. Read key files listed above for implementation details
