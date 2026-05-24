---
name: api
description: "Skill for the Api area of lightrag-java. 548 symbols across 86 files."
---

# Api

548 symbols | 86 files | Cohesion: 64%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how LightRagBuilder, FileSnapshotStore, OpenAiCompatibleEmbeddingModel work
- Modifying api-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` | buildsWithRequiredDependencies, registersTaskEventListeners, keepsContextualExtractionRefinementDisabledByDefault, enablesContextualExtractionRefinementWhenConfigured, usesDedicatedCapabilityModelsWhenProvided (+102) |
| `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java` | ingestBuildsChunkEntityRelationAndVectorIndexes, usesDedicatedQueryAndExtractionModels, ingestPersistsSnapshotWhenConfigured, builderLoadFromSnapshotRestoresStorageBeforeBuild, builderLoadFromSnapshotRestoresLegacyPayloadWithoutDocumentStatuses (+81) |
| `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | builder, ingest, createEntity, createRelation, editEntity (+58) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagTaskApiTest.java` | getTaskRetriesInterruptedRecoveryAfterInitialStorageFailure, getTaskMarksAllInterruptedRunningStagesAsFailed, chunkStore, graphStore, documentStatusStore (+21) |
| `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | LightRagBuilder, chatModel, embeddingModel, queryModel, storage (+18) |
| `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` | stream, normalizeMetadataConditions, normalizeMetadataCondition, normalizeScalarConditionPayload, normalizeNumericCondition (+8) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | rebuildSnapshot, loadState, intersects, latestByKey, latest (+5) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagWorkspaceTest.java` | oneLightRagInstanceRoutesDifferentCallsToDifferentWorkspaceProviders, structuredQueryRoutesDifferentCallsToDifferentWorkspaceProviders, saveSnapshotAndRestoreSnapshotOperateOnTheTargetWorkspaceOnly, repeatedWorkspaceResolutionReusesTheSameLogicalProviderInstance, TestWorkspaceStorageProvider (+5) |
| `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java` | customStorageProvider, snapshotStore, autoConfiguresFixedWindowChunkerFromProperties, backsOffWhenApplicationProvidesCustomChunker, chunk (+4) |
| `lightrag-core/src/test/java/io/github/lightrag/api/LightRagRelationalDocumentGraphPersistenceTest.java` | postgresInspectAndChunkStatusQueriesSurviveProviderRestart, postgresTaskDocumentQueriesSurviveTaskCompletion, mySqlInspectAndChunkStatusQueriesSurviveProviderRestart, newPostgresConfig, newMySqlConfig (+4) |

## Entry Points

Start here when exploring this area:

- **`LightRagBuilder`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java:32`
- **`FileSnapshotStore`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java:19`
- **`OpenAiCompatibleEmbeddingModel`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java:21`
- **`Builder`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/api/CreateEntityRequest.java:27`
- **`Builder`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/api/CreateRelationRequest.java:36`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `LightRagBuilder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | 32 |
| `FileSnapshotStore` | Class | `lightrag-core/src/main/java/io/github/lightrag/persistence/FileSnapshotStore.java` | 19 |
| `OpenAiCompatibleEmbeddingModel` | Class | `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java` | 21 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/CreateEntityRequest.java` | 27 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/CreateRelationRequest.java` | 36 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/EditEntityRequest.java` | 26 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/MergeEntitiesRequest.java` | 28 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/TaskSubmitOptions.java` | 19 |
| `Builder` | Class | `lightrag-core/src/main/java/io/github/lightrag/api/UpdateRelationRequest.java` | 34 |
| `RerankModel` | Interface | `lightrag-core/src/main/java/io/github/lightrag/model/RerankModel.java` | 5 |
| `EmbeddingModel` | Interface | `lightrag-core/src/main/java/io/github/lightrag/model/EmbeddingModel.java` | 4 |
| `name` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/CreateEntityRequest.java` | 33 |
| `entityName` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/EditEntityRequest.java` | 33 |
| `newName` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/EditEntityRequest.java` | 38 |
| `builder` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 127 |
| `ingest` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 140 |
| `createEntity` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 254 |
| `createRelation` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 259 |
| `editEntity` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 264 |
| `updateRelation` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` | 269 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Ingest → ResolveScope` | cross_community | 7 |
| `Ingest → ResolveProvider` | cross_community | 7 |
| `CreateEntity → Disabled` | cross_community | 7 |
| `CreateEntity → EmbeddingBatcher` | cross_community | 7 |
| `CreateEntity → FixedWindowChunker` | cross_community | 7 |
| `EditEntity → Disabled` | cross_community | 7 |
| `EditEntity → EmbeddingBatcher` | cross_community | 7 |
| `EditEntity → FixedWindowChunker` | cross_community | 7 |
| `CreateRelation → Disabled` | cross_community | 7 |
| `CreateRelation → EmbeddingBatcher` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Postgres | 191 calls |
| Query | 125 calls |
| Indexing | 109 calls |
| Storage | 41 calls |
| Neo4j | 33 calls |
| Task | 14 calls |
| Mysql | 11 calls |
| Boot | 9 calls |

## How to Explore

1. `gitnexus_context({name: "LightRagBuilder"})` — see callers and callees
2. `gitnexus_query({query: "api"})` — find related execution flows
3. Read key files listed above for implementation details
