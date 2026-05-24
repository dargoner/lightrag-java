---
name: query
description: "Skill for the Query area of lightrag-java. 583 symbols across 98 files."
---

# Query

583 symbols | 98 files | Cohesion: 72%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how Builder, ContextAssembler, DefaultPathScorer work
- Modifying query-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java` | rerankReordersFinalContextsAndPromptContext, logsRerankStageDurationWhenRerankIsActive, includeReferencesReturnsStructuredReferencesAndEnrichedContexts, includeReferencesAppliesMetadataFilteringBeforeBuildingContextsAndReferences, movesAdditionalInstructionsIntoSystemPromptAndKeepsRawQueryAsUserPrompt (+59) |
| `lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java` | localUsesEntitySimilarityAndOneHopNeighbors, localTrimsChunksToChunkTopK, localUsesLlKeywordsInsteadOfRawQueryWhenProvided, localTrimsEntitiesToMaxEntityTokens, localAppliesMetadataConditionsToCollectedChunks (+37) |
| `lightrag-core/src/test/java/io/github/lightrag/query/GlobalQueryStrategyTest.java` | globalUsesRelationSimilarityAndEndpointEntities, globalTrimsChunksToChunkTopK, globalUsesHlKeywordsInsteadOfRawQueryWhenProvided, globalTrimsRelationsToMaxRelationTokens, globalAppliesMetadataFiltersWithoutChangingRelationRetrieval (+36) |
| `lightrag-core/src/test/java/io/github/lightrag/query/MixQueryStrategyTest.java` | mixMergesHybridChunksWithDirectChunkRetrieval, mixUsesKeywordOverridesForGraphRetrievalButRawQueryForDirectChunks, mixUsesHybridVectorSearchRequestForDirectChunkRetrievalWhenSupported, logsDetailedStageDurationsForMixRetrieval, mixPropagatesDirectChunkBranchFailure (+24) |
| `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` | builder, Builder, query, mode, topK (+21) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | queryRequestDefaultsToMixMode, queryRequestAcceptsMultiHopOverrides, queryRequestRejectsNonPositiveMultiHopSettings, queryRequestAcceptsTokenBudgetOverrides, queryRequestRejectsNonPositiveTokenBudgets (+14) |
| `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java` | query, queryStructured, resolveStructuredAnswer, renderStandardPrompt, toStructuredEntity (+13) |
| `lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java` | resolveDeterministicKeywords, normalizeQuery, isShortLiteralQuery, isSentenceDelimiter, containsQuestionCue (+12) |
| `lightrag-core/src/test/java/io/github/lightrag/query/QueryKeywordExtractorTest.java` | returnsManualKeywordOverridesWithoutCallingModel, extractsKeywordsForGraphAwareModesWhenOverridesAreMissing, keywordExtractionPromptRequiresSameLanguageKeywords, chineseQueriesDropTranslatedKeywordsAndBackfillChineseDualPathKeywords, usesDeterministicKeywordsForShortLiteralHybridQueriesWithoutCallingModel (+10) |
| `lightrag-core/src/main/java/io/github/lightrag/query/MetadataFilterNormalizer.java` | normalize, normalize, normalizeConditions, parseInstant, normalizeCondition (+9) |

## Entry Points

Start here when exploring this area:

- **`Builder`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java:214`
- **`ContextAssembler`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/query/ContextAssembler.java:8`
- **`DefaultPathScorer`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathScorer.java:14`
- **`RuleBasedQueryIntentClassifier`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java:7`
- **`PathAwareAnswerSynthesizer`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java:6`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` | 214 |
| `ContextAssembler` | Class | `lightrag-core/src/main/java/io/github/lightrag/query/ContextAssembler.java` | 8 |
| `DefaultPathScorer` | Class | `lightrag-core/src/main/java/io/github/lightrag/query/DefaultPathScorer.java` | 14 |
| `RuleBasedQueryIntentClassifier` | Class | `lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java` | 7 |
| `PathAwareAnswerSynthesizer` | Class | `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java` | 6 |
| `StagedGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/StorageCoordinator.java` | 228 |
| `InMemoryGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryGraphStore.java` | 18 |
| `LockedGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 611 |
| `Neo4jGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/Neo4jGraphStore.java` | 9 |
| `MirroringGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 258 |
| `WorkspaceScopedNeo4jGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/WorkspaceScopedNeo4jGraphStore.java` | 25 |
| `PostgresGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphStore.java` | 20 |
| `MirroringGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 416 |
| `LockedGraphStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java` | 525 |
| `InMemoryChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/memory/InMemoryChunkStore.java` | 14 |
| `MySqlChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlChunkStore.java` | 15 |
| `LockedChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProvider.java` | 471 |
| `LockedChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/neo4j/PostgresNeo4jStorageProvider.java` | 368 |
| `PostgresChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresChunkStore.java` | 15 |
| `LockedChunkStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresMilvusNeo4jStorageProvider.java` | 502 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 9 |
| `SearchAdditionalDirectChunkMatches → EscapeFilterLiteral` | cross_community | 9 |
| `Retrieve → RequireNonBlankString` | cross_community | 9 |
| `SearchAdditionalDirectChunkMatches → Get` | cross_community | 8 |
| `SearchAdditionalDirectChunkMatches → Split` | cross_community | 8 |
| `Retrieve → CollectStringValues` | cross_community | 8 |
| `Assemble → StorageException` | cross_community | 7 |
| `Assemble → StorageException` | cross_community | 7 |
| `SearchAdditionalDirectChunkMatches → Search` | cross_community | 7 |
| `SearchAdditionalDirectChunkMatches → DotProduct` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Api | 137 calls |
| Indexing | 98 calls |
| Postgres | 56 calls |
| Neo4j | 12 calls |
| Storage | 8 calls |
| Boot | 8 calls |
| Evaluation | 5 calls |
| Mysql | 3 calls |

## How to Explore

1. `gitnexus_context({name: "Builder"})` — see callers and callees
2. `gitnexus_query({query: "query"})` — find related execution flows
3. Read key files listed above for implementation details
