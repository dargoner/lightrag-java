# Java LightRAG SmartChunker Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a staged SmartChunker implementation to the Java LightRAG SDK with V1 foundational structured chunking, V2 structure-preserving metadata-rich chunk assembly, and V3 semantic refinement plus LangChain4j-compatible adaptation.

**Architecture:** Keep the existing `Chunker` SPI stable and introduce a new `SmartChunker` implementation plus supporting config/model/adapter types under `lightrag-core`. Preserve current `FixedWindowChunker` behavior as the default, let builders opt into SmartChunker explicitly, and add the new behavior incrementally through TDD so V1, V2, and V3 can be verified independently.

**Tech Stack:** Java 17, Gradle Kotlin DSL, JUnit 5, AssertJ, existing LightRAG indexing/query/storage pipeline

---

## File Structure

- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkMetadata.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SentenceBoundaryAnalyzer.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredBlock.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredDocument.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredDocumentParser.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkOverlapStrategy.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructureAwareChunkPlanner.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticSimilarity.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/LangChain4jChunkAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/StructuredDocumentParserTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/LangChain4jChunkAdapterTest.java`
- Modify: `lightrag-core/build.gradle.kts`

## Chunk 1: V1 Foundation

### Task 1: Add failing SmartChunker V1 tests

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/api/LightRagBuilderTest.java`

- [ ] **Step 1: Add a failing sentence-aware chunk boundary test**

Cover:

- SmartChunker keeps sentence boundaries when splitting prose
- overlap starts at a sentence boundary instead of mid-sentence

- [ ] **Step 2: Add a failing metadata propagation test**

Cover:

- emitted chunks preserve source metadata
- emitted chunks add `section_path`, `content_type`, and `source_block_ids`

- [ ] **Step 3: Add a failing builder opt-in test**

Cover:

- builder can accept a `SmartChunker`
- builder default remains `FixedWindowChunker`

- [ ] **Step 4: Run targeted tests to verify they fail**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`

Expected: FAIL because SmartChunker classes do not exist yet.

### Task 2: Implement SmartChunker V1

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerConfig.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkMetadata.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SentenceBoundaryAnalyzer.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkOverlapStrategy.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/api/LightRagBuilder.java`

- [ ] **Step 1: Implement config and metadata constants**

Requirements:

- target/max/overlap token knobs
- sane defaults aligned with the design draft
- metadata key constants centralized in one place

- [ ] **Step 2: Implement sentence-aware splitting and overlap**

Requirements:

- fall back to code-point windows when sentence detection cannot help
- never split surrogate pairs
- preserve contiguous chunk order starting at `0`

- [ ] **Step 3: Build initial SmartChunker implementation**

Requirements:

- implement existing `Chunker` SPI
- derive stable chunk ids `<documentId>:<order>`
- preserve source metadata and append SmartChunker metadata

- [ ] **Step 4: Keep builder compatibility**

Requirements:

- no breaking change to existing default chunker
- optional use through existing `.chunker(...)`

- [ ] **Step 5: Run targeted V1 tests**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.DocumentIngestorTest`

Expected: PASS.

## Chunk 2: V2 Structure Preservation

### Task 3: Add failing V2 parser and structure-preserving tests

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/StructuredDocumentParserTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SmartChunkerTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/indexing/DocumentIngestorTest.java`

- [ ] **Step 1: Add a failing heading and section-path test**

Cover:

- markdown-style headings or numbered headings produce hierarchical `section_path`

- [ ] **Step 2: Add a failing list-preservation test**

Cover:

- a leading sentence and its list items remain in the same logical chunk
- if split, each follow-up chunk repeats the leading sentence context

- [ ] **Step 3: Add a failing table-header carry test**

Cover:

- large tables split by rows
- each emitted table chunk preserves the header row and part metadata

- [ ] **Step 4: Run targeted tests to verify they fail**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.StructuredDocumentParserTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.DocumentIngestorTest`

Expected: FAIL because V2 structure-aware parsing is missing.

### Task 4: Implement V2 structure-aware parsing and metadata enrichment

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredBlock.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredDocument.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructuredDocumentParser.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/StructureAwareChunkPlanner.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`

- [ ] **Step 1: Parse documents into structured blocks**

Requirements:

- support headings, paragraphs, bullet/numbered lists, and pipe tables
- retain source order and hierarchical section path

- [ ] **Step 2: Add structure-aware planning**

Requirements:

- headings are strong boundaries
- lists are attached to their lead sentence
- tables are split row-wise with repeated header rows

- [ ] **Step 3: Enrich metadata**

Requirements:

- add `content_type`, `section_path`, `table_part_index`, `table_part_total`, `prev_chunk_id`, `next_chunk_id`
- keep metadata compatible with current query/reference code by preserving `source` / `file_path` when present

- [ ] **Step 4: Run targeted V2 tests**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.StructuredDocumentParserTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.DocumentIngestorTest`

Expected: PASS.

## Chunk 3: V3 Semantic Refinement And LangChain4j Compatibility

### Task 5: Add failing V3 refinement and adapter tests

**Files:**
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/SemanticChunkRefinerTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightragjava/indexing/LangChain4jChunkAdapterTest.java`
- Modify: `lightrag-core/build.gradle.kts`

- [ ] **Step 1: Add a failing semantic merge test**

Cover:

- adjacent chunks merge when similarity is above threshold
- chunks stay separate when similarity is below threshold

- [ ] **Step 2: Add a failing adapter test**

Cover:

- SmartChunk output can be converted to LangChain4j `TextSegment`
- metadata is preserved in the adapter output

- [ ] **Step 3: Run targeted tests to verify they fail**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SemanticChunkRefinerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.LangChain4jChunkAdapterTest`

Expected: FAIL because semantic refinement and adapter layers do not exist.

### Task 6: Implement V3 semantic refinement and adapter layer

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticChunkRefiner.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SemanticSimilarity.java`
- Create: `lightrag-core/src/main/java/io/github/lightragjava/indexing/LangChain4jChunkAdapter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- Modify: `lightrag-core/build.gradle.kts`

- [ ] **Step 1: Add a lightweight semantic similarity SPI**

Requirements:

- no model hard-coding in SmartChunker
- default implementation is deterministic and local for tests

- [ ] **Step 2: Add optional adjacent-chunk refinement**

Requirements:

- gated by config flag
- merges only when combined size remains within limits
- updates `prev_chunk_id` / `next_chunk_id` links after merge

- [ ] **Step 3: Add LangChain4j adapter**

Requirements:

- convert `Chunk` objects to `dev.langchain4j.data.segment.TextSegment`
- preserve chunk text and metadata
- keep adapter isolated from core chunking flow

- [ ] **Step 4: Run targeted V3 tests**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SemanticChunkRefinerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.LangChain4jChunkAdapterTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`

Expected: PASS.

## Chunk 4: Verification

### Task 7: Run final focused verification

**Files:**
- No source edits expected

- [ ] **Step 1: Run the full focused SmartChunker suite**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.FixedWindowChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SmartChunkerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.StructuredDocumentParserTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.SemanticChunkRefinerTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.LangChain4jChunkAdapterTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.DocumentIngestorTest`
- `gradle :lightrag-core:test --tests io.github.lightragjava.api.LightRagBuilderTest`

- [ ] **Step 2: Run one broader indexing regression slice**

Run:

- `gradle :lightrag-core:test --tests io.github.lightragjava.indexing.*`

- [ ] **Step 3: Record any remaining risk**

Focus:

- unsupported rich document formats beyond plain/markdown-like text
- semantic refinement quality depends on the chosen similarity SPI
- LangChain4j compatibility stays adapter-only unless ingestion APIs are added later
