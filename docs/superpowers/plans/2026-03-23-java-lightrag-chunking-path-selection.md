# Java LightRAG Chunking Path Selection

## Goal

Clarify which parsing and chunking path should be used for each document type, and record the real-world verification evidence gathered on the `feat/smart-chunker` branch.

This note is implementation-facing. It answers:

- which parser path runs first
- when `SmartChunker` uses structured blocks vs plain text
- when `GENERIC` documents rely on sentence and semantic boundaries
- when ingest-time embedding semantic merge should be enabled

## Parsing Stage

Parsing always happens before chunking.

Current raw-source parsing preference:

1. plain text for directly readable text formats
2. `MinerU` for pdf / office / html and images
3. `Tika` fallback when enabled and `MinerU` is unavailable for supported office/pdf/html types

Important consequence:

- `MinerU` can produce `ParsedBlock` structure
- `Tika` and plain-text parsing mainly produce `plainText`
- chunking behavior depends on whether the downstream path receives structured blocks or only plain text

## Chunking Selection

`ChunkingOrchestrator` selects the chunker mode from `DocumentIngestOptions`.

Decision order:

1. if `strategyOverride=REGEX`, use manual regex chunking
2. if `strategyOverride=FIXED`, use fixed window chunking
3. if `strategyOverride=SMART`, use `SmartChunker`
4. if `strategyOverride=AUTO`:
   - use regex chunking when regex rules are present
   - otherwise use `SmartChunker`

## SmartChunker Paths

### Structured path

When all of the following are true:

- resolved mode is `SMART`
- parsed document contains `blocks`
- resolved document type is not `GENERIC`

then `ChunkingOrchestrator` calls:

- `SmartChunker.chunkParsedDocument(parsed, documentType)`

This path preserves:

- `section_path`
- `content_type`
- source block ids
- type-aware templates such as `QA` question-answer pairing

### Plain-text path

When the resolved document type is `GENERIC`, `SmartChunker` ignores parsed structured blocks and uses:

- `SmartChunker.chunk(new Document(...plainText...))`

That path relies on:

- heading detection when markdown-style headings exist
- list / table recognition when text shape is obvious
- sentence boundary splitting
- optional local heuristic semantic merge

For plain prose without reliable headings, `section_path` usually collapses to the document title, which is expected.

## Recommended Path By Document Type

### `LAW`

Recommended when the document is statute, regulation, agreement, policy text, or clearly article-based legal material.

Preferred path:

- `MinerU` parse
- `DocumentTypeHint.LAW`
- `SmartChunker`

Why:

- legal documents usually have reliable article / chapter structure
- preserving `section_path` matters more than aggressive semantic collapsing
- merge boundaries should not cross legal section boundaries

### `QA`

Recommended when the document is FAQ, interview transcript, `问答`, `答记者问`, or explicit Q/A material.

Preferred path:

- `MinerU` parse
- `DocumentTypeHint.QA`
- `SmartChunker`

Why:

- `SmartChunker` has explicit QA template pairing
- adjacent `Q/A` or `问/答` blocks can be combined before final chunk output

### `BOOK`

Recommended for books, reports, manuals, longform narrative, or chapter-based prose.

Preferred path:

- `MinerU` parse when available
- `DocumentTypeHint.BOOK`
- `SmartChunker`

Why:

- when headings are clean, chapter/section preservation works well
- when headings are weak, the output still falls back to sentence-aware prose chunking

Practical note:

- `BOOK` currently benefits from structure preservation, but its section quality depends heavily on source formatting
- for OCR-heavy or heading-poor books, effect is closer to `GENERIC`

### `GENERIC`

Recommended for ordinary business documents, narrative text, copied articles, dense prose, and materials with no stable legal / FAQ / chapter template.

Preferred path:

- `DocumentTypeHint.GENERIC`
- `SmartChunker`
- enable ingest-time embedding semantic merge when the full `LightRag` pipeline and `EmbeddingModel` are available

Why:

- `GENERIC` does not trust parsed structure enough to preserve it
- it falls back to plain-text sentence-aware chunking
- this is the best place to use semantic similarity to merge adjacent chunks

## Semantic Merge Modes

There are currently two semantic merge layers.

### Standalone local semantic merge

Triggered by:

- direct `SmartChunker.chunk(document)`
- `SmartChunkerConfig.semanticMergeEnabled=true`

Characteristics:

- model-free
- heuristic similarity
- good default for SDK-local use

### Ingest-time embedding semantic merge

Triggered by:

- `LightRag` / `IndexingPipeline`
- SmartChunker as the effective chunker
- `enableEmbeddingSemanticMerge(true)`

Characteristics:

- uses the configured `EmbeddingModel`
- starts from `SmartChunker.chunkStructural(...)`
- performs pairwise adjacent merge only
- respects `section_path` and `content_type` boundaries
- intended for full ingest pipeline, not standalone chunker use

Recommendation:

- for ordinary `GENERIC` documents inside full ingest, prefer embedding semantic merge
- for `LAW` and `QA`, keep structure/type boundaries as the primary mechanism

## Real Verification Evidence

All numbers below come from fresh manual test runs against real public URLs.

### English law PDF

Source:

- US Constitution PDF

Observed:

- `parsed_blocks=532`
- `smart_chunks=530`

Conclusion:

- legal section retention worked
- chunk metadata preserved section path

### Chinese law PDF

Source:

- `中华人民共和国民法典`

Observed:

- `parsed_blocks=2412`
- `smart_chunks=2412`

Conclusion:

- strong legal structure retention
- output correctly kept chapter/catalog style boundaries

### English QA PDF

Observed:

- `parsed_blocks=82`
- `smart_chunks=52`

Conclusion:

- question and answer were combined into the same chunk

### Chinese QA PDF

Observed:

- `parsed_blocks=28`
- `smart_chunks=24`

Conclusion:

- `问：... 答：...` stayed together in one chunk

### Book PDF

Observed:

- `parsed_blocks=179`
- `smart_chunks=183`

Conclusion:

- usable
- but section quality depends on source heading quality

### Generic dense prose, standalone SmartChunker path

Observed:

- `parsed_blocks=179`
- `baseline_chunks=308`
- `semantic_chunks=269`

Conclusion:

- for ordinary dense prose, semantic merge reduces chunk count even when no strong structure exists
- this is the expected `GENERIC` behavior

### Generic dense prose, embedding-driven merge path

Observed:

- `parsed_blocks=179`
- `baseline_chunks=308`
- `prepared_chunks=307`

Conclusion:

- ingest-time embedding merge path is active
- merge happens through the real embedding-based preparation pipeline, not only through standalone local heuristics

## Operational Guidance

Use this default mapping unless there is a strong business override:

1. legal material: `LAW + SMART`
2. FAQ / interview / Q&A: `QA + SMART`
3. books / manuals / reports with headings: `BOOK + SMART`
4. ordinary text with weak structure: `GENERIC + SMART`
5. if the application uses full LightRag ingest with embeddings and the document is ordinary prose: enable embedding semantic merge
6. if the user already knows a stable manual split rule: use `REGEX`

## Current Limits

- `GENERIC` intentionally does not preserve MinerU block structure today
- `BOOK` still has room for stronger chapter/title recognition
- ingest-time embedding merge is adjacent-pair merge, not arbitrary graph-style regrouping
- standalone `SmartChunker.chunk(...)` still uses local heuristics rather than the ingest-time embedding path

## Implementation References

- `lightrag-core/src/main/java/io/github/lightragjava/indexing/ChunkingOrchestrator.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunker.java`
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/SmartChunkerEmbeddingPreparationStrategy.java`
- `lightrag-core/src/test/java/dev/io/github/lightragjava/indexing/PublicDocumentSmartChunkerRealApiManualTest.java`
- `lightrag-core/src/test/java/io/github/lightragjava/indexing/EmbeddingSemanticMergeRealApiManualTest.java`
