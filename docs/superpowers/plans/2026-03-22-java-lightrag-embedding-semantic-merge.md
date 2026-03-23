# Java LightRAG Embedding Semantic Merge Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ingest-only embedding-driven semantic merge for `SmartChunker` output while preserving standalone `SmartChunker.chunk(document)` compatibility and existing ingest/storage contracts.

**Architecture:** Keep `SmartChunker` model-free and expose a package-private structural chunk path for ingest. Build embedding-aware collaborators inside `IndexingPipeline`, inject a document chunk preparation strategy into `DocumentIngestor`, and extend `SemanticChunkRefiner` with explicit merge modes so standalone heuristic merge and ingest embedding merge can share normalization code without sharing behavior.

**Tech Stack:** Java 17, Gradle, JUnit 5, AssertJ

---

### Task 1: Builder Contract And Fail-Fast Guardrails

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Write the failing builder tests**

```java
@Test
void rejectsEmbeddingSemanticMergeThresholdOutsideUnitRange() {
    assertThatThrownBy(() -> LightRag.builder()
        .embeddingSemanticMergeThreshold(1.1d))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 0.0 and 1.0");
}

@Test
void failsBuildWhenEmbeddingSemanticMergeIsEnabledForNonSmartChunker() {
    assertThatThrownBy(() -> LightRag.builder()
        .chatModel(new FakeChatModel())
        .embeddingModel(new FakeEmbeddingModel())
        .storage(new FakeStorageProvider())
        .chunker(new FixedWindowChunker(100, 10))
        .enableEmbeddingSemanticMerge(true)
        .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SmartChunker");
}
```

- [ ] **Step 2: Run the builder test class and confirm the new cases fail**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.api.LightRagBuilderTest"
```

Expected: FAIL because the new fluent methods, default threshold plumbing, and fail-fast validation do not exist yet.

- [ ] **Step 3: Implement the builder-facing contract**

```java
public final class LightRagBuilder {
    private boolean embeddingSemanticMergeEnabled = false;
    private double embeddingSemanticMergeThreshold = 0.80d;

    public LightRagBuilder enableEmbeddingSemanticMerge(boolean enabled) {
        this.embeddingSemanticMergeEnabled = enabled;
        return this;
    }

    public LightRagBuilder embeddingSemanticMergeThreshold(double threshold) {
        if (threshold < 0.0d || threshold > 1.0d) {
            throw new IllegalArgumentException("embeddingSemanticMergeThreshold must be between 0.0 and 1.0");
        }
        this.embeddingSemanticMergeThreshold = threshold;
        return this;
    }
}
```

Implementation notes:
- Add the two new builder fields with defaults from the spec.
- In `build()`, reject `enableEmbeddingSemanticMerge(true)` unless `chunker instanceof SmartChunker`.
- Thread the two values through `LightRag` so later tasks can pass them into `IndexingPipeline`.
- Keep existing builder behavior unchanged when the new feature is not enabled.

- [ ] **Step 4: Re-run the builder test class and make sure it passes**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.api.LightRagBuilderTest"
```

Expected: PASS for the new builder contract and previous builder tests.

- [ ] **Step 5: Commit the builder contract change**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: add embedding semantic merge builder options"
```

### Task 2: SmartChunker Structural Output And Sentence-Loop Regression

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`

- [ ] **Step 1: Add failing SmartChunker tests for structural output and forward progress**

```java
@Test
void exposesStructuralChunksBeforeStandaloneSemanticMerge() {
    var chunker = new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(120)
        .maxTokens(120)
        .overlapTokens(8)
        .semanticMergeEnabled(true)
        .semanticMergeThreshold(0.2d)
        .build());

    var structural = chunker.chunkStructural(new Document(
        "doc-1", "Guide", "Retrieval overview.\n\nRetrieval details.", Map.of()
    ));

    assertThat(structural).hasSize(2);
}

@Test
void advancesWhenFirstSentenceAloneFillsChunk() {
    var chunker = new SmartChunker(SmartChunkerConfig.builder()
        .targetTokens(8)
        .maxTokens(8)
        .overlapTokens(3)
        .build());

    assertThat(chunker.chunk(new Document(
        "doc-2", "Guide", "12345678. tail.", Map.of()
    ))).isNotEmpty();
}
```

- [ ] **Step 2: Run the SmartChunker test class and verify failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SmartChunkerTest"
```

Expected: FAIL because `chunkStructural(...)` does not exist and the forward-progress regression is still present.

- [ ] **Step 3: Implement structural chunk access without changing public standalone behavior**

```java
public final class SmartChunker implements Chunker {
    List<Chunk> chunkStructural(Document document) {
        // Build V1/V2 chunks with SmartChunker metadata but without semantic refinement.
    }

    SmartChunkerConfig config() {
        return config;
    }

    @Override
    public List<Chunk> chunk(Document document) {
        var chunks = chunkStructural(document);
        return config.semanticMergeEnabled()
            ? semanticChunkRefiner.refine(
                document.id(), chunks, config.maxTokens(), config.semanticMergeThreshold(),
                SemanticChunkRefiner.defaultSimilarity(), MergeMode.GREEDY_CASCADING)
            : chunks;
    }
}
```

Implementation notes:
- Extract the existing draft-building logic so `chunkStructural(...)` becomes the single source of truth for V1/V2 output.
- Keep `chunkStructural(...)` and `config()` package-private.
- Fix the sentence loop by guaranteeing `start` always advances when one sentence fills the whole chunk and overlap rewind would otherwise stall.
- Do not change standalone merge eligibility beyond the current `maxTokens + similarity` behavior.

- [ ] **Step 4: Re-run SmartChunker tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SmartChunkerTest"
```

Expected: PASS for the new structural-output and regression cases, while existing standalone compatibility tests stay green.

- [ ] **Step 5: Commit the SmartChunker preparation change**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java
git commit -m "feat: expose structural smart chunks for ingest"
```

### Task 3: Shared Embedding Batching And Refiner Modes

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/EmbeddingBatcher.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkSimilarityScorer.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/EmbeddingChunkSimilarityScorer.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java`

- [ ] **Step 1: Write failing refiner tests for both merge modes**

```java
@Test
void pairwiseSinglePassMergesOnlyOneAdjacentPairPerPass() {
    var refiner = new SemanticChunkRefiner();
    var refined = refiner.refine("doc-1", chunks, 200, 0.8d, similarity, MergeMode.PAIRWISE_SINGLE_PASS);
    assertThat(refined).hasSize(2);
}

@Test
void embeddingModeRejectsSectionBoundaryAndTableMerges() {
    var refiner = new SemanticChunkRefiner();
    var refined = refiner.refine("doc-1", chunks, 200, 0.8d, similarity, MergeMode.PAIRWISE_SINGLE_PASS);
    assertThat(refined).extracting(Chunk::id).containsExactly("doc-1:0", "doc-1:1");
}

@Test
void embeddingModeFailsFastWhenRequiredSmartChunkMetadataIsMissing() {
    assertThatThrownBy(() -> refiner.refine("doc-1", chunksWithoutSectionMetadata, 200, 0.8d, similarity,
        MergeMode.PAIRWISE_SINGLE_PASS))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("smart_chunker.section_path");
}
```

- [ ] **Step 2: Run the refiner test class and confirm the new cases fail**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SemanticChunkRefinerTest"
```

Expected: FAIL because the refiner has no merge modes, no embedding guard rules, and no pairwise single-pass behavior.

- [ ] **Step 3: Implement shared batching and mode-aware refinement**

```java
final class EmbeddingBatcher {
    List<List<Double>> embedAll(List<String> texts) { ... }
}

public interface ChunkSimilarityScorer {
    SemanticSimilarity similarityFor(List<Chunk> chunks);
}

public final class SemanticChunkRefiner {
    List<Chunk> refine(
        String documentId,
        List<Chunk> chunks,
        int maxTokens,
        double threshold,
        SemanticSimilarity similarity,
        MergeMode mergeMode
    ) { ... }
}
```

Implementation notes:
- Keep `GREEDY_CASCADING` for standalone heuristic compatibility.
- Implement `PAIRWISE_SINGLE_PASS` exactly as the spec pseudo-code defines: merge `A+B` and skip re-evaluating the merged output against `C` in the same pass.
- Restrict `section_path` / `content_type` / `table` guards to the embedding mode only.
- In `PAIRWISE_SINGLE_PASS`, throw a clear `IllegalStateException` when required SmartChunker metadata needed for merge eligibility is missing.
- Extract `IndexingPipeline` batch splitting logic into `EmbeddingBatcher`, but do not wire the pipeline to it yet in this task.
- Keep normalization of IDs, `order`, `prev_chunk_id`, `next_chunk_id`, and merged `source_block_ids` inside `SemanticChunkRefiner`.

- [ ] **Step 4: Re-run the refiner test class**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SemanticChunkRefinerTest"
```

Expected: PASS for the new merge-mode behavior and previous normalization tests.

- [ ] **Step 5: Commit the batching and refiner changes**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/EmbeddingBatcher.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkSimilarityScorer.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/EmbeddingChunkSimilarityScorer.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java
git commit -m "feat: add embedding-driven semantic refinement primitives"
```

### Task 4: Ingest Preparation Strategy And Pipeline Wiring

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentChunkPreparationStrategy.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DefaultChunkPreparationStrategy.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerEmbeddingPreparationStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Add failing integration tests for ingest-only embedding merge**

```java
@Test
void usesStructuralSmartChunksDuringEmbeddingSemanticMerge() {
    var rag = LightRag.builder()
        .chatModel(new IngestionChatModel())
        .embeddingModel(new DeterministicEmbeddingModel())
        .storage(new FakeStorageProvider())
        .chunker(new SmartChunker(SmartChunkerConfig.builder()
            .semanticMergeEnabled(true)
            .build()))
        .enableEmbeddingSemanticMerge(true)
        .embeddingBatchSize(2)
        .build();

    rag.ingest(List.of(document));

    assertThat(storedChunks).hasSize(1);
}
```

Add one `DocumentIngestorTest` that proves prepared chunks are still validated after strategy-based refinement, and one `LightRagBuilderTest` that proves:
- ingest uses `chunkStructural(...)`
- embedding batching honors `embeddingBatchSize`
- merge runs exactly once even when standalone heuristic merge is enabled in `SmartChunkerConfig`
- disabling ingest embedding merge keeps using the existing `chunker.chunk(document)` path and preserves current ingest output

- [ ] **Step 2: Run the targeted ingest tests and confirm failure**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
                              --tests "io.github.lightragjava.api.LightRagBuilderTest"
```

Expected: FAIL because `DocumentIngestor` cannot accept a preparation strategy yet and `IndexingPipeline` still chunks directly through the old path.

- [ ] **Step 3: Implement strategy-based chunk preparation and pipeline wiring**

```java
interface DocumentChunkPreparationStrategy {
    List<Chunk> prepare(Document document, Chunker chunker);
}

final class DefaultChunkPreparationStrategy implements DocumentChunkPreparationStrategy {
    public List<Chunk> prepare(Document document, Chunker chunker) {
        return chunker.chunk(document);
    }
}

final class SmartChunkerEmbeddingPreparationStrategy implements DocumentChunkPreparationStrategy {
    public List<Chunk> prepare(Document document, Chunker chunker) {
        var smartChunker = (SmartChunker) chunker;
        var chunks = smartChunker.chunkStructural(document);
        return semanticChunkRefiner.refine(
            document.id(),
            chunks,
            smartChunker.config().maxTokens(),
            embeddingSemanticMergeThreshold,
            chunkSimilarityScorer.similarityFor(chunks),
            MergeMode.PAIRWISE_SINGLE_PASS
        );
    }
}
```

Implementation notes:
- Extend `DocumentIngestor` constructor to accept a `DocumentChunkPreparationStrategy`; keep an overload that defaults to `DefaultChunkPreparationStrategy` so existing call sites stay simple.
- Build the embedding strategy inside `IndexingPipeline`, because that class owns `EmbeddingModel`, `embeddingBatchSize`, and the new builder settings.
- Pass the new builder options from `LightRag` into the `IndexingPipeline` constructor explicitly; do not re-read them from global state.
- Replace the private `embedInBatches(...)` logic in `IndexingPipeline` with `EmbeddingBatcher`.
- Keep `DocumentIngestor` as the only class that runs chunk-contract validation and persistence.
- Preserve the old ingest path when embedding semantic merge is disabled: the selected strategy must delegate to `chunker.chunk(document)` exactly as before.

- [ ] **Step 4: Re-run the targeted ingest tests**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
                              --tests "io.github.lightragjava.api.LightRagBuilderTest"
```

Expected: PASS for strategy injection, exact-once merge, batch reuse, and fail-fast behavior.

- [ ] **Step 5: Commit the ingest integration**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentChunkPreparationStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DefaultChunkPreparationStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerEmbeddingPreparationStrategy.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/DocumentIngestor.java \
        lightrag-core/src/main/java/io/github/lightragjava/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "feat: wire embedding semantic merge into ingest pipeline"
```

### Task 5: Documentation, Regression Coverage, And Final Verification

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Add or finish the remaining regression/documentation updates**

```markdown
- explain that embedding semantic merge is ingest-only
- show `.enableEmbeddingSemanticMerge(true)`
- show `.embeddingSemanticMergeThreshold(0.80d)`
- note that standalone `SmartChunker.chunk(...)` remains model-free
```

Coverage checklist:
- sentence-aware chunking no longer stalls
- ingest embedding merge does not cross `section_path`
- ingest embedding merge does not cross `content_type`
- ingest embedding merge never merges table chunks
- non-`SmartChunker` + merge enabled fails in `build()`
- missing SmartChunker merge metadata fails fast during ingest embedding refinement
- merge disabled preserves the pre-existing ingest output path

- [ ] **Step 2: Run the focused regression suite**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test --tests "io.github.lightragjava.indexing.SmartChunkerTest" \
                              --tests "io.github.lightragjava.indexing.SemanticChunkRefinerTest" \
                              --tests "io.github.lightragjava.indexing.DocumentIngestorTest" \
                              --tests "io.github.lightragjava.api.LightRagBuilderTest"
```

Expected: PASS for all feature and regression coverage added in Tasks 1-4.

- [ ] **Step 3: Run the full core test task**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
./gradlew :lightrag-core:test
```

Expected: PASS across the full `lightrag-core` module.

- [ ] **Step 4: Inspect the final diff before closing**

Run:
```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git status --short
git diff --stat
```

Expected: Only the planned implementation, test, and documentation files remain modified.

- [ ] **Step 5: Commit the docs and final verification updates**

```bash
cd /home/dargoner/work/lightrag-java/.worktrees/smart-chunker
git add README.md README_zh.md \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java \
        lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java
git commit -m "docs: document ingest-only embedding semantic merge"
```
