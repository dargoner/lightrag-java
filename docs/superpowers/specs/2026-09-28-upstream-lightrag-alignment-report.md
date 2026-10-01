# Upstream LightRAG Alignment Report — 2026-09-28

- **Java**: `lightrag-java` 0.24.0-SNAPSHOT (`28419f6`, 2026-06-17)
- **Upstream reference**: `D:\ai-code\LightRAG`, HEAD `453dce83d` (2026-09-26); `lightrag/_version.py` declares `1.5.8` (no `v1.5.8` tag yet; latest tag `v1.5.7`). The previous reference directory `D:\ai-code\lightrag-origin` no longer exists.
- **Previous alignment**: `2026-05-23-upstream-lightrag-alignment-report.md`, aligned against the then-current tree (declared `1.5.0`). Since then, ~1,500 non-merge commits landed under `lightrag/` alone.
- **Method**: read-only static comparison of both working trees, module by module. Core claims were spot-checked against source; runtime tests were not executed (see §9).

Legend: **Aligned** (behavior equivalent) / **Partial** (main path present, details differ) / **Missing** (Java has nothing) / **Divergent** (both exist, behavior differs) / **Java-only**.

Java paths are relative to `lightrag-core/src/main/java/io/github/lightrag/` unless noted; upstream paths are relative to the LightRAG repo root.

## 1. Executive summary

The core RAG skeleton is still materially aligned: chunking strategy selection (F/R/V/P), KG extraction/merge basics, the five query modes, storage adapters with workspace isolation, document deletion with LLM-cache cleanup, and a persistent task runtime all work and were re-verified at the level of entry points.

What changed is the upstream **platform layer**: between 1.5.0 and 1.5.8 upstream grew a parser engine registry with a sidecar interchange format, a multimodal analyze stage with a VLM role, token-accounting/truncation contracts, offline repair/migration CLIs, and scheduling/observability machinery. Java has none of these layers, and several upstream refinements inside the shared core (chunk selection quotas, heading context, summarization, source-id caps) are also absent.

Ranked gap list (details in §2–§7):

| # | Gap | Upstream anchor | Java state |
|---|---|---|---|
| 1 | Multimodal/VLM lifecycle (parse → sidecar → analyze) | `lightrag/pipeline.py:6841-7938`, `lightrag/sidecar/writer.py:60-372`, `lightrag/llm_roles.py:56` | None; `ProcessOptions.imageAnalysis` parsed but never consumed (`api/ProcessOptions.java:108`) |
| 2 | Parser platform: registry, hints, `LIGHTRAG_PARSER`, native md/docx, Docling | `lightrag/parser/registry.py:266-314`, `lightrag/parser/routing.py:62,1102-1225` | Hardcoded orchestrator + minimal MinerU API path (`indexing/DocumentParsingOrchestrator.java:48-132`) |
| 3 | Query chunk-selection quotas & heading context | `lightrag/operate.py:6350-6823`, `chunk_schema.py:148-172`, `enable_content_headings` default on | Zero hits for `relatedChunkNumber`/`kgChunkPick`/`contentHeadings` in Java main |
| 4 | LLM description summarization on merge | `lightrag/operate.py:372-531` | `summaryModel` is plumbed through config/builder/pipeline but no summarization code path exists |
| 5 | Token governance (real tokenizer, truncation markers, usage) | `lightrag/utils.py:5200-5223,6867-6922` | Whitespace/codepoint counting (`query/QueryBudgeting.java:15-24`); `finish_reason`/`usage` discarded |
| 6 | Offline ops tools (rebuild-vdb, graph migration, repairs) | `lightrag/tools/*` (10 modules, 7 console scripts) | None; repair requires a running app (`api/LightRag.java:559-563`) |
| 7 | Admission/backpressure, dead-worker recovery, owner-token fencing, pipeline metrics | `lightrag/api/admission*.py`, `kg/shared_storage.py:2361-2492`, `pipeline_metrics.py` | Per-workspace lock + task reconciliation only (`task/TaskExecutionService.java:270-310`) |
| 8 | Storage contracts: graph read surface, source-id caps, embedding-space refusal, Noop vector, DocStatus query API | `lightrag/base.py:1119-1252,1589-2055`, `lightrag/kg/vector_space.py:120-175`, `noop_vector_db_impl.py:12-66` | `GraphStore` = CRUD only; no caps; dimension-only checks; no noop backend |
| 9 | Model layer breadth & request options | 13 chat bindings, binding options (`llm/binding_options.py:525-781`) | One OpenAI-compatible binding; no `temperature`/`max_tokens`/`response_format`; no retries; rerank has no production impl |
| 10 | Extraction robustness: upstream prompt parity, text-mode fallback, record caps, validator hook | `lightrag/prompt.py:56-269`, `lightrag.py:768`, `operate.py:3983` | Java-authored JSON-only prompt; no caps/validator; weight semantics differ |

Still aligned (re-verified subset): chunk window/overlap validation, semantic-vector threshold modes, rerank candidate expansion + `minRerankScore`, deletion result shape and retryable LLM-cache cleanup, per-workspace task serialization, metadata filtering (Java-only), multi-workspace isolation.

## 2. Indexing, chunking, extraction, merge

**Strategy selection** — Divergent. Upstream dispatches C/F/R/V/P only (`lightrag/pipeline.py:5230-5560`); Java adds SMART and REGEX strategies and defaults AUTO→SMART (`indexing/ChunkingOrchestrator.java:100-132`). Same inputs produce different chunkings; upstream's `C` (custom chunker callback) has no Java equivalent.

**Custom chunker registry** — Missing. Upstream added `chunker/registry.py` (336 lines, 2026-09-08): `ChunkerSpec`/`ChunkingContext`/`register_chunker`, signature validation, executor offload. Java is a fixed enum switch; parse metadata (engine, sidecar location, process_options) never reaches the chunker.

**Chunk ids** — Divergent. Upstream `{doc_id}-chunk-{order:03d}` (`lightrag/utils_pipeline.py:160-201`); Java `documentId + ":" + order` (`indexing/FixedWindowChunker.java:124`). Breaks cross-runtime chunk-level cache key reuse and migration.

**Semantic vector chunking** — Partial. Four threshold modes and R-fallback aligned; upstream additionally supports `number_of_chunks`, `min_chunk_size`, embedding-side truncation, and secondary R-splitting of oversized segments (`lightrag/chunker/semantic_vector.py:649-708`).

**Paragraph chunking** — Partial. Core heuristics, ratio constants, part suffixes mirrored (`indexing/ParagraphSemanticChunker.java:19-24,412-512`). Missing upstream's table-header injection (`lightrag/chunker/paragraph_semantic.py:319+`), heading glue, `drop_references`/references-tail handling, and table markup module (`lightrag/table_markup.py`).

**Pre-embedding hard split & sidecar backfill** — Missing. Upstream enforces `embedding_token_limit` before embedding and injects multimodal chunks (`lightrag/pipeline.py:5593-5647`), and backfills chunk sidecar refs via `_source_span` (`sidecar/backfill.py:185`). Java `Chunk` has no span/sidecar fields (`types/Chunk.java`); oversized chunks go straight to the embedding API.

**Chunk heading schema** — Partial. `lightrag/chunk_schema.py` (383 lines) is a unified heading/`parent_headings`/sidecar contract with cleaning rules. Java only writes `paragraph_semantic.heading/parent_headings` metadata inside the P chunker; other strategies lose heading data.

**Extraction prompt & mode** — Divergent. Java uses its own JSON-only prompt (`indexing/KnowledgeExtractor.java:41-105`), not a translation of upstream (`lightrag/prompt.py:56-269`); upstream has a text-mode fallback with `entity_extraction_use_json` switch (`lightrag/lightrag.py:1045`). Section-context breadcrumb injection (`operate.py:4120-4131`) is absent on the Java side.

**Extraction limits & validation** — Missing. Upstream caps records/entities per response and wires a `kg_extraction_validator` hook (`lightrag.py:768`, `operate.py:3983`), with LaTeX/VLM JSON-escape repair (`operate.py:960`). Java has gleaning rounds and balanced-brace JSON recovery only; no caps (long responses can fail wholesale) and no validator hook.

**Weight semantics** — Divergent. Java clamps extracted weights to `[0,1]` (`KnowledgeExtractor.java:609-616`); upstream JSON path fixes weight 1.0 (`operate.py:907-1099`). Propagates into merge/rank.

**Merge: summarization** — Missing (highest impact in this domain). Upstream runs LLM map-reduce description summarization above thresholds (`operate.py:372-531`, config `lightrag.py:1012`). Java's `summaryModel` is wired through `LightRagConfig`/`LightRagBuilder`/`IndexingPipeline` but nothing performs summarization; `GraphMaterializationPipeline.entitySummary` concatenates name/type/description (`indexing/GraphMaterializationPipeline.java:1071-1078`).

**Merge: type/description handling** — Divergent. Upstream majority-votes entity types and sanitize/dedups descriptions before ordering (`operate.py:2384-2426,2576-2600`); Java takes first non-empty and keeps existing unless blank (`indexing/GraphAssembler.java:306-319`).

**Merge: relation weight** — Divergent. Upstream accumulates new-source weight plus an evidence floor `max(weight, evidence_count)` and filters `UNKNOWN` sources (`operate.py:2956-3000`, `constants.py:54`). Java uses `Math.max` of existing/new (`indexing/GraphAssembler.java:414-420`) and no evidence floor/filter.

**Source-id & file-path caps** — Missing. Upstream caps source ids at 200 (KEEP/FIFO) and file paths at 75 (`constants.py:71-84`, `operate.py:2524-3120`). Java's `sourceChunkIds` are unbounded and `RelationRecord.filePath` is always `""` (`indexing/GraphAssembler.java:435`).

**Chunk tracking storage** — Divergent (functional). Upstream stores `entity_chunks`/`relation_chunks` and exposes `has_chunk_tracking_row` (`operate.py:2490-2522`, `utils.py:7364`). Java embeds `sourceChunkIds` in graph records plus per-chunk snapshots and journal (`indexing/GraphMaterializationPipeline.java:645-684`). Deletion semantics are equivalent; upstream-style "entities by chunk" queries are not.

**Extraction concurrency** — Partial. Java has configurable per-chunk parallelism (`indexing/IndexingPipeline.java:1020-1109`) but two code paths (indexing vs graph-materialization refinement, `GraphMaterializationPipeline.java:610-631`) differ in concurrency semantics, and neither adapts to LLM concurrency budgets as upstream's `llm_model_max_async` semaphore does.

## 3. Query, retrieval, rerank, prompts

**Empty-retrieval behavior** — Divergent. Upstream returns `fail_response` without an LLM call when context is empty (`lightrag/operate.py:4736-4788`). Java proceeds with a `(none)` context (`query/ContextAssembler.java:34-59`) — hallucination risk.

**Keyword extraction** — Partial/Divergent. Upstream sends a `language` variable, uses `response_format={"type":"json_object"}`, fence/repair-tolerant parsing, and its own cache key (`operate.py:5051-5220`). Java uses a self-maintained prompt, plain Jackson parsing, and no keyword-specific cache key (`query/QueryKeywordExtractor.java:27-64,137-147`). Java adds its own Chinese-keyword heuristics (`:161-343`).

**Short/empty query fallback** — Partial. Upstream only falls back when both keyword lists are empty and either rejects very short queries or promotes the raw query when short (`operate.py:4759-4764`). Java falls back by mode with no length gate (`query/QueryKeywordExtractor.java:233-239`).

**local retrieval ranking** — Divergent. Upstream ranks relations by `(degree, weight)` (`operate.py:6309-6311`); Java derives relation scores from parent-entity similarity (`query/LocalQueryStrategy.java:135-153`). Different relation set/order reaches the prompt.

**hybrid/mix merging** — Divergent. Upstream merges entity/relation/chunk sources round-robin to preserve source diversity (`operate.py:5432-5486,5811-5862`); Java merges by global score sort (`query/HybridQueryStrategy.java:38-66`, `MixQueryStrategy.java:206-249`).

**KG→chunk selection strategy family** — Missing (structural). Upstream has `related_chunk_number` (default 5) with WEIGHT/VECTOR pickers, per-group quotas and a floor of 1, plus cross-entity/relation dedup and recent fixes (empty-group drop, stage-2 ordering, unresolved reporting; `operate.py:6316-6347,6350-6823`, `utils.py:6642+,6724+`). Java unions all `sourceChunkIds` then truncates by score/token budget (`query/LocalQueryStrategy.java:176-201`) — no quotas or kill switch.

**Token budgeting** — Partial/Divergent. Upstream estimates against the real template with 200-token buffer and truncates entity/relation JSON lines with a real tokenizer (`operate.py:5948-5991,5617-5649`). Java uses whitespace token counts and a 16-token buffer (`query/QueryBudgeting.java:15-24`, `query/QueryEngine.java:28,622-637`) — under-counts CJK badly.

**Chunk truncation** — Partial. Upstream since 2026-08 has a two-stage, encode-verified truncation that re-renders through the actual reference formatter (`utils.py:7055-7162,7825-7874`). Java matches the May-era single-stage behavior (`query/QueryBudgeting.java:51-68`).

**rerank integration** — Partial. Candidate window in Java expands by multiplier (`QueryEngine.java:354-381`), filters by `minRerankScore`, then trims; upstream instead uses `chunk_top_k` as `top_n`, normalizes/validates scores, optionally chunks long documents with score aggregation (`utils.py:7023-7052,7084-7119`, `rerank.py:36-229`), and falls back to original order on failure. Java's `RerankModel` has **no production implementation in-repo** (only test stubs) and throws on failure.

**Context format & references** — Divergent. Upstream emits JSON entity/relation lines plus `{reference_id, content[, content_headings]}` chunk lines and a `Reference Document List` (`prompt.py:442-467`, `utils.py:7735-7822`), so the LLM can emit aligned `[n]` citations. Java emits plain `- id | score | text` lines without reference ids (`query/ContextAssembler.java:10-26`) while the system prompt still demands citations — the model cannot match numbers to sources.

**content_headings** — Missing. Upstream (default on since 2026-06-06) attaches parent-heading breadcrumbs to chunks before token truncation and in naive mode (`operate.py:5719-5762,5864-5868,6907-6909`, `chunk_schema.py:148-172`). Java query side has zero hits, though P/SMART chunkers already store heading metadata that could feed it.

**user_prompt_prefix** — Missing. Upstream supports a server-side prefix with per-request opt-out, folded into cache keys (`utils.py:398-426`, `base.py:177-189`). Java has no such field (`api/QueryRequest.java`).

**Query validation** — Missing. Upstream rejects empty/too-short queries with a CJK-weighted threshold (`query_validation.py:41-54,117-136`). Java validates non-empty only (`api/QueryRequest.java:52-84`).

**Streaming/result surface** — Partial. Upstream returns `response_time`, `llm_generated`, six progress event types, NDJSON stream (`operate.py:114-128`, `api/routers/query_routes.py:285-360`). Java returns none of these; demo streams SSE without progress (`QueryStreamService.java`, `StreamingQueryController.java`).

**Answer cache** — Partial/Divergent. Upstream keys include policy version + full retrieval parameter set + LLM identity, bypasses cache for history-carrying requests, and does not cache truncated responses (`operate.py:4659,4861-4919`). Java caches at the model layer keyed on role + prompt text only (`model/CachedChatModel.java:36-56`), bypasses nothing for history, never caches streams, and has no model-identity component — model swaps silently reuse stale answers.

**Java-only query features** (no upstream equivalent): multi-hop strategy + path-aware synthesizer (`query/MultiHopQueryStrategy.java`, `synthesis/PathAwareAnswerSynthesizer.java`), metadata filtering suite (`query/MetadataFilter*`), parent-chunk expansion (`query/ParentChunkExpander.java`), per-request `modelFunc` override (upstream removed `QueryParam.model_func` in 2026-05), one-shot hybrid retrieval contract, Chinese keyword heuristics.

**Defaults** — Divergent. Upstream `top_k=40`, `chunk_top_k=20` (`lightrag/constants.py:57-58`); Java 10/10 (`api/QueryRequest.java:46-47`).

## 4. Storage

**Backend matrix (upstream → Java)**

| Layer | Upstream | Java |
|---|---|---|
| KV family | Json/Redis/PG/Mongo/OpenSearch (`kg/__init__.py:4-10`) | Typed stores (Document/Chunk/LlmCache…) over InMemory/PG/MySQL/ArcadeDB (`storage/StorageProvider.java:13-40`) |
| Graph | NetworkX/Neo4J/PG(AGE)/PGTable/Mongo/Memgraph/OpenSearch (`kg/__init__.py:13-21`) | InMemory/PG(native tables)/Neo4j/ArcadeDB |
| Vector | Noop/Nano/Milvus/PGVector/Faiss/Qdrant/Mongo/OpenSearch (`kg/__init__.py:24-33`) | InMemory/Milvus/PG(pgvector)/ArcadeDB; no noop |
| DocStatus | Json/Redis/PG/Mongo/OpenSearch | InMemory/PG/MySQL/ArcadeDB |

Java-only: MySQL relation backend, ArcadeDB stack, file snapshots (`persistence/FileSnapshotStore.java`), document graph snapshot/journal, task trio, `OneShotRetrievalStore`.

**Graph read contract** — Missing. Upstream enforces `get_knowledge_graph` (frontier-capped BFS), `iter_labels`, `iter_edges`, `get_popular_labels`, `search_labels` per backend (`base.py:1119-1252`, `kg/pgtable_impl.py:923-1291`). Java `GraphStore` exposes only `allEntities`/`allRelations`/`findRelations` (`storage/GraphStore.java:60-72`) — no visualization backend, no bounded traversal.

**Embedding-space identity** — Partial→Missing. Upstream records model/dimension markers and raises typed `VectorSpaceMismatchError`, rebuilt-tool clearable (`kg/vector_space.py:35-175`, `exceptions.py:702`); missing evidence never refuses. Java checks column dimension at table creation only (`storage/postgres/PostgresSchemaManager.java:452-480`); same-dimension different-model vectors silently mix.

**Noop / graph-first vector backend** — Missing. `kg/noop_vector_db_impl.py:12-66` lets upstream build graphs without vectors and rebuild VDB offline (`tools/rebuild_vdb.py`). Java always requires real vectors.

**Pending buffers & write batching** — Partial. Upstream vector stores buffer pending ops with `index_done_callback` and expose `has_pending_index_ops` (`base.py:207-290`); PG writes split by payload bytes/rows (`kg/postgres_impl.py:99-100,286-325`). Java batches per call, flushes Milvus eagerly (`storage/milvus/MilvusVectorConfig.java:35`), no payload splitting, no pending observability.

**Locks & recovery** — Aligned (weaker semantics). Upstream keyed locks + lease with dead-holder detection (`kg/shared_storage.py:509-860,2475-2492`). Java uses PG advisory locks / MySQL `GET_LOCK` (`storage/postgres/PostgresAdvisoryLockManager.java:22-62`); dead-process release is implicit, no 409-style refusal surface.

**DocStatus richness** — Partial/Missing. Upstream 7 states, per-record `track_id`/`chunks_list`/`content_hash`/timestamps, and a query/dedup API surface (status counts, pagination, by-file/basename/hash, source-conflict list/repair; `base.py:1317-1420,1589-2055`). Java 4 states, 4 fields (`storage/DocumentStatusStore.java:11-46`), no dedup or conflict concepts.

**Migrations/tooling** — Missing. Upstream `storage_migrations.py` plus 10 CLI tools cover backfill/rebuild/repair; Java has versioned DDL only (`storage/postgres/PostgresSchemaManager.java:80-112`) and no offline entry points.

**Snapshot/journal model** — Java-only. Atomic three-part snapshot + rollback (`storage/StorageCoordinator.java:94-157`) and per-document graph resume (`storage/DocumentGraphSnapshotStore.java`) exceed upstream's recovery guarantees but add non-interoperable tables.

## 5. API, server, task orchestration, observability

**Shape** — Divergent. Upstream is a full FastAPI server (auth, rate limit, admission, WebUI, Ollama-compat); Java is an SDK + Spring Boot starter + demo. Endpoint-level parity is therefore partial by design.

**Missing server surfaces**: API-key/JWT auth and login rate limiting (`lightrag/api/utils_api.py:392-460`, `login_rate_limit.py:69-100`) — Java starter has no security dependency at all; request body limits (`body_limit_middleware.py:24-58`); admission/backpressure with 429 (`admission_middleware.py:52-140`, `MAX_PENDING_DOCUMENTS`); directory scan endpoints; `reprocess_failed`; `cancel_pipeline`; `recovery/force_reset`; Ollama-compat endpoints; pipeline_status view; rich `/health` (scheduling, capabilities, queues, server_mode).

**Document ingestion surface** — Partial. Upstream per-request `chunking` params and filename hints ride with the upload (`document_routes.py:811-890,5335-5345`); Java models `ChunkOptions`/`ProcessOptions` but only consumes `skipKnowledgeGraph` and chunking override in resume paths (`IndexingPipeline.java:1131-1134`); demo presets are coarse (`demo/UploadController.java:41`).

**Pipeline model** — Divergent. Upstream single shared `pipeline_status` dictionary with busy/scanning/destructive reservations, owner tokens, and ingress mailbox (`pipeline.py:3153-3265`, `kg/pipeline_ingress.py:215-253`). Java: persisted per-workspace task records with fair-lock serialization (`task/TaskExecutionService.java:78-89,233-240`) — auditable but no busy/batch view, no owner-token fencing, no destructive-vs-read phase distinction.

**Crash recovery** — Partial. Upstream reaps dead reservations by pid/start-time and fences to `recovery_required` with a force-reset exit (`shared_storage.py:2361-2492`, `document_routes.py:7087-7100`). Java marks non-terminal tasks FAILED on first workspace touch (`TaskExecutionService.java:270-310`); no cross-process pid checks, no fences.

**Cancellation** — Divergent (capability present). Upstream cooperative flag driving PROCESSING→FAILED; Java persists `cancelRequested` + thread interrupt with stage checkpoints (`TaskExecutionService.java:172-208,863-871`).

**Events & metrics** — Java stronger but unreachable. 24 task event types + stage/document progress (`api/TaskEventType.java`, `TaskExecutionService.java:466-777`) vs upstream status history; but Java exposes no REST subscription, and lacks upstream `pipeline_metrics.py` counters surfaced via `/health.scheduling_metrics`.

**Query HTTP contract** — Divergent. `response`/`references`/`response_time`/`llm_generated` with NDJSON streaming upstream (`query_routes.py:285-306,745-1000`); demo returns `answer`/`contexts`/`references` over SSE (`demo/QueryController.java`, `demo/QueryStreamService.java`).

## 6. Parser, multimodal, sidecar, tools

**Parser routing** — Missing. Upstream: unified registry (`parser/registry.py:266-314`), filename hint `name.[engine].ext` (`routing.py:62,858-1029`), parameterized hints (`param_schema.py:144-384`), `LIGHTRAG_PARSER` suffix rules (`routing.py:1102-1225`), registry-derived upload allowlist (`registry.py:368-385`), third-party engines via `lightrag.parsers` entry points (`plugins.py:30-66`). Java: hardcoded media-type/extension branch in `indexing/DocumentParsingOrchestrator.java:48-132`; `.xlsx/.rtf/.csv` etc. throw; demo allowlist is a static list (`demo/UploadedDocumentMapper.java:25-30`).

**Engines** — Missing/Partial. Upstream native markdown/textpack (image fetch with SSRF/volume budgets, `parser/markdown/parser.py:1-45`), native docx (numbering resolver, smart heading via LLM bridge, `parser/docx/*`, `parser/llm_bridge.py`), Docling (`parser/external/docling/*`), MinerU with page ranges/language/formula/table options, bundle cache and reparse flags (`parser/external/mineru/*`). Java: plain UTF-8 text passthrough, MinerU official-API minimal path with URL-only transport (`indexing/MineruApiClient.java:297-306`), Tika fallback class present but deliberately unplugged (`LightRagAutoConfiguration.java:190-196`, config key unread). Docling: absent.

**Sidecar** — Divergent (Java consumes only). Upstream `sidecar/writer.py:60-372` writes `*.parsed/` (blocks.jsonl, tables/drawings/equations.json, assets) with `<thead>` and `parent_headings` (2026-06-08); Java reads `.blocks.jsonl` as an input format (`indexing/LightRagSidecarParsingProvider.java:16-105`) but never writes sidecars, and `Chunk` carries no `sidecar`/`_source_span` so `sidecar/backfill.py`-style linkage is impossible.

**Multimodal analyze stage** — Missing. Upstream: `process_options` i/t/e gate, VLM role, `prompt_multimodal.py` templates, surrounding-context enrichment, analyze cache with truncation guard, failure recorded not dropped (`pipeline.py:6841-7938`, `multimodal_context.py:936-1040`, `llm/_vision_utils.py:93-176`). Java: `ProcessOptions.imageAnalysis` parsed but never read anywhere in main; no VLM role (see §7).

**Ops tools** — Missing. Upstream `lightrag/tools/`: `rebuild_vdb`, `migrate_graph_storage` (dry-run default), `kg_integrity_repair`, `chunk_tracking_repair` (offline-only, plan/resume), `source_conflict_repair`, `migrate_llm_cache`, `clean_llm_query_cache`, `check_initialization`, `hash_password`, `download_cache`. Java has zero CLI entry points; closest is in-process `api/LightRag.java:559-563` repair.

**File atomicity** — Partial. Upstream `file_atomic.py` (pid/tid-suffixed temp files, Windows retry budget, orphan reaping). Java has snapshot-store atomic writes only (`persistence/FileSnapshotStore.java:85-93`).

## 7. Model layer, roles, caching, tokenization

**Provider breadth** — Missing. Upstream: 13 chat bindings and 12 embedding bindings in `lightrag/llm/*` (server CLI supports 7/8). Java: exactly one OpenAI-compatible chat and embedding implementation (`model/openai/OpenAiCompatibleChatModel.java`, `OpenAiCompatibleEmbeddingModel.java`).

**Request options** — Missing. Upstream binding options cover temperature/max_tokens/top_p/stop/reasoning_effort/`think` levels etc. (`llm/binding_options.py:525-781`) plus `response_format=json_object` for extraction and keyword calls. Java's payload is `model`/`messages`/`stream` only (`OpenAiCompatibleChatModel.java:98-114`).

**Roles** — Partial. Upstream roles are runtime-registerable (`extract`, `keyword`, `query`, `vlm`), with per-role max_async/timeout/priority queues, hot reconfiguration, and queue-status introspection (`llm_roles.py:52-79,186-263,393-479,594-619`). Java has constructor-injected `chatModel`/`queryModel`/`keywordModel`/`extractionModel`/`summaryModel` with chat fallback (`config/LightRagConfig.java:14-24`) — note the extra `summary` role (upstream summarizes under the extract role/identity, `operate.py:567,617`) and no runtime switching or per-role concurrency.

**Retries/error classification** — Missing. Upstream: SDK retries off, tenacity 3 attempts with backoff, 408/409/5xx/transient-400 classified, permanent 429 fail-fast (`llm/openai.py:203-304,522-551`, `_error_utils.py:30-58`). Java model calls have no retry/backoff (`model/openai/OpenAiCompatibleChatModel.java:116-139`).

**LLM cache** — Partial. Upstream key = mode + cache_type + hash over explicit parameter sets and LLM identity, length-prefixed encoding, truncation guard, history bypass, maintenance tools (`utils.py:901-931,1090-1104,5200-5223`; `operate.py:4861-4919`). Java key = role + prompt hash, no model identity, stream bypass, no TTL (`model/CachedChatModel.java:36-56`).

**Rerank model** — Missing (production). Upstream ships cohere/jina/aliyun HTTP rerankers with document chunking and aggregation (`rerank.py:36-443`); Java defines the `RerankModel` interface but the repository contains only test stubs.

**Token accounting & truncation** — Missing. Upstream `TokenTracker` contract with per-provider reporting (`utils.py:6867-6922`, `llm/ollama.py:168-200`) and `TruncatedResponse` markers from all providers (`openai.py:912-921`, `gemini.py:665-670`, `ollama.py:424-428`, `bedrock.py:515-520`, `anthropic.py:247-250`, `zhipu.py:190-193`, `lmdeploy.py:182-185`, `hf.py:218-222`). Java discards `usage` and `finish_reason` entirely (zero hits in main), so it cannot detect truncation or account cost, and caches truncated responses.

**Tokenizer** — Divergent/Missing. Upstream real-tokenizer contract with encode-verified spans and safe truncation (`utils.py:3474-3959,4173-4184,4265`). Java: codepoint-based chunk windows (`indexing/UnicodeCodePointChunkTextTokenizer.java:13-34`) and whitespace-based query/extraction budgets (`query/QueryBudgeting.java:15-24`, `indexing/KnowledgeExtractor.java:737-743`).

**Embedding contract** — Partial/Missing. Upstream enforces shape/dim checks, per-call `context="query"/"document"` and asymmetric prefixes, per-item token truncation (`utils.py:638-730,794-798`; `llm/openai.py:1037,1128-1164`). Java `EmbeddingModel.embedAll(List<String>)` has no context/dimension metadata (`model/EmbeddingModel.java`); batching exists (`indexing/EmbeddingBatcher.java:13-40`) but defaults to no batching in the demo config.

## 8. Java-only extensions

- Multi-hop query strategy + path-aware answer synthesis; metadata filter suite; parent-chunk expansion; one-shot hybrid retrieval store (`query/*`, `synthesis/*`).
- SmartChunker / RegexChunker / ParentChildChunkBuilder and document type hints (AUTO/LAW/BOOK/QA) with ingest presets.
- Pre-chunked ingestion and sidecar-as-input ingestion.
- Task runtime extras: 24 event types, per-task/stage/document duration and queue-wait metrics, snapshot save/restore (`api/LightRag.java:633-642`).
- Storage extras: MySQL + ArcadeDB backends, file snapshot store, document graph snapshot/journal with resume.
- RAGAS batch evaluation CLI + Python graders (`evaluation/`).

## 9. Verification and uncertainties

Spot-checked directly against source while writing this report (not delegated): Java zero-hit greps for `relatedChunkNumber`/`kgChunkPick`/`contentHeadings`/`userPromptPrefix`/`finish_reason`/`tokenUsage`; `implements RerankModel` only in tests; `imageAnalysis` consumed nowhere; query defaults 10/10/6000/8000/30000; `CachedChatModel` key composition and stream bypass; `GraphStore` read surface; chunk id format; starter dependency list. Python-side: `constants.py` defaults (40/20/5/VECTOR/0.0), `enable_content_headings` (`lightrag.py:755`), `TruncatedResponse` (`utils.py:5200`), `TokenTracker` (`utils.py:6867`), `ROLES` incl. `vlm` (`llm_roles.py:52-56`), `MIN_RAG_QUERY_WEIGHT=3`, `entity_extract_max_records`/`summary_max_tokens`/`DEFAULT_MAX_SOURCE_IDS_PER_ENTITY=200`, graph read contract in `base.py:1119-1252`, parser/sidecar/multimodal files present.

Uncertainties / not verified in this pass:

- No runtime tests were executed on either side; all findings are static.
- Upstream has no `v1.5.8` tag (`_version.py` declares 1.5.8); the "1.5.0 baseline" refers to the version declared at the previous report's date, whose exact commit cannot be pinned from repository evidence alone.
- Whether the Java `ChunkOptions`/per-request chunking parameters actually influence runtime chunking (persistence-only vs consumed) was not proven end-to-end.
- Paragraph-chunker anchor heuristics were compared at the level of constants and main methods, not block-by-block behavior.
- Production `RerankModel` implementations may exist outside this repository (Spring only passes through user beans).
- Upstream `lightrag/api/routers/*` interaction details with storage (e.g. source-conflict REST half) were reviewed at the interface level only.

## 10. Suggested next steps (if/when alignment work is scheduled)

1. **Decide the platform boundary**: the parser/multimodal/sidecar stack is the single largest gap. If Java needs it, the incremental path is sidecar **write** support first (reuse the existing read path and `blocks.jsonl` conventions), then a minimal VLM role, then the analyze stage.
2. **Cheap core wins**: content_headings injection (heading metadata already exists in P/SMART chunks), KG→chunk quotas (`related_chunk_number` + VECTOR picker), fail_response short-circuit, query validation, `[n]` reference ids in context.
3. **KG quality**: wire the already-plumbed `summaryModel` into merge summarization; add source-id caps and relation `filePath` accumulation; settle weight semantics ([0,1] vs 1.0+evidence floor).
4. **Token governance**: introduce a real tokenizer contract and truncation markers before touching prompts; stop caching truncated responses.
5. **Storage**: graph label/BFS read surface, DocStatus query/dedup API, embedding-space fingerprint refusal, noop vector backend.
6. **Ops**: a minimal tools module (rebuild-vdb, chunk-tracking repair) so data drift doesn't require full rebuilds through the API.
7. **Server parity (only if Java must stand in for the FastAPI server)**: auth, body limits, admission/backpressure; otherwise document the SDK-vs-server boundary explicitly.
