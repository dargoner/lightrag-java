---
name: boot
description: "Skill for the Boot area of lightrag-java. 225 symbols across 30 files."
---

# Boot

225 symbols | 30 files | Cohesion: 70%

## When to Use

- Working with code in `lightrag-spring-boot-starter/`
- Understanding how extractionModel, summaryModel, rerankModel work
- Modifying boot-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | getChat, getEmbedding, getIndexing, getQuery, getDemo (+102) |
| `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java` | bindsPipelineWorkspaceAndDemoDefaults, bindsLegacyIngestPropertiesForBackwardCompatibility, providesP0DefaultsWhenNotConfigured, bindsWorkspaceOverridesAndCachesWorkspaceProviders, autoConfiguresLightRagForInMemoryProfile (+30) |
| `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/SpringWorkspaceStorageProvider.java` | defaultWorkspaceId, maxActiveWorkspaces, postgresConfig, requireValue, resolvePostgresSchema (+16) |
| `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java` | chunker, lightRag, resolveDefaultChatModel, postgresConfig, resolvePostgresSchema (+11) |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | retryJob, listDocumentStatus, getDocumentStatus, deleteDocument |
| `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | extractionModel, summaryModel, rerankModel |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/GraphController.java` | deleteEntity, createRelation, updateRelation |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/UploadControllerTest.java` | allowsPresetOverridePerUploadRequest, usesConfiguredDefaultPresetWhenNoOverrideIsProvided, keepsLegacyIngestPropertyOverridesCompatible |
| `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/IngestPreset.java` | documentTypeHint, chunkGranularity, parentChildEnabled |
| `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaResolver.java` | alignWithDataSourceSchema, resolveCurrentSchema, normalize |

## Entry Points

Start here when exploring this area:

- **`extractionModel`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java:79`
- **`summaryModel`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java:84`
- **`rerankModel`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java:116`
- **`health`** (Method) — `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/LightRagHealthIndicator.java:18`
- **`contribute`** (Method) — `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/LightRagInfoContributor.java:17`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `extractionModel` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | 79 |
| `summaryModel` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | 84 |
| `rerankModel` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` | 116 |
| `health` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/LightRagHealthIndicator.java` | 18 |
| `contribute` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/LightRagInfoContributor.java` | 17 |
| `documentTypeHint` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/IngestPreset.java` | 43 |
| `chunkGranularity` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/IngestPreset.java` | 47 |
| `parentChildEnabled` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/IngestPreset.java` | 51 |
| `chunker` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java` | 98 |
| `lightRag` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java` | 239 |
| `resolveDefaultChatModel` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java` | 297 |
| `getChat` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 21 |
| `getEmbedding` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 25 |
| `getIndexing` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 37 |
| `getQuery` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 41 |
| `getDemo` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 45 |
| `getWorkspace` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 49 |
| `getChunking` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 143 |
| `getIngest` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 147 |
| `getEmbeddingBatchSize` | Method | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 155 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Ingest → ResolveScope` | cross_community | 7 |
| `Ingest → ResolveProvider` | cross_community | 7 |
| `CreateRelation → Disabled` | cross_community | 7 |
| `CreateRelation → EmbeddingBatcher` | cross_community | 7 |
| `CreateRelation → FixedWindowChunker` | cross_community | 7 |
| `DeleteDocument → Disabled` | cross_community | 7 |
| `DeleteDocument → EmbeddingBatcher` | cross_community | 7 |
| `DeleteDocument → FixedWindowChunker` | cross_community | 7 |
| `DeleteEntity → Disabled` | cross_community | 7 |
| `DeleteEntity → EmbeddingBatcher` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Api | 17 calls |
| Demo | 13 calls |
| Indexing | 7 calls |
| Postgres | 5 calls |
| Storage | 2 calls |
| Query | 1 calls |
| Openai | 1 calls |
| Evaluation | 1 calls |

## How to Explore

1. `gitnexus_context({name: "extractionModel"})` — see callers and callees
2. `gitnexus_query({query: "boot"})` — find related execution flows
3. Read key files listed above for implementation details
