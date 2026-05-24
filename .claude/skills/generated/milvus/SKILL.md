---
name: milvus
description: "Skill for the Milvus area of lightrag-java. 114 symbols across 8 files."
---

# Milvus

114 symbols | 8 files | Cohesion: 71%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how MilvusSdkClientAdapter, MilvusStoreProjection, createOwnedClient work
- Modifying milvus-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | createOwnedClient, dropCollection, createCollection, handleSchemaDrift, collectionSchema (+26) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusVectorStoreTest.java` | ensureCollection, saveAllEnrichedCreatesCollectionAndPersistsFullTextPayload, semanticSearchDelegatesToDenseChannel, keywordSearchDelegatesToBm25ChannelWithMergedQueryText, hybridSearchDelegatesToDenseAndBm25ChannelsWithRrfRerankByDefault (+18) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStore.java` | ensureCollection, collectionName, saveAll, search, search (+14) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorConfig.java` | sharedCollectionName, collectionName, normalizeSegment, shortDigest, requireNonBlank (+4) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java` | ensureCollection, MilvusClientAdapter, semanticSearch, keywordSearch, hybridSearch (+4) |
| `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java` | saveAll, search, search, captureSnapshot, list (+4) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterIntegrationTest.java` | ensureCollectionRebuildsAnalyzerSchemaWhenDefinitionDrifts, ensureCollectionFailsWhenSchemaDefinitionDriftsByDefault, ensureCollectionIgnoresSchemaDefinitionDriftWhenConfigured, listLegacyIds, createHybridCollection (+3) |
| `lightrag-core/src/test/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapterTest.java` | sharedCollectionSchemaContainsWorkspaceTypeAndRelationMetadataFields, resolvesSharedCollectionNameFromPrefix, appliesFiniteConnectionAndRpcTimeouts, testConfig, resolvesBoundedConsistencyByDefault (+1) |

## Entry Points

Start here when exploring this area:

- **`MilvusSdkClientAdapter`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java:37`
- **`MilvusStoreProjection`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java:124`
- **`createOwnedClient`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java:79`
- **`dropCollection`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java:307`
- **`createCollection`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java:367`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `MilvusSdkClientAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 37 |
| `MilvusStoreProjection` | Class | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java` | 124 |
| `MilvusClientAdapter` | Interface | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java` | 6 |
| `Projection` | Interface | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorStorageAdapter.java` | 97 |
| `createOwnedClient` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 79 |
| `dropCollection` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 307 |
| `createCollection` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 367 |
| `handleSchemaDrift` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 378 |
| `collectionSchema` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 501 |
| `fieldSchemas` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 519 |
| `indexParams` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 586 |
| `connectConfig` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 619 |
| `sharedCollectionName` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusVectorConfig.java` | 157 |
| `ensureCollection` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusClientAdapter.java` | 7 |
| `ensureCollection` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 83 |
| `list` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 128 |
| `semanticSearch` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 172 |
| `keywordSearch` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 195 |
| `hybridSearch` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 218 |
| `deleteAll` | Method | `lightrag-core/src/main/java/io/github/lightrag/storage/milvus/MilvusSdkClientAdapter.java` | 258 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Query | 16 calls |
| Indexing | 7 calls |
| Api | 3 calls |

## How to Explore

1. `gitnexus_context({name: "MilvusSdkClientAdapter"})` — see callers and callees
2. `gitnexus_query({query: "milvus"})` — find related execution flows
3. Read key files listed above for implementation details
