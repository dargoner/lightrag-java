---
name: indexing
description: "Skill for the Indexing area of lightrag-java. 941 symbols across 149 files."
---

# Indexing

941 symbols | 149 files | Cohesion: 72%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how MineruDocumentAdapter, MineruParsingProvider, PlainTextParsingProvider work
- Modifying indexing-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/indexing/SmartChunker.java` | chunk, chunkParsedDocument, chunkStructural, chunkStructural, config (+55) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` | extract, extractWindow, buildUserPrompt, buildContinuePrompt, buildSystemPrompt (+42) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` | chunkEmbeddingText, chunkPreparationStrategy, saveChunkVectors, saveEntityVectors, saveRelationVectors (+34) |
| `lightrag-core/src/test/java/dev/io/github/lightrag/indexing/PublicDocumentSmartChunkerRealApiManualTest.java` | smartChunkerUsesSemanticBoundariesForGenericDensePublicPdf, printsRepresentativeGenericSemanticChunksForInspection, printsFullGenericSemanticChunksForInspection, comparesGenericSemanticChunksBetweenMediumAndCoarse, printsFullChineseGenericSemanticChunksForInspection (+30) |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/SmartChunkerTest.java` | splitsOnSentenceBoundariesAndKeepsSentenceAwareOverlap, adaptiveChunkingMakesLongGenericParagraphsCoarser, adaptiveChunkingKeepsHeadingTransitionParagraphsFiner, preservesSourceMetadataAndAddsSmartChunkMetadata, consumesParsedBlocksWhenStructuredMineruBlocksAreAvailable (+25) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphManagementPipeline.java` | editEntity, updateRelation, deleteRelation, mergeEntities, validateRelationIdsUnique (+24) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | saveEntityVectors, saveRelationVectors, toVectorRecords, entitySummary, relationSummary (+23) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` | mergeEntity, ensureEntity, entityMergeKeys, normalizeKey, normalizeOptionalKey (+21) |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java` | generate, generate, gleansAdditionalEntitiesWhenConfigured, skipsGleaningWhenContextBudgetIsTooSmall, includesConfiguredLanguageAndEntityTypesInPrompt (+20) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruApiClient.java` | parseZip, parseStructuredBlocks, extractText, extractBbox, appendText (+19) |

## Entry Points

Start here when exploring this area:

- **`MineruDocumentAdapter`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruDocumentAdapter.java:10`
- **`MineruParsingProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruParsingProvider.java:8`
- **`PlainTextParsingProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/PlainTextParsingProvider.java:8`
- **`TikaFallbackParsingProvider`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/TikaFallbackParsingProvider.java:13`
- **`GraphAssembler`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java:18`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `MineruDocumentAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruDocumentAdapter.java` | 10 |
| `MineruParsingProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruParsingProvider.java` | 8 |
| `PlainTextParsingProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/PlainTextParsingProvider.java` | 8 |
| `TikaFallbackParsingProvider` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/TikaFallbackParsingProvider.java` | 13 |
| `GraphAssembler` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` | 18 |
| `FixedWindowChunker` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/FixedWindowChunker.java` | 9 |
| `SmartChunker` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/SmartChunker.java` | 14 |
| `DocumentTypeResolver` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/DocumentTypeResolver.java` | 4 |
| `MineruApiClient` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruApiClient.java` | 23 |
| `MineruSelfHostedClient` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruSelfHostedClient.java` | 6 |
| `LangChain4jChunkAdapter` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/LangChain4jChunkAdapter.java` | 10 |
| `HttpTransport` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruApiClient.java` | 47 |
| `DocumentParsingProvider` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/DocumentParsingProvider.java` | 4 |
| `Chunker` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/Chunker.java` | 7 |
| `MineruClient` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruClient.java` | 8 |
| `Transport` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/MineruApiClient.java` | 42 |
| `smartChunk` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/ChunkingOrchestrator.java` | 64 |
| `smartChunker` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/ChunkingOrchestrator.java` | 130 |
| `smartChunkerConfig` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/ChunkingProfile.java` | 23 |
| `similarityFor` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/EmbeddingChunkSimilarityScorer.java` | 15 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Retrieve → RequireNonBlankString` | cross_community | 9 |
| `Retrieve → CollectStringValues` | cross_community | 8 |
| `CreateEntity → Disabled` | cross_community | 7 |
| `EditEntity → Disabled` | cross_community | 7 |
| `CreateRelation → Disabled` | cross_community | 7 |
| `RestoreSnapshot → Normalize` | cross_community | 7 |
| `RestoreSnapshot → Open` | cross_community | 7 |
| `RestoreSnapshot → Apply` | cross_community | 7 |
| `RestoreSnapshot → StorageException` | cross_community | 7 |
| `Retrieve → NormalizeField` | cross_community | 7 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Query | 158 calls |
| Postgres | 92 calls |
| Storage | 68 calls |
| Api | 56 calls |
| Task | 21 calls |
| Mysql | 16 calls |
| Openai | 13 calls |
| Neo4j | 12 calls |

## How to Explore

1. `gitnexus_context({name: "MineruDocumentAdapter"})` — see callers and callees
2. `gitnexus_query({query: "indexing"})` — find related execution flows
3. Read key files listed above for implementation details
