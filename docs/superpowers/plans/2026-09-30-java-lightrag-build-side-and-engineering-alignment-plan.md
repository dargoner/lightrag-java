# Java LightRAG Build-Side & Engineering Upstream Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the indexing/merge (§2), storage-contract (§4), model-layer (§7) and ops-tooling (§6) gaps between lightrag-java 0.24.0-SNAPSHOT and upstream Python LightRAG 1.5.8 as catalogued in `docs/superpowers/specs/2026-09-28-upstream-lightrag-alignment-report.md`, so that knowledge-graph quality, extraction robustness, provider configuration and offline recovery behave the same way as the Python implementation.

**Architecture:** All work lands in `lightrag-core` (plus Spring starter properties and demo config). Merge behavior is centralized into one LLM-summarization collaborator (`DescriptionSummarizer`) and one set of pure helpers (`DescriptionFragments`, `SourceIdLimits`, `FilePathLimits`, `TextSanitizer`) called from the two existing merge layers — `GraphAssembler` (in-memory, per document batch) and the storage-level merge in `IndexingPipeline.saveGraph` / `GraphMaterializationPipeline.saveGraph`. New model-layer capability (request options, response metadata, retries, per-role concurrency) is added behind `default` methods and decorators so the 21 existing test fakes keep compiling. Configuration rides the existing `LightRagBuilder → LightRagConfig → LightRag → pipelines` chain; Spring properties are added in the starter mirroring `lightrag.chat.*`.

**Tech Stack:** Java 17, Gradle (multi-module), JUnit 5, AssertJ, Jackson, OkHttp (already present), Spring Boot starter/demo. No new third-party dependencies.

**Upstream reference:** `D:\ai-code\LightRAG` @ `453dce83d` (declares `1.5.8`). Line numbers below refer to that checkout. Upstream `git log -S` dating is included so each task can be classified as "upstream changed later" vs "old debt" (see memory: `feedback-upstream-gap-dating`).

**Companion plan:** `docs/superpowers/plans/2026-09-28-java-lightrag-query-upstream-parity-plan.md` (query side, Tasks 1–15).

---

## Scope boundary between the two plans

**Owned by the query plan — do not re-implement here:** query defaults 40/20, query validation, `fail_response`, `user_prompt_prefix`, KG→chunk selection quotas (`relatedChunkNumber`, WEIGHT/VECTOR pickers), round-robin chunk/entity/relation merge, `(degree, weight)` relation ranking, rerank contract (`topN`, score validation, `RerankFailureMode`), the `TokenCounter` contract and query-side budgeting/truncation, `[n]` reference ids + `content_headings` **rendering**, `responseTime`/`llmGenerated`, answer-cache key policy.

**Owned by this plan:** merge description summarization, merge type/description/weight semantics, source-id and file-path caps, extraction record caps + validator hook + section-context breadcrumb + LaTeX repair, chat request options, response metadata (`finish_reason`/`usage`) + truncation markers + cache truncation guard, retry/backoff, per-role LLM concurrency budget, production rerank binding, graph read surface (labels/BFS), embedding-space refusal + Noop vector route, DocStatus query surface, offline `rebuild-vdb` CLI.

**Deliberately out of both plans (stated, tracked):** chunking-strategy parity (F/R/V/P/Smart/Regex) and chunk-id format `{doc_id}-chunk-{order:03d}`; parser platform / multimodal / sidecar write-back; server-layer parity (auth, admission, `/health`, NDJSON); provider breadth beyond one OpenAI-compatible binding; upstream `entity_chunks`/`relation_chunks` tracking **store** (Java's per-chunk snapshot store plays that role); entity-level `file_path` column (see Decision 4); `kg_extraction_validator`'s multimodal interaction (no VLM in Java).

**Shared artifact ownership rule:** `io.github.lightrag.model.TokenCounter` + `HeuristicTokenCounter` are specified by the query plan Task 10. If that plan has not landed when Task 1 here is implemented, create them exactly as specified there; whichever plan lands first owns them, the other reuses them. Never define them twice.

---

## Decisions locked with the requester (2026-09-30)

1. **Summarization defaults follow upstream exactly:** `forceLlmSummaryOnMerge=8`, `summaryMaxTokens=1200`, `summaryContextSize=12000`, `summaryLengthRecommended=600`, language = existing `knowledge-extractor` language. Below the thresholds there is **no LLM call** — fragments are `<SEP>`-joined and stored, which is upstream's storage format. This changes user-visible description text (today: first non-empty description wins). Accepted.
2. **Weight semantics adopt upstream:** extracted relation weight becomes a constant `1.0` (the JSON path returns `weight=1.0` — `operate.py:1084`), the merge accumulates new-evidence weight on top of the stored scalar, and an evidence floor `max(weight, distinct evidence chunks)` repairs legacy rows. Java's current `[0,1]` clamp (`KnowledgeExtractor.java:609-616,637-639`) is retired. Accepted.
3. **Caps are 200 source ids (KEEP by default, FIFO selectable) and 75 file paths (same method).** Source-id caps are only enabled when the storage provider exposes the per-chunk attribution index (`DocumentGraphSnapshotStore`), because Java's incremental deletion decides "record survives" from the graph record's source list; with caps active, the survival test must consult the attribution index instead (Task 4). The capability is resolved per provider instance (never per class — `StorageCoordinator` wrappers of the same class can differ); a provider without the store cannot even build a `LightRag` (`LightRagBuilder` requires it), so the runtime probe auto-disables caps for directly constructed pipelines and emits at most one WARN per adapter class.
4. **Entity `file_path` is not implemented** (Java `GraphStore.EntityRecord` has no such column; adding it touches 4 backends, snapshots, and the graph APIs, and no Java reader consumes it — query citations use chunk metadata). Relation `file_path` **is** implemented (column already exists: `PostgresSchemaManager.java:179`, `Neo4j` `relation.file_path`). This is a documented divergence.
5. **Model layer stops at one binding.** Request options, retries, response metadata and per-role concurrency land on the existing OpenAI-compatible binding; cohere/jina/aliyun/ollama/gemini bindings stay out of scope (follow-up plan).
6. **Concurrency defaults:** `maxAsyncLlm=4`, `embeddingMaxAsync=8`, `maxParallelInsert` 2 → 3 (`MAX_ASYNC`/`EMBEDDING_FUNC_MAX_ASYNC`/`MAX_PARALLEL_INSERT`, `constants.py:96,97,715`; the Java default today is still 2, `LightRagBuilder.java:54`). Per-role priority queues (upstream `priority_limit_async_func_call`) are **not** ported: Java's pipelines are blocking, so a fair per-role semaphore is the honest equivalent.
7. **Ops CLI shape follows the existing RAGAS precedent** (`JavaExec` task + `main` in `evaluation/`-style package, `lightrag-core/build.gradle.kts:52-64`): `RebuildVectorIndexCommand` parses `--workspace`, `--mode check|rebuild`, `--storage-profile`, `--force` (one contract, see Task 18). Documented divergences from upstream: upstream is an interactive menu (`[1] check / [2] graph VDB / [3] chunks VDB / [4] all`, `rebuild_vdb.py:1117-1131`) with an interactive `confirm_server_stopped()` gate (`:1113`) and env-only config — the Java CLI is non-interactive for automation and `--mode rebuild` always covers all three namespaces (the upstream `[2]/[3]` per-namespace split is not carried over). Refuses to run without `--force` when the vector marker is missing.

---

## Platform calibration (aiplatform, verified 2026-10-01, revised after Codex review round 1)

The plan was re-checked against the only production consumer of this SDK: `D:\ai-code\aiplatform` (Maven backend, module `backend/aide-kno`). **The platform is mid-upgrade onto this SDK's main line** (uncommitted working tree): `lightrag.version` is `0.24.0-SNAPSHOT` (`backend/pom.xml:47`), **both forked SDK classes are deleted** (`D:/ai-code/aiplatform/backend/aide-kno/src/main/java/io/github/lightrag/api/LightRag.java` and `.../indexing/GraphMaterializationPipeline.java` — staged deletions), no positional `new IndexingPipeline(...)`/`new GraphMaterializationPipeline(...)` construction remains, and the main-line APIs are adopted (`CancellationCheckpoint` in `KnowledgeGraphServiceImpl:2083-2089`, `builder.maxConcurrentDocumentTasks(...)` from `LightRagProperties` in `LightRagRuntimeFactory:277-283`). Every task below lands on the platform atomically with that upgrade. Load-bearing facts (verified against the working tree, not the last commit):

1. **Per-KB runtimes.** `LightRagRuntimeFactory` builds one `LightRag` per knowledge base via `LightRagBuilder` (`:237-319`), LRU-cached (`LightRagRuntimeRegistry:33-54`); storage = `PostgresMilvusNeo4jStorageProvider` (platform DataSource + `WorkspaceScopedNeo4jGraphStore` + Milvus); writes are fenced by a Redis-distributed `RedissonLightRagStorageLockManager` (`LightRagRuntimeFactory:451-458`) because dev runs several app instances against shared stores.
2. **The fork is retired in the working tree; constructor compatibility is now an SDK-API convention, not a platform compile requirement.** The two shadowing classes are staged-deleted, so ingest *and* rebuilds (`materializeDocumentGraph`) both run in this repository's pipelines again — merge-semantics changes reach both paths from the first commit. This plan still delivers new collaborators through added constructors/overloads (Decision 8) to avoid breaking SDK API consumers, but nothing in aiplatform forces it any more.
3. **Platform models are hand-written adapters** (`LightRagPlatformChatModel`, `LightRagPlatformRerankModel`, `EmbeddingClient`; direct HTTP to provider endpoints). Retries/backoff/timeouts already live there (chat 3 attempts + `Retry-After`, embedding 2), `temperature`/`max_tokens` are already sent (`LightRagPlatformChatModel:216-229`), `response_format` is not, and only `choices[0].message.content` is parsed — `finish_reason`/`usage` are dropped (`:1139-1165`). Task 12's retry sits inside `model/openai/*` only — no double retry for platform adapters; Tasks 10/11 need an adapter-side upgrade to take effect.
4. **Ingest = platform-side chunking + `submitIngestChunks`; rebuilds also run in this repository now** (fact 2). Platform chunk metadata keys are `smart_chunker.section_path`/`sectionPath`/`section_path` (joined with `" > "`, `ParagraphChunkingStrategy:238,315-321`), `headingPath` (JSON array), `platformDocumentId`, `pageStart/pageEnd`, … — **no `file_path`** (Task 5's platform note; the SDK side reads `file_path`/`filePath` from metadata with an `unknown_source` fallback, `HybridVectorPayloads:37-43`).
5. **Deletion has a platform-side twin**: `LightRagDeletionAdapter.filterEntity/filterRelation` (`:572-611`) repeats the "record survives iff its own `sourceChunkIds` still contains a retained chunk" rule and rebuilds `RelationRecord` without `filePath`.
6. Platform already owns: rerank (SDK rerank explicitly disabled, `LightRagRetrievalEngine:471-500`), graph browsing (entity search/pagination/bounded BFS over `allEntities/allRelations` — `KnowledgeGraphServiceImpl:472-490,3481-3507` — plus a SQL keyset export reader), document status/dedup (`Document.docStatus` + `km_document*` tables), a model-stage concurrency gate (`WikiModelStageConcurrencyGate:26-41`, blocks in `:73-103`) and per-KB rebuild endpoints (`DocumentController:390-402`). Tasks 14/15/16/17/18 are therefore **SDK-only deliverables — do not justify them by platform need**; the platform layers above are candidate adopters, not dependencies.

### Per-task verdicts

| Task | Verdict for aiplatform |
|---|---|
| 1 Summarization | Effective on ingest **and** on rebuilds (both now run in this repo's pipelines, fact 2); platform has a real summary model (`LightRagRuntimeFactory:252`) → expect new LLM cost/latency once fragments ≥ thresholds; summarizer failure policy = propagate/fail the document, like upstream (see Task 1); `<SEP>` joins leak unsplit into platform graph APIs/UI. |
| 2 Type vote | Effective on ingest; platform displays `type`, no first-wins dependency. |
| 3 Weight semantics | Effective on ingest; platform shows `weight` but ranks by degree/`sourceChunkIds.size`/name → display values change (may exceed 1). |
| 4 Source caps | No auto-disable (platform provider exposes the snapshot store). **Cross-repo**: platform deletion twin needs the same attribution fix; capped `sourceChunkIds` shrink what platform APIs page/sort by. |
| 5 `file_path` | Inert until the platform adds `file_path` to chunk metadata and stops dropping `filePath` in the deletion rewrite. **Verify before enabling:** grep the platform chunk-dispatch metadata writers for `file_path`/`filePath` at upgrade time (`KnowledgeChunkDispatcher:361` already reads `sectionPath`/`section_path` variants, so the write side is the thing to check) — do not enable the cap while relations would all collapse to `unknown_source`. |
| 6 Record caps | Effective (prompt-level, jar extractor). |
| 7 Validator hook | SDK-only (no platform consumer today). |
| 8 Section context | Effective — platform writes `smart_chunker.section_path` in chunk metadata. |
| 9 LaTeX repair | Effective (post-parse, model-agnostic). |
| 10 Request options | SDK-provided models only; platform adapter must forward options (`response_format` is the new win). |
| 11 Response metadata | Guard inert for platform models until the adapter parses `finish_reason`/`usage`. |
| 12 Retries | `model/openai/*` only — no double retry with platform adapters. |
| 13 Concurrency budget | Wraps all `ChatModel`s including the platform's; the platform holds `WikiModelStageConcurrencyGate` permits **outside** the model call (`WikiKnowledgeIndexWriter:4090-4091` acquires, the model runs inside at `:4092`) → gate-then-SDK-slot nesting is possible; the platform must set `maxAsyncLlm`/`embeddingMaxAsync` explicitly and never acquire a gate while holding an SDK slot (see Task 13). |
| 14 Rerank binding | SDK-only — the platform disables SDK rerank on the current head (`LightRagRetrievalEngine:471-500`) and keeps its own rerank path. |
| 15 Graph read surface | SDK-only today; the platform browse layer is the candidate adopter (`KnowledgeGraphServiceImpl:472-490,3481-3507`). |
| 16 Embedding space / Noop | SDK-only. |
| 17 DocStatus query | SDK-only; the platform tracks `Document.docStatus` itself. |
| 18 `rebuild-vdb` CLI | SDK-only (platform rebuild endpoint `DocumentController:390-402` + transfer bundles). |

### Platform rollout checklist (on the 0.24.0 upgrade)

- [x] `lightrag.version` = `0.24.0-SNAPSHOT` and **the two fork files are deleted** — already done in the platform working tree (calibration facts 1-2). Remaining: adapt `LightRagRuntimeFactory` to `WorkspaceConcurrencyMode`/`maxConcurrentDocumentTasks` and the typed `CancellationCheckpoint` (replacing the `Runnable` overloads) while keeping the per-KB concurrency 3 semantics.
- [ ] Set explicitly: `maxAsyncLlm`, `embeddingMaxAsync`, summarization thresholds, `maxSourceIdsPerEntity/Relation` + method, `maxFilePaths`, extraction record caps.
- [ ] Apply the attribution-aware survival filter in `LightRagDeletionAdapter` (mirror of Task 4) **before** enabling caps in production; keep `filePath` in `filterRelation`.
- [ ] **HARD GATE — do not enable the relation `file_path` cap (`maxFilePaths` / Task 5) in production until all three hold:** (a) the platform chunk-dispatch metadata writers actually emit `file_path`/`filePath` (verified absent today: `LightRagChunkDocumentMapper:161-192` writes no such key and no chunking strategy under `service/chunking` contains one), (b) `LightRagDeletionAdapter.filterRelation` preserves `filePath` across rewrites (it currently drops it, `:572-611`), (c) an ingest-then-inspect test shows real paths instead of `unknown_source`. Until then the cap truncates a list that only ever holds placeholder paths.
- [ ] Platform adapter acceptance tests, one per SDK task (these are adapter gates, not SDK tests): **Task 10** — a platform-adapter test asserting the outgoing HTTP payload carries `temperature`/`max_tokens`/`top_p`/`response_format` when `ChatRequestOptions` are set (today options are ignored, `LightRagPlatformChatModel:216-229`); **Task 11** — a test asserting a `finish_reason="length"` response surfaces `finishReason` and is skipped by the answer cache, and that `usage` is populated (today both are dropped while parsing `:1139-1165`); **Task 13** — the concurrency stress test from Task 13 run against the real gate: concurrent wiki map/reduce/page + ingest, no call waiting beyond the bounded window.
- [ ] Parse `finish_reason`/`usage` in `LightRagPlatformChatModel` and forward `ChatRequestOptions` if Tasks 10/11 are to take effect (the adapter already sends `temperature`/`max_tokens`, `:216-229`, but drops `finish_reason`/`usage`, `:1139-1165`).
- [ ] Concurrency nesting rule: never acquire a `WikiModelStageConcurrencyGate` while holding an SDK concurrency slot; acquire the gate first, then call the model (Task 13's safe rule; today's platform code already nests acquire → model call, `WikiKnowledgeIndexWriter:4090-4091` → `:4092`).
- [ ] Add a platform regression test on the real call path (`LightRagRetrievalEngine`) for short/blank queries once query-plan Task 3's fail-response behavior lands: `@NotBlank` on `RetrievalSearchRequest:13-15` maps to 400, but a raw SDK `IllegalArgumentException` currently falls into the generic 500 mapper (`GlobalExceptionHandler:41-70`) — decide which surface the platform wants.
- [ ] Render `<SEP>`-joined descriptions and the new weight scale in graph APIs/UI; re-check `sourceChunkIds`-size-based sorting under caps.

**Decision 8 (revised 2026-10-01 after Codex review round 1):** the fork retirement is **already executed** in the platform working tree — both classes staged-deleted, `lightrag.version` at `0.24.0-SNAPSHOT`, no positional `new IndexingPipeline(...)`/`new GraphMaterializationPipeline(...)` remains (calibration facts 1-2). Constructor compatibility is therefore no longer a platform compile requirement; this plan still delivers new collaborators through added constructors/overloads as an **SDK API convention** (external consumers beyond aiplatform), but nothing in the platform upgrade depends on it. Because both ingest and rebuild (`materializeDocumentGraph`) already run in this repository's pipelines, every merge-semantics task below (Tasks 1-5, 13) reaches the platform from the first commit after the version bump.

---

## Behavior Changes (user-visible)

| Change | Before | After | Introduced in |
|---|---|---|---|
| Entity/relation descriptions | first non-empty description wins | `<SEP>`-joined deduped fragments; LLM map-reduce summary at ≥8 fragments or ≥1200 tokens | Task 1 |
| Entity type on merge | first non-empty wins | majority vote across batch + stored type (ties: first seen) | Task 2 |
| Extracted relation weight | clamped model `weight`/`confidence` in `[0,1]` | constant `1.0`; merge accumulates + evidence floor | Task 3 |
| `sourceChunkIds` size | unbounded | ≤200 (KEEP default; FIFO selectable), deletion survival via attribution index | Task 4 |
| Relation `filePath` | always `""` | accumulated from chunk `file_path`, deduped, ≤75 + `...truncated...(KEEP Old)` | Task 5 |
| Extraction prompt | no quantity guidance | `at most 100 total rows / 40 entity rows` (configurable) | Task 6 |
| Extraction output | used as-is | optional `kgExtractionValidator` hook per chunk | Task 7 |
| Extraction prompt | no section context | `---Section Context---` breadcrumb from chunk heading metadata (≤256 tokens) | Task 8 |
| Extracted text with LaTeX | `\f`/`\b` damage kept | JSON-escape repair + dollar-math whitespace repair | Task 9 |
| Chat request payload | `model`/`messages`/`stream` | + `temperature`/`max_tokens`/`top_p`/`response_format` from config and per-call overrides | Task 10 |
| Model responses | `String`, metadata dropped | `ChatResponse` with `finishReason`/`usage`; truncated answers never cached | Task 11 |
| Model calls | single attempt | 3 attempts, exponential backoff, transient-only classification | Task 12 |
| LLM concurrency | unbounded per pipeline thread pool | fair per-role semaphore (LLM 4 / embedding 8), `maxParallelInsert` 2→3 | Task 13 |
| Rerank | interface only | `OpenAiCompatibleRerankModel` (Cohere/Jina-compatible `/rerank`) + Spring property | Task 14 |
| Graph read | CRUD only | `labels()`, `searchLabels()`, bounded BFS `getKnowledgeGraph()` with `truncated` flag | Task 15 |
| Vector writes | dimension check only | embedding-space marker + typed refusal on model/space change; Noop vector route | Task 16 |
| Doc status | get-by-id / list-all | status filter + pagination + batch ids | Task 17 |
| Ops | none | `rebuild-vdb` CLI (`--mode check\|rebuild`) + Gradle task | Task 18 |

---

## File Structure

### New files — core main

- `lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionSummarizer.java` — upstream `_handle_entity_relation_summary` map-reduce port.
- `lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionFragments.java` — split/sanitize/dedup helpers over `<SEP>`-joined descriptions.
- `lightrag-core/src/main/java/io/github/lightrag/indexing/TextSanitizer.java` — encoding-safe sanitize + LaTeX escape repair (Task 9 extends it).
- `lightrag-core/src/main/java/io/github/lightrag/indexing/SourceIdLimits.java` — `apply_source_ids_limit` port (KEEP/FIFO).
- `lightrag-core/src/main/java/io/github/lightrag/indexing/FilePathLimits.java` — file-path cap + `...truncated...` placeholder.
- `lightrag-core/src/main/java/io/github/lightrag/indexing/SectionContextFormatter.java` — heading breadcrumb with token/char budgets.
- `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphChunkAttribution.java` — entity/relation→chunk index view over `DocumentGraphSnapshotStore` used by deletion survival (Task 4).
- `lightrag-core/src/main/java/io/github/lightrag/api/KgExtractionValidator.java` — per-chunk hook contract.
- `lightrag-core/src/main/java/io/github/lightrag/api/KnowledgeGraphView.java` — `nodes`/`edges`/`truncated`.
- `lightrag-core/src/main/java/io/github/lightrag/model/ChatRequestOptions.java` — `temperature`/`maxTokens`/`topP`/`responseFormat`.
- `lightrag-core/src/main/java/io/github/lightrag/model/ChatResponse.java` — content + `finishReason` + `usage` + `truncated()`.
- `lightrag-core/src/main/java/io/github/lightrag/model/LimitedChatModel.java`, `LimitedEmbeddingModel.java` — semaphore decorators.
- `lightrag-core/src/main/java/io/github/lightrag/model/LlmConcurrencyBudget.java` — per-role budget.
- `lightrag-core/src/main/java/io/github/lightrag/model/NoopEmbeddingModel.java` — used only with the Noop vector route.
- `lightrag-core/src/main/java/io/github/lightrag/model/openai/ModelRetrySupport.java` — retry/backoff + transient classification.
- `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleRerankModel.java` — production rerank binding.
- `lightrag-core/src/main/java/io/github/lightrag/storage/NoopVectorStore.java` — named noop store (replaces the anonymous adapter use in pipelines).
- `lightrag-core/src/main/java/io/github/lightrag/storage/EmbeddingSpaceStore.java` — marker SPI (`Marker(modelIdentity, dimensions, recordedAt)`).
- `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresEmbeddingSpaceStore.java` — single-row marker table.
- `lightrag-core/src/main/java/io/github/lightrag/exception/VectorSpaceMismatchException.java` — typed refusal.
- `lightrag-core/src/main/java/io/github/lightrag/ops/RebuildVectorIndexCommand.java` + `RebuildVectorIndexService.java` — offline VDB rebuild.

### Modified files — core main

- `indexing/GraphAssembler.java` — `ChunkExtraction` gains `filePath`; fragment accumulation for descriptions; type vote; relation weight sum; file-path accumulation.
- `indexing/IndexingPipeline.java` — summarizer + helpers wired into `saveGraph` (`:1223-1250`, helpers `:1607-1656`); extraction caps/section context/validator call sites; truncation guard; vector write short-circuit for Noop.
- `indexing/GraphMaterializationPipeline.java` — same merge helpers (`:914-980`, `:1234-1255`) and extraction call sites.
- `indexing/KnowledgeExtractor.java` — prompt slots (`:41-126`), record caps, validator hook, section context (`buildUserPrompt` `:648-662`), weight fix, LaTeX repair, token estimate via `TokenCounter`.
- `indexing/DeletionPipeline.java` — attribution-aware survival test in `removeDocumentIncrementally` (`:302-350`).
- `api/LightRag.java` — builder wiring for summarizer/budget/marker/validator/rerank; new `getKnowledgeGraph`/`searchGraphLabels`/`queryDocumentStatuses` APIs.
- `api/LightRagBuilder.java` — new options + `maxParallelInsert` default 2→3 (`:53-55`).
- `config/LightRagConfig.java` — new fields.
- `model/ChatModel.java` — `ChatRequest` gains `options`; `generateResponse` default.
- `model/CachedChatModel.java` — truncation guard + `ChatResponse` pass-through.
- `model/EmbeddingModel.java` — `default String cacheIdentity()`.
- `model/openai/OpenAiCompatibleChatModel.java`, `OpenAiCompatibleEmbeddingModel.java` — options payload, response metadata, retries, identity.
- `storage/GraphStore.java` — read-surface default methods.
- `storage/StorageProvider.java` — optional `embeddingSpaceStore()`.
- `storage/InMemoryStorageProvider.java`, `storage/postgres/PostgresStorageProvider.java` — marker store wiring; Postgres schema table.
- `storage/postgres/PostgresSchemaManager.java` — `embedding_space` table + drift check reuse.
- `api/DocumentProcessingStatus.java` — optional `metadata` passthrough for filters (Task 17).

### Modified files — starter / demo / build

- `lightrag-spring-boot-starter/.../LightRagProperties.java` + `LightRagAutoConfiguration.java` — `lightrag.rerank.*`, `lightrag.summary.*` (summary knobs finally get properties), `lightrag.extraction.max-records/max-entities`, model options (`temperature`, `max-tokens`, `top-p`, `max-attempts`), `max-async-llm`, `embedding-max-async`.
- `lightrag-core/build.gradle.kts` — `runRebuildVdb` `JavaExec` task.
- `README.md` — new builder options, CLI usage.

### Tests (create unless listed as modify)

- New: `indexing/DescriptionSummarizerTest.java`, `indexing/DescriptionFragmentsTest.java`, `indexing/SourceIdLimitsTest.java`, `indexing/FilePathLimitsTest.java`, `indexing/SectionContextFormatterTest.java`, `indexing/TextSanitizerTest.java`, `storage/GraphStoreReadSurfaceTest.java`, `storage/EmbeddingSpaceGuardTest.java`, `storage/NoopVectorStoreTest.java`, `model/openai/ModelRetrySupportTest.java`, `model/LlmConcurrencyBudgetTest.java`, `model/ChatRequestOptionsTest.java`, `model/openai/OpenAiCompatibleRerankModelTest.java`, `ops/RebuildVectorIndexServiceTest.java`, `api/LightRagGraphReadApiTest.java`, `api/LightRagDocumentStatusQueryTest.java`.
- Modify: `indexing/GraphAssemblerTest.java`, `indexing/KnowledgeExtractorTest.java`, `indexing/IndexingPipelineBatchGraphPersistenceTest.java`, `indexing/GraphMaterializationPipelineTest.java`, `indexing/DocumentIngestorTest.java` (only if chunk metadata contract shifts), `query/QueryEngineTest.java` (rerank binding wiring), `api/LightRagBuilderTest.java`, `E2ELightRagTest.java`, `storage/InMemoryGraphStoreTest.java`, `storage/postgres/PostgresGraphStoreTest.java`, `storage/InMemoryStorageProviderTest.java` (if present), `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`.

---

## Phase 1 — Merge quality (the P0 build-side items)

### Task 1: LLM description summarization on merge

Upstream summarizes entity/relation description fragments with a map-reduce loop (`operate.py:372-530`), delegating each LLM call to `_summarize_descriptions` (`operate.py:532-651`, prompt `prompt.py:297-325`). Thresholds: `summary_context_size` 12000, `summary_max_tokens` 1200, `force_llm_summary_on_merge` 8, `summary_length_recommended` 600 (`constants.py:30-36`). Upstream added the "dedup across stored and new" pass on 2026-08 (`_combine_descriptions_dedup`, `operate.py:2384-2426`); the summarizer itself has existed since the first commit (2024-10-07) — Java's `summaryModel` plumbing has never had a call site (`IndexingPipeline.java:62-63,400`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionSummarizer.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionFragments.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/TextSanitizer.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` (`:275-371`, `:373-438`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (`saveGraph` `:1223-1250`, helpers `:1607-1656`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java` (`saveGraph` `:914-980`, helpers `:1234-1255`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` (`:792-795`, `:872-874`), `LightRagBuilder.java`, `config/LightRagConfig.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/DescriptionSummarizerTest.java`, `DescriptionFragmentsTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// DescriptionSummarizerTest
@Test
void joinsFragmentsWithoutLlmBelowBothThresholds() {
    var model = new RecordingChatModel("SUMMARY");
    var summarizer = summarizer(model, /*force*/ 8, /*maxTokens*/ 1200, /*context*/ 12000);
    var result = summarizer.summarize("Entity", "trade tariff", List.of("first fragment", "second fragment", "first fragment"));
    assertThat(result.description()).isEqualTo("first fragment<SEP>second fragment");
    assertThat(result.llmUsed()).isFalse();
    assertThat(model.generateCalls()).isZero();
}

@Test
void summarizesWithLlmAtTheForceThreshold() { /* 8 distinct fragments -> one LLM call, llmUsed=true, description == "SUMMARY" */ }

@Test
void mapReducesWhenTheFragmentListExceedsTheSummaryContext() { /* 6 fragments x 5000 tokens -> >1 LLM call; final result is a summary, not a join */ }

@Test
void singleFragmentIsSanitizedAndReturnedWithoutLlm() { /* "bad\u0000text" -> "badtext", no LLM call */ }

@Test
void cacheKeyChangesWhenTheFragmentSetChanges() {
    // same name/type, different fragments INSIDE the visible window -> different prompt text,
    // hence a different content-addressed cache entry (the model call must not be served from cache)
}

@Test
void cacheKeyIsStableWhenFragmentsDifferOnlyBeyondTheTruncationWindow() {
    // summarizeWithLlm truncates the JSONL list to summaryContextSize tokens (operate.py:584-598);
    // fragments cut off are never seen by the model, so the two calls send byte-identical prompts and
    // legitimately share one cache entry -- assert on the request text handed to the model, not on the
    // caller-side fragment list
}

// GraphAssemblerTest (modify)
@Test
void accumulatesDescriptionFragmentsAcrossChunksForTheSameEntity() {
    // two extractions naming the same entity with different descriptions
    // -> Entity.description == "desc-a<SEP>desc-b"
}

// E2ELightRagTest (modify)
@Test
void ingestSummarizesEntityDescriptionsWithTheSummaryModelOnceTheForceThresholdIsReached() {
    // 9 chunks each mentioning entity "tariff schedule" with a distinct description
    // assert the stored Entity description equals the summary model output
    // and the extraction model saw no summary prompt
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.DescriptionSummarizerTest" --tests "io.github.lightrag.indexing.GraphAssemblerTest"`
Expected: FAIL — `DescriptionSummarizer` does not exist; `GraphAssembler` keeps only the first description.

- [ ] **Step 3: Implement**

`DescriptionSummarizer` (faithful port of `_handle_entity_relation_summary`):

```java
public final class DescriptionSummarizer {
    public record Summary(String description, boolean llmUsed) {}

    private static final String PROMPT_TEMPLATE = """
        ---Role---
        You are a Knowledge Graph Specialist, proficient in data curation and synthesis.
        ... (copy prompt.py:297-325 verbatim, with %s slots: description_type, name, description_list JSONL, summary_length, language)
        """;

    private final ChatModel summaryModel;
    private final TokenCounter tokenCounter;
    private final int forceLlmSummaryOnMerge;
    private final int summaryMaxTokens;
    private final int summaryContextSize;
    private final int summaryLengthRecommended;
    private final String language;

    public Summary summarize(String descriptionType, String name, List<String> descriptionList) {
        if (descriptionList.isEmpty()) return new Summary("", false);
        if (descriptionList.size() == 1) return new Summary(TextSanitizer.sanitizeForEncoding(descriptionList.get(0)), false);
        var current = new ArrayList<>(descriptionList);
        boolean llmUsed = false;
        while (true) {
            long total = current.stream().mapToLong(tokenCounter::countTokens).sum();
            if (total <= summaryContextSize || current.size() <= 2) {
                if (current.size() < forceLlmSummaryOnMerge && total < summaryMaxTokens) {
                    return new Summary(TextSanitizer.sanitizeForEncoding(String.join("<SEP>", current)), llmUsed);
                }
                return new Summary(summarizeWithLlm(descriptionType, name, current), true);
            }
            var chunks = splitIntoContextChunks(current);   // ≥2 per chunk, mirror operate.py:466-506
            var next = new ArrayList<String>();
            for (var chunk : chunks) {
                if (chunk.size() == 1) { next.add(chunk.get(0)); }
                else { next.add(summarizeWithLlm(descriptionType, name, chunk)); llmUsed = true; }
            }
            current = next;
        }
    }
}
```

`summarizeWithLlm` builds the JSONL `{"Description": ...}` list truncated to `summaryContextSize` tokens (`operate.py:584-598`), calls `summaryModel.generate(new ChatRequest(systemPrompt, userPrompt))`, sanitizes the result, and logs when the reply exceeds `summaryMaxTokens` (upstream only warns; there is no re-loop for a single oversize answer).

**Failure policy (must be explicit — the plan's earlier draft left it open):** upstream wraps neither `_summarize_descriptions` nor the map/reduce loop in `try/except` (`operate.py:454-464,508-526,532-651`), so a summary-model failure **propagates** and fails the document ingest; already-completed map summaries live only in memory and are discarded with the attempt. Java matches this: `summarizeWithLlm` lets `ModelException`/runtime failures propagate, the pipeline's existing failure handling marks the document failed, and the structured LLM cache keeps whatever calls succeeded (a retry re-hits them). Do **not** silently fall back to the `<SEP>` join on error — that would persist a description upstream would never have written, and diverge per merge layer. Add a test: summary model throws → the ingest/materialization call fails (assert what the existing failure-path tests already guarantee about partial graph writes; do not add new atomicity guarantees in this task).

**Cache soundness:** the SDK already wires the summary model through `CachedChatModel` (`api/LightRag.java:836-837`, helper `:1077-1078`), so summary calls are cached under the generic content-addressed key. The soundness argument, stated precisely: the key is derived from the **rendered request**, and `summarizeWithLlm` makes every decision from the exact prompt it sends — description type, name, the ordered deduped fragment list **as truncated to `summaryContextSize` tokens** (`operate.py:584-598`), `summary_length_recommended` and the language. Two calls with the same key therefore send byte-identical prompts; two fragment sets that differ only past the truncation cut legitimately share a key, because the model never sees the difference. Keep it that way: never move any of those inputs out of the prompt into surrounding Java code, and never truncate anywhere else (a prompt cut by the cache layer, an HTTP `max_tokens`, or a "context window" guard applied after the prompt is built would silently break the equivalence). `DescriptionSummarizerTest.cacheKeyChangesWhenTheFragmentSetChanges` / `cacheKeyIsStableWhenFragmentsDifferOnlyBeyondTheTruncationWindow` pin both directions on the request text handed to the model; `chatModelIdentity` stays in the key via Task 14 of the query plan.

`DescriptionFragments` provides the split/dedup step used by the merge sites:

```java
public final class DescriptionFragments {
    public static final String SEPARATOR = "<SEP>";

    /** stored fragments first, then new ones, each sanitized, exact duplicates dropped (operate.py:2384-2426). */
    public static List<String> combine(List<String> stored, List<String> incoming) { ... }

    public static List<String> split(String joined) { /* split on SEPARATOR, strip, drop blanks */ }
}
```

`GraphAssembler`: `MutableEntity` / `MutableRelation` keep a `List<String> descriptionFragments`; `mergeFrom` appends sanitized fragments (no more "first non-empty"); `toEntity()` / `toRelation()` emit `String.join("<SEP>", DescriptionFragments.combine(List.of(), fragments))`.

Storage-level merge (`IndexingPipeline.mergeEntity`/`mergeRelationGroup`, `GraphMaterializationPipeline.mergeEntity`/`mergeRelation`): replace the `existing.description().isEmpty() ? incoming : existing` rule with

```java
var fragments = DescriptionFragments.combine(
    DescriptionFragments.split(existing.description()),
    DescriptionFragments.split(incoming.description()));
var summary = summarizer.summarize("Entity", existing.name(), fragments);
var description = summary.description();
```

The summarizer is a constructor argument of both pipelines (an overridable no-op instance is used by tests that assert raw joins: `DescriptionSummarizer.withoutLlm(...)` returning joins only).

Builder/config: `LightRagBuilder.forceLlmSummaryOnMerge(int)`, `.summaryMaxTokens(int)`, `.summaryContextSize(int)`, `.summaryLengthRecommended(int)`; defaults from `KnowledgeExtractor.DEFAULT_LANGUAGE`-style constants added to `DescriptionSummarizer` (`DEFAULT_FORCE_LLM_SUMMARY_ON_MERGE = 8`, `DEFAULT_SUMMARY_MAX_TOKENS = 1200`, `DEFAULT_SUMMARY_CONTEXT_SIZE = 12_000`, `DEFAULT_SUMMARY_LENGTH_RECOMMENDED = 600`). The summary model is the already-wired `config.summaryModel()` (`LightRag.java:794`), which falls back to `chatModel` (`LightRagConfig.java:56-74`) — no new model role.

**Platform constraint (recalibrated):** the aiplatform fork is already deleted from the platform working tree (Platform calibration fact 2), so nothing forces these constructor shapes any more — keeping the existing `IndexingPipeline`/`GraphMaterializationPipeline` constructors intact and delivering the summarizer through an added overload is now a *source-compatibility convention* for SDK consumers, not a platform compile requirement. The existing constructor delegates with an LLM-enabled default summarizer built from the already-passed `summaryModel`, so any consumer that does not re-wire still gets upstream-threshold summarization with default knobs.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS — including the two cache-boundary tests (a fragment change inside the visible window must produce a new request text; a change only beyond the `summaryContextSize` cut must produce the identical request text) and the throwing-summary-model failure test (ingest fails; no `<SEP>` fallback persisted). Existing assertions that expect a single first-wins description must be updated to the `<SEP>` join (grep `description` in `GraphAssemblerTest`, `GraphMaterializationPipelineTest`, `IndexingPipelineBatchGraphPersistenceTest`).

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionSummarizer.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/DescriptionFragments.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/TextSanitizer.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/DescriptionSummarizerTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/DescriptionFragmentsTest.java
git commit -m "feat: summarize merged entity and relation descriptions with the summary model"
```

---

### Task 2: Entity type majority vote

Upstream votes the entity type across the current batch plus the stored type (`operate.py:2451-2471,2576-2583`). Java takes the first non-empty type at both merge layers (`GraphAssembler.java:311-313`, `IndexingPipeline.java:1611`, `GraphMaterializationPipeline.java:1238`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` (`:275-371`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (`:1607-1616`), `GraphMaterializationPipeline.java` (`:1234-1243`)
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphAssemblerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void picksTheMajorityEntityTypeAcrossTheBatch() {
    // entities: A(type=Person) x2, A(type=Organization) x1  -> "Person"
}

@Test
void breaksTypeTiesByFirstSeenOrderAndCountsTheStoredTypeOnce() {
    // batch: Person x1, Organization x1; stored record type=Organization
    // -> counter {Person:1, Organization:2} -> "Organization"
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.GraphAssemblerTest"`
Expected: FAIL — the first-seen type wins.

- [ ] **Step 3: Implement**

`MutableEntity` keeps a `LinkedHashMap<String, Integer> typeCounts`; `mergeFrom` increments instead of first-wins; `toEntity()` picks `typeCounts.entrySet().stream().max(comparingInt(Entry::getValue))` (first-seen wins ties because `max` returns the first maximal element on a `LinkedHashMap` stream). Storage-level merge seeds the counter with `existing.type()` counted **once** (`operate.py:2471`) plus the incoming counts, so a re-extraction does not dilute a stored type — to keep this expressible, `Entity` gains a package-private type-count carrier? No: keep it simple and honest — the storage layer re-derives the vote as

```java
var counts = new LinkedHashMap<String, Integer>();
countOnce(counts, existing.type());
count(counts, incoming.type());
var type = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(existing.type());
```

and documents that batch-internal multiplicity is resolved by the assembler, while the storage merge sees one aggregate vote per side (upstream's `nodes_data` list is likewise already collapsed to one entry per chunk-extraction). Add a code comment only where the one-count-per-side rule is non-obvious.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/GraphAssemblerTest.java
git commit -m "feat: majority-vote entity types during merge"
```

---

### Task 3: Relation weight semantics and evidence floor

Upstream: JSON extraction always emits `weight = 1.0` (`operate.py:1084`); the merge sums the weights of incoming rows whose chunk is not already stored onto the stored scalar and then floors at the number of distinct evidence chunks, excluding `manual_creation` / `UNKNOWN` (`operate.py:2981-3000`, `constants.py:54`; evidence floor landed 2026-08-20). Java: model weight clamped to `[0,1]` (`KnowledgeExtractor.java:609-616,637-639`) and merge takes `Math.max` (`GraphAssembler.java:414-420`, `IndexingPipeline.java:1632-1642`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`:493-521`, `:609-616`, `:637-639`, `:849-865`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` (`:414-420`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (`:1632-1642`), `GraphMaterializationPipeline.java` (`:1245-1255`), `GraphManagementPipeline.java` (`:529-543`, `:76`, `:180`)
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/RelationEvidence.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphManagementPipelineTest.java` (drives the public merge-entities API over an in-memory provider, the way `E2ELightRagTest` does — there is no existing unit test for this class)
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/RelationEvidenceTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`, `GraphAssemblerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// KnowledgeExtractorTest
@Test
void fixesExtractedRelationWeightToUnityRegardlessOfModelOutput() {
    // model returns "weight": 0.35 and "confidence": 9 for one relation
    assertThat(result.relations()).allSatisfy(relation -> assertThat(relation.weight()).isEqualTo(1.0d));
}

// GraphAssemblerTest
@Test
void accumulatesRelationWeightAcrossContributingChunks() {
    // same relation extracted from 3 chunks -> weight == 3.0
}

// RelationEvidenceTest
@Test
void evidenceFloorRepairsLegacyRowsAndIgnoresManualSources() {
    // legacy undersized scalar over two real sources -> lifted to the floor (operate.py:3000)
    assertThat(RelationEvidence.merge(0.5d, List.of(), List.of("c1", "c2"), List.of("c1", "c2"))).isEqualTo(2.0d);
    // manual_creation/UNKNOWN are never evidence: they neither raise the floor nor repair a scalar (constants.py:54)
    assertThat(RelationEvidence.merge(0.5d, List.of(), List.of("manual_creation", "UNKNOWN"), List.of("manual_creation", "UNKNOWN"))).isEqualTo(0.5d);
    // a new (unseen) source adds its row weight on top of the stored scalar — 1.0 on the standard
    // extraction path (upstream sums dp["weight"]; operate.py:2980-2989)
    assertThat(RelationEvidence.merge(1.0d, List.of("c1", "c2"), List.of("c1"), List.of("c1", "c2"))).isEqualTo(2.0d);
    // a re-fed source adds nothing: filtering is on the stored edge's own sources (operate.py:2980)
    assertThat(RelationEvidence.merge(9.0d, List.of("c1"), List.of("c1"), List.of("c1"))).isEqualTo(9.0d);
}

@Test
void evidenceFloorCountsTheCappedStoredListNotThePreCapUnion() {
    // 250 distinct evidence chunks, cap 200 -> the caller passes the capped list (SourceIdLimits, Task 4),
    // so the floor is 200, never 250: upstream caps first (operate.py:2904) and floors on the capped
    // list (join :2954 -> evidence_count :2993-2998 -> floor :3000).
    var capped = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
    assertThat(RelationEvidence.merge(1.0d, List.of(), capped, capped)).isEqualTo(200.0d);
}

@Test
void capDroppedNewSourcesAddNoWeight() {
    // The weight base is the RETAINED incoming list, not the raw batch: upstream drops new rows the
    // KEEP cap threw away before summing (operate.py:2916-2929), then sums the survivors (:2980-2989).
    // 250 brand-new sources under a 200 KEEP cap therefore add 200, never 250; under FIFO all 250
    // rows survive the filter and the full 250 adds (:2926-2929). The retention list itself is
    // computed by the caller (SourceIdLimits.retainIncomingEvidence, Task 4) — this pins the helper
    // side of the contract; Task 4's SourceIdLimitsTest and the E2E cap test pin the 250->200 pipeline.
    var retained = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
    assertThat(RelationEvidence.merge(0d, retained, List.of(), retained)).isEqualTo(200.0d);
    var allFifoRows = IntStream.rangeClosed(1, 250).mapToObj(i -> "c" + i).toList();
    var fifoCapped = allFifoRows.subList(50, 250);
    assertThat(RelationEvidence.merge(0d, allFifoRows, List.of(), fifoCapped)).isEqualTo(250.0d);
}

// GraphManagementPipelineTest (new; public merge-entities API over an in-memory provider)
@Test
void relationsRedirectedOntoOneEndpointTakeTheEvidenceFloor() {
    // two relations collapsed by an entity merge, weights 1.0 and 1.0 over disjoint chunk sets
    // -> weight == max(1.0, 1.0, distinct merged evidence) == 2.0   (upstream utils_graph.py:2886-2917)
}

@Test
void manualWeightBelowTheEvidenceCountIsRejected() {
    // createRelation/editRelation with weight 0.5 over 2 real source chunks -> IllegalArgumentException
    // (upstream validate_relation_weight, utils_graph.py:192-217)
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest"`
Expected: FAIL — weights are the model's clamped values.

- [ ] **Step 3: Implement**

`KnowledgeExtractor.parseRelations` sets `weight = 1.0` unconditionally (delete `parseWeightOrConfidence` and its clamp; the prompt keeps accepting a `weight` field so old prompts keep parsing, but the value is ignored, exactly like upstream). Remove the "weight" line from the JSON schema block only if it is present in `SYSTEM_PROMPT_TEMPLATE` — upstream's JSON prompt does not ask for a weight, so drop it from the Java prompt too (mechanical edit of `:85-93`).

`GraphAssembler.MutableRelation.mergeFrom`: `weight += relation.weight()` (sum, not max).

New `RelationEvidence` helper, the single home of the upstream weight rule (`operate.py:2980-3000`):

```java
public final class RelationEvidence {
    private static final Set<String> NO_EVIDENCE = Set.of("manual_creation", "UNKNOWN");

    /**
     * upstream operate.py:2980-3000, verbatim order:
     *   1. added  = count of RETAINED incoming sources not reflected in the stored scalar
     *               (operate.py:2980-2989); every contribution is 1.0 on the standard extraction
     *               path this plan targets (operate.py:1084) — upstream sums each row's
     *               dp["weight"], forking only for parsed-strength rows — so the sum over rows
     *               reduces to the count of not-yet-stored source ids.
     *               "Retained" is load-bearing: upstream filters this batch's rows BEFORE the sum
     *               (operate.py:2916-2924). Under KEEP a new row whose source the cap dropped
     *               (neither in the capped list nor in the already-known set) never reaches the weight
     *               sum, so 250 brand-new sources under a 200 cap add 200, never 250. Under FIFO
     *               every row survives the filter and the full 250 adds (:2926-2929) even though
     *               only 200 stay stored. The caller therefore passes the retained list, never the
     *               raw incoming list; with caps disabled the two coincide.
     *               The already-known set is upstream's existing_full_source_ids slot. Upstream fills
     *               it from relation_chunks_storage when a tracking row exists and from the stored
     *               edge scalar otherwise (:2864-2886); Java can only supply the scalar, i.e. the
     *               no-tracking path. The one divergence (a tracking store running ahead of the
     *               edge) is documented in Task 4's SourceIdLimits note and pinned by test.
     *   2. weight = storedWeight + added;
     *   3. floor  = distinct real sources of the CAPPED merged list (operate.py:2993-3000),
     *               where "real" excludes manual_creation / UNKNOWN (constants.py:54);
     *   4. result = max(weight, floor)   -- a legacy/scalar boost survives, an undersized one is repaired.
     *
     * cappedMergedChunkIds MUST be the list AFTER the source-id cap (SourceIdLimits, Task 4) -- upstream
     * computes evidence_count from the capped `source_ids` (operate.py:2904 -> join :2954 -> :2993-2998),
     * so capping after the floor would let the floor count evidence the record no longer stores. When
     * Task 4's caps are disabled the merged list passes through uncapped and the two orders coincide.
     */
    public static double merge(
        double storedWeight,
        List<String> retainedIncomingChunkIds,
        List<String> storedChunkIds,
        List<String> cappedMergedChunkIds
    ) { ... }

    /** distinct evidence of a source-id list: size of the set minus NO_EVIDENCE. */
    public static long distinctEvidence(List<String> chunkIds) { ... }

    /** upstream validate_relation_weight (utils_graph.py:192-217): a manual weight below the evidence count is invalid. */
    public static double validateManualWeight(double weight, List<String> storedChunkIds) { ... }
}
```

Call it from `mergeRelation(existing, incoming)`:

```java
var mergedChunkIds = union(existing.sourceChunkIds(), incoming.sourceChunkIds());
// Task 4 replaces BOTH lines below with the cap and the KEEP edge filter:
//   var cappedMergedChunkIds = capsEnabled
//       ? SourceIdLimits.apply(mergedChunkIds, maxSourceIdsPerRelation, sourceIdsLimitMethod)
//       : mergedChunkIds;
//   var retainedIncoming = capsEnabled
//       ? SourceIdLimits.retainIncomingEvidence(incoming.sourceChunkIds(), existing.sourceChunkIds(),
//             cappedMergedChunkIds, sourceIdsLimitMethod)
//       : incoming.sourceChunkIds();
var cappedMergedChunkIds = mergedChunkIds;            // no cap yet (Task 4)
var retainedIncoming = incoming.sourceChunkIds();     // nothing dropped yet (Task 4)
var weight = RelationEvidence.merge(
    existing.weight(), retainedIncoming, existing.sourceChunkIds(), cappedMergedChunkIds);
var record = new GraphStore.RelationRecord(..., weight, cappedMergedChunkIds);
// (7-arg convenience ctor; Task 5 later switches this same construction to the 8-arg form that also
//  carries the merged filePath — keep the cap→filter→floor→record ordering either way)
// incoming.weight() is deliberately unused: it is this batch's own sum over contributing chunks and
// would double-count chunks already reflected in the stored scalar; the helper re-derives the added
// weight from the source ids — each worth 1.0 on this plan's standard extraction path (upstream sums
// each surviving row's dp["weight"], operate.py:2980-2989; parsed-strength rows fork).
// The cap-dropped-new-sources filter lives at the CALLER, not in the helper, because only the caller
// knows the limit method (KEEP filters, FIFO does not — operate.py:2916-2929).
```

Until Task 4 lands, `capsEnabled` is absent and the two replacement lines are just `cappedMergedChunkIds = mergedChunkIds` / `retainedIncoming = incoming.sourceChunkIds()`; Task 4 inserts the cap and the retained-evidence filter at exactly this point. Cap-before-floor is the ordering Codex round 2 disputed and the upstream source settles: `apply_source_ids_limit` (`operate.py:2904`) runs before `evidence_count` (`:2993-2998`), and the entity-merge collapse floors on the joined merged source ids with the same rule (`utils_graph.py:2886-2917`). The retained-evidence filter (round 3 M3) mirrors `operate.py:2916-2929`: the weight sum runs over the rows that survived the cap filter, so the caller — not the helper — is responsible for passing exactly those rows.

**Consumer sweep (all `weight` merge sites in the repo — verified 2026-10-01):**

| Site | Current rule | Disposition |
|---|---|---|
| `GraphAssembler.MutableRelation.mergeFrom:419` | `Math.max` | **Change to sum** (same document batch, per-chunk contributions). |
| `IndexingPipeline.mergeRelation:1639` | `Math.max` | **Change to `RelationEvidence.merge`** (storage-level, cross-chunk). |
| `GraphMaterializationPipeline.mergeRelation:1252` | `Math.max` | **Same change** (rebuild path). |
| `GraphManagementPipeline.mergeRelationRecord:539` | `Math.max` | **Change to `max(current, incoming)` floored at the merged distinct evidence count** — the entity-merge path collapsing redirected relations onto one endpoint; upstream's rule is verbatim `max(input weights, distinct merged evidence sources)` (`utils_graph.py:2886-2917`) via `apply_relation_weight_floor` (`:220-229`). The merged lists are the stored (already capped at write time) `source_id`s, exactly the view upstream floors on — do not try to reconstruct a pre-cap union. |
| `KnowledgeExtractor.mergeRelation:864` | `Math.max` | **Keep.** Merges rows extracted from the *same* chunk (gleaning rounds); both operands are constant `1.0` after this task, and summing here would double-count one chunk's evidence before the source-id dedup. Add a comment plus a test asserting two same-chunk rows keep `weight == 1.0`. |
| `DefaultExtractionMergePolicy.mergeRelation:72` | `Math.max` | **Keep**, same reason (per-chunk refinement variants, `:46-56`). |
| `GraphManagementPipeline.createRelation:76` / `editRelation:180` | caller-provided weight, unvalidated | **Validate with the upstream rule**: a weight below the relation's distinct evidence count is invalid (`validate_relation_weight`, `utils_graph.py:192-217`); `RelationEvidence` hosts the check. |
| Read-side consumers (`DefaultPathScorer:34`, `LocalQueryStrategy:247`, `GlobalQueryStrategy:196`, `QueryEngine:701`, `DeletionPipeline:400`, storage writers) | read/persist | **No code change**; they must stop assuming `[0,1]`. Grep assertions on weight bounds in tests before running (Step 4). |

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS. `LocalQueryStrategy` ranking uses `weight` (query plan Task 8 changes the ranking key); no query-side assertion should depend on the old `[0,1]` range — grep tests for `weight` before running.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/RelationEvidence.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphManagementPipeline.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/GraphAssemblerTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/RelationEvidenceTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/GraphManagementPipelineTest.java
git commit -m "feat: align relation weight accumulation and evidence floor with upstream"
```

---

### Task 4: Source-id caps (200, KEEP/FIFO) with attribution-aware deletion

Upstream caps `source_id` at 200 per entity/relation (`constants.py:71-72`) with `KEEP` (head) or `FIFO` (tail) (`utils.py:7244-7276`, marked 2025-10), and can do so because `entity_chunks`/`relation_chunks` tracking remains authoritative for deletion (`utils.py:7292-7306`). Java's incremental deletion derives survival from the graph record's own `sourceChunkIds` (`DeletionPipeline.java:309-320`) — capping without changing that decision would delete entities whose only remaining evidence chunks were capped out.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/SourceIdLimits.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphChunkAttribution.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphSnapshotCapabilities.java` (per-instance capability probe + class-level warn-once)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (merge helpers), `GraphMaterializationPipeline.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/DeletionPipeline.java` (`:302-350`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `config/LightRagConfig.java`, `api/LightRag.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/SourceIdLimitsTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphSnapshotCapabilitiesTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// SourceIdLimitsTest
@Test
void keepMethodKeepsTheHeadAndFifoKeepsTheTail() {
    var ids = IntStream.rangeClosed(1, 250).mapToObj(i -> "c" + i).toList();
    assertThat(SourceIdLimits.apply(ids, 200, SourceIdLimits.Method.KEEP)).containsExactlyElementsOf(ids.subList(0, 200));
    assertThat(SourceIdLimits.apply(ids, 200, SourceIdLimits.Method.FIFO)).containsExactlyElementsOf(ids.subList(50, 250));
    assertThat(SourceIdLimits.apply(ids, 0, SourceIdLimits.Method.KEEP)).isEmpty();
    assertThat(SourceIdLimits.apply(ids.subList(0, 3), 200, SourceIdLimits.Method.KEEP)).hasSize(3);
}

@Test
void keepDropsCapEvictedNewSourcesFromTheWeightBase() {
    // 250 brand-new sources, KEEP cap 200: upstream drops the 50 new rows outside the capped list
    // BEFORE the weight sum (operate.py:2916-2929 -> :2980-2989), so the edge weighs 200, not 250.
    var incoming = IntStream.rangeClosed(1, 250).mapToObj(i -> "c" + i).toList();
    var keepCapped = SourceIdLimits.apply(incoming, 200, SourceIdLimits.Method.KEEP);          // c1..c200
    var keepRetained = SourceIdLimits.retainIncomingEvidence(incoming, List.of(), keepCapped, SourceIdLimits.Method.KEEP);
    assertThat(keepRetained).containsExactlyElementsOf(keepCapped);
    assertThat(RelationEvidence.merge(0d, keepRetained, List.of(), keepCapped)).isEqualTo(200.0d);

    // FIFO keeps every new row (operate.py:2926-2929): the weight base is the full 250 even though
    // only the tail 200 stay stored, and the floor (200) does not pull it back down.
    var fifoCapped = SourceIdLimits.apply(incoming, 200, SourceIdLimits.Method.FIFO);          // c51..c250
    var fifoRetained = SourceIdLimits.retainIncomingEvidence(incoming, List.of(), fifoCapped, SourceIdLimits.Method.FIFO);
    assertThat(fifoRetained).containsExactlyElementsOf(incoming);
    assertThat(RelationEvidence.merge(0d, fifoRetained, List.of(), fifoCapped)).isEqualTo(250.0d);
}

@Test
void withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback() {
    // tracking c1..c250, stored scalar c1..c200 (a previous KEEP cap), a later batch re-feeds c201.
    // Upstream WITH a relation_chunks row keeps that row (c201 is tracked) and weighs 200 + 1 = 201
    // (operate.py:2922-2925, :2980-2989, floor :2993-3000). Java has no relation_chunks analog at the
    // merge point, so it runs upstream's own no-tracking fallback (:2881-2886): the row is dropped and
    // the edge stays at 200. This test pins the documented divergence (see the SourceIdLimits note);
    // a future tracking-store port must flip it deliberately.
    var incoming = List.of("c201");
    var stored = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
    var keepRetained = SourceIdLimits.retainIncomingEvidence(
        incoming, stored, stored, SourceIdLimits.Method.KEEP);
    assertThat(keepRetained).isEmpty();
    assertThat(RelationEvidence.merge(200.0d, keepRetained, stored, stored)).isEqualTo(200.0d);
}

@Test
void theTrackingBaselineShapesForkTheKeepFilterFromTheScalarFallback() {
    // Fork table at limit 2 over the three baselines upstream can hand the KEEP filter — the scalar
    // (Java's no-tracking fallback, :2881-2886), the present-but-empty authoritative row (:2860-2879,
    // NOT reseeded from the scalar), and a tracked list lagging/leading the scalar. Each row
    // recomputes full -> capped -> filter exactly as the merge does (:2889, :2904, :2916-2929); the
    // leading row is the 200/250-scale case of withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback.
    var incoming = List.of("c3", "c4");
    var capped = SourceIdLimits.apply(List.of("c1", "c2", "c3", "c4"), 2, SourceIdLimits.Method.KEEP);
    // scalar baseline c1..c3 — Java today, and upstream with no tracking row: c3 is still stored
    assertThat(SourceIdLimits.retainIncomingEvidence(
        incoming, List.of("c1", "c2", "c3"), capped, SourceIdLimits.Method.KEEP))
        .containsExactly("c3");
    // upstream with a present-but-empty row: baseline [], so the cap sees only the new ids
    assertThat(SourceIdLimits.retainIncomingEvidence(incoming, List.of(),
        SourceIdLimits.apply(incoming, 2, SourceIdLimits.Method.KEEP), SourceIdLimits.Method.KEEP))
        .containsExactly("c3", "c4");
    // upstream with a lagging tracked list c1..c2 against the scalar c1..c3: drops the refeed Java keeps
    assertThat(SourceIdLimits.retainIncomingEvidence(
        incoming, List.of("c1", "c2"), capped, SourceIdLimits.Method.KEEP))
        .isEmpty();
    // upstream with a leading tracked list c1..c3 against the scalar c1..c2: keeps the cap-evicted
    // refeed Java drops (the divergence scenario of the 200/250 test above)
    assertThat(SourceIdLimits.retainIncomingEvidence(
        incoming, List.of("c1", "c2", "c3"), capped, SourceIdLimits.Method.KEEP))
        .containsExactly("c3");
}

@Test
void repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly() {
    // Tracking ahead: tracking c1..c250, stored scalar c1..c200, and the SAME cap-evicted source c201
    // is re-fed three times. Upstream keeps the tracked row every event (:2922-2925) and the weight
    // filter reads the stored scalar (:2980-2986) — c201 never enters it, because the join persists
    // the CAPPED list (:2904, :2954) — so the weight grows 200 -> 201 -> 202 -> 203; the gap is one
    // surviving row's weight per re-feed EVENT (1.0 here; upstream sums each surviving row's
    // dp["weight"]), not per source. Java drops the row every time and stays at 200.
    var incoming = List.of("c201");
    var stored = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
    var retained = SourceIdLimits.retainIncomingEvidence(incoming, stored, stored, SourceIdLimits.Method.KEEP);
    assertThat(retained).isEmpty();                                 // Java's no-tracking filter
    var javaWeight = 200.0d;
    for (int refeed = 0; refeed < 3; refeed++) {                    // each event merges the previous record
        javaWeight = RelationEvidence.merge(javaWeight, retained, stored, stored);
    }
    assertThat(javaWeight).isEqualTo(200.0d);                       // upstream-with-tracking reaches 203
}

@Test
void cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap() {
    // The COMPLETE merge (IndexingPipeline.mergeRelationWithCaps, extracted package-private so it is
    // directly testable), not just the two helpers: existing c1..c200 / weight 200, a later batch
    // re-feeds c201 under the 200 KEEP cap. Java's no-tracking merge drops the fragment, so the merged
    // RECORD equals the stored one — the same record upstream's no-tracking early return hands back
    // (:2932-2951). Upstream WITH a tracking row would instead return a record whose
    // description/keywords carry c201's fragment and weigh 201 (see the fork table above).
    var storedIds = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
    var existing = new GraphStore.RelationRecord(
        "e1~e2", "e1", "e2", "kw", "stored description", 200.0d, storedIds);
    var incoming = new Relation("e1~e2", "e1", "e2", "kw", "c201 description", 1.0d, List.of("c201"));

    var merged = IndexingPipeline.mergeRelationWithCaps(
        existing, incoming, true, 200, SourceIdLimits.Method.KEEP);

    assertThat(merged).isEqualTo(existing);             // ids, weight, description, keywords all unchanged
    assertThat(merged.sourceChunkIds()).hasSize(200);
    assertThat(merged.weight()).isEqualTo(200.0d);
}

// E2ELightRagTest
@Test
void sourceIdsAreCappedAtTheConfiguredLimitOnIngest() {
    // 250 chunks mention the same relation -> the stored record keeps the KEEP-head 200 ids AND weighs
    // 200, not 250: the cap drops the 50 new rows from the weight base before the sum (round-3 M3).
    var storage = InMemoryStorageProvider.create();
    var rag = LightRag.builder()
        .chatModel(sameRelationChatModel())               // every chunk extracts the same relation
        .embeddingModel(new FakeEmbeddingModel())
        .storage(storage)
        .maxSourceIdsPerRelation(200)
        .build();

    rag.ingest(WORKSPACE, List.of(documentWithChunkCount(250)));

    var relation = storage.graphStore().allRelations().get(0);
    assertThat(relation.sourceChunkIds()).hasSize(200);
    assertThat(relation.weight()).isEqualTo(200.0d);
}

@Test
void deletingCappedOutSourceChunksStillKeepsTheEntityWhenAttributionRemains() {
    // entity sourced from 250 chunks across two documents; the KEEP cap retained only document A's
    // chunk ids on the record. Deleting A empties the stored list, and the record must still survive
    // because document B's chunk snapshots assemble back to the same entity id.
    var openai = new GraphStore.EntityRecord(
        "openai", "OpenAI", "ORGANIZATION", "vendor", List.of(), List.of("doc-a:c1", "doc-a:c2"));
    var snapshots = List.of(
        chunkSnapshot("doc-b", "doc-b:c1", extractedEntity("OpenAI", "ORGANIZATION")));
    var attribution = GraphChunkAttribution.from(List.of(openai), snapshots, "doc-a");

    assertThat(attribution.entityHasAttribution(openai)).isTrue();
    // control: with B's snapshot absent, the same record has no attribution and is deleted
    assertThat(GraphChunkAttribution.from(List.of(openai), List.of(), "doc-a")
        .entityHasAttribution(openai)).isFalse();
}

@Test
void aliasMergedEntitySurvivesDeletionThroughItsNameAttribution() {
    // the stored record's id/name is the primary spelling; the surviving snapshot chunk spells only
    // the alias. entityHasAttribution must match through the stored record's normalized name/alias
    // view (DeletionPipeline.resolveEntityIds:516-529), not by raw string equality.
    var openai = new GraphStore.EntityRecord(
        "openai", "OpenAI", "ORGANIZATION", "vendor", List.of("Open AI"), List.of("doc-a:c1"));
    var snapshots = List.of(
        chunkSnapshot("doc-b", "doc-b:c1", extractedEntity("Open AI", "ORGANIZATION", List.of("Open AI"))));

    assertThat(GraphChunkAttribution.from(List.of(openai), snapshots, "doc-a")
        .entityHasAttribution(openai)).isTrue();
}

@Test
void aliasSpelledRelationSurvivesDeletionThroughEndpointResolution() {
    // the stored relation id was canonicalized from the stored endpoint ids ("openai" ~ "microsoft").
    // The surviving chunk spells the relation as ("Open AI", "Microsoft"); after resolving every
    // assembled endpoint through the stored records' merge keys the canonicalized id must equal the
    // stored relation id — matching raw snapshot spellings would canonicalize a different id and the
    // relation would be wrongly deleted.
    var openai = new GraphStore.EntityRecord(
        "openai", "OpenAI", "ORGANIZATION", "vendor", List.of("Open AI"), List.of());
    var microsoft = new GraphStore.EntityRecord(
        "microsoft", "Microsoft", "ORGANIZATION", "vendor", List.of(), List.of("doc-b:c9"));
    var storedRelation = new GraphStore.RelationRecord(
        RelationCanonicalizer.relationId("microsoft", "openai"),
        "microsoft", "openai", "partner", "desc", 2.0, "doc-b:c9", "");
    var snapshots = List.of(chunkSnapshot("doc-b", "doc-b:c9",
        List.of(), List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
            "Open AI", "Microsoft", "partner", "desc", 1.0))));
    var attribution = GraphChunkAttribution.from(List.of(openai, microsoft), snapshots, "doc-a");

    assertThat(storedRelation.relationId())
        .isEqualTo(RelationCanonicalizer.canonicalize(
            storedRelation.srcId(), storedRelation.tgtId()).relationId());
    assertThat(attribution.relationHasAttribution(storedRelation)).isTrue();
}
```

Add these fixtures next to the existing builder doubles: `chunkSnapshot(documentId, chunkId, ExtractedEntityRecord...)` / `chunkSnapshot(documentId, chunkId, List<ExtractedEntityRecord>, List<ExtractedRelationRecord>)` build the 9-component `DocumentGraphSnapshotStore.ChunkGraphSnapshot` with `ChunkExtractStatus.SUCCEEDED` (`api/ChunkExtractStatus.java:6` — the enum is declared at `:3`, the constant at `:6`), order 0, a content hash of the chunk id and `Instant.now()`; `extractedEntity(name, type)` / `extractedEntity(name, type, aliases)` wrap `DocumentGraphSnapshotStore.ExtractedEntityRecord:75-87` with an empty description. `GraphStore.EntityRecord` is 6-ary `(id, name, type, description, aliases, sourceChunkIds)` (`GraphStore.java:77-84`) and `GraphStore.RelationRecord` 8-ary `(relationId, srcId, tgtId, keywords, description, weight, sourceId, filePath)` (`:95-104`). The two E2E helpers are new in `E2ELightRagTest`: `sameRelationChatModel()` extracts the same single relation for every chunk, and `documentWithChunkCount(n)` produces a document that chunks into n pieces (tune the builder's chunk size/overlap, or stage the chunks through the resume path if a straight ingest cannot reach 250 chunks).

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.SourceIdLimitsTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`SourceIdLimits` is a direct port of `apply_source_ids_limit` (`limit <= 0` → empty; no truncation under the limit; KEEP head / FIFO tail) with `enum Method { KEEP, FIFO }` and a `parse(String)` accepting upstream's `IGNORE_NEW` alias for KEEP. It also hosts `retainIncomingEvidence(incoming, existingSourceIds, cappedMerged, method)` — the weight-base row filter of `operate.py:2916-2929`: under KEEP a new row whose source is neither in the capped list nor in `existingSourceIds` is dropped before the weight sum; under FIFO every row is kept (`:2926-2929`). The two sets upstream keeps apart at the relation merge are (round-4 M2):

| Set | Upstream source | Used by |
|---|---|---|
| tracking list | `relation_chunks_storage.get_by_id(...)`, `NO_EVIDENCE` placeholders filtered — `operate.py:2864-2879` | KEEP row filter (`:2916-2929`; the tracking membership test is `:2922-2925`) |
| stored scalar | the edge's own `already_source_ids` | weight sum (`:2980-2989`) and the fallback tracking value when no tracking row exists (`:2881-2886`) |
| capped merged list | `apply_source_ids_limit(full_source_ids, ...)` (`:2904`) | stored `source_id` (`GRAPH_FIELD_SEP.join(source_ids)` `:2954`) and the floor (`:2993-3000`) |

Java has no `relation_chunks` analog at the merge point — the merge helpers see only the stored record, whose `sourceChunkIds` is the capped scalar — so it passes the scalar as `existingSourceIds`: that is upstream's own **no-tracking path** (`relation_chunks_storage is None`, or a store present with no row for the edge), not an approximation of the tracking path. Where that path's early return fires (`:2932-2951`), Java's merge reaches the same record unchanged, pinned by `cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap`.

**Documented divergence (tracking-store scenarios).** Java implements upstream's **no-tracking path** exactly (`relation_chunks_storage is None`, or a store present with no row for the edge), so the fork is scoped, not approximated: everything below is upstream behavior *with a live tracking row*, unreachable in Java until a relation-chunks store is ported. Upstream's KEEP-filter baseline is the tracked list whenever a row exists (`:2864-2879`; a present-but-empty row is authoritative and is **not** reseeded from the scalar, `:2860-2864`), so with a live row three things fork away from Java's scalar baseline: (a) **survival** — a fragment whose source is tracked survives the KEEP filter even when the cap evicted it (`:2922-2925`), where Java's scalar baseline drops it; (b) **description/keywords/summary** — a surviving fragment keeps `edges_data` non-empty, so upstream skips the "everything filtered out → return the old edge" early return (`:2932-2951`) and merges that fragment's description/keywords into the edge, while Java's empty retained set adds nothing; (c) **weight** — each surviving fragment whose source is absent from the stored scalar contributes its own row weight `dp["weight"]` (`:2980-2989`) — 1.0 on the standard extraction path (`operate.py:1084`), a parsed strength otherwise. Worked example for (a)+(c): tracking `c1..c250`, stored scalar `c1..c200` (KEEP cap 200), a later batch re-feeds `c201` → upstream keeps the row and weighs `200 + 1 = 201` (floor 200, `:2993-3000`) → **201**; Java drops the row → **200**. The weight gap is bounded by the surviving fragment's own weight (1.0 in this plan's standard-extraction scenario) **per re-feed event**, not per source: the capped join (`:2954`) never lets the cap-evicted source into the scalar, so every re-feed of the same source adds that row's weight again upstream while Java stays flat (`repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly`). Rows Java keeps that upstream's tracked baseline drops are weight-neutral — their sources sit in the scalar, which the weight filter always excludes (`:2980-2986`) — but they still fork description/keywords/summary in the opposite direction. The floor is the same capped list on both sides, so Java is never below `max(stored weight, capped distinct evidence)`. Full fidelity would need the relation's tracked chunk set resolved from the chunk snapshots — the same authority `GraphChunkAttribution` builds for deletion — and the merge path has no such index today. The fork is pinned at helper level by `SourceIdLimitsTest` (`withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback`, `theTrackingBaselineShapesForkTheKeepFilterFromTheScalarFallback`, `repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly`) and at record level by `cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap`, so a future tracking-store port flips them deliberately instead of silently changing weights.

Apply the cap at the two storage-level merge helpers, on the merged id list, immediately before constructing the record — and, where Task 3 has already landed, **before** the evidence floor runs, so the floor counts the capped list exactly like upstream (`apply_source_ids_limit` `operate.py:2904` → join `:2954` → `evidence_count` `:2993-2998` → floor `:3000`):

```java
var mergedChunkIds = union(existing.sourceChunkIds(), incoming.sourceChunkIds());
var chunkIds = capsEnabled ? SourceIdLimits.apply(mergedChunkIds, limit, method) : mergedChunkIds;
// Weight base: upstream drops this batch's rows the KEEP cap threw away BEFORE summing
// (operate.py:2916-2924; FIFO keeps them, :2926-2929) — 250 new sources under a 200 KEEP cap
// add 200, never 250. Each retained row contributes unit weight (upstream sums the surviving
// row's dp["weight"], 1.0 on the standard extraction path, operate.py:1084). Task 3: the floor
// takes the same capped list, never the pre-cap union.
// The second argument is upstream's existing_full_source_ids slot: Java can only supply the stored
// scalar (upstream's no-tracking fallback, :2881-2886) — the tracking-store divergence is documented
// in the SourceIdLimits note above and pinned by withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback.
var retainedIncoming = capsEnabled
    ? SourceIdLimits.retainIncomingEvidence(incoming.sourceChunkIds(), existing.sourceChunkIds(), chunkIds, method)
    : incoming.sourceChunkIds();
var weight = RelationEvidence.merge(existing.weight(), retainedIncoming, existing.sourceChunkIds(), chunkIds);
return new GraphStore.RelationRecord(
    existing.id(), existing.srcId(), existing.tgtId(), existing.keywords(),
    existing.description().isEmpty() ? incoming.description() : existing.description(),
    weight, chunkIds);
```

Extract exactly this body as a shared **package-private static** helper — `IndexingPipeline.mergeRelationWithCaps(GraphStore.RelationRecord existing, Relation incoming, boolean capsEnabled, int limit, SourceIdLimits.Method method)` — with both pipelines' private `mergeRelation` wrappers passing their stored caps fields; GraphMaterializationPipeline's copy (`.java:1245-1255`) delegates to it so the complete merge has one implementation and one test target, `SourceIdLimitsTest.cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap` (the two pipelines live in the same package).

New knobs: `LightRagBuilder.maxSourceIdsPerEntity(int)` / `.maxSourceIdsPerRelation(int)` (default 200, upstream `constants.py:71-72`) and `.sourceIdsLimitMethod(SourceIdLimits.Method)` (default `KEEP`).

`GraphChunkAttribution` builds the authoritative index from the per-chunk attribution snapshots. **The snapshot carries names, not graph ids** (`ChunkGraphSnapshot` holds `ExtractedEntityRecord{name,type,description,aliases}` / `ExtractedRelationRecord{sourceEntityName,targetEntityName,...}`, `DocumentGraphSnapshotStore:49-105`), so the index must derive ids by running each snapshot through the **same assembly the write path uses** — and then map the assembled ids onto the stored graph's id space through the stored records' merge keys, because a snapshot's assembly only knows the names that appear in *that* snapshot:

```java
/**
 * Entity/relation -> chunk attribution over per-chunk snapshots, minus one document's chunks.
 * Ids come from GraphAssembler.assemble(...) on each snapshot — the same call the write path makes
 * (GraphMaterializationPipeline.assembleChunkGraph:910-912, used for journal keys :533-535 and
 * toChunkStatus :632; IndexingPipeline.chunkGraphKeys:1892-1908 does it at ingest), so entity ids
 * (normalized name) and relation ids (RelationCanonicalizer md5 of the two canonical endpoints)
 * cannot drift from the stored records.
 *
 * That is not sufficient on its own for ALIAS-MERGED records: a snapshot whose chunk mentions a
 * relation endpoint by an alias while the entity row for the primary name lives in a DIFFERENT
 * snapshot assembles to the alias endpoint ("Open AI"), whose relation id differs from the stored
 * record's ("OpenAI"). Both sides are therefore re-anchored: every assembled endpoint name is
 * resolved through the stored records' merge keys (normalized name + aliases -> stored entity id,
 * the same view DeletionPipeline.resolveEntityIds:516-529 uses) before RelationCanonicalizer runs,
 * and entities additionally keep that normalized name/alias view as a fallback for ids no stored
 * record matches (e.g. an entity only present in the excluded document's chunks).
 */
public final class GraphChunkAttribution {
    public static GraphChunkAttribution from(
        List<GraphStore.EntityRecord> storedEntities,                      // alias/name -> id resolver
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> snapshots,
        String excludingDocumentId
    ) { ... }   // reuse the toChunkExtractions mapping from GraphMaterializationPipeline:882-908

    public boolean entityHasAttribution(GraphStore.EntityRecord entity) { /* resolved ids ∪ normalized name/alias */ }

    /** Endpoint names in every assembled snapshot relation are first resolved to stored entity ids
     *  through the stored records' merge keys; RelationCanonicalizer runs on the RESOLVED endpoints,
     *  so the resulting id is the stored record's id even when the snapshot chunk spells an alias
     *  (the alias-merged case pinned by aliasSpelledRelationSurvivesDeletionThroughEndpointResolution). */
    public boolean relationHasAttribution(GraphStore.RelationRecord relation) { /* resolved-endpoint ids */ }
}
```

`DeletionPipeline.removeDocumentIncrementally` changes the survival tests:

```java
// removeDocumentIncrementally already receives the whole storage Snapshot — whose chunkGraphSnapshots()
// still contain every OTHER document's chunks (they are filtered out only at the end, :346). No store
// round-trip or new listing API is needed; snapshot.entities() doubles as the alias resolver.
var attribution = capsEnabled
    ? GraphChunkAttribution.from(snapshot.entities(), snapshot.chunkGraphSnapshots(), documentId)
    : null;
var entities = snapshot.entities().stream()
    .map(entity -> removeEntityChunkReferences(entity, chunkIds))
    .filter(entity -> !entity.sourceChunkIds().isEmpty()
        || (attribution != null && attribution.entityHasAttribution(entity)))
    .toList();
// relations likewise (attribution.relationHasAttribution(relation)), then the existing
// "both endpoints survive" filter
```

The index is built only when a cap is actually active (`capsEnabled`), because with uncapped records `sourceChunkIds` is already authoritative and the scan is unnecessary work. Note in the code why: with `maxSourceIds*` caps the graph record is a truncated view; the per-chunk snapshots are the authority (upstream: `entity_chunks`/`relation_chunks`, which are name-keyed — `utils.py:7292-7306`).

**Edge cases to pin with tests (this is the part the first draft got wrong):** entity whose stored record survives only via an alias-merged id; entity whose surviving evidence chunks were evicted by the cap from its own `sourceChunkIds` (record list becomes empty after removing the deleted document's ids, but another document's snapshot still attributes it → must survive); relation whose endpoints survive by different name spellings, **including the alias-merged case**: a surviving chunk extracts the relation with the alias spelling while the entity row for the primary name lives in the deleted document's chunks — the resolved relation id must still match the stored record.

Caps require the index. The enable predicate resolves through `GraphSnapshotCapabilities`, which probes the **provider instance** — never a class-keyed memo (round-4 M3): `StorageCoordinator` is a public-constructor wrapper that forwards `documentGraphSnapshotStore()` to a mutable `RelationalStorageAdapter` delegate (`StorageCoordinator.java:25-33,86-87`), whose default implementation throws (`RelationalStorageAdapter.java:24-27`) while the PostgreSQL/MySQL adapters return real stores (`PostgresRelationalStorageAdapter.java:173-176`), so two providers of the same class can legitimately disagree. What *is* deduplicated is the WARN, a noise policy keyed by adapter class. `LightRag` builds pipelines from several factories on every public entry point (`newIndexingPipeline` `api/LightRag.java:828-858`, `newDeletionPipeline` `:864-870`, `newGraphManagementPipeline` `:872-874`, `newGraphMaterializationPipeline` `:903-932`), so probing a supporting provider per construction is trivially cheap while a warn set without the class key would repeat per pipeline. Commit the helper in this task:

```java
// io.github.lightrag.indexing.GraphSnapshotCapabilities
public final class GraphSnapshotCapabilities {
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();
    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotCapabilities.class);

    private GraphSnapshotCapabilities() {
    }

    /**
     * Capability is a property of the provider INSTANCE, never of its class: StorageCoordinator
     * (public constructor, StorageCoordinator.java:25-33) forwards this call to a mutable
     * RelationalStorageAdapter delegate (:86-87) whose default implementation throws
     * (RelationalStorageAdapter.java:24-27), while the PostgreSQL/MySQL adapters return a live store
     * (PostgresRelationalStorageAdapter.java:173-176). Deliberately no memo at all: the only call
     * sites are pipeline constructors (once per construction, not per call), so the probe is cheap,
     * always correct, and a static map would either pin provider instances (a leak) or key on
     * equality, which the capability does not follow.
     */
    public static boolean supportsDocumentGraphSnapshots(StorageProvider provider) {
        try {
            provider.documentGraphSnapshotStore();
            return true;
        } catch (UnsupportedOperationException exception) {
            return false;   // relational-only adapters
        }
    }

    /** capsRequested && available. The WARN is deduplicated per adapter class — warn volume is a
     *  noise policy, and it never replaces the per-instance capability answer. */
    public static boolean resolveCapsEnabled(
        StorageProvider provider, int maxSourceIdsPerEntity, int maxSourceIdsPerRelation) {
        var requested = maxSourceIdsPerEntity < Integer.MAX_VALUE
            || maxSourceIdsPerRelation < Integer.MAX_VALUE;          // default 200 => requested
        if (!requested) {
            return false;                                            // explicit opt-out: no probe, no WARN
        }
        var available = supportsDocumentGraphSnapshots(provider);
        if (!available && WARNED.add(provider.getClass())) {
            log.warn("LightRAG graph source-id caps requested (entity={}, relation={}) but storage provider {} "
                    + "has no document graph snapshot store; keeping source ids unbounded",
                maxSourceIdsPerEntity, maxSourceIdsPerRelation, provider.getClass().getName());
        }
        return available;
    }

    /** Test seams: the warn set is process-wide static state; tests reset it and read a class back. */
    static void resetWarnOnceForTests() {
        WARNED.clear();
    }

    static boolean warnedOnce(Class<?> providerClass) {
        return WARNED.contains(providerClass);
    }
}
```

Each pipeline constructor resolves once — `var capsEnabled = GraphSnapshotCapabilities.resolveCapsEnabled(storageProvider, maxSourceIdsPerEntity, maxSourceIdsPerRelation);` — and stores the boolean; there is no per-call branching later (apply sites call `SourceIdLimits.apply(...)` only when it is true, else the merged list passes through untouched). Through the public API the answer is effectively always `true`: `LightRagBuilder.build` already rejects providers without the snapshot store (`api/LightRagBuilder.java:329-332,352-355` → `IllegalStateException("documentGraphSnapshotStore is required")`) and the ingest path dereferences it unconditionally (`IndexingPipeline.initializeDocumentGraphState:1730,1759`), so the probe/WARN pair is a safety net for directly constructed pipelines, not a routine path. The **WARN fires at most once per adapter class per JVM regardless of how many pipelines/workspaces are created**, never a silent truncation without the attribution index; `maxSourceIdsPerEntity(Integer.MAX_VALUE)` is a valid explicit opt-out that is a no-op where caps were already disabled. The default stays upstream's 200 (`constants.py:71-72`).

Test it (`GraphSnapshotCapabilitiesTest`): (a) `capabilityIsResolvedPerInstanceNotPerClass` — a single test-local `ToggleableStorageProvider` class (delegating to `InMemoryStorageProvider.create()`, with one flag that makes `documentGraphSnapshotStore()` throw) instantiated twice, supporting and not; `supportsDocumentGraphSnapshots` must return `true` for one and `false` for the other (a class-keyed memo would answer the first result for both); (b) `warnFiresOncePerAdapterClass` — `resetWarnOnceForTests()`, then a **capturing Logback appender** attached to `GraphSnapshotCapabilities`'s logger, then two *distinct unsupported instances* of that class are resolved: both return `false` and the appender captured **exactly one** WARN event (the `warnedOnce(Class)` seam alone only proves set membership, not that `log.warn` ran once — assert the captured event count, and keep the seam as the reset/cleanup check; detach the appender and reset the set in `@AfterEach`); (c) `explicitOptOutSkipsTheProbeEntirely` — `Integer.MAX_VALUE` for both knobs returns `false` and the toggle double records no `documentGraphSnapshotStore()` call.

**Cross-repo (aiplatform):** the platform provider (`PostgresMilvusNeo4jStorageProvider`) exposes the snapshot store, so caps are live there — but the platform repeats the survival rule in `LightRagDeletionAdapter.filterEntity/filterRelation` (`D:\ai-code\aiplatform\backend\aide-kno\...\service\index\LightRagDeletionAdapter.java:572-611`) and must be switched to the same attribution test before caps are enabled in production (rollout checklist).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest" --tests "io.github.lightrag.storage.*"`
Expected: PASS, including the new delete-survival scenario.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/SourceIdLimits.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphChunkAttribution.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphSnapshotCapabilities.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/DeletionPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/SourceIdLimitsTest.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/GraphSnapshotCapabilitiesTest.java \
        lightrag-core/src/test/java/io/github/lightrag/E2ELightRagTest.java
git commit -m "feat: cap graph source ids with attribution-aware deletion survival"
```

---

### Task 5: Relation `file_path` accumulation and 75-path cap

Upstream keeps `file_path` on entities and relations, deduped, capped at 75 with a `...truncated...(KEEP Old)` marker (`operate.py:2634-2689` nodes, `:3067-3120` edges; `constants.py:84-88`). Java stores `file_path` for relations in every backend (`PostgresSchemaManager.java:179`, `WorkspaceScopedNeo4jGraphStore.java:587`) but the write path always passes `""` (`GraphAssembler.java:435`) and the storage-level merge drops it (`IndexingPipeline.java:1632-1642`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/FilePathLimits.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java` (`ChunkExtraction` `:200-213`, `:107-143`, `MutableRelation` `:373-438`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (`:1360-1400` chunk extraction build, `:1669-1679` relation record), `GraphMaterializationPipeline.java` (`:882-908`, `:1268-1278`)
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/FilePathLimitsTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/GraphAssemblerTest.java`, `E2ELightRagTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// FilePathLimitsTest
@Test
void keepsTheHeadAndAppendsTheTruncationMarkerWhenOverTheLimit() {
    var paths = IntStream.rangeClosed(1, 80).mapToObj(i -> "/docs/" + i + ".md").toList();
    var limited = FilePathLimits.apply(paths, 75, SourceIdLimits.Method.KEEP);
    assertThat(limited).hasSize(76).endsWith("...truncated...(KEEP Old)");
    assertThat(limited.subList(0, 75)).containsExactlyElementsOf(paths.subList(0, 75));
}

@Test
void skipsExistingPlaceholdersSoTheyNeverAccumulate() { /* input containing "...truncated...(KEEP Old)" -> exactly one marker in the output */ }

// GraphAssemblerTest
@Test
void accumulatesDedupedFilePathsForRelations() { /* relation from two chunks with file_path /a.md and /b.md -> filePath "/a.md<SEP>/b.md" */ }

// E2ELightRagTest
@Test
void ingestStoresRelationFilePathsFromChunkMetadata() { /* relation.filePath() == doc file_path */ }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.FilePathLimitsTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`FilePathLimits.apply(paths, limit, method)` ports `operate.py:2661-2687`: skip pre-existing placeholder entries while noting `hasPlaceholder`; over the limit keep head (KEEP) or tail (FIFO) and append `"...truncated...(KEEP Old)"` / `"...truncated...(FIFO)"`; the `original_count` in the log becomes `"<n>+"` when a placeholder was already present.

`GraphAssembler.ChunkExtraction` gains `String filePath` (canonical 4-arg ctor; keep 3-arg and 2-arg convenience ctors so existing call sites compile) — the pipelines pass `chunk.metadata().getOrDefault("file_path", "unknown_source")` (same key `QueryReferences.java:66` and `HybridVectorPayloads.java:37` already read; the `SmartChunker` copies document metadata into chunk metadata, `SmartChunker.java:664-673`). Add a `MetadataKeys.FILE_PATH` constant to the existing `SmartChunkMetadata` style to stop the literal duplication (constant + `DEFAULT_FILE_PATH = "unknown_source"`).

`MutableRelation` accumulates `LinkedHashSet<String> filePaths` (via `addSourceChunkId`-style method) and `toRelation()` passes `RelationCanonicalizer.joinValues(FilePathLimits.apply(...))` into the `filePath` component (replacing the `""` at `:435`).

Storage-level `mergeRelation` merges file paths the same way `GraphManagementPipeline.java:541` already does for the manual API:

```java
var filePaths = FilePathLimits.apply(union(existing.filePaths(), incoming.filePaths()), maxFilePaths, method);
new GraphStore.RelationRecord(existing.id(), existing.srcId(), existing.tgtId(), existing.keywords(), description,
    weight, RelationCanonicalizer.joinValues(chunkIds), RelationCanonicalizer.joinValues(filePaths));
```

Knob: `LightRagBuilder.maxFilePaths(int)` default 75. Entity `file_path` stays out of scope per Decision 4 — state that in the class javadoc of `FilePathLimits` so the asymmetry is discoverable.

**Cross-repo (aiplatform):** platform chunk metadata is assembled by `LightRagChunkDocumentMapper:161-192` — it writes `headingPath` (JSON), `platformDocumentId`/`platformChunkId`, `pageStart/pageEnd`, `chunkRole`, `blockIds`, `parentId/parentChain`, and merges everything from the document and chunk metadata maps (`putMetadataEntries` at `:162` and `:176`). No verified producer emits a `file_path` key today (the chunking strategies under `service/chunking` contain none), so relations would be stored as `unknown_source` until the platform adds the key; because document/chunk metadata is merged through, a document-level key would also reach chunks — either way the write side must be re-verified at upgrade time before enabling the cap (rollout checklist HARD GATE). The platform deletion adapter also rebuilds `RelationRecord` without `filePath` and would drop accumulated paths on rewrites.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest" --tests "io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStoreTest"`
Expected: PASS. `WorkspaceScopedNeo4jGraphStoreTest` already asserts a `<SEP>`-joined `file_path` round trip; extend it with an ingest-level assertion only if it breaks.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/FilePathLimits.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphAssembler.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/GraphMaterializationPipeline.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/FilePathLimitsTest.java
git commit -m "feat: accumulate relation file paths from chunk metadata with upstream caps"
```

---

## Phase 2 — Extraction robustness

### Task 6: Extraction record caps in the prompt

Upstream injects per-response quantity limits into the extraction prompts (`max_total_records=100`, `max_entity_records=40`; `constants.py:26-27`, `lightrag.py:768-780`, `prompt.py:95-96,132,155,201-202,241,264`, template slots filled at `operate.py:4021-4054`). Java's prompt (`KnowledgeExtractor.java:41-105`) has no quantity guidance, so a long response can blow the provider's output limit wholesale.

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`:41-126`, `:668-674`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `config/LightRagConfig.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void systemPromptCarriesTheConfiguredRecordCaps() {
    var extractor = extractorWithCaps(/*maxRecords*/ 7, /*maxEntities*/ 3);
    extractor.extract(chunk("..."));
    assertThat(model.lastRequest().systemPrompt())
        .contains("at most 7 total records")
        .contains("at most 3 entity objects");
}

@Test
void defaultsMatchUpstreamLimits() {
    assertThat(KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_RECORDS).isEqualTo(100);
    assertThat(KnowledgeExtractor.DEFAULT_MAX_EXTRACTION_ENTITIES).isEqualTo(40);
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest"`
Expected: FAIL — constants and prompt slots absent.

- [ ] **Step 3: Implement**

Add the two constants and two `%d` slots to `SYSTEM_PROMPT_TEMPLATE` inside the "3. Output Rules" block (`:73-104`), worded like upstream: `- Output at most %d total records across entities and relations in this response.` / `- Output at most %d entity objects in this response.` — the template uses `String.formatted`, so switch the three existing `%s` slots to explicit indices (`%1$s`, `%2$s`, `%3$s`) and append `%4$d`, `%5$d`; update `buildSystemPrompt()` (`:668-674`) accordingly. Add the same two lines to `CONTINUE_USER_PROMPT` (`:106-126`) with upstream's gleaning wording ("…and at most %d entity rows; a relationship row may reference entities already extracted correctly in the previous response.").

Constructor: `entityExtractMaxRecords` / `entityExtractMaxEntities` (validated `>= 1`), builder options `.entityExtractMaxRecords(int)` / `.entityExtractMaxEntities(int)`, wired through `GraphExtractionOptions` (`api/GraphExtractionOptions.java`) and `LightRagConfig`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest" --tests "io.github.lightrag.query.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java
git commit -m "feat: add upstream extraction record caps to the extraction prompts"
```

---

### Task 7: `kgExtractionValidator` hook

Upstream exposes a caller-supplied validator invoked per chunk with the chunk key, the extraction-visible chunk text and the parsed `(maybe_nodes, maybe_edges)` pair, replacing them with its return value; the return shape is validated (`lightrag.py:1310`, `operate.py:3983,4388-4421`; added 2026-08-22). Java has no hook.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/KgExtractionValidator.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`extractWithCacheIds` `:234-269`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `config/LightRagConfig.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void validatorSeesEachChunkAndCanDropEntitiesAndRelations() {
    var seen = new ArrayList<String>();
    var extractor = extractorWithValidator((chunkId, text, result) -> {
        seen.add(chunkId);
        return new ExtractionResult(List.of(), List.of(), result.warnings());
    });
    var result = extractor.extract(chunkWithText("chunk-1", "raw text"));
    assertThat(seen).containsExactly("chunk-1");
    assertThat(result.entities()).isEmpty();
    assertThat(result.relations()).isEmpty();
}

@Test
void validatorReturningNullFailsTheChunkWithAClearMessage() {
    assertThatThrownBy(() -> extractorWithValidator((id, text, result) -> null).extract(chunk("c1")))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("kgExtractionValidator must return an ExtractionResult");
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.KnowledgeExtractorTest"`
Expected: FAIL — no such hook.

- [ ] **Step 3: Implement**

```java
package io.github.lightrag.api;

@FunctionalInterface
public interface KgExtractionValidator {
    /** Called once per chunk after parsing/gleaning and before merging; return the accepted pair. */
    ExtractionResult validate(String chunkId, String chunkText, ExtractionResult extracted);
}
```

In `KnowledgeExtractor.extractWithCacheIds`, after the gleaning loop and before returning: `if (validator != null) { current = Objects.requireNonNull(validator.validate(chunk.id(), chunk.text(), current), "kgExtractionValidator must return an ExtractionResult"); }` — placed **before** any future multimodal injection (upstream's ordering rationale at `operate.py:4376-4387`) and it sees the raw chunk text, matching upstream's byte-for-byte contract.

Constructor + builder option `.kgExtractionValidator(KgExtractionValidator)`; wire in `LightRag.newIndexingPipeline` / `newGraphMaterializationPipeline` via `GraphExtractionOptions`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/KgExtractionValidator.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java
git commit -m "feat: add a per-chunk kg extraction validator hook"
```

---

### Task 8: Section-context breadcrumb in the extraction prompt

Upstream injects an optional `---Section Context---` block built from the chunk's heading breadcrumb, char-capped per level (80 chars) and token-budgeted at 256 tokens, collapsing to `first → … → leaf` over budget; the block is empty when the chunk has no heading so the prompt stays byte-identical otherwise (`operate.py:4112-4131`, `constants.py:39-47`, prompt template `entity_extraction_section_context`; landed 2026-06-06/06-08). Java records heading metadata in two shapes but never feeds the extraction prompt: `smart_chunker.section_path` (`SmartChunkMetadata.java:4`, written by `SmartChunker.java:666` and `ParagraphSemanticChunker.java:531`) and `paragraph_semantic.heading` / `parent_headings` (`ParagraphSemanticChunker.java:28-29,536-537`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/SectionContextFormatter.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`buildUserPrompt` `:648-662`, `CONTINUE_USER_PROMPT`)
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/SectionContextFormatterTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/indexing/KnowledgeExtractorTest.java`, `E2ELightRagTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// SectionContextFormatterTest
@Test
void buildsTheBreadcrumbFromSectionPathOrHeadingMetadata() {
    assertThat(SectionContextFormatter.breadcrumb(chunk(Map.of("smart_chunker.section_path", "Doc > Ch 1 > Fees")), counter()))
        .containsExactly("Doc", "Ch 1", "Fees");
    assertThat(SectionContextFormatter.breadcrumb(chunk(Map.of(
        "paragraph_semantic.parent_headings", "Doc > Ch 1", "paragraph_semantic.heading", "Fees")), counter()))
        .containsExactly("Doc", "Ch 1", "Fees");
}

@Test
void collapsesToFirstAndLeafOverTheTokenBudget() { /* long headings -> ["Doc", "…", "Fees"] */ }

@Test
void capsEachLevelAtEightyChars() { /* 200-char heading -> 80 chars */ }

// KnowledgeExtractorTest
@Test
void chunkWithHeadingMetadataGetsASectionContextBlockAndOthersDoNot() {
    // userPrompt contains "---Section Context---\nDoc > Ch 1 > Fees" for the tagged chunk
    // and contains no "Section Context" for the plain chunk
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.SectionContextFormatterTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`SectionContextFormatter` reads `Chunk.metadata()`, prefers `paragraph_semantic.parent_headings` + `paragraph_semantic.heading`, falls back to `smart_chunker.section_path` split on `>` / `|` (the P chunker writes `Document > Section`; the parent-child builder writes `section | sentence` — split on both), strips, drops blanks, applies the 80-char per-level cap, then the 256-token budget with the `first → … → leaf` collapse. Method `String block(Chunk chunk, TokenCounter counter)` returns the ready-to-embed text (`""` when absent).

`KnowledgeExtractor.buildUserPrompt` inserts the block into both the primary and continue prompts:

```
---Data to be Processed---
Chunk ID: %s
Document ID: %s
%s
<Input Text>
```

i.e. a `heading_context_block` line that is exactly `""` when there is no heading — verify with a test that the prompt bytes are unchanged for headless chunks. Knob `.enableSectionContext(boolean)` default `true` (upstream `enable_content_headings` default on) on the builder; when false the block is never built.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/SectionContextFormatter.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/SectionContextFormatterTest.java
git commit -m "feat: inject section-context breadcrumbs into the extraction prompt"
```

---

### Task 9: LaTeX escape repair for extracted text

LLMs writing LaTeX inside JSON strings under-escape backslashes, so `\frac` arrives as form-feed + `frac` and `\beta` as backspace + `eta` after JSON decoding. Upstream repairs the two zero-risk cases (form-feed/backspace + letter), additionally repairs whitespace-class damage **inside paired dollar math**, and logs suspected-but-unrepaired damage (`utils.py:6357-6436`, patterns `:6046-6238`, contract `docs/design/LatexEscapeRepairContract.md`; the JSON-path call site is `operate.py:955-960`). Java has no repair.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/indexing/LatexEscapeRepair.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/TextSanitizer.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`parseExtractionResult` `:293-300`)
- Create: `lightrag-core/src/test/java/io/github/lightrag/indexing/LatexEscapeRepairTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void repairsFormFeedAndBackspaceLatexDamage() {
    assertThat(LatexEscapeRepair.repair("a \f rac{b}{c} and \b eta", "")).isEqualTo("a \\frac{b}{c} and \\beta");
}

@Test
void repairsWhitespaceClassDamageInsideDollarMathOnly() {
    assertThat(LatexEscapeRepair.repair("$\\tau = 1$", "")).isEqualTo("$\\tau = 1$");
    assertThat(LatexEscapeRepair.repair("tab\there in prose", "")).isEqualTo("tab\there in prose"); // untouched outside math
}

@Test
void leavesIsolatedControlCharactersForSanitization() { /* "x\fy" without a following letter stays, then TextSanitizer drops it */ }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.LatexEscapeRepairTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

Port the two regex families verbatim (`\f` + letter → `\\f` + letter; `\b` + letter → `\\b` + letter), the paired-dollar-span scanner with the whitespace-class command-name map (`\t`→`\tau`, `\n`→`\nu`, `\r`→`\rho`, `\v`→`\vee`, `\f`→`\phi`, `\b`→`\beta` when the residue is a known command name), and the suspect logger. Java replacement strings need `Matcher.quoteReplacement("\\f")`.

Apply in `KnowledgeExtractor.parseExtractionResult`: run entity name/description/aliases and relation keywords/description through `LatexEscapeRepair.repair` (upstream applies the nested variant to every parsed string leaf); apply before the sanitizer in `TextSanitizer` so isolated control characters are dropped afterwards.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.indexing.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/indexing/LatexEscapeRepair.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/TextSanitizer.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/test/java/io/github/lightrag/indexing/LatexEscapeRepairTest.java
git commit -m "feat: repair LaTeX escape damage in extracted text"
```

---

## Phase 3 — Model layer and engineering

### Task 10: Chat request options

Upstream binding options cover `temperature`/`max_tokens`/`top_p`/`stop`/`reasoning_effort` etc. (`llm/binding_options.py:525-781`) and extraction/keyword calls request `response_format={"type":"json_object"}`. Java's payload is `model`/`messages`/`stream` only (`OpenAiCompatibleChatModel.java:98-114`) and `ChatRequest` has three components (`ChatModel.java:17-21`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/ChatRequestOptions.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java` (`ChatRequest`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` (`:98-114`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java` (`:241,256,274`), `query/QueryKeywordExtractor.java:102`
- Modify: `lightrag-spring-boot-starter/.../LightRagProperties.java`, `LightRagAutoConfiguration.java`; `api/LightRagBuilder.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/ChatRequestOptionsTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void requestOptionsAreSerializedIntoTheProviderPayload() throws Exception {
    try (var server = new MockWebServer()) {
        server.enqueue(new MockResponse().setBody("""
            {"choices":[{"message":{"content":"Answer"}}]}"""));
        server.start();
        // constructor order is (baseUrl, modelName, apiKey, timeout, defaults) — OpenAiCompatibleChatModel.java:34-49
        var model = new OpenAiCompatibleChatModel(server.url("/v1/").toString(), "gpt-4o-mini", "secret",
            Duration.ofSeconds(5), new ChatRequestOptions(0.2d, 512, 0.9d, "json_object"));

        model.generate(new ChatRequest("system", "user"));

        assertThat(server.takeRequest().getBody().readUtf8())   // MockWebServer has no lastRequestBody()
            .contains("\"temperature\":0.2")
            .contains("\"max_tokens\":512")
            .contains("\"top_p\":0.9")
            .contains("\"response_format\":{\"type\":\"json_object\"}");
    }
}

@Test
void perRequestOverridesWinOverModelDefaults() throws Exception {
    try (var server = new MockWebServer()) {
        server.enqueue(new MockResponse().setBody("""
            {"choices":[{"message":{"content":"Answer"}}]}"""));
        server.enqueue(new MockResponse().setBody("""
            {"choices":[{"message":{"content":"Answer"}}]}"""));
        server.start();
        var baseUrl = server.url("/v1/").toString();
        var timeout = Duration.ofSeconds(5);
        // model default maxTokens 512, per-request 64 -> the payload carries 64, never 512: merge
        // direction is defaults.merge(request.options()), so the request's non-null fields win.
        var model = new OpenAiCompatibleChatModel(baseUrl, "gpt-4o-mini", "secret", timeout,
            new ChatRequestOptions(null, 512, null, null));                 // model-level default
        model.generate(new ChatRequest("system", "user",
            new ChatRequestOptions(null, 64, null, null)));                 // per-request override
        var overrideBody = server.takeRequest().getBody().readUtf8();
        assertThat(overrideBody).contains("\"max_tokens\":64").doesNotContain("\"max_tokens\":512");
        // unset request fields fall back to the model default: the response format default survives
        var fallback = new OpenAiCompatibleChatModel(baseUrl, "gpt-4o-mini", "secret", timeout,
            new ChatRequestOptions(null, null, null, "json_object"));
        fallback.generate(new ChatRequest("system", "user",
            new ChatRequestOptions(0.2d, null, null, null)));
        var fallbackBody = server.takeRequest().getBody().readUtf8();
        assertThat(fallbackBody)
            .contains("\"temperature\":0.2")
            .contains("\"response_format\":{\"type\":\"json_object\"}");
    }
}

@Test
void extractionRequestsAskForJsonObjectResponses() {
    assertThat(model.lastRequest().options().responseFormat()).isEqualTo("json_object");
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.ChatRequestOptionsTest"`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Implement**

```java
public record ChatRequestOptions(Double temperature, Integer maxTokens, Double topP, String responseFormat) {
    public static final ChatRequestOptions NONE = new ChatRequestOptions(null, null, null, null);
    public static final ChatRequestOptions JSON_OBJECT = new ChatRequestOptions(null, null, null, "json_object");
    public ChatRequestOptions { /* range validation: temperature 0..2, maxTokens >= 1, topP 0..1 */ }
    public ChatRequestOptions merge(ChatRequestOptions override) { /* non-null fields of override win */ }
}
```

`ChatRequest` gains a fourth component `ChatRequestOptions options`; keep the 3-arg `(system, user, conversationHistory)` and 2-arg `(system, user)` convenience constructors delegating with `ChatRequestOptions.NONE` so all existing call sites and the 21 test fakes compile unchanged, and add a `(systemPrompt, userPrompt, ChatRequestOptions options)` convenience constructor (the three-parameter lists differ by third-component type, so overload resolution stays unambiguous) for the per-request override test above. `OpenAiCompatibleChatModel` holds instance-level defaults (`ChatRequestOptions`) and writes **`defaults.merge(request.options())`** into the payload, omitting null fields — the receiver is the base and the argument's non-null fields win, so a per-request override beats the model default; `request.options().merge(defaults)` would be backwards and let the model defaults clobber the request (the `perRequestOverridesWinOverModelDefaults` test above would fail).

Call sites: `KnowledgeExtractor` passes `ChatRequestOptions.JSON_OBJECT` on the primary and gleaning extraction requests (upstream sends `response_format` for extraction); `QueryKeywordExtractor` likewise. Prompt text that asks for JSON-only output stays.

Spring: `lightrag.chat.temperature`, `.max-tokens`, `.top-p`, `.response-format` plus the same keys under every role block (`query-model`, `keyword-model`, `extraction-model`); builder `.chatRequestOptions(ChatRequestOptions)` as the programmatic path.

**Platform note:** the SDK side currently has no options at all — `ChatModel.ChatRequest` is three components (`ChatModel.java:17-21`) and the OpenAI-compatible payload carries only `model`/`messages`/`stream` (`OpenAiCompatibleChatModel.java:98-114`). aiplatform is a separate story: `LightRagPlatformChatModel` already sends its own `temperature`/`max_tokens` over direct HTTP (`:216-229`) and ignores `ChatRequestOptions` entirely; options take effect there only after the adapter forwards them (the `response_format` for extraction is the real win). Tracked in the rollout checklist, including the adapter acceptance test that asserts the outgoing payload carries the options.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.*" --tests "io.github.lightrag.indexing.*"` then `./gradlew :lightrag-spring-boot-starter:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/ChatRequestOptions.java \
        lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java \
        lightrag-core/src/test/java/io/github/lightrag/model/ChatRequestOptionsTest.java
git commit -m "feat: send temperature, max tokens, top-p and response format with chat requests"
```

---

### Task 11: Response metadata, truncation markers, cache guard

Upstream providers return `TruncatedResponse` markers when `finish_reason == "length"` and report token usage through `TokenTracker` (`utils.py:5200-5223,6867-6922`), and truncated answers are never cached. Java discards `finish_reason` (`OpenAiCompatibleChatModel.java:81-87`) and `usage` (zero hits in main), and `CachedChatModel` caches everything it sees.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/ChatResponse.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/CachedChatModel.java` (`:23-32`), `model/openai/OpenAiCompatibleChatModel.java` (`:54-87`, stream `:274-277`)
- Modify: `lightrag-core/src/test/java/io/github/lightrag/model/CachedChatModelTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/ChatResponseTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void mapsFinishReasonLengthToATruncatedResponse() {
    // server replies choices[0].finish_reason == "length", usage {prompt_tokens: 12, completion_tokens: 3}
    var response = model.generateResponse(new ChatRequest("s", "u"));
    assertThat(response.truncated()).isTrue();
    assertThat(response.usage().completionTokens()).isEqualTo(3);
}

@Test
void cachedModelDoesNotCacheTruncatedResponses() {
    var delegate = new ChatModel() {
        @Override public String generate(ChatRequest request) { return "partial"; }
        @Override public ChatResponse generateResponse(ChatRequest request) { return new ChatResponse("partial", "length", null); }
    };
    var cached = new CachedChatModel("extract", delegate, store);
    cached.generateResponse(new ChatRequest("s", "u"));
    assertThat(store.snapshot()).isEmpty();
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.CachedChatModelTest"`
Expected: FAIL — `generateResponse` does not exist.

- [ ] **Step 3: Implement**

```java
public record ChatResponse(String content, String finishReason, Usage usage) {
    public record Usage(Integer promptTokens, Integer completionTokens) {}
    public boolean truncated() { return "length".equalsIgnoreCase(finishReason == null ? "" : finishReason); }
    public static ChatResponse of(String content) { return new ChatResponse(content, null, null); }
}
```

`ChatModel` keeps `generate` as the abstract method and adds:

```java
/** Providers with response metadata override this; the default adapts the plain-content contract. */
default ChatResponse generateResponse(ChatRequest request) { return ChatResponse.of(generate(request)); }
```

This keeps every existing fake compiling. `OpenAiCompatibleChatModel` implements `generate` (delegating to `generateResponse`) and overrides `generateResponse` to read `finish_reason` + `usage`. Streaming chunks are unchanged except that a terminal chunk carrying `finish_reason` marks the stream result; the streaming consumer (`QueryEngine`) is untouched in this task (progress events stay out of scope).

`CachedChatModel`:

```java
@Override public String generate(ChatRequest request) { return generateResponse(request).content(); }
@Override public ChatResponse generateResponse(ChatRequest request) {
    // cache read/write as today, but a truncated response is returned without being stored
}
```

Note the dependency on the query plan's Task 14 (key policy) if it has landed: apply the truncation guard to whichever key construction is present.

**Platform note:** for aiplatform this guard is inert until `LightRagPlatformChatModel` overrides `generateResponse` to report `finish_reason`/`usage` — it currently reads only `choices[0].message.content` (the full response body is parsed at `:1139-1165`; `finish_reason`/`usage` are dropped, and it throws on empty content), so truncation passes silently. Tracked in the rollout checklist, including the adapter acceptance test (`finish_reason="length"` surfaces, truncated answer skipped by the answer cache, `usage` populated).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.*" --tests "io.github.lightrag.query.*" --tests "io.github.lightrag.indexing.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/ChatResponse.java \
        lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/CachedChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java \
        lightrag-core/src/test/java/io/github/lightrag/model/CachedChatModelTest.java \
        lightrag-core/src/test/java/io/github/lightrag/model/ChatResponseTest.java
git commit -m "feat: capture finish reason and token usage, never cache truncated answers"
```

---

### Task 12: Retry and error classification

Upstream classifies provider errors (408/409/5xx/transient-400 retryable, permanent 429 fail-fast) and retries up to 3 attempts with backoff (`llm/openai.py:203-304,522-551`, `_error_utils.py:30-58`). Java issues one attempt (`OpenAiCompatibleChatModel.java:116-139`); the repo's only retry precedent is `PostgresRetrySupport` (3 attempts, 100 ms → 1 s exponential, transient-only).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/openai/ModelRetrySupport.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` (`:116-139`), `OpenAiCompatibleEmbeddingModel.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/openai/ModelRetrySupportTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void retriesTransientStatusesAndGivesUpPermanently() {
    var attempts = new AtomicInteger();
    var result = ModelRetrySupport.call(() -> {
        if (attempts.incrementAndGet() < 3) throw new ModelException("boom", 503, null, null);
        return "ok";
    }, 3, Duration.ofMillis(1));
    assertThat(result).isEqualTo("ok");
    assertThat(attempts).hasValue(3);
}

@Test
void doesNotRetryPermanentRateLimitOrBadRequest() {
    assertThat(ModelRetrySupport.isRetryable(new ModelException("rate", 429, null, null))).isFalse();
    assertThat(ModelRetrySupport.isRetryable(new ModelException("bad", 400, null, null))).isFalse();
    assertThat(ModelRetrySupport.isRetryable(new ModelTimeoutException("t"))).isTrue();
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.openai.ModelRetrySupportTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`ModelRetrySupport.call(Supplier<T>, maxAttempts, initialBackoff)` with exponential backoff capped at 1 s, `Thread.sleep` with interrupt restoration, and `isRetryable(Throwable)`: retryable = `ModelTimeoutException`, IO/timeout causes, HTTP 408/409/5xx, and 400 whose message matches the transient markers upstream uses; non-retryable = 429, other 4xx, `IllegalArgumentException`, `ExtractionException`. Reuse `ModelException`'s existing status field (`exception/ModelException.java:42-56`).

Wire: `OpenAiCompatibleChatModel`/`OpenAiCompatibleEmbeddingModel` wrap their HTTP execution in `ModelRetrySupport.call(...)` with constructor-provided `maxAttempts`/`initialBackoff` (defaults 3 / 100 ms) — request bodies are immutable, so retries are safe. Builder `.modelMaxAttempts(int)` and Spring `lightrag.chat.max-attempts` / `lightrag.embedding.max-attempts` (0 or 1 disables).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/openai/ModelRetrySupport.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java \
        lightrag-core/src/test/java/io/github/lightrag/model/openai/ModelRetrySupportTest.java
git commit -m "feat: retry transient model failures with exponential backoff"
```

---

### Task 13: Per-role LLM concurrency budget

Upstream wraps every role function in a priority limiter with `MAX_ASYNC=4` for LLMs, `EMBEDDING_FUNC_MAX_ASYNC=8` for embeddings and `MAX_PARALLEL_INSERT=3` for insert concurrency (`constants.py:96,97,715`; `utils.py:1193+`; `llm_roles.py:52-79`). Java's only limiter is the per-workspace document gate (`TaskExecutionService.java:509-519`); chunk extraction fan-out is `chunkExtractParallelism` per document and multiply by concurrent documents, with no global cap. Java's default `maxParallelInsert=2` also lags upstream's 3 (`LightRagBuilder.java:53-55`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/LlmConcurrencyBudget.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/LimitedChatModel.java`, `LimitedEmbeddingModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` (`cachedModel` `:1034-1036`, wiring `:792-795`, `:872-874`, `:1019-1021`, embedding wiring `:795,874,1002-1006`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` (`:53-55`), `config/LightRagConfig.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/LlmConcurrencyBudgetTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void capsConcurrentChatCallsPerRole() throws Exception {
    var budget = new LlmConcurrencyBudget(2, 8);
    var delegate = new BlockingChatModel();               // records max observed concurrency
    var model = budget.limitChat("extract", delegate);
    var threads = IntStream.range(0, 8).mapToObj(i -> Thread.ofPlatform().start(() -> model.generate(request()))).toList();
    for (var thread : threads) thread.join();
    assertThat(delegate.maxConcurrent()).isEqualTo(2);
}

@Test
void distinctRolesDoNotShareLLMSlots() { /* "query" saturates its own semaphore without blocking now-blocked "extract" */ }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.LlmConcurrencyBudgetTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

```java
public final class LlmConcurrencyBudget {
    public LlmConcurrencyBudget(int maxAsyncLlm, int embeddingMaxAsync) { /* fair semaphores */ }
    public ChatModel limitChat(String role, ChatModel delegate) { return new LimitedChatModel(semaphoreFor(role), delegate); }
    public EmbeddingModel limitEmbedding(EmbeddingModel delegate) { return new LimitedEmbeddingModel(embeddingSemaphore, delegate); }
}
```

`LimitedChatModel` acquires/releases around `generate` and each `generateResponse` call, restoring the interrupt flag when `acquire` is interrupted and throwing `ModelTimeoutException`-style `IllegalStateException("interrupted while waiting for an LLM slot")` — pick one and keep it consistent with the repo's interruption style (`IndexingPipeline.mergeGroupedConcurrently` sets the interrupt flag and throws `RuntimeException`). The budget deliberately exposes **no standalone acquire/release API**: the slot's lifetime is exactly one delegate call, which is what makes the platform's gate → model nesting deadlock-free (see the platform note; an application-held lease would reintroduce slot → gate orderings).

Wiring order matters: `budget.limitChat(role, cachedModel(role, delegate, cacheStore))` — the cache must sit **outside** the limiter so cache hits do not consume a slot (upstream behavior). Embedding gets the same treatment at the four embedding wiring sites. Skip the limiter for `NoopEmbeddingModel`.

Defaults: `maxAsyncLlm=4`, `embeddingMaxAsync=8`, `maxParallelInsert` 2→3; builder `.maxAsyncLlm(int)`, `.embeddingMaxAsync(int)`; Spring `lightrag.max-async-llm`, `lightrag.embedding-max-async`, `lightrag.max-parallel-insert`. Note in the class javadoc that upstream's *priority* ordering (summary priority 8, `constants.py:683`) is intentionally not ported because Java's pipelines block; fairness is the substitute.

**Platform note (gate nesting):** the budget wraps every `ChatModel`, including platform adapters, and aiplatform already gates concurrency above the SDK. Verified ordering today: `WikiKnowledgeIndexWriter.generateWiki` acquires a `WikiModelStageConcurrencyGate` permit (`:4090-4091`) and calls the model **inside** that permit (`:4092` → `generateWikiUnbounded:4095-4100`); those models come from `resolveWikiModel()` (platform adapters). The one sanctioned nesting is therefore:

```java
// CORRECT — aligned line-by-line with the platform method (WikiKnowledgeIndexWriter.generateWiki:4060-4094);
// the real method additionally throws on model == null (:4061-4063) and falls through to
// generateWikiUnbounded when the gate is absent (:4065-4067), both omitted here for brevity:
private String generateWiki(ChatModel model, ChatModel.ChatRequest request) {
    WikiModelStageConcurrencyGate gate = wikiModelStageConcurrencyGate;
    WikiIngestRuntimeConfig runtimeConfig = currentWikiIngestRuntimeConfig();
    String modelKey = runtimeConfig.synthesisModelConfigId() == null                    // :4068-4072
            ? "platform-default"
            : String.valueOf(runtimeConfig.synthesisModelConfigId());
    String stage = WIKI_MODEL_STAGE.get();                                              // :4073-4076
    if (!StringUtils.hasText(stage)) {
        stage = "general";
    }
    int requestedLimit = switch (stage) {                                               // :4077-4089
        case "map" -> runtimeConfig.mapParallel();
        case "reduce" -> runtimeConfig.reduceParallel();
        case "taxonomy" -> runtimeConfig.reduceParallel();
        case "page" -> runtimeConfig.pageGenerateParallel();
        default -> Math.min(runtimeConfig.mapParallel(),
                Math.min(runtimeConfig.reduceParallel(), runtimeConfig.pageGenerateParallel()));
    };
    // acquire(String modelKey, String stage, int requestedLimit) — WikiModelStageConcurrencyGate.java:26-32;
    // there is no acquire(stage) overload. The effective limit is
    // min(platformLimit, requestedLimit, modelLimit) with floor 1 (:35-42).
    try (WikiModelStageConcurrencyGate.Permit ignored = gate.acquire(modelKey, stage, requestedLimit)) {
        return generateWikiUnbounded(model, request);    // :4092 -> :4095-4100
        // The SDK slot is taken inside this call (LimitedChatModel) and released before returning.
    }
}

// FORBIDDEN — shape sketch, not compilable as written: never acquire a platform gate while holding
// an SDK slot (slot -> gate). A gate->slot thread (above) and a slot->gate thread (here) can
// deadlock each other.
var budgetLimited = budget.limitChat("wiki", platformModel);
budgetLimited.generate(request);     // if the platform model ever grew a gate acquisition inside, ...
```

- **Gate first, model second — never the reverse.** `LlmConcurrencyBudget` deliberately exposes no standalone "acquire a slot" API: `LimitedChatModel` takes its slot around exactly one `generate`/`generateResponse` call and releases it before returning, so SDK-internal code can never hold a slot across a gate wait. Any platform stage that needs the budget at stage level must acquire the *gate* first and call the budget-wrapped model inside it, as above.
- Slots are not re-entrant: do not invoke a budget-limited model of the same role from inside another model call of that role (fair semaphore, not a re-entrant lock) — relevant if a custom `kgExtractionValidator` (Task 7) or a future hook issues its own LLM call; call it outside the model window or give it its own role.
- With the 4-slot default a single process serializes LLM calls across knowledge bases — the platform must set `maxAsyncLlm`/`embeddingMaxAsync` explicitly at upgrade (rollout checklist) and size them above its own gate widths, or wiki stages will starve behind ingest.
- Add a concurrency stress test for the platform upgrade window: concurrent ingest + query + wiki generation, asserting no thread parks longer than a bounded window (starvation, not deadlock, is the realistic failure).

**Platform sizing source and constraints (verified 2026-10-01):** the platform's own LLM concurrency is bounded per `(modelKey, stage)` by `WikiModelStageConcurrencyGate`, whose effective limit is `min(platformLimit, requestedLimit, modelLimit)` with floor 1 (`WikiModelStageConcurrencyGate:35-42`). The inputs are real config, not guesses:

| Knob (platform) | Source | Default | Cap |
|---|---|---|---|
| gate `platformLimit` | `platform.knowledge.wiki.executor.max-concurrency` → `KnowledgeAsyncConfiguration:34` | 16 | 64 |
| `mapParallel` | `WikiIngestRuntimeConfig.mapParallel()` (`WikiKnowledgeIndexWriter:494`) | 4 (`CITATION_BATCH_CONCURRENCY:74`) | 40 (`:142`) |
| `reduceParallel` (also `taxonomy`) | `WikiIngestRuntimeConfig.reduceParallel()` (`:712`, gate `:4079-4081`) | 4 (`SUMMARY_BATCH_CONCURRENCY:76`) | 40 (`:143`) |
| `pageGenerateParallel` | `WikiIngestRuntimeConfig.pageGenerateParallel()` (`:3665,3696`) | 1 (`DEFAULT_WIKI_PAGE_GENERATE_PARALLEL:138`) | 16 (`:144`) |

Constraints this puts on the SDK budget: **`maxAsyncLlm` must be ≥ the sum of the platform stage widths that can be simultaneously active, plus ingest/query headroom.** With shipped defaults that is 4 (map) + 4 (reduce) + 4 (taxonomy) + 1 (page) = 13 concurrent wiki calls alone, so the SDK default 4 would queue ~9 of them; recommend ≥16 on the platform. Never set it below the largest single stage width (map/reduce = 4 with defaults), or that stage's own gate permits cannot be used concurrently. `embeddingMaxAsync` is the **only** bound on platform embedding fan-out (the platform knowledge module has no embedding-concurrency gate — the wiki gate covers LLMs only), so it must cover the ingest path's per-document chunk-batch concurrency × concurrent documents; raise it alongside `maxConcurrentDocumentTasks`, not independently.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.*" --tests "io.github.lightrag.indexing.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS. Watch for deadlock-prone tests that saturate the budget: tests constructing `LightRag` with a blocking fake model must raise `maxAsyncLlm` explicitly or use the default (4) which exceeds their thread counts.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/LlmConcurrencyBudget.java \
        lightrag-core/src/main/java/io/github/lightrag/model/LimitedChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/LimitedEmbeddingModel.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/config/LightRagConfig.java \
        lightrag-core/src/test/java/io/github/lightrag/model/LlmConcurrencyBudgetTest.java
git commit -m "feat: bound model concurrency with a per-role budget"
```

---

### Task 14: Production rerank binding

Upstream ships cohere/jina/aliyun HTTP rerankers with document chunking and score aggregation (`rerank.py:36-443`); Java has only the interface and test stubs (report §7). The query plan Task 9 aligns the *contract* and leaves the production binding to this plan.

**Platform relevance:** SDK-only — aiplatform disables SDK rerank (`LightRagRetrievalEngine.disableSdkRerank`) and reranks in its own service with heuristic fallback. This task is for standalone SDK users.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleRerankModel.java`
- Modify: `lightrag-spring-boot-starter/.../LightRagProperties.java`, `LightRagAutoConfiguration.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` (document `rerankModel` usage with the new binding)
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/openai/OpenAiCompatibleRerankModelTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void mapsCohereStyleResultsBackToCandidateIds() throws Exception {
    // same constructor convention as the sibling adapters: (baseUrl, modelName, apiKey, timeout)
    var model = new OpenAiCompatibleRerankModel(baseUrl, "bge-reranker-v2", apiKey, timeout);
    var results = model.rerank(new RerankRequest("query", List.of(
        new RerankCandidate("c1", "text one"), new RerankCandidate("c2", "text two")), 2));
    assertThat(results).extracting(RerankModel.RerankResult::id).containsExactly("c2", "c1");
    assertThat(server.takeRequest().getBody().readUtf8())   // MockWebServer: takeRequest(), no lastRequestBody()
        .contains("\"top_n\":2").contains("\"documents\":[\"text one\",\"text two\"]");
}

@Test
void rejectsResponsesWithUnknownIndexesOrNonFiniteScores() { /* -> ModelException */ }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.openai.OpenAiCompatibleRerankModelTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement**

`OpenAiCompatibleRerankModel implements RerankModel` issuing `POST {baseUrl}/rerank` with `{model, query, documents: [text...], top_n}` (Cohere/Jina-compatible), parsing `results: [{index, relevance_score}]`, mapping index → candidate id, validating index in range and score finite (upstream `normalize_rerank_result`, `utils.py:7026-7052`, covered by the query plan Task 9 contract), and reusing `ModelRetrySupport` + the shared OkHttp client construction from `OpenAiCompatibleChatModel`. Constructor `(baseUrl, modelName, apiKey, timeout)` — identical to the sibling adapters (`OpenAiCompatibleChatModel.java:34-49`, `OpenAiCompatibleEmbeddingModel.java:31-35`), matching the test snippet in Step 1 — plus an options-carrying overload for provider-specific extras (upstream `rerank_model_max_tokens` etc. stay out of scope; document that).

Spring: `lightrag.rerank.base-url`, `.model`, `.api-key`, `.timeout`, `.max-attempts`; the bean is only created when `base-url` is set (`@ConditionalOnProperty`), matching the role-model pattern (`LightRagAutoConfiguration.java:71-108`).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest" --tests "io.github.lightrag.model.*"` then `./gradlew :lightrag-spring-boot-starter:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleRerankModel.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java \
        lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java \
        lightrag-core/src/test/java/io/github/lightrag/model/openai/OpenAiCompatibleRerankModelTest.java
git commit -m "feat: add an OpenAI-compatible production rerank binding"
```

---

## Phase 4 — Storage contracts and ops

### Task 15: Graph read surface (labels, search, bounded BFS)

Upstream enforces `get_knowledge_graph` (frontier-capped BFS, `"*"` = whole graph ordered by degree then label, `is_truncated` flag), `get_all_labels`/`iter_labels`, and `search_labels` on every graph backend (`base.py:1119-1252`; networkx reference `kg/networkx_impl.py:1087-1250`; SDK entry `lightrag.py:2241-2270` with `max_graph_nodes` default 1000, `constants.py:13`). Java's `GraphStore` is CRUD-only (`GraphStore.java:12-148`) and the demo has no visualization backend.

**Platform relevance:** SDK-only today — aiplatform already builds entity search/pagination and a bounded BFS over `allEntities/allRelations` with a SQL keyset fallback. It is the natural adopter of `getKnowledgeGraph` (bounded traversal without the full load), but adoption is optional and tracked outside this plan.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/KnowledgeGraphView.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java` (add default methods)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` (new public API), `LightRagBuilder.java` (`maxGraphNodes` default 1000)
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/GraphStoreReadSurfaceTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagGraphReadApiTest.java`

- [ ] **Step 1: Write the failing tests**

```java
// GraphStoreReadSurfaceTest (runs against InMemoryGraphStore)
@Test
void labelsAreSortedDistinctEntityIds() { ... }

@Test
void searchLabelsMatchesIdAndNameCaseInsensitivelyAndHonorsTheLimit() { ... }

@Test
void starReturnsTheHighestDegreeNodesAndFlagsTruncation() {
    var view = store.getKnowledgeGraph("*", 3, 2);
    assertThat(view.truncated()).isTrue();
    assertThat(view.nodes()).hasSize(2);
    assertThat(view.nodes()).extracting(GraphEntity::id).containsExactly(/* two highest degree, label-ascending on ties */);
}

@Test
void bfsRespectsDepthAndMaxNodesAndIncludesOnlyEdgesAmongReturnedNodes() {
    var view = store.getKnowledgeGraph("a", 1, 10);
    assertThat(view.nodes()).extracting(GraphEntity::id).containsExactlyInAnyOrder("a", "b", "c");
    assertThat(view.edges()).allSatisfy(edge -> assertThat(view.nodes()).extracting(GraphEntity::id)
        .contains(edge.sourceEntityId(), edge.targetEntityId()));
}

@Test
void unknownLabelReturnsAnEmptyView() { ... }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.GraphStoreReadSurfaceTest"`
Expected: FAIL — methods do not exist.

- [ ] **Step 3: Implement**

Interface defaults (so every adapter — including the four wrappers — inherits them without edits):

```java
default List<String> labels() { /* distinct entity ids, insertion order from allEntities() */ }

default List<String> searchLabels(String query, int limit) { /* id or name contains, case-insensitive */ }

default KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) { ... }
```

BFS mirrors `kg/networkx_impl.py:1154-1230`: frontier-capped, per-level ordering by `(-degree, label)`, `has_unexplored_neighbors` sets `truncated`, edges emitted only when both endpoints are in the result (deduplicated by relation id). Degree comes from a `Map<String, Integer>` built from `allRelations()` once per call — document that adapters may override with a pushdown implementation (Postgres/Neo4j follow-up) because the default is O(graph) per call, which is fine for the 1000-node visualization budget but not for large graphs.

`LightRag` API: `getGraphLabels(workspaceId)`, `searchGraphLabels(workspaceId, query, limit)`, `getKnowledgeGraph(workspaceId, nodeLabel, maxDepth, maxNodes)`; `maxNodes` clamped to `maxGraphNodes` (default 1000, builder `.maxGraphNodes(int)`), `nodeLabel == "*"` supported. Reuse the existing `GraphEntity`/`GraphRelation` API records; `KnowledgeGraphView(List<GraphEntity> nodes, List<GraphRelation> edges, boolean truncated)`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.*" --tests "io.github.lightrag.api.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/KnowledgeGraphView.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/GraphStore.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/GraphStoreReadSurfaceTest.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagGraphReadApiTest.java
git commit -m "feat: expose bounded graph traversal and label search"
```

---

### Task 16: Embedding-space marker, typed refusal, Noop vector route

Upstream derives a collection suffix from the embedding model name + dimension (`base.py:365-382`), records the markers in the vector store and raises a typed `VectorSpaceMismatchError` on mismatch, clearable by the rebuild tool (`kg/vector_space.py:120-175`, `exceptions.py:702`). Java checks only column dimension at table creation (`PostgresSchemaManager.java:452-480`) and silently mixes vectors from same-dimension different-model embeddings. Upstream also ships a Noop vector backend so graphs can be built without vectors and materialized offline (`kg/noop_vector_db_impl.py:12-66`); Java's noop exists only as an anonymous test adapter (`VectorStorageAdapter.java:128`).

**Platform relevance:** SDK-only — aiplatform fixes the embedding model per KB and routes vectors through Milvus with its own resolver; the Noop route has no platform use case.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/EmbeddingSpaceStore.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/exception/VectorSpaceMismatchException.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/NoopVectorStore.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/StorageProvider.java` (optional accessor), `InMemoryStorageProvider.java`, `postgres/PostgresStorageProvider.java`, `postgres/PostgresSchemaManager.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/EmbeddingModel.java` (`default String cacheIdentity()`), `model/openai/OpenAiCompatibleEmbeddingModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/IndexingPipeline.java` (vector write sites `:1344-1430`), `GraphMaterializationPipeline.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/EmbeddingSpaceGuardTest.java`, `NoopVectorStoreTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void refusesWhenTheStoredMarkerDescribesADifferentEmbeddingSpace() {
    // marker recorded for "openai-compatible:text-embedding-3-small@url" / 1536
    var lightRag = builder.embeddingModel(fakeEmbedding("other-model", 1536)).build();
    assertThatThrownBy(() -> lightRag.ingest("default", List.of(document())))
        .isInstanceOf(VectorSpaceMismatchException.class)
        .hasMessageContaining("other-model");
}

@Test
void recordsTheMarkerOnFirstVectorWriteAndAcceptsAMatchingSpace() { ... }

@Test
void noopVectorStoreSkipsEmbeddingEntirelyAndStillPersistsTheGraph() {
    var lightRag = builder.noopVectorStore(true).embeddingModel(new NoopEmbeddingModel()).build();
    lightRag.ingest("default", List.of(document()));
    assertThat(lightRag.query("default", QueryRequest.builder().query("x").mode(QueryMode.LOCAL).build()).answer()).isNotNull();
    assertThat(graphStore().allEntities()).isNotEmpty();
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.EmbeddingSpaceGuardTest"`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Implement**

`EmbeddingSpaceStore` (`save(Marker)`, `Optional<Marker> load()`, `delete()`, `Marker(String modelIdentity, int dimensions, String recordedAt)`) follows the optional-store pattern of `StorageProvider.llmCacheStore()`: a `default` method that throws `UnsupportedOperationException(EMBEDDING_SPACE_STORE_UNSUPPORTED_MESSAGE)`. Implementations: InMemory (map) and Postgres (`embedding_space` table, workspace-scoped, added to `PostgresSchemaManager` DDL). Providers without it keep today's behavior; the guard logs once that space refusal is unavailable.

Guard behavior (in the pipelines, right before the first vector write): if a marker exists and `(modelIdentity, dimensions)` differ → `VectorSpaceMismatchException` naming both spaces and pointing at `rebuild-vdb`; if no marker → save one. `modelIdentity` comes from `EmbeddingModel.cacheIdentity()` (new default returning `"unknown"`; `OpenAiCompatibleEmbeddingModel` returns `"openai-compatible:" + model + "@" + baseUrl`), dimensions from the first produced vector's length. Do **not** probe with a dummy embedding call (cost); the first real write is early enough (`persistChunkVectorState`).

`NoopVectorStore implements VectorStore` (no-op `saveAll`, empty `search`/`list`); pipelines short-circuit vector construction when `storageProvider.vectorStore() instanceof NoopVectorStore`, so no embedding call is issued at all. `LightRagBuilder.noopVectorStore(boolean)` (default false) wraps the configured provider with a decorator overriding `vectorStore()`; validation (`LightRagBuilder.java:318-334`) still passes because a `VectorStore` is present. Document that query-time vector search silently returns nothing under this route and that it is meant to be paired with Task 18.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.*" --tests "io.github.lightrag.E2ELightRagTest"` then `./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest"`
Expected: PASS (the Postgres test needs Docker; skip and record if unavailable).

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/storage/EmbeddingSpaceStore.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/NoopVectorStore.java \
        lightrag-core/src/main/java/io/github/lightrag/exception/VectorSpaceMismatchException.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/StorageProvider.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/InMemoryStorageProvider.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java \
        lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresSchemaManager.java \
        lightrag-core/src/main/java/io/github/lightrag/model/EmbeddingModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleEmbeddingModel.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/EmbeddingSpaceGuardTest.java \
        lightrag-core/src/test/java/io/github/lightrag/storage/NoopVectorStoreTest.java
git commit -m "feat: refuse mismatched embedding spaces and support a noop vector route"
```

---

### Task 17: Document-status query surface

Upstream DocStatus supports status filtering, paging, and lookup by id set (`base.py:1589-2055`, `get_docs_by_ids`; SDK `aget_docs_by_ids`, `lightrag.py:5548`). Java exposes only get-by-id and list-all (`LightRag.java:515,523`).

**Platform relevance:** SDK-only — aiplatform keeps its own document status/dedup tables (`Document.docStatus`, `km_document*`) and only reads `StatusRecord` in the deletion adapter.

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` (`:515-527`)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/DocumentProcessingStatus.java` (add `metadata` passthrough)
- Create: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentStatusQueryTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void filtersByStatusWithOffsetAndLimit() {
    // 5 PROCESSED + 3 FAILED documents
    var page = lightRag.queryDocumentStatuses("default", Set.of(DocumentStatus.FAILED), 1, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo(DocumentStatus.FAILED));
    assertThat(page.total()).isEqualTo(3);
}

@Test
void looksUpSeveralDocumentsByIdAndOmitsUnknownIds() {
    assertThat(lightRag.getDocumentStatuses("default", List.of("d1", "missing")))
        .extracting(DocumentProcessingStatus::documentId).containsExactly("d1");
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagDocumentStatusQueryTest"`
Expected: FAIL — methods do not exist.

- [ ] **Step 3: Implement**

```java
public record DocumentStatusPage(List<DocumentProcessingStatus> items, int total, int offset, int limit) {}

public DocumentStatusPage queryDocumentStatuses(String workspaceId, Set<DocumentStatus> statuses, int offset, int limit);
public List<DocumentProcessingStatus> getDocumentStatuses(String workspaceId, List<String> documentIds);
```

Implemented over `documentStatusStore().list()` (in-memory filter + stable order by `documentId`, deterministic paging); document in the javadoc that pushdown is a follow-up (the store SPI stays unchanged in this pass). `DocumentProcessingStatus` gains the raw `metadata` map from `StatusRecord` so callers can read `file_path`-style keys without a second store call.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/api/DocumentProcessingStatus.java \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagDocumentStatusQueryTest.java
git commit -m "feat: add document status filtering, paging and batch lookup"
```

---

### Task 18: Offline `rebuild-vdb` CLI

Upstream's tool rebuilds each vector namespace from its authoritative source (entities ← graph nodes, relations ← graph edges, chunks ← chunk store), tolerates a typed `VectorSpaceMismatchError` on the vector targets (the condition it exists to clear), aborts on source-side failures, and offers a read-only consistency check (`lightrag/tools/rebuild_vdb.py`, module docstring; requires writers stopped). Java has no offline entry point; the closest precedent is the RAGAS CLI (`evaluation/RagasBatchEvaluationCli.java:14`, Gradle task `lightrag-core/build.gradle.kts:52-64`).

**Platform relevance:** SDK-only — aiplatform has in-service rebuild endpoints, wiki index rebuild and transfer bundles; the CLI serves standalone deployments (and later, an offline drift check for the platform's export path).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/ops/RebuildVectorIndexService.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/ops/RebuildVectorIndexCommand.java`
- Modify: `lightrag-core/build.gradle.kts` (register `runRebuildVdb`)
- Create: `lightrag-core/src/test/java/io/github/lightrag/ops/RebuildVectorIndexServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void checkModeReportsDriftWithoutWritingVectors() {
    var report = service.check("default");
    assertThat(report.missingEntityVectorIds()).containsExactly("e2");
    assertThat(vectorStore.list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(1);   // untouched
}

@Test
void rebuildModeRegeneratesAllThreeNamespacesFromTheAuthoritativeSources() {
    var report = service.rebuild("default");
    assertThat(vectorStore.list(StorageSnapshots.CHUNK_NAMESPACE)).hasSize(chunkCount);
    assertThat(vectorStore.list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(entityCount);
    assertThat(vectorStore.list(StorageSnapshots.RELATION_NAMESPACE)).hasSize(relationCount);
    assertThat(report.embeddedItems()).isEqualTo(chunkCount + entityCount + relationCount);
}

@Test
void rebuildRefusesToRunWithoutForceWhenTheEmbeddingSpaceMarkerIsMissing() { /* VectorSpaceMismatchException or IllegalStateException naming --force */ }
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.ops.RebuildVectorIndexServiceTest"`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Implement**

`RebuildVectorIndexService(StorageProvider, EmbeddingModel, LlmConcurrencyBudget, boolean force)`:

- `check(workspaceId)` — reads `graphStore.allEntities()/allRelations()` and `chunkStore.list()`, compares id sets with each vector namespace (`StorageSnapshots.CHUNK/ENTITY/RELATION_NAMESPACE`), returns `RebuildReport(missing*, stale*, embeddedItems)`; writes nothing.
- `rebuild(workspaceId)` — clears the three namespaces, then re-embeds using the exact payload builders the pipelines use (`HybridVectorPayloads.chunkPayloads/entityPayloads/relationPayloads`, `:19/:52/:73`) and `EmbeddingBatcher`, honoring the embedding concurrency budget (Task 13). Reuse `IndexingPipeline.saveChunkVectors/saveEntityVectors/saveRelationVectors` semantics by extracting them into a shared `GraphVectorIndexer` only if the extraction is mechanical; otherwise call the payload builders directly and document the duplication.
- Guard: when the marker store exists and a marker is present, verify identity/dimension before rebuilding and clear the marker afterwards so the next startup re-records it; when the marker store is unsupported or empty and `--force` is absent, fail with a message naming `--force`.

`RebuildVectorIndexCommand.main(String[])` parses `--workspace`, `--mode check|rebuild`, `--storage-profile` (reusing `evaluation/RagasStorageProfile.java:5` profile names and `RagasBatchEvaluationService`'s storage-construction switch `:93-145`), `--force`, and prints the report. Gradle:

```kotlin
tasks.register<JavaExec>("runRebuildVdb") {
    group = "ops"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lightrag.ops.RebuildVectorIndexCommand")
}
```

Document in the command's javadoc: stop all writers first (upstream states the same).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.ops.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/ops/RebuildVectorIndexService.java \
        lightrag-core/src/main/java/io/github/lightrag/ops/RebuildVectorIndexCommand.java \
        lightrag-core/build.gradle.kts \
        lightrag-core/src/test/java/io/github/lightrag/ops/RebuildVectorIndexServiceTest.java
git commit -m "feat: add an offline vector index rebuild command"
```

---

## Phase 5 — Verification

### Task 19: Full-suite verification and evaluation run

**Files:**
- Create: `docs/superpowers/specs/2026-09-30-build-side-parity-verification.md`

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: PASS (Spring Boot demo included). Record which Testcontainers-backed suites were skipped without Docker. Also assert the Task 13 default actually landed: `LightRagBuilder`'s `maxParallelInsert` reads 3 — as of this plan's writing the source still reads 2 (`LightRagBuilder.java:54`), so this is a **pending change to verify at execution time, not a description of current code**.

- [ ] **Step 2: Run the RAGAS batch evaluation**

Run: `./gradlew :lightrag-core:runRagasBatchEval`
Record graph-quality deltas (entity count, relation count, mean description length before/after Task 1) alongside the retrieval metrics, since this plan's dominant change is merge-time text quality rather than retrieval parameters.

- [ ] **Step 3: Exercise the offline tool against a drifted store**

Run the `runRebuildVdb` task with `--mode check` on a store whose vectors were deleted (assert the drift report), then `--mode rebuild --force` and `--mode check` again (assert clean). Record the three outputs in the verification note.

- [ ] **Step 4: Write the verification note**

`docs/superpowers/specs/2026-09-30-build-side-parity-verification.md` contains: commands run, pass/skip counts, evaluation deltas, the behavior-change table from this plan with a "verified by" column (test name or command), and every divergence that remains open (entity `file_path`, priority-based LLM scheduling, provider breadth, chunk-tracking store parity, pushdown graph traversal).

- [ ] **Step 5: Update README**

Document the new builder options (summarization thresholds, caps and methods, extraction caps, validator, request options, retries, concurrency budget, `noopVectorStore`, `maxGraphNodes`, `maxFilePaths`), the Spring properties, and the `runRebuildVdb` usage with its "stop writers first" warning.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-09-30-build-side-parity-verification.md README.md
git commit -m "docs: record build-side parity verification results"
```

---

## Self-Review

**Report coverage (2026-09-28 report sections → tasks):**

| Report item | Task |
|---|---|
| §2 Merge: summarization (`operate.py:372-531`) | 1 |
| §2 Merge: type/description handling (`operate.py:2384-2426,2576-2600`) | 1, 2 |
| §2 Merge: relation weight (`operate.py:2956-3000`) | 3 |
| §2 Source-id & file-path caps (`constants.py:71-84`) | 4, 5 |
| §2 Extraction prompt: record caps + section context + LaTeX repair (`lightrag.py:768`, `operate.py:3983,4120-4131,955`) | 6, 8, 9 |
| §2 Extraction limits: validator hook | 7 |
| §2 Extraction concurrency vs LLM budget | 13 |
| §4 Graph read contract (`base.py:1119-1252`) | 15 |
| §4 Embedding-space identity + Noop vector backend | 16 |
| §4 DocStatus richness (query surface only) | 17 |
| §6 Ops tools (`rebuild_vdb`) | 18 |
| §7 Request options (`binding_options.py:525-781`) | 10 |
| §7 Retries/error classification (`llm/openai.py:203-304`) | 12 |
| §7 Rerank model (production) | 14 |
| §7 Token accounting & truncation markers (`utils.py:5200-5223,6867-6922`) | 11 |
| §7 Roles: per-role concurrency | 13 |
| §7 Tokenizer (extraction budget) | out of scope here; query plan Task 10 owns `TokenCounter`, Task 1 consumes it |

**Placeholder scan:** no `TBD`/`TODO` tokens. Every step names exact files and line anchors; code blocks that are "port verbatim" (summary prompt, LaTeX patterns, BFS ordering) name the upstream source range so the implementer copies rather than invents.

**Type consistency:** `DescriptionSummarizer.Summary`, `DescriptionFragments.combine/split`, `TextSanitizer.sanitizeForEncoding`, `LatexEscapeRepair.repair`, `SourceIdLimits.apply/Method`, `FilePathLimits.apply`, `GraphChunkAttribution.entityHasAttribution/relationHasAttribution`, `RelationEvidence.merge`, `ChatRequestOptions.NONE/JSON_OBJECT/merge`, `ChatResponse.truncated/usage`, `ModelRetrySupport.call/isRetryable`, `LlmConcurrencyBudget.limitChat/limitEmbedding`, `EmbeddingSpaceStore.Marker`, `NoopVectorStore`, `KnowledgeGraphView.truncated`, `DocumentStatusPage.items/total/offset/limit`, `RebuildReport` are used with identical shapes everywhere they appear.

**Platform calibration (revised after Codex review round 1):** verified 2026-10-01 against the production consumer's working tree (`D:\ai-code\aiplatform`, mid-upgrade: `lightrag.version` = `0.24.0-SNAPSHOT`, both forked SDK classes staged-deleted, no positional pipeline construction remains). Per-task verdicts and the cross-repo rollout checklist live in the Platform calibration section. Tasks 14–18 are SDK-only; Tasks 10/11/13 need adapter-side or config changes on the platform side to take effect.

**Sequencing constraints:**
- Task 1 must land before Tasks 2 and 4 if implemented in one sitting: the description-fragment representation (`<SEP>`-joined) is what Task 2's dedup and Task 4's record shape assume.
- Tasks 1/2/3/4/5 reach the platform's ingest *and* rebuild paths as soon as the version bump lands (the fork is already deleted in the platform working tree); the remaining platform-side companion changes (attribution-aware deletion, `file_path` metadata, rollout checklist) are not fixable from this repository.
- Task 4 must land before Task 18's `--mode check` is meaningful on capped stores, because drift detection compares id sets.
- Task 11 (truncation guard) and the query plan's Task 14 both touch `CachedChatModel`; implement Task 11 after the key-policy change if both are in flight, and keep the truncation guard independent of key construction.
- Task 13 changes default concurrency (4 LLM slots); suites with blocking fake models must either raise `maxAsyncLlm` or stay below 4 concurrent calls.
- Task 16's Noop route must land before Task 18 (the CLI is its consumer), and Task 18's layout depends on `StorageSnapshots` namespaces which Task 16 does not change.

---

## Questions for Codex (review round 1)

Answer each question with `file:line` evidence from the actual sources (this repository, `D:\ai-code\LightRAG`, and for platform claims `D:\ai-code\aiplatform`). Round verdict format: per-question answer, then a `must-fix` / `should-fix` / `nit` list for this plan.

1. **Fork compatibility (Decision 8).** Verify the positional 21-argument `IndexingPipeline` construction in the platform fork (`D:\ai-code\aiplatform\backend\aide-kno\src\main\java\io\github\lightrag\api\LightRag.java:849`) and that every constructor/overload this plan adds keeps it compiling; find any other platform code touching signatures this plan changes (the forked `GraphMaterializationPipeline`, `storageProvider.llmCacheStore()`, `IndexingPipeline` fields).
2. **Task 1 summarizer.** Verify `DescriptionSummarizer`/`DescriptionFragments` shapes, the thresholds (8/1200/12000/600) and the `<SEP>` storage format against upstream (`operate.py:372-531`, constants); is the summary LLM call cached and is the cache key sound (does it cover the input fragment set)? What happens on summarizer failure mid-merge?
3. **Task 3 weight semantics.** Verify the upstream constant weight `1.0` (`operate.py:1084`), the evidence floor, and the claimed Java clamp sites (`KnowledgeExtractor.java:609-616,637-639`); flag every consumer that assumes `weight ∈ [0,1]` (query-side display, rerank, platform UI).
4. **Task 4 attribution-aware deletion.** Verify the platform's parallel deletion logic (`LightRagDeletionAdapter.filterEntity/filterRelation`) and that mirroring it is required; verify the SDK survival rule against `DocumentGraphSnapshotStore`.
5. **Task 13 concurrency.** Verify the defaults (`maxAsyncLlm=4`, `embeddingMaxAsync=8`, `maxParallelInsert` 2 → 3) against upstream constants; assess layering with the platform's `WikiModelStageConcurrencyGate` and the fork's chunk-parallel extraction (deadlock/starvation risk).
6. **Tasks 10/11 adapter boundary.** Verify `LightRagPlatformChatModel` does not implement request options / `finish_reason` / `usage` today, i.e. the "platform-side adapter work needed" notes.
7. **Tasks 14-18 SDK-only verdicts.** Verify each against the platform (SDK rerank disabled; graph browse; document-status tables; rebuild endpoints).
8. **Upstream anchors.** Spot-check per-task upstream ranges (`operate.py`, `constants.py`, `llm/openai.py`, `base.py`, `lightrag.py`) for drift.

---

## Review round 1 dispositions (Codex, 2026-10-01)

Review artifact: local Codex CLI run over both plans (`review-round1.md`); round verdict was "build plan not ready to start" with 7 must-fix items. Every claim below was re-verified against the sources before acting on it. Adjudication:

| # | Finding | Disposition |
|---|---|---|
| M1 | Decision 8 contradicts the platform state (claims forks kept until the 0.24.0 window) | **Accepted — fixed.** The retirement is already executed in the platform working tree (`0.24.0-SNAPSHOT`, both fork classes staged-deleted, no positional pipeline construction). Decision 8 and calibration facts 1-2 rewritten: constructor compatibility is now an SDK-API convention, not a platform compile requirement, and merge-semantics tasks reach ingest *and* rebuild from the first commit. |
| M2 | Task 4 attribution cannot derive entity/relation ids from the snapshot schema | **Accepted — fixed, with a different resolution than proposed.** The snapshot does not store ids, but ids are reproducible through the same path the write side uses: `GraphAssembler.assemble` over chunk snapshots (`GraphMaterializationPipeline.assembleChunkGraph:910-912` precedent), plus a name/alias fallback mirroring `DeletionPipeline.resolveEntityIds:516-529`. Task 4 now specifies that derivation, builds the index lazily only when caps are active, and adds edge-case tests; no snapshot schema change is needed. |
| M3 | Task 1 leaves the summarizer failure policy undefined | **Accepted — fixed.** Policy: propagate and fail the document, like upstream's map-reduce exceptions; no `<SEP>` fallback on error; a throwing-model test was added. |
| M4 | Task 1's cache must cover the full fragment set, not just the prompt hash | **Accepted as precondition — documented, then refined in round 2.** The content-addressed key is sound because the summary prompt materializes every decision input (type/name/fragment list/length/language); round 2 corrected the wording to "the fragment list **as truncated to `summaryContextSize`** — i.e. exactly what the model receives" and split the test into the two boundary directions (see round 2 dispositions). |
| M5 | Task 3 misses existing weight consumers (`GraphManagementPipeline:539`, `DefaultExtractionMergePolicy:72`, read-side scorers) | **Accepted — fixed.** A consumer sweep table now assigns every `Math.max(weight)` and weight-reading site a disposition: `GraphManagementPipeline` degrades through upstream's entity-merge rule (max of inputs, floored by distinct merged evidence); same-chunk merges keep max (both operands are 1.0); read-side/display sites may exceed 1 and must not assume `[0,1]`. |
| M6 | Task 13 does not define resource ownership against the platform's model-stage gate | **Accepted — fixed (rule corrected again in round 2).** Task 13's platform note records the verified nesting (`WikiKnowledgeIndexWriter:4090-4091` acquire → model `:4092`) and the safe rule: **gate first, model second** — the SDK slot is taken inside the platform gate for the duration of one model call, and a gate is never acquired while holding a slot (the round-1 wording had this reversed; see round 2 dispositions). Budget sizing above platform gate widths and a concurrency stress test were added; the rollout checklist mirrors the rule. |
| M7 | CLI contract inconsistent between Decision 7 (`--check`/`--rebuild`) and Task 18 (`--mode check\|rebuild`) | **Accepted — fixed.** Unified on `--workspace`/`--mode check\|rebuild`/`--storage-profile`/`--force` everywhere (Decision 7, behavior table, sequencing notes). Upstream is an interactive menu (`rebuild_vdb.py:1117-1131`) with an interactive stop-writers confirmation (`:1113`) — the non-interactive Java shape and the all-three-namespaces rebuild are now documented divergences. |

Should-fix items were also actioned: Task 5's platform note was rewritten with verified write-side evidence (`LightRagChunkDocumentMapper:162-192` metadata keys and pass-through merge; no verified `file_path` producer) and keeps the "verify before enabling" gate; Task 14-18 verdict rows now cite current-head evidence instead of the retired fork; Tasks 10/11 platform notes now separate "SDK has no request options today" (`ChatModel.java:17-21`, `OpenAiCompatibleChatModel.java:98-114`) from "the platform adapter already sends `temperature`/`max_tokens` but drops `finish_reason`/`usage`" (`LightRagPlatformChatModel:216-229,1139-1165`). The nit (`maxParallelInsert` default 2, `LightRagBuilder.java:54`) was folded into Decision 6.

Open verification for round 2: Task 13's budget sizing against the platform's gate widths needs the stress test at implementation time; Task 5's write-side `file_path` check happens at platform upgrade time; residual line-number drift in untouched task bodies should be re-checked when each task is implemented.

---

## Review round 2 dispositions (Codex, 2026-10-01)

Review artifact: `review-round2.md` (same local Codex CLI run, read-only sandbox over both plans + sources). Round verdict for this plan: **不可开工** — 4 must-fix, 4 should-fix, 1 nit. Three findings were accepted and fixed; one (cap vs floor) was re-adjudicated against the upstream source and settled with a different, evidence-backed fix than the one proposed. All items are actioned.

| # | Finding | Disposition |
|---|---|---|
| M1 | Concurrency rule contradicts itself (rollout checklist `:81` gate→model vs Task 13 body `:1264-1274` slot→gate) and lacks an executable nesting API | **Accepted — fixed, resolving toward the checklist (gate first), which is also what the platform code does.** The correct rule is **gate first, model second**: the platform acquires the `WikiModelStageConcurrencyGate` permit (`WikiKnowledgeIndexWriter:4090-4091`) and the SDK slot is taken *inside* it for exactly one model call (`:4092`); the reverse (acquiring a gate while holding a slot) can deadlock against it. Task 13's body now carries the executable CORRECT/FORBIDDEN code block, the no-standalone-acquire-API rule, a non-reentrancy warning, and the sizing constraint; the stale reversed wording in the round-1 dispositions row was corrected too. |
| M2 | Task 4 relation attribution does not resolve alias endpoints before building the relation id | **Accepted — fixed.** `relationHasAttribution` is now specified to run every assembled snapshot endpoint through the stored records' merge keys (normalized name + aliases → stored entity id) **before** `RelationCanonicalizer` computes the id, and `aliasSpelledRelationSurvivesDeletionThroughEndpointResolution` pins the alias-merged case. |
| M3 | Source-id cap leaves the weight floor depending on a truncated id list; keep an independent evidence count or let the attribution store supply the floor | **Re-adjudicated — refuted as proposed; fixed by explicit cap-before-floor ordering.** Upstream does *not* keep an independent evidence count: it caps `source_ids` first (`operate.py:2904`), joins (`:2954`), and only then computes `evidence_count` (`:2993-2998`) for the floor (`:3000`); the entity-merge collapse floors on the joined stored `source_id` the same way (`utils_graph.py:2886-2917`). A stored record therefore never has a floor above its capped view, and Java must mirror exactly that ordering rather than invent a pre-cap union. Task 3's `RelationEvidence.merge` contract, its call site, and Task 4's cap snippet now state cap→floor explicitly; `evidenceFloorCountsTheCappedStoredListNotThePreCapUnion` pins it (250 sources, cap 200 → floor 200, never 250); the `GraphManagementPipeline` sweep row records that the merged stored lists — already capped at write time — are the view upstream floors on. |
| M4 | Caps enablement condition not precise enough (is the shipped default 200 safely auto-disabled on an index-less provider?) | **Accepted — fixed.** The predicate is written as executable code: `capsRequested = maxSourceIdsPerEntity < Integer.MAX_VALUE || maxSourceIdsPerRelation < Integer.MAX_VALUE`; `attributionAvailable` = probe via `documentGraphSnapshotStore()` catching `UnsupportedOperationException`; `capsEnabled = capsRequested && attributionAvailable`, with exactly one WARN when requested-but-unavailable. So default 200 on an index-less backend disables caps (never silent truncation), and `Integer.MAX_VALUE` is the documented explicit opt-out. (Round 3 found the "exactly one WARN" claim unimplementable as a per-construction predicate — `LightRag` builds several pipelines per entry point; round 3 hoists the probe and the warn-once into the runtime-level memoized `GraphSnapshotCapabilities` — see round 3 dispositions M3.) |

Should-fix / nit: Task 1's cache tests now live in Step 1 **and** Step 4, including the truncation-boundary pair (a change inside the visible window must change the request text; a change only beyond the `summaryContextSize` cut must not — the model never saw it); the rollout checklist makes the relation `file_path` cap a **HARD GATE** tied to three verified-absent preconditions (`LightRagChunkDocumentMapper:161-192` emits no `file_path`; the deletion adapter drops `filePath`; no test shows real paths); Task 13 gained the verified platform sizing table (`WikiIngestRuntimeConfig` map/reduce/page defaults 4/4/1, caps 40/40/16, gate platform limit 16 via `KnowledgeAsyncConfiguration:34` + `WikiIngestExecutorProperties`) and the resulting `maxAsyncLlm ≥ Σ stage widths + headroom` constraint (≈13 wiki calls alone with defaults, recommend ≥16; `embeddingMaxAsync` is the only bound on embedding fan-out); the rollout checklist lists the Task 10/11/13 platform-adapter acceptance tests (`ChatRequestOptions` in the outgoing payload; `finish_reason`/`usage` parsed and truncated answers skipped by the answer cache; the concurrency stress test against the real gate) with cross-refs in the task bodies; the Task 19 verification step marks the `maxParallelInsert` 2→3 default as a **pending change to verify**, not current code (`LightRagBuilder.java:54` still reads 2 today).

Open verification for round 3: the cap→floor ordering must survive first implementation (both `IndexingPipeline.mergeRelation` and `GraphMaterializationPipeline` must pass the capped list, never the union); Task 4's caps and Task 3's floor land in the same two helpers, so whichever is implemented second must preserve the ordering; the platform sizing table is config-verified but its *deployed* values must be read back at upgrade time.

---

## Review round 3 dispositions (Codex, 2026-10-01)

Review artifact: `review-round3.md` (same local Codex CLI run, read-only). Round verdict for this plan: **不可开工** — 4 must-fix (one of them a regression introduced by the round-2 rewrites). All four are actioned in this revision; the two round-3 findings that had already been fixed in round 2 (Task 4 alias attribution, `file_path` HARD GATE) stay as they are.

| # | Finding | Disposition |
|---|---|---|
| M1 | Task 13's "executable" CORRECT block calls `gate.acquire(stage)`, but the platform API is `acquire(String modelKey, String stage, int requestedLimit)` — the block cannot compile | **Accepted — fixed.** The block now mirrors the real platform call shape (`try (WikiModelStageConcurrencyGate.Permit ignored = gate.acquire(modelKey, stage, requestedLimit))`), with the argument provenance inlined (modelKey = synthesis model config id / `"platform-default"`; stage = `WIKI_MODEL_STAGE.get()` default `"general"`; requestedLimit = per-stage parallel value) and the effective-limit formula `min(platformLimit, requestedLimit, modelLimit)`. Sources: `WikiKnowledgeIndexWriter.generateWiki:4060-4094` (acquire at `:4090-4091`, model call `:4092`, modelKey `:4068-4072`, stage `:4073-4076`, requestedLimit `:4077-4089`) and `WikiModelStageConcurrencyGate.java:26-32,35-42`. |
| M2 | `RelationEvidence.merge` counts cap-dropped new sources in `weight`: with 250 new sources under a KEEP cap of 200 the Java helper can produce 250 where upstream produces 200 | **Accepted — fixed.** The contract now takes `retainedIncomingChunkIds`, and the javadoc states the upstream order: the KEEP filter (`operate.py:2916-2929`) drops this batch's rows whose source is neither in the capped list nor already stored **before** the weight sum (`:2980-2989`); FIFO keeps every row (`:2926-2929`). `SourceIdLimits.retainIncomingEvidence(...)` (Task 4) computes the retained list at both storage-level call sites; Task 3's standalone snippet passes `incoming.sourceChunkIds()` unchanged (caps disabled ⇒ nothing dropped) with the Task 4 replacement written inline. Tests added: `capDroppedNewSourcesAddNoWeight` (helper side: retained 200 → weight 200; raw FIFO 250 → weight 250) and `keepDropsCapEvictedNewSourcesFromTheWeightBase` (SourceIdLimits side, KEEP 200 vs FIFO 250), plus `sourceIdsAreCappedAtTheConfiguredLimitOnIngest` now asserts the stored **weight** is 200, not 250. |
| M3 | "Exactly one WARN" has no implementation basis — `LightRag` constructs pipelines from multiple factories (`:835-856,869,873,915-930`), so a per-construction probe warns repeatedly | **Accepted — fixed.** Caps enablement now resolves through the new runtime-level `GraphSnapshotCapabilities`: a static memo keyed by adapter class (one probe per class, ever) plus a static warn-once set, so every pipeline — however many are constructed — reads the same `capsEnabled` and the WARN fires exactly once per adapter class per JVM. `GraphSnapshotCapabilitiesTest.capabilityProbeIsMemoizedAndWarnsOnce` pins both halves. |
| M4 | `ChatRequestOptions` usage contradicts its own contract: the plan defines `merge(override)` (override wins) but writes `request.options().merge(defaults)`, so model defaults clobber per-request overrides | **Accepted — fixed.** The payload builder now reads `defaults.merge(request.options())` with an explicit note on why the reverse order fails `perRequestOverridesWinOverModelDefaults`, and that test now names the concrete numbers (model default 512, request 64 → payload 64). |

Should-fix carried into this revision: Task 4's deletion-survival scenarios are now concrete test bodies instead of comment stubs — real `GraphStore.EntityRecord`/`RelationRecord` fixtures, snapshot records with explicit alias spellings, a control case asserting the entity is deleted when no snapshot attributes it, and a `relationId` equality assertion proving the stored id is the canonicalized stored-endpoint id (see Step 1 of Task 4). The platform `file_path` HARD GATE (write-side producer check + deletion-adapter rebuild) is unchanged in the rollout checklist; Task 13's 4/4/1 and 40/40/16/platform-limit-16 sizing table is config-verified but its *deployed* values must be read back at upgrade time. The cross-plan compile check is restated in the note below.

**Cross-plan implementation order (compile-level check, round 3).** Query plan Task 14 depends on three artifacts owned by this plan, and neither plan can ship half of the pair: `ChatRequestOptions` + the 4-component `ChatRequest` (Task 10 here), `ChatResponse.finishReason()/usage()` (Task 11 here), and `LimitedChatModel.cacheIdentity()` delegation (Task 13 here). Implement this plan's Tasks 10 → 11 → 13 before the query plan's Task 14, or port the three shapes first; the query plan already carries the same note (`2026-09-28-...-plan.md`, Task 14 cross-plan dependency).

---

## Review round 4 dispositions (Codex, 2026-10-01)

Review artifact: `review-round4.md` (same local Codex CLI run, read-only). Round verdict for this plan: **不可开工** — 3 must-fix (M1 partly fixed, M2 not fixed and carrying a semantic error, M3 partly fixed and introducing a class-keyed capability cache). All three are actioned below; the review's should-fix items are folded into the tasks, and its verified-correct double-checks (cap/floor test arithmetic, deletion fixture shapes, resolveEntityIds view) are unchanged.

| # | Finding | Disposition |
|---|---|---|
| M1 | Task 13's "executable" CORRECT block still cannot run: `modelKey`/`stage`/`requestedLimit`/`gate`/`request` are undeclared, and it calls `resolveWikiModel().generate(request)` where the real code calls `generateWikiUnbounded(model, request)` | **Accepted — fixed.** The block is now a line-aligned excerpt of the real method (`WikiKnowledgeIndexWriter.generateWiki:4060-4094`, signature `private String generateWiki(ChatModel model, ChatModel.ChatRequest request)`; it omits only the `model == null` throw `:4061-4063` and the `gate == null` fallthrough `:4065-4067`, both named in the comment), with `modelKey`/`stage`/`requestedLimit` computed exactly as the platform computes them (`:4068-4089`) and the call inside the permit written as `generateWikiUnbounded(model, request)` (`:4092` → `:4096-4100`); the FORBIDDEN half is explicitly labelled a non-compilable shape sketch. |
| M2 | `retainIncomingEvidence` carries only the stored scalar, so it cannot represent upstream's `existing_full_source_ids` (tracking can run ahead); the plan wrongly claimed "the observable weight matches" | **Accepted — fixed via the review's second branch: the divergence is explicit, bounded, and pinned.** The helper's second parameter is documented as upstream's `existing_full_source_ids` slot; Java supplies the stored scalar, which is upstream's own no-tracking path (`operate.py:2881-2886`), not an approximation of the tracking path. Task 4 gains a set table (tracking `:2864-2879` / scalar `:2980-2989` / capped list `:2904`, join `:2954`), a worked divergence example (tracking `c1..c250`, stored `c1..c200`, re-fed `c201` → upstream 201, Java 200), the bound as stated at the time (weight-only; stored ids, floor and survival identical) — **superseded by the round-5 M2a row**: with a live tracking row, survival and the merged text fork as well, while the stored ids and the floor stay the same capped list on both sides — and the full-fidelity path (resolve the tracked chunk set from the chunk snapshots — the `GraphChunkAttribution` authority — which the merge path does not have today). `SourceIdLimitsTest.withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback` pins it. |
| M3 | The class-keyed `SUPPORT` memo is unsound (`StorageCoordinator` instances of one class can differ), the package-private setter seam was missing from the block, and the memo test baked the bug in | **Accepted — fixed.** `GraphSnapshotCapabilities` no longer memoizes capability at all: `supportsDocumentGraphSnapshots` probes the instance (evidence in the javadoc: `StorageCoordinator.java:25-33,86-87`, `RelationalStorageAdapter.java:24-27`, `PostgresRelationalStorageAdapter.java:173-176`), while the WARN set stays class-keyed as pure noise control. Explicit `resetWarnOnceForTests()` / `warnedOnce(Class)` seams are in the code block, and the test is rewritten into three cases: per-instance resolution (same class, different capability), warn-once across distinct unsupported instances, and explicit opt-out skipping the probe. |
| M4 | `perRequestOverridesWinOverModelDefaults` is still a comment, not a test | **Accepted — fixed.** The test now builds an `OpenAiCompatibleChatModel` with a model default of `maxTokens` 512, sends a request with `maxTokens` 64, and asserts the captured payload contains `"max_tokens":64` and not `512`, plus a fallback assertion that unset request fields inherit the model default (`response_format`). The plan also states the `ChatRequest(system, user, ChatRequestOptions)` convenience constructor the test needs. |

Should-fix folded in: `ChunkExtractStatus.SUCCEEDED` now cited at `:6` (the enum is declared at `:3`); the `file_path` HARD GATE, the cross-plan compile check, and the `maxParallelInsert` 2→3 "pending change to verify" marker are unchanged; Task 13's sizing formula and platform line numbers were already correct — only the code shape changed.

Open verification for round 4: the divergence pin must be flipped deliberately if a relation-tracking store is ever ported (the test name announces the future port); the capability probe must stay instance-scoped in the implementation (a later "optimization" back to a class-keyed map silently reopens M3).

---

## Review round 5 dispositions (Codex, 2026-10-01)

Review artifact: `review-round5.md` (same local Codex CLI run, read-only). Round verdict for this plan: **不可开工** — 2 must-fix (M2 divergence boundary still over-narrow and under-tested; M4 test code still not compilable) plus 3 should-fix/nits. All are actioned below.

| # | Finding | Disposition |
|---|---|---|
| M2a | "Divergence is confined to the weight number: stored id set, floor and every survival decision are identical" is false — with a live tracking row the KEEP filter itself decides differently, a surviving fragment skips the `:2932-2951` early return, and description/keywords/summary then merge that fragment's text | **Accepted — fixed.** The divergence paragraph is rewritten and re-scoped: the fork is only reachable upstream-with-a-live-tracking-row (Java implements upstream's no-tracking path exactly), and it lists three forked effects — (a) survival via the tracked baseline (`:2922-2925`), (b) description/keywords/summary via the skipped early return (`:2932-2951`), (c) weight via the retained fragment's own row weight (`dp["weight"]`, 1.0 under standard extraction; `:2980-2989`). The sentence "stored id set and every survival decision are identical" is gone; the paragraph now states that rows Java keeps and upstream drops are weight-neutral (their sources sit in the scalar, always excluded by the weight filter) but still fork the merged text in the opposite direction. |
| M2b | "At most 1.0 per re-fed source" must be "per re-feed event" — repeated re-feeds accumulate because the evicted source never lands in the stored scalar | **Accepted — fixed.** The bound is now per re-feed event with the mechanism spelled out (the join at `:2954` persists the capped list, so the source never enters the scalar), and `SourceIdLimitsTest.repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly` pins the growth (upstream 200 → 201 → 202 → 203; Java flat at 200). |
| M2c | The pinning test validates only the helper, never the complete relation merge; tracking-present-empty and tracking-behind cases are missing | **Accepted — fixed.** Three tests added: `theTrackingBaselineShapesForkTheKeepFilterFromTheScalarFallback` (limit-2 fork table over scalar / present-but-empty / lagging / leading baselines, each recomputing full → capped → filter as the merge does; the leading row is the 200/250 case of the existing pin), `repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly`, and the record-level `cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap`, which calls the extracted package-private `IndexingPipeline.mergeRelationWithCaps(...)` and asserts the merged `RelationRecord` equals the stored one (the same record upstream's no-tracking early return returns). The implementation sketch now mandates that extraction, with both pipelines' `mergeRelation` wrappers passing their stored caps fields and `GraphMaterializationPipeline`'s copy delegating. |
| M4a | `OpenAiCompatibleChatModel` constructor parameter order is reversed in the test snippets | **Accepted — fixed.** Both snippets now use `(baseUrl, modelName, apiKey, timeout, defaults)` — the real order, `OpenAiCompatibleChatModel.java:34-49` — and the comment cites it. Task 14's rerank snippet had the same reversal and is fixed to the sibling convention (`OpenAiCompatibleEmbeddingModel.java:31-35`). |
| M4b | `MockWebServer.lastRequestBody()` does not exist | **Accepted — fixed.** Both Task 10 snippets use `server.takeRequest().getBody().readUtf8()` (the existing `OpenAiCompatibleChatModelTest.java:39-43` pattern) with `throws Exception`, and the Task 14 snippet matches; the comment names the API so it cannot be reintroduced. |
| should-fix | M1 wording ("verbatim excerpt" over-claims), M3 warn-once proof, untracked `docs/slides/__pycache__` files | **Accepted.** (1) The Task 13 comment now reads "aligned line-by-line … the real method additionally throws on `model == null` (`:4061-4063`) and falls through when the gate is absent (`:4065-4067`), both omitted for brevity", and the round-4 M1 row was corrected to match. (2) `warnFiresOncePerAdapterClass` now asserts through a capturing Logback appender (exactly one WARN event) with the `warnedOnce` seam kept only as the reset/cleanup check. (3) The `docs/slides/__pycache__/*.pyc` files are untracked build artifacts outside this plan's scope — left in place, flagged to the repository owner instead of deleted here. |

Open verification for round 5: the fork table's four baselines are the complete set of shapes upstream's tracking read can produce (`:2860-2886`); if upstream later adds a fifth (e.g. a tracking store that reseeds from the scalar), the table and the divergence paragraph must be extended before the port lands.

---

## Review round 6 dispositions (Codex, 2026-10-01)

Review artifact: `review-round6.md` (same local Codex CLI run, read-only). Round verdict for this plan: **不可开工** — 1 must-fix (rerank constructor contract conflict) and 2 should-fix. Both are actioned here; the round-5 items (M2b bound arithmetic, M2c test coverage and the `mergeRelationWithCaps` extraction, M4a/M4b snippets, M1 wording, M3 appender, fork-table and repeated-refeed arithmetic) are all confirmed fixed by this round's independent re-derivation.

| # | Finding | Disposition |
|---|---|---|
| Must-fix | Rerank constructor contract conflict: the Step 1 test snippet uses `(baseUrl, modelName, apiKey, timeout)` while the Step 3 implementation note wrote `(baseUrl, apiKey, model, timeout)` | **Accepted — fixed.** The Step 3 note now specifies `Constructor (baseUrl, modelName, apiKey, timeout)` and cites the sibling adapters it mirrors (`OpenAiCompatibleChatModel.java:34-49`, `OpenAiCompatibleEmbeddingModel.java:31-35`); both were re-verified in source (`String baseUrl, String modelName, String apiKey[, Duration timeout]`). |
| Should-fix | `:846` "fixed 1.0" is narrower than upstream: the weight sum adds each surviving row's `dp["weight"]` (`operate.py:2980-2989`), which is 1.0 only on the standard extraction path (`:1084`) and otherwise a parsed strength | **Accepted — fixed.** Effect (c) now reads "contributes its own row weight `dp["weight"]` (`:2980-2989`) — 1.0 on the standard extraction path (`operate.py:1084`), a parsed strength otherwise"; the per-event bound reads "bounded by the surviving fragment's own weight (1.0 in this plan's standard-extraction scenario) **per re-feed event**"; the sketch's weight-base comment and the repeated-refeed test comment carry the same qualifier; the round-5 M2a row was corrected to match. Citation updated `:2980-2987` → `:2980-2989`. |
| Should-fix | Stale Java line numbers in both plans (the review named the query plan's BYPASS refs `:314-316`) | **Accepted — fixed on the query plan** (its refs now cite `:280-282` and `:317-319`). This plan's Java refs were re-verified line-by-line this round and all match: `IndexingPipeline.java:62-63,400,1632-1656`, `GraphMaterializationPipeline.java:1245-1255`, `GraphAssembler.java:311-313,414-420,435`, `KnowledgeExtractor.java:609-616,637-639`. |
| Note | Untracked `docs/slides/__pycache__/*.pyc` | Unchanged from round 5: untracked build artifacts outside this plan's scope, flagged to the repository owner rather than deleted here. |

---

## Review round 7 dispositions (Codex, 2026-10-01)

Review artifact: `review-round7.md` (same local Codex CLI run, read-only; incremental re-check of the round-6 fixes). Round verdict for this plan: **有条件可开工** — the round-6 must-fix (rerank constructor contract) is confirmed fixed, and every other round-6 item is confirmed; two documentation residues remained and are fixed here.

| # | Finding | Disposition |
|---|---|---|
| Condition 1 | `:580` still said each source id is "fixed at exactly 1.0" with no standard-extraction qualifier | **Accepted — fixed.** The sketch comment now reads "each worth 1.0 on this plan's standard extraction path (upstream sums each surviving row's `dp["weight"]`, `operate.py:2980-2989`; parsed-strength rows fork)", consistent with `:849`. |
| Condition 2 | Round-3's M2 row still carried "weight-only; stored ids, floor and survival identical", inconsistent with the round-5 three-fork conclusion | **Accepted — fixed.** The parenthetical now reads "the bound as stated at the time … — **superseded by the round-5 M2a row**: with a live tracking row, survival and the merged text fork as well, while the stored ids and the floor stay the same capped list on both sides". |
| Verified | Rerank contract, `:2980-2989` unification, Java line refs, cross-consistency | Round 7 confirmed: Step 1 / Step 3 both use `(baseUrl, modelName, apiKey, timeout)` matching `OpenAiCompatibleChatModel.java:34-49` and `OpenAiCompatibleEmbeddingModel.java:31-35`; no `:2980-2991` remains and `:2980-2987` survives only inside the round-6 "updated →" note; `docs/slides/__pycache__` stays out of scope. |
