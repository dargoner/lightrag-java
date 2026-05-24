---
name: evaluation
description: "Skill for the Evaluation area of lightrag-java. 69 symbols across 19 files."
---

# Evaluation

69 symbols | 19 files | Cohesion: 70%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how RagasBatchEvaluationService, RagasEvaluationService, hashCode work
- Modifying evaluation-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/RealConvertedMarkdownRetrievalEvaluationTest.java` | embed, tokens, appendHanTokens, loadDocumentSources, toSource (+8) |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/ParentChildOfflineRetrievalEvaluationTest.java` | embed, tokens, loadDocumentSources, toSource, parentChildProfileImprovesTop1HitRateOnSectionAwareDataset (+3) |
| `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | main, createChatModel, parseArgs, envOrDefault, envOrFallback (+2) |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/RagasBatchEvaluationServiceTest.java` | evaluatesDatasetInBatchUsingSingleInMemoryIngest, evaluatesDatasetUsingPostgresNeo4jTestcontainersProfile, retrievalOnlyBatchSkipsQueryStageChatCalls, FakeChatModel, FakeEmbeddingModel (+1) |
| `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationCli.java` | main, parseArgs, requireArg, envOrDefault, envOrFallback (+1) |
| `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationService.java` | RagasBatchEvaluationService, evaluateBatch, loadTestCases, close, close |
| `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationService.java` | documentId, RagasEvaluationService, loadDocuments, toDocument, extractTitle |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/OfflineRetrievalAccuracyTest.java` | embed, tokens, loadEvaluationDocuments |
| `lightrag-core/src/test/java/io/github/lightrag/evaluation/RagasEvaluationServiceTest.java` | evaluatesMarkdownDirectoryIntoAnswerAndContexts, FakeChatModel, FakeEmbeddingModel |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | toChunkSnapshots, normalizedEntityType |

## Entry Points

Start here when exploring this area:

- **`RagasBatchEvaluationService`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationService.java:29`
- **`RagasEvaluationService`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationService.java:19`
- **`hashCode`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java:56`
- **`toChunkSnapshots`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java:549`
- **`normalizedEntityType`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java:917`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `RagasBatchEvaluationService` | Class | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationService.java` | 29 |
| `RagasEvaluationService` | Class | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationService.java` | 19 |
| `hashCode` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java` | 56 |
| `toChunkSnapshots` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 549 |
| `normalizedEntityType` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 917 |
| `normalizedEntityType` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 1320 |
| `toChunkGraphSnapshots` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | 1414 |
| `shortHash` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/SpringWorkspaceStorageProvider.java` | 324 |
| `evaluateBatch` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationService.java` | 33 |
| `loadTestCases` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationService.java` | 146 |
| `main` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 25 |
| `createChatModel` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 99 |
| `parseArgs` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 111 |
| `envOrDefault` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 134 |
| `envOrFallback` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 139 |
| `requiredEnv` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasBatchEvaluationCli.java` | 147 |
| `main` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationCli.java` | 15 |
| `parseArgs` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationCli.java` | 51 |
| `requireArg` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationCli.java` | 66 |
| `envOrDefault` | Method | `lightrag-core/src/main/java/io/github/lightrag/evaluation/RagasEvaluationCli.java` | 74 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Main → InMemorySnapshotStore` | cross_community | 6 |
| `Main → ExtractionModel` | cross_community | 6 |
| `Main → EscapeFilterLiteral` | cross_community | 5 |
| `Main → DocumentId` | cross_community | 5 |
| `Main → LightRagBuilder` | cross_community | 5 |
| `Main → Noop` | cross_community | 5 |
| `Main → InMemorySnapshotStore` | cross_community | 5 |
| `Main → Equals` | cross_community | 4 |
| `Main → ChatModel` | cross_community | 4 |
| `Main → Build` | cross_community | 4 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Query | 17 calls |
| Indexing | 12 calls |
| Api | 3 calls |

## How to Explore

1. `gitnexus_context({name: "RagasBatchEvaluationService"})` — see callers and callees
2. `gitnexus_query({query: "evaluation"})` — find related execution flows
3. Read key files listed above for implementation details
