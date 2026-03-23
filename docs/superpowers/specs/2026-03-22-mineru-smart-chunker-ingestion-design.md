# MinerU + SmartChunker Ingestion Design

## Background

The current Java LightRAG SDK already has three chunking layers:

- fixed-window chunking as the baseline fallback
- `SmartChunker` for sentence-aware and markdown-like structural chunking
- ingest-only embedding-driven semantic merge for SmartChunker output

This is enough for plain text and markdown-style input, but it does not reach the parsing quality expected for complex documents such as PDF, Word, PowerPoint, HTML, scans, legal texts, books, and FAQ-style corpora.

The main gap is architectural:

- complex document parsing and OCR are still outside the SDK
- `SmartChunker` is doing regex-based structure recovery from text, not consuming a high-quality structured parse
- the UI is at risk of exposing technical knobs such as parsing mode and chunking internals instead of business-level choices

The target direction is:

- MinerU handles high-quality document parsing
- Tika provides a non-image fallback when MinerU is unavailable
- SmartChunker remains the primary intelligent chunking strategy
- regex chunking is added as a manual strategy for highly regular corpora
- the user only chooses high-level business intent such as document type and chunk granularity

## Goals

- Support a unified ingest architecture for:
  - `txt`
  - `md`
  - `markdown`
  - `pdf`
  - `doc`
  - `docx`
  - `ppt`
  - `pptx`
  - `html`
  - images
- Integrate MinerU in two deployment modes:
  - official API
  - self-hosted service
- Automatically fall back when MinerU is unavailable:
  - for document-like formats, fall back to Tika
  - for images, fail instead of pretending parsing succeeded
- Keep parsing and chunking as separate layers
- Add a manual regex chunking strategy alongside SmartChunker
- Support document templates for:
  - `generic`
  - `law`
  - `book`
  - `qa`
- Support parent/child chunk organization for retrieval
- Keep `LightRag` indexing and query pipelines largely unchanged above the parsing/chunk orchestration boundary
- Keep the default UI simple:
  - document type
  - chunk granularity
  - advanced overrides only when needed

## Non-Goals

- Do not make MinerU a hard runtime dependency for plain-text workflows
- Do not expose parsing mode as a first-class user-facing option
- Do not force users to choose between Smart, Regex, and Fixed chunking in the default UI
- Do not make `SmartChunker` directly parse raw PDF/Word/PowerPoint binaries
- Do not implement image OCR fallback outside MinerU in this phase

## Design Summary

The ingest stack will be divided into four layers:

1. parsing layer
2. document typing layer
3. chunking layer
4. retrieval organization layer

The architecture is:

```text
raw file
  -> DocumentParsingOrchestrator
  -> ParsedDocument
  -> DocumentTypeResolver
  -> ChunkingOrchestrator
  -> ParentChildChunkBuilder (optional)
  -> LightRag indexing pipeline
```

Each layer has a single responsibility:

- parsing decides how raw bytes become structured textual content
- document typing decides which semantic template best matches the corpus
- chunking decides how to turn parsed content into chunks
- retrieval organization decides whether chunks stay single-level or become parent/child

## Architecture

### 1. Parsing Layer

Introduce a unified parsing boundary:

```java
interface DocumentParsingProvider {
    ParsedDocument parse(RawDocumentSource source);
}
```

Top-level orchestrator:

```java
final class DocumentParsingOrchestrator {
    ParsedDocument parse(RawDocumentSource source);
}
```

Providers:

- `PlainTextParsingProvider`
  - supports `txt`, `md`, `markdown`
  - directly decodes UTF-8 text
- `MineruParsingProvider`
  - uses either:
    - `MineruApiClient`
    - `MineruSelfHostedClient`
  - produces structured output from MinerU
- `TikaFallbackParsingProvider`
  - fallback for:
    - `pdf`
    - `doc`
    - `docx`
    - `ppt`
    - `pptx`
    - `html`
  - does not apply to images

MinerU routing policy:

- plain text files never call MinerU
- complex document files try MinerU first
- when MinerU succeeds:
  - use MinerU structured result
- when MinerU fails:
  - use Tika fallback for document-like formats
  - fail for images

### 2. Parsed Document Model

Introduce a normalized ingest model:

```java
record ParsedDocument(
    String documentId,
    String title,
    String plainText,
    List<ParsedBlock> blocks,
    Map<String, String> metadata
) {}
```

```java
record ParsedBlock(
    String blockId,
    String blockType,
    String text,
    String sectionPath,
    List<String> sectionHierarchy,
    Integer pageNo,
    String bbox,
    Integer readingOrder,
    Map<String, String> metadata
) {}
```

Important principles:

- `plainText` is always available for fallback chunkers
- `blocks` may be empty for plain-text mode
- page and coordinate metadata are optional
- all parsing backends normalize into one model
- `sectionPath` is the rendered human-readable path
- `sectionHierarchy` is the canonical structured path used by:
  - SmartChunker template logic
  - semantic merge boundary checks
  - parent/child grouping

### 3. MinerU Integration

Introduce a MinerU-specific adapter:

```java
final class MineruDocumentAdapter {
    ParsedDocument adapt(MineruParseResult result, RawDocumentSource source);
}
```

MinerU result sources:

- `content_list.json` as the primary structured source
- `full.md` as the textual fallback inside MinerU mode

Priority:

1. use structured block output when valid
2. otherwise degrade to MinerU markdown output
3. if MinerU itself is unavailable, use Tika fallback

Supported deployment modes:

- official MinerU API
- self-hosted MinerU service

The upper layers must not care which mode was used. They only receive:

- `parse_mode=mineru|tika|plain`
- `parse_backend=mineru_api|mineru_self_hosted|tika|plain`
- `parse_error_reason` when a downgrade occurred

### 4. Document Typing Layer

Introduce semantic document typing independent of file format:

```java
enum DocumentType {
    GENERIC,
    LAW,
    BOOK,
    QA
}
```

```java
final class DocumentTypeResolver {
    DocumentType resolve(ParsedDocument document, DocumentTypeHint hint);
}
```

Document type semantics:

- `GENERIC`
  - default prose, reports, notes
- `LAW`
  - legal chapters, articles, clauses, enumerations
- `BOOK`
  - chapters, sections, subsections, figure/table captions
- `QA`
  - `Q/A`, `问/答`, FAQ-style documents

Resolution policy:

- if the user explicitly chooses a document type, use it
- if the user chooses auto, infer using filename, parsed structure, and content patterns

### 5. Chunking Layer

Introduce an orchestration boundary above concrete chunkers:

```java
final class ChunkingOrchestrator {
    ChunkingResult chunk(ParsedDocument document, DocumentType documentType, ChunkingProfile profile);
}
```

Modes:

```java
enum ChunkingMode {
    SMART,
    REGEX,
    FIXED
}
```

Concrete chunkers:

- `SmartChunker`
- `RegexChunker`
- `FixedWindowChunker`

Important design rule:

- `SmartChunker` remains the primary intelligent chunking strategy
- `RegexChunker` is a parallel manual strategy, not a mode hidden inside SmartChunker
- `FixedWindowChunker` remains the ultimate fallback

Introduce a normalized chunking output contract:

```java
record ChunkingResult(
    List<ChunkDraft> chunks,
    ChunkingMode effectiveMode,
    boolean downgradedToFixed,
    String fallbackReason
) {}
```

Chunking fallback policy:

- `SMART`
  - first attempt SmartChunker's type-specific internal fallback strategies
    - paragraph-specific fallback
    - list-specific fallback
    - table-specific fallback
  - if those Smart-internal fallbacks still cannot produce valid chunks, downgrade to `FIXED`
- `REGEX`
  - if regex rules produce at least one valid boundary set, return Regex output
  - if regex rules are invalid or produce unusable output, fall back to `FIXED`
- `FIXED`
  - never downgrades further

This fallback chain is chunking-stage fallback and is distinct from parsing-stage fallback.

Therefore:

- SmartChunker's type-specific fallback is the first fallback inside `SMART`
- `FixedWindowChunker` is the final cross-mode chunking fallback

### 6. SmartChunker V4 Responsibilities

SmartChunker V4 should evolve from a markdown-regex-oriented chunker into a general structured chunker that can consume parsed blocks.

New responsibilities:

- accept normalized structured blocks, not only regex-derived blocks
- improve sentence splitting for Chinese and mixed-language text
- improve list continuation and nested list handling
- improve empty-title and empty-section-path resilience
- use type-specific fallback strategies instead of one universal fixed-window fallback
- preserve richer block lineage metadata

SmartChunker must support document-type-aware behavior:

- `LAW`
  - do not merge across articles by default
  - preserve article path metadata
- `BOOK`
  - preserve chapter/section hierarchy
  - isolate figure/table captions when possible
- `QA`
  - keep question and answer strongly associated
  - avoid splitting a question away from its answer

Type information enters SmartChunker through `ChunkingOrchestrator`:

- `DocumentTypeResolver` produces the effective `DocumentType`
- `ChunkingOrchestrator` passes that type into the Smart chunking path
- SmartChunker template behavior is selected from the resolved type, not re-inferred internally

Semantic merge applicability:

- semantic merge is defined only for `SMART`
- `REGEX` and `FIXED` do not run semantic merge in this design phase
- if advanced UI exposes semantic merge controls while effective mode is not `SMART`, those controls are ignored and rendered as inactive in result diagnostics

### 7. RegexChunker

Introduce a dedicated manual chunking strategy:

```java
record RegexChunkRule(
    String pattern,
    boolean keepMatchAtChunkStart,
    Integer sectionNameGroup
) {}
```

```java
record RegexChunkerConfig(
    List<RegexChunkRule> rules,
    int maxTokens,
    int overlapTokens
) {}
```

Use cases:

- legal corpora with explicit article markers
- FAQ datasets
- API docs
- logs
- enterprise templates with stable delimiters

Regex chunking is especially useful when:

- the user knows the corpus structure well
- automatic inference is less trustworthy than explicit rules

### 8. Parent/Child Retrieval Organization

Introduce a retrieval organization step after chunking:

```java
final class ParentChildChunkBuilder {
    ParentChildChunkSet build(ChunkingResult result, ParentChildProfile profile);
}
```

Role split:

- child chunks:
  - smaller
  - used for embedding and retrieval
- parent chunks:
  - larger
  - returned as final context to the LLM

Query flow:

```text
query
  -> child retrieval
  -> child hit
  -> parent lookup
  -> parent context returned
```

Recommended default behavior:

- QA documents benefit strongly from parent/child
- law and book documents should use parent boundaries aligned to semantic sections
- parent/child is supported for all chunking modes, but lineage richness differs by mode:
  - `SMART`
    - full lineage expected
  - `REGEX`
    - section and rule-derived lineage required
  - `FIXED`
    - minimal lineage acceptable, parent grouping falls back to positional grouping

`ChunkDraft` is required to preserve enough lineage for parent grouping:

- document type
- rendered section path
- canonical section hierarchy
- source block ids
- page metadata when available

This means `ParentChildChunkBuilder` consumes the enriched `ChunkingResult`, not a plain list of anonymous text chunks.

Minimum lineage contract by mode:

- `SMART`
  - must emit:
    - document type
    - section path
    - section hierarchy
    - source block ids
- `REGEX`
  - must emit:
    - document type
    - rule-derived section path when available
    - match lineage or synthetic section lineage
- `FIXED`
  - must emit:
    - document type
    - chunk order
    - synthetic parent grouping markers when semantic hierarchy is unavailable

## UI Configuration Model

### User-Facing Defaults

The default UI must stay simple. The user should not be forced to understand parsing backends or technical chunking classes.

Default fields:

1. `documentType`
   - `auto`
   - `generic`
   - `law`
   - `book`
   - `qa`
2. `chunkGranularity`
   - `fine`
   - `medium`
   - `coarse`

Optional advanced section:

- chunking strategy override
  - `auto`
  - `smart`
  - `regex`
  - `fixed`
- regex rules
- semantic merge enable/disable
- semantic merge threshold
- token-level advanced overrides
- parent/child enable/disable

### System-Auto Fields

These fields are internal and should normally not be selected by the user:

- parsing mode
- parsing backend
- fallback path
- document type inference output
- concrete chunker selection
- parent/child default policy

### Granularity Mapping Contract

`chunkGranularity` is a user-friendly profile selector that maps to `ChunkingProfile` defaults.

Baseline mapping:

- `fine`
  - smaller target chunk size
  - smaller parent chunk size
  - parent/child enabled by default for `qa`
- `medium`
  - balanced target chunk size
  - balanced parent chunk size
  - default system choice
- `coarse`
  - larger target chunk size
  - larger parent chunk size
  - weaker overlap by default

Example default profile mapping:

```java
enum ChunkingStrategyOverride {
    AUTO,
    SMART,
    REGEX,
    FIXED
}

record ChunkingProfile(
    ChunkingStrategyOverride strategyOverride,
    int targetTokens,
    int maxTokens,
    int overlapTokens,
    boolean semanticMergeEnabled,
    double semanticMergeThreshold,
    boolean parentChildEnabled,
    int parentTargetTokens,
    int childTargetTokens
) {}
```

Resolution rules:

- `strategyOverride=AUTO`
  - `ChunkingOrchestrator` derives the initial strategy using a deterministic priority order:
    1. if enabled regex rules are present, choose `REGEX`
    2. otherwise choose `SMART`
  - `DocumentType` does not choose between `SMART` and `REGEX`; it selects the template behavior inside the chosen strategy
  - parsing quality does not choose the top-level mode:
    - if `SMART` is chosen and structured blocks are available, use the structured Smart path
    - if `SMART` is chosen and only `plainText` is available, use the text Smart path
    - if the chosen strategy fails at runtime, apply the chunking fallback chain and the final effective mode may become `FIXED`
- explicit override wins over document-type defaults
- effective mode is always one of:
  - `SMART`
  - `REGEX`
  - `FIXED`

`AUTO` decision table:

| `strategyOverride` | enabled regex rules present | initial selected mode | notes |
| --- | --- | --- | --- |
| `SMART` | yes/no | `SMART` | explicit override wins |
| `REGEX` | yes | `REGEX` | explicit override wins |
| `REGEX` | no | `REGEX` | invalid config, then runtime fallback to `FIXED` |
| `FIXED` | yes/no | `FIXED` | explicit override wins |
| `AUTO` | yes | `REGEX` | regex presence is treated as explicit manual intent |
| `AUTO` | no | `SMART` | default intelligent path |

This contract intentionally means:

- advanced users who configure regex rules but keep `AUTO` will still run `REGEX`
- document type still controls template behavior, section boundaries, and semantic merge policy
- `SMART` remains the default auto-selected strategy when no regex rules are provided

Suggested default mapping:

- `fine`
  - `targetTokens=300`
  - `maxTokens=500`
  - `overlapTokens=50`
  - `semanticMergeThreshold=0.85`
  - `parentChildEnabled=true` for `qa`, optional otherwise
  - `parentTargetTokens=900`
  - `childTargetTokens=220`
- `medium`
  - `targetTokens=800`
  - `maxTokens=1200`
  - `overlapTokens=100`
  - `semanticMergeThreshold=0.80`
  - `parentChildEnabled=false` by default except `qa`
  - `parentTargetTokens=1800`
  - `childTargetTokens=480`
- `coarse`
  - `targetTokens=1500`
  - `maxTokens=2200`
  - `overlapTokens=150`
  - `semanticMergeThreshold=0.75`
  - `parentChildEnabled=false` by default
  - `parentTargetTokens=2800`
  - `childTargetTokens=900`

Semantic merge enablement defaults:

- `fine`
  - enabled for `SMART`
- `medium`
  - enabled for `SMART`
- `coarse`
  - enabled for `SMART` only when document type is `generic` or `book`

### Automatic Strategy Defaults

When the user selects a document type, the system should derive the recommended chunking strategy automatically:

- `law`
  - default to `SMART` with law template
- `book`
  - default to `SMART` with book template
- `qa`
  - default to `SMART` with QA template
- `generic`
  - default to `SMART`

Only advanced users should override this to:

- `REGEX`
- `FIXED`

Semantic merge defaults by effective mode:

- `SMART`
  - may enable semantic merge
- `REGEX`
  - semantic merge disabled
- `FIXED`
  - semantic merge disabled

## Metadata Model

Recommended normalized metadata:

- `parse_mode`
- `parse_backend`
- `parse_error_reason`
- `doc_type`
- `source_file`
- `source_type`
- `page_no`
- `bbox`
- `reading_order`
- `mineru_block_id`
- `mineru_block_type`
- `smart_chunker.section_path`
- `smart_chunker.section_hierarchy`
- `smart_chunker.content_type`
- `smart_chunker.source_block_ids`
- `parent_chunk_id`
- `child_chunk_id`

## Failure and Fallback Policy

### Complex document formats

For `pdf`, `doc`, `docx`, `ppt`, `pptx`, and `html`:

1. try MinerU
2. if MinerU fails, use Tika fallback
3. if Tika fails, fail ingest

### Images

For images:

1. try MinerU
2. if MinerU fails, fail ingest

Images do not use Tika fallback in this phase.

### Plain text

For `txt`, `md`, and `markdown`:

- do not call MinerU
- do not call Tika
- go directly to plain-text parsing

## Testing Strategy

The design requires tests for:

- MinerU API parsing success
- MinerU self-hosted parsing success
- MinerU timeout/unreachable downgrade to Tika
- image MinerU failure causing ingest failure
- document type resolution:
  - generic
  - law
  - book
  - qa
- SmartChunker template behavior for law/book/qa
- regex chunking correctness
- parent/child mapping correctness
- fallback metadata correctness
- ingest semantic merge not crossing:
  - section path
  - content type
  - table boundaries

## Rollout Plan

### V1

- parsing orchestrator
- MinerU API + self-hosted integration
- Tika fallback
- normalized parsed document model
- simple chunking orchestrator
- regex chunker baseline
- complex documents in V1 chunk through:
  - `ParsedDocument.plainText`
  - existing SmartChunker text path
  - or Regex/Fixed when selected
- V1 therefore supports MinerU/Tika parsing plus text-based chunking, but does not yet consume structured MinerU blocks directly
- parent/child is not exposed in the product UI
- runtime always uses single-level chunks

### V2

- MinerU structured adapter
- SmartChunker V4 block-input support
- law/book/qa templates
- empty-title and list-continuation hardening
- parent/child is still not exposed in the product UI
- runtime still uses single-level chunks

### V3

- parent/child chunking
- page and bbox metadata propagation
- retrieval pipeline using child recall and parent expansion
- chunk preview and debug visibility in UI

## Risks

- MinerU structured output versions may drift, so adapters must be defensive
- Tika fallback quality will be materially weaker than MinerU for complex layouts
- SmartChunker must not become a monolith that mixes parsing, typing, and chunking concerns
- parent/child retrieval increases storage and indexing complexity

## Recommendation

Adopt:

- MinerU as the preferred parser for complex documents
- Tika as the non-image fallback parser
- SmartChunker as the default intelligent chunker
- RegexChunker as the manual strategy for rule-driven corpora
- fixed-window chunking as the last-resort fallback
- document-type-driven defaults in the UI

This gives the system:

- strong complex-document support
- graceful degradation
- controllable chunking for power users
- a path toward enterprise-grade retrieval quality without forcing UI users to understand internal pipeline details

## Parent/Child Optional Execution

Parent/child is a configurable execution stage, not an unconditional pipeline step.

Phased availability contract:

- V1
  - UI hides the parent/child option
  - runtime forces `parentChildEnabled=false`
- V2
  - UI still hides the parent/child option
  - runtime still forces `parentChildEnabled=false`
- V3
  - UI may expose the parent/child option
  - runtime honors the configured value

This avoids a no-op configuration surface before the feature is implemented.

- when `parentChildEnabled=true`
  - run `ParentChildChunkBuilder`
  - index child chunks for retrieval
  - preserve parent linkage for context expansion
- when `parentChildEnabled=false`
  - skip `ParentChildChunkBuilder`
  - keep a single-level chunk set
  - `LightRag` ingests the single-level chunks directly

This means the runtime path is:

```text
ChunkingOrchestrator
  -> [optional] ParentChildChunkBuilder
  -> LightRag indexing pipeline
```
