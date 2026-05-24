---
name: refinement
description: "Skill for the Refinement area of lightrag-java. 58 symbols across 15 files."
---

# Refinement

58 symbols | 15 files | Cohesion: 74%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how DefaultExtractionGapDetector, DefaultRefinementWindowResolver, DefaultExtractionMergePolicy work
- Modifying refinement-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java` | distribute, hasContent, toPatch, distributeEntityPatch, distributeRelationPatch (+8) |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java` | mergeEntity, mergeRelation, preferredText, longerText, mergeKeywords (+7) |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolverTest.java` | distributesRelationPatchToAllSupportingChunks, keepsPatchedRelationOnEachTargetChunk, doesNotFallbackWhenSupportingChunkIdsDoNotMatchWindow, fallsBackToDeterministicChunkMatchWhenEnabled, window (+1) |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipelineTest.java` | returnsPrimaryExtractionsUnchangedWhenRefinementIsDisabled, augmentsPrimaryExtractionsWhenAdjacentWindowProducesAttributedRelation, skipsRefinementWhenWindowTokenBudgetWouldBeExceededForChineseText, primary |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java` | refine, meetsThresholds, exceedsWindowLimits |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicyTest.java` | injectsMinimalEndpointEntitiesWhenChunkReceivesRelationPatch, retainsPrimaryRelationsWhileAppendingPatchedOnes, primary |
| `lightrag-core/src/test/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetectorTest.java` | requestsAdjacentRefinementWhenPrescreenAndQualitySignalsBothMatch, skipsRefinementWhenOnlyPrescreenMatches, primary |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/AttributionResolver.java` | distribute, AttributionResolver |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java` | resolve, DefaultRefinementWindowResolver |
| `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionGapDetector.java` | assess, ExtractionGapDetector |

## Entry Points

Start here when exploring this area:

- **`DefaultExtractionGapDetector`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetector.java:7`
- **`DefaultRefinementWindowResolver`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java:6`
- **`DefaultExtractionMergePolicy`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java:13`
- **`DefaultAttributionResolver`** (Class) — `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java:11`
- **`refineExtractions`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java:542`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `DefaultExtractionGapDetector` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionGapDetector.java` | 7 |
| `DefaultRefinementWindowResolver` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java` | 6 |
| `DefaultExtractionMergePolicy` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultExtractionMergePolicy.java` | 13 |
| `DefaultAttributionResolver` | Class | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java` | 11 |
| `ExtractionGapDetector` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionGapDetector.java` | 4 |
| `RefinementWindowResolver` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindowResolver.java` | 5 |
| `ExtractionMergePolicy` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionMergePolicy.java` | 6 |
| `AttributionResolver` | Interface | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/AttributionResolver.java` | 4 |
| `refineExtractions` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` | 542 |
| `distribute` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/AttributionResolver.java` | 5 |
| `resolve` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultRefinementWindowResolver.java` | 7 |
| `assess` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionGapDetector.java` | 5 |
| `merge` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionMergePolicy.java` | 7 |
| `refine` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java` | 34 |
| `meetsThresholds` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java` | 67 |
| `exceedsWindowLimits` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/ExtractionRefinementPipeline.java` | 73 |
| `resolve` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/RefinementWindowResolver.java` | 6 |
| `distribute` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java` | 18 |
| `hasContent` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java` | 112 |
| `toPatch` | Method | `lightrag-core/src/main/java/io/github/lightrag/indexing/refinement/DefaultAttributionResolver.java` | 116 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Query | 6 calls |
| Indexing | 4 calls |

## How to Explore

1. `gitnexus_context({name: "DefaultExtractionGapDetector"})` — see callers and callees
2. `gitnexus_query({query: "refinement"})` — find related execution flows
3. Read key files listed above for implementation details
