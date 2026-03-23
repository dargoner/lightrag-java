# Java LightRAG Embedding Semantic Merge Design

## Overview

This document defines the next V3 upgrade for Java LightRAG SmartChunker: replacing the current local heuristic semantic merge with embedding-driven semantic merge.

The current `SmartChunker` implementation already supports:

- V1 sentence-aware splitting
- V2 structure-aware chunking for headings, lists, and tables
- V3 optional local heuristic adjacent-chunk merge

The next step is to keep `SmartChunker` itself lightweight and local, while reusing the existing `LightRag` ingest-time `EmbeddingModel` to perform semantic merge when documents flow through the full indexing pipeline.

Compatibility note:

- the current branch already exposes `SmartChunkerConfig.semanticMergeEnabled` and `semanticMergeThreshold`
- standalone `SmartChunker.chunk(document)` currently performs local heuristic merge when those flags are enabled

This phase does not remove that standalone behavior immediately. Instead, it introduces ingest-only embedding semantic merge as a new pipeline feature while keeping the existing local heuristic path for direct standalone `SmartChunker` usage. A later cleanup can deprecate or bridge the standalone flags once the ingest-time embedding path is proven stable.

When ingest-time embedding semantic merge is enabled, the ingest pipeline must avoid running the standalone heuristic merge first. Otherwise the same document would be merged twice with different criteria, and the embedding stage would only see already-collapsed chunks.

## Goals

- Reuse the existing `EmbeddingModel` configured on `LightRagBuilder`
- Apply semantic merge only during `LightRag.ingest(...)` and downstream indexing flow
- Keep `new SmartChunker(...).chunk(document)` usable without any model dependency
- Ensure ingest-time embedding merge runs exactly once, without double-merging chunks that already passed the standalone heuristic path
- Merge only adjacent chunks
- Keep all existing `DocumentIngestor` chunk contract guarantees intact after merge
- Preserve compatibility with current `Chunk`, `ChunkStore`, snapshot, and PostgreSQL metadata contracts
- Preserve the current public behavior of `.chunker(new SmartChunker(...))` for standalone direct usage

## Non-Goals

- Do not redesign the `Chunker` SPI
- Do not make `SmartChunker` require an embedding model
- Do not add cross-document or non-adjacent chunk merging
- Do not change chunk metadata from `Map<String, String>`
- Do not introduce embedding caching, persistence, or background warmup in this phase
- Do not require LangChain4j for semantic merge
- Do not redefine standalone heuristic merge boundaries in this phase; direct `SmartChunker.chunk(document)` keeps its current compatibility behavior

## Current Constraint

The current embedding API is:

- `EmbeddingModel.embedAll(List<String> texts)`

This API is already available in the indexing pipeline through `LightRag` and `IndexingPipeline`, but it is not available inside the standalone `Chunker` SPI.

At the same time, `DocumentIngestor` requires that final chunks:

- have `documentId == source.id()`
- have unique chunk IDs within the document
- use contiguous `order` values starting at `0`

That means ingest-time embedding semantic merge must happen before final ingest validation persists chunks, and any merge step must normalize IDs, orders, and neighbor metadata before returning final chunks.

## Architecture Options Considered

### Option 1: Inject `EmbeddingModel` into `SmartChunker`

Pros:

- `SmartChunker.chunk(document)` would produce final semantically-merged chunks directly

Cons:

- makes `SmartChunker` model-dependent
- breaks the current lightweight local-only usage model
- complicates testing and offline usage
- expands the responsibilities of the `Chunker` SPI

### Option 2: Add ingest-only embedding semantic refinement after chunking

Pros:

- reuses the existing `EmbeddingModel`
- keeps `SmartChunker` local and framework-neutral
- avoids changing the `Chunker` SPI
- fits the current indexing pipeline shape

Cons:

- semantic merge only applies in full ingest flow
- standalone `SmartChunker` calls keep using the local structure-aware plus optional heuristic path

### Option 3: Introduce a second chunking SPI only for ingest

Pros:

- conceptually explicit separation

Cons:

- too much API churn
- forces coordinated changes across builder, ingest, pipeline, tests, and docs
- unnecessary for current scope

## Recommendation

Adopt Option 2.

Keep `SmartChunker` responsible for V1 and V2 structure-aware chunking, retain the current standalone heuristic merge behavior for compatibility, and add ingest-only embedding-driven V3 merge as a refinement step that runs only for ingest-resolved raw `SmartChunker` output before final `DocumentIngestor` chunk contract validation and persistence.

Responsibility split:

- `IndexingPipeline` owns model-dependent assembly: it constructs embedding-aware collaborators from `EmbeddingModel + embeddingBatchSize`
- `DocumentIngestor` remains the sole owner of per-document chunk preparation, validation, and persistence boundaries
- a new injected chunk-preparation strategy bridges those two layers so `DocumentIngestor` can refine chunks without directly owning `EmbeddingModel`

## Proposed Flow

Current ingest flow:

1. `DocumentIngestor.prepare(...)`
2. `chunker.chunk(document)`
3. validate chunk contract
4. persist chunks
5. continue with embedding, extraction, and graph assembly

New ingest flow when ingest-time embedding semantic merge is enabled:

1. `DocumentIngestor.prepare(...)`
2. resolve raw chunks
3. optional embedding semantic refinement for `SmartChunker` raw output
4. normalize final chunk IDs, orders, and neighbor metadata
5. validate chunk contract
6. persist chunks
7. continue with embedding, extraction, and graph assembly

This preserves the existing storage and indexing boundaries while upgrading semantic merge quality.

Raw chunk resolution rules:

- default path: keep using `chunker.chunk(document)`
- embedding semantic merge path: if the configured chunker is `SmartChunker`, call a new ingest-only package-private method that returns V1/V2 structural chunks before local heuristic merge
- the ingest pipeline must not call `SmartChunker.chunk(document)` and then run embedding merge on top of its result

This avoids the double-merge problem while preserving the current public meaning of direct `SmartChunker.chunk(document)`.

## Components

### `DocumentChunkPreparationStrategy`

To make ownership explicit, `DocumentIngestor` should no longer hard-code `chunker.chunk(document)` as its only preparation path. Instead, it should delegate chunk preparation to an injected strategy.

Suggested shape:

```java
interface DocumentChunkPreparationStrategy {
    List<Chunk> prepare(Document document, Chunker chunker);
}
```

Implementations for this phase:

- `DefaultChunkPreparationStrategy`
  - calls `chunker.chunk(document)`
  - used whenever ingest-time embedding semantic merge is disabled
- `SmartChunkerEmbeddingPreparationStrategy`
  - requires `chunker instanceof SmartChunker`
  - calls `smartChunker.chunkStructural(document)`
  - computes adjacent similarities through `ChunkSimilarityScorer`
  - calls `SemanticChunkRefiner`
  - returns normalized final chunks back to `DocumentIngestor`

Construction boundary:

- `IndexingPipeline` creates the strategy because it owns `EmbeddingModel`, `embeddingBatchSize`, and builder-level semantic-merge settings
- `DocumentIngestor` receives the selected strategy via constructor and remains the only class that validates chunk contract and persists prepared chunks

This makes the refinement owner unambiguous without pushing `EmbeddingModel` into `DocumentIngestor` itself.

### `SemanticChunkRefiner` extension

Do not introduce a second refiner type with overlapping responsibilities.

The existing `SemanticChunkRefiner` already owns:

- adjacent merge orchestration
- text merge
- `source_block_ids` merge
- `id/order` normalization
- `prev_chunk_id` / `next_chunk_id` rebuild

This phase should extend that existing refiner so it can work with interchangeable similarity strategies and mode-specific merge policies:

- local heuristic similarity for standalone direct `SmartChunker` use
- embedding similarity for ingest-time semantic merge

Suggested shape:

```java
public final class SemanticChunkRefiner {
    List<Chunk> refine(
        String documentId,
        List<Chunk> chunks,
        int maxTokens,
        double threshold,
        SemanticSimilarity similarity,
        MergeMode mergeMode
    )
}
```

Suggested merge modes:

```java
enum MergeMode {
    GREEDY_CASCADING,
    PAIRWISE_SINGLE_PASS
}
```

The refiner should own merge eligibility evaluation rather than leaving it scattered in callers, but eligibility must remain mode-specific.

Mode semantics:

- standalone heuristic mode:
  - invoked only from `SmartChunker.chunk(document)`
  - uses `SemanticChunkRefiner.defaultSimilarity()`
  - uses `SmartChunkerConfig.semanticMergeThreshold`
  - uses `MergeMode.GREEDY_CASCADING` to preserve current compatibility
  - keeps the current standalone merge boundary: merge only depends on `maxTokens` plus heuristic similarity threshold in this phase
- ingest embedding mode:
  - invoked only from `SmartChunkerEmbeddingPreparationStrategy`
  - uses embedding-backed similarity
  - uses builder-level `embeddingSemanticMergeThreshold`
  - uses `MergeMode.PAIRWISE_SINGLE_PASS`
  - adds SmartChunker metadata guards so merge requires matching `section_path`, matching `content_type`, and `content_type != table`

Priority rules:

- if ingest-time embedding semantic merge is disabled, ingest keeps using the chunker's normal path, so `SmartChunkerConfig.semanticMergeEnabled` continues to control standalone/local merge behavior
- if ingest-time embedding semantic merge is enabled, ingest ignores `SmartChunkerConfig.semanticMergeEnabled` for that ingest path and uses `chunkStructural(document)` plus builder-level embedding settings
- direct standalone calls to `new SmartChunker(...).chunk(document)` always keep using `SmartChunkerConfig`

### `SmartChunker` ingest-only raw chunk access

To support ingest-time embedding merge without changing the public `Chunker` SPI, `SmartChunker` should expose package-private ingest helpers inside `io.github.lightragjava.indexing`.

Suggested shape:

```java
public final class SmartChunker implements Chunker {
    List<Chunk> chunkStructural(Document document);
    SmartChunkerConfig config();
}
```

Rules:

- `chunk(document)` keeps its current public behavior, including optional local heuristic merge
- `chunkStructural(document)` returns the pre-merge V1/V2 output with full SmartChunker metadata
- ingest-time embedding merge uses `chunkStructural(document)`, not `chunk(document)`
- `config()` is package-private and only used by ingest-time refinement to read `maxTokens`

This resolves both the double-merge ambiguity and the current inability to read `maxTokens` through the generic `Chunker` interface.

### `ChunkSimilarityScorer`

New helper responsible for:

- converting chunk text into vectors
- computing cosine similarity between adjacent chunk vectors
- keeping vector math out of merge orchestration logic

Recommended location:

- `io.github.lightragjava.indexing`

Suggested shape:

```java
public interface ChunkSimilarityScorer {
    SemanticSimilarity similarityFor(List<Chunk> chunks);
}
```

Implementation note:

- the first implementation should consume a shared embedding batching helper instead of calling `embedAll(...)` blindly
- similarity only needs adjacent pairs: chunk `i` with chunk `i + 1`
- the first implementation only needs to support `SmartChunker` output
- the first implementation is only used with `MergeMode.PAIRWISE_SINGLE_PASS`

Algorithm note for V3:

- ingest embedding mode does not re-embed merged `(A+B)` text in the same pass
- it computes similarity only for original adjacent raw chunks
- after one successful merge of `A+B`, the merged result is emitted and is not compared again with `C` in the same refinement pass

Pseudo-code for `PAIRWISE_SINGLE_PASS`:

```text
i = 0
while i < chunks.size:
  if i + 1 < chunks.size and canMerge(chunks[i], chunks[i + 1]):
    emit merge(chunks[i], chunks[i + 1])
    i += 2
  else:
    emit chunks[i]
    i += 1
```

This keeps batching predictable and avoids an unbounded re-embedding loop while still delivering true embedding-based adjacent semantic merge.

### `EmbeddingBatcher`

The current `IndexingPipeline.embedInBatches(...)` is private, so this phase should extract that batching logic into a shared helper owned by the indexing package.

Suggested shape:

```java
final class EmbeddingBatcher {
    EmbeddingBatcher(EmbeddingModel embeddingModel, int embeddingBatchSize);
    List<List<Double>> embedAll(List<String> texts);
}
```

Ownership and usage:

- `LightRagBuilder.embeddingBatchSize(...)` remains the single configuration source for batch size
- `IndexingPipeline` constructs one `EmbeddingBatcher` from `EmbeddingModel + embeddingBatchSize`
- chunk/entity/relation vector generation reuses that helper
- `ChunkSimilarityScorer` reuses the same helper for ingest-time semantic merge
- `DocumentChunkPreparationStrategy` never owns batching config directly; it receives scorer/refiner collaborators that were already built by `IndexingPipeline`

This keeps batching semantics identical across vector storage and semantic merge, and avoids duplicating batch splitting logic in two places.

### Smart chunking options in builder/pipeline

The builder should expose semantic-merge controls separately from `.chunker(...)`.

Suggested builder surface:

- `enableEmbeddingSemanticMerge(boolean enabled)`
- `embeddingSemanticMergeThreshold(double threshold)`

The configured chunker remains whatever the user passes via `.chunker(...)`.

Recommended default:

- embedding semantic merge disabled by default
- `embeddingSemanticMergeThreshold` defaults to `0.80d`, matching the current `SmartChunkerConfig` default for easier migration

That preserves current behavior unless users opt in explicitly.

Configuration source for ingest-time merge:

- `enableEmbeddingSemanticMerge(boolean enabled)`
- `embeddingSemanticMergeThreshold(double threshold)`
- `embeddingSemanticMergeThreshold` is validated in `LightRagBuilder` using the same `0.0..1.0` range as `SmartChunkerConfig.semanticMergeThreshold`

Configuration source for `maxTokens`:

- only `SmartChunker` output is supported in this phase
- ingest-time embedding merge reads `maxTokens` from `SmartChunker.config()`
- if embedding semantic merge is enabled but the configured chunker is not `SmartChunker`, `LightRagBuilder.build()` should fail fast with a clear configuration error

This avoids rewriting arbitrary custom chunk IDs and metadata from unrelated `Chunker` implementations, and avoids surprising silent no-op behavior for a feature the caller explicitly enabled.

## Merge Rules

Embedding semantic merge must be conservative.

Two adjacent chunks may merge only if all of the following are true:

1. They are adjacent in the same document
2. Their `smart_chunker.section_path` values are equal
3. Their `smart_chunker.content_type` values are equal
4. Combined code-point count stays within configured `maxTokens`
5. Cosine similarity is greater than or equal to the configured threshold

Additional restriction for this phase:

- `smart_chunker.content_type=table` must never use embedding semantic merge

This prevents the current heuristic-merge class of bugs where highly similar text from different headings or different content types is merged while metadata still claims a single section/type.

Standalone heuristic merge note:

- direct `SmartChunker.chunk(document)` keeps the current compatibility behavior in this phase
- the stricter `section_path` / `content_type` / `table` guards are only required for ingest-time embedding semantic merge

## Metadata Rules

Metadata must stay within the existing `Map<String, String>` contract.

Preserve existing keys when present:

- `source`
- `file_path`

Preserve SmartChunker keys:

- `smart_chunker.section_path`
- `smart_chunker.content_type`

Rebuild and normalize:

- `smart_chunker.source_block_ids`
- `smart_chunker.prev_chunk_id`
- `smart_chunker.next_chunk_id`

Preserve table metadata without reinterpretation:

- `smart_chunker.table_part_index`
- `smart_chunker.table_part_total`

Do not introduce non-string metadata values in this phase.

If merged chunks have incompatible `section_path` or `content_type`, they must not merge at all.

## Chunk ID And Order Normalization

After the refine step finishes:

- final chunk order must be reassigned from `0..n-1`
- final chunk IDs must be recomputed as `<documentId>:<order>`
- neighbor links must be rebuilt from normalized order

This is required to satisfy the existing `DocumentIngestor` contract and to keep `ChunkStore.listByDocument(...)` ordering behavior stable.

## Failure Semantics

- If embedding semantic merge is disabled, keep the current flow unchanged
- If embedding semantic merge is enabled and the configured chunker is not `SmartChunker`, `LightRagBuilder.build()` fails fast with a configuration error
- If embedding semantic merge is enabled and raw SmartChunker metadata required for merge eligibility is missing, fail fast because the ingest-only SmartChunker contract has been violated
- If embedding generation fails, ingest should fail rather than silently changing quality semantics
- If only one chunk exists, refinement is a no-op
- If standalone `SmartChunkerConfig.semanticMergeEnabled=true` and ingest-time embedding semantic merge is also enabled, the ingest pipeline must still use `chunkStructural(document)` so only the embedding merge path runs during ingest

## Testing Strategy

### Unit tests

Add focused tests for:

- adjacent chunks with same section/type and high similarity merge
- low similarity does not merge
- different `section_path` does not merge
- different `content_type` does not merge
- combined text over `maxTokens` does not merge
- normalization rebuilds `id`, `order`, `prev_chunk_id`, and `next_chunk_id`

### Pipeline tests

Add tests covering:

- standalone `new SmartChunker(...).chunk(document)` remains model-free
- standalone `new SmartChunker(...).chunk(document)` keeps the current heuristic merge behavior for compatibility
- `LightRag.ingest(...)` with merge enabled uses `EmbeddingModel`
- `LightRag.ingest(...)` with merge disabled preserves current chunk output
- ingest-time embedding merge uses `SmartChunker.chunkStructural(...)` rather than `SmartChunker.chunk(...)` to avoid double merge
- enabling ingest-time embedding merge with a non-`SmartChunker` chunker fails fast in `LightRagBuilder.build()` with a clear error
- ingest-time embedding merge reuses shared indexing batch semantics rather than embedding all chunks in one unconditional call
- when `SmartChunkerConfig.semanticMergeEnabled=true`, ingest-time embedding merge still executes only once
- embedding merge uses pairwise single-pass refinement and does not re-embed merged text during the same pass

### Contract tests

Add a `DocumentIngestor` or `LightRagBuilder`-level test proving that:

- refined chunks still satisfy current ingest validation
- persisted chunk IDs and orders are normalized correctly

### Regression tests

Add explicit regression coverage for:

- sentence-aware chunking does not loop forever when a single sentence alone fills a chunk
- ingest-time embedding semantic merge does not cross section boundaries
- ingest-time embedding semantic merge does not cross content type boundaries
- ingest-time embedding semantic merge does not run on table chunks

## Acceptance Criteria

This phase is complete when:

- `SmartChunker` standalone usage still has no model dependency
- `LightRag.ingest(...)` can optionally enable embedding semantic merge
- embedding merge reuses the existing `EmbeddingModel`
- embedding merge reuses the existing `SemanticChunkRefiner` orchestration path
- final chunk output still satisfies current ingest/storage contracts
- merge does not cross `section_path` or `content_type`
- merge does not run on `content_type=table`
- existing default chunking behavior remains unchanged unless users opt in
- enabling embedding semantic merge with a non-`SmartChunker` chunker fails fast in `LightRagBuilder.build()`
- ingest-time embedding merge uses `SmartChunker.chunkStructural(...)` and runs exactly once without double-merging standalone heuristic output
