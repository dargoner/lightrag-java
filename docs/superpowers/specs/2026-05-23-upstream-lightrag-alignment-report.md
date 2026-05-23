# Upstream LightRAG Alignment Report - 2026-05-23

This report records the current Java alignment status against the local upstream reference at `D:\ai-code\lightrag-origin`. Scope is intentionally limited to upstream LightRAG behavior that already has a clear Java equivalent or a small Java API gap.

## Aligned

- Role-specific models: Java supports separate `queryModel`, `keywordModel`, `extractionModel`, and `summaryModel`; Spring properties expose `query-model`, `keyword-model`, and `extraction-model` with fallback to the default chat model.
- Keyword extraction role: `keywordModel` is wired into `QueryEngine` and Spring configuration, so graph-query keyword extraction can use the upstream-style dedicated role.
- Chunking entry names: `F/Fix` maps to fixed-window chunking, `R/Recursive` maps to recursive character chunking, `V/Vector` maps to semantic-vector chunking, and `P/Paragraph` maps to paragraph semantic chunking.
- Semantic vector chunking: Java supports upstream threshold modes `percentile`, `standard_deviation`, `interquartile`, and `gradient`, plus `buffer_size`; when no embedding model is available it falls back to recursive chunking.
- Paragraph table splitting: Java preserves table wrapper boundaries and uses balanced row splitting with upstream-style tiny-last-slice handling.
- Paragraph bridge text: Java mirrors upstream Stage B.1 for consecutive large tables in one content block by duplicating short bridge text into both boundary table chunks, or splitting longer bridge text by overlap budget.
- `process_options` / `chunk_options`: Java models upstream per-document process selectors and slim chunk option snapshots, persists them in document metadata, supports `!` to skip KG construction, and normalizes Spring kebab-case chunk option keys to upstream snake_case JSON.
- Rerank candidate expansion: Java expands the internal `chunkTopK` candidate window before rerank and then trims to the original request budget.
- Rerank score filtering: Java now exposes `minRerankScore`, default `0.0`, matching upstream `MIN_RERANK_SCORE`; chunks below the configured reranker score are filtered.
- Rerank failure visibility: Java propagates configured reranker failures instead of silently using the original retrieval order; if no reranker is configured, rerank remains inactive.
- Deletion result shape: Java document/entity/relation deletion now returns `DeletionResult(status, docId, message, statusCode, filePath)` with upstream-compatible status strings.
- Document deletion: Java removes the target document/status/chunks incrementally, subtracts the deleted chunk ids from entity/relation source references, removes graph records and vectors that lose all source chunks, and keeps unaffected document-derived graph state without re-extracting remaining documents. Failed status-only records can also be deleted.
- Async deletion entry: Java exposes `submitDeleteByDocumentId(...)` through the existing task runtime, giving document deletion the same submitted-task surface as ingest/rebuild/materialization.
- Async task exclusivity: Java submitted tasks now serialize execution per workspace, so async ingest/delete/rebuild/materialization do not concurrently mutate the same workspace storage.
- LLM cache storage: Java now exposes `LlmCacheStore` with in-memory, PostgreSQL, MySQL, and ArcadeDB implementations, plus an upstream-style `clearCache(workspaceId)` SDK entry.
- LLM cache population: Java wraps EXTRACT / SUMMARY / QUERY / KEYWORD model calls with `CachedChatModel`, persists cache entries in the configured `LlmCacheStore`, and records extraction cache ids on chunk metadata for delete cleanup.
- Deletion retry/cache cleanup: Java document deletion now collects cache ids from chunk metadata, deletes configured LLM cache entries when `deleteLlmCache=true`, verifies removal, persists upstream-style `deletion_llm_cache_ids`, `last_deletion_attempt_at`, `deletion_failed`, and `deletion_failure_stage` on cleanup failures, and supports retrying cache cleanup from the persisted status-only record.
- Entity/relation deletion: Java removes entity/relation graph records, vectors, and per-chunk graph tracking while preserving source documents and chunks.
- ArcadeDB hybrid retrieval: Java has dense + BM25 retrieval paths and Java-side RRF fusion because ArcadeDB does not expose a confirmed native `vector.fuse`/RRF operator in the verified path.
- Metadata filtering: Java supports structured metadata filter expressions over regular and dynamic metadata fields, with database-side EQ/IN where implemented and Java-side composition where the backend cannot push down the exact expression.

## Partially Aligned

- Paragraph chunking is aligned for F/R/V/P selection, table row slicing, bridge text, part suffixes, and hierarchy-aware merge constraints. Remaining differences are lower-level native sidecar details such as exact upstream anchor-position selection heuristics.
- Deletion failures still throw Java exceptions for existing transactional/rebuild failures instead of always returning `DeletionResult(status="fail")`; this preserves current Java error semantics.
- Async pipeline status shape: Java has submitted task support, task/stage snapshots, event listeners, cancellation, interrupted-task recovery, and per-workspace execution exclusivity. It does not mirror upstream's exact shared `pipeline_status` dictionary fields because Java uses persisted task records instead.
- MinerU parsing is present for PDFs, Office documents, HTML, and image OCR through the Java parsing pipeline. Parser failures now surface directly instead of downgrading to Tika, but Java does not implement the full upstream multimodal analysis lifecycle.

## Not Implemented By Design

- VLM role and RagAnything-style multimodal platform: upstream has `VLM` role configuration, `i/t/e` modality switches, sidecar analysis files, and VLM analysis workers. Java currently has no complete vision-model request pipeline or multimodal sidecar write-back stage, so this is intentionally not fabricated in this alignment pass.
- Docling/native parser routing and parser-hint DSL: upstream has a broad file-processing router (`LIGHTRAG_PARSER`, filename hints, parser queues). Java currently keeps the smaller `plain -> MinerU` parsing chain and reports MinerU configuration/runtime problems directly.
- Upstream async parse/analyze/process queue topology: Java has submitted task support and per-workspace execution exclusivity for ingest/delete/rebuild/materialization, but not the same native/mineru/docling/analyze/process worker queues. This remains tied to the broader parser/VLM platform work.

## Verification

- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.api.LightRagBuilderTest --tests io.github.lightrag.query.QueryEngineTest :lightrag-spring-boot-starter:test --tests io.github.lightrag.spring.boot.LightRagAutoConfigurationTest`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.E2ELightRagTest`
- `.\gradlew.bat :lightrag-core:test --rerun-tasks --tests io.github.lightrag.indexing.DocumentIngestorTest --tests io.github.lightrag.E2ELightRagTest`
- `.\gradlew.bat :lightrag-spring-boot-starter:test --rerun-tasks --tests io.github.lightrag.spring.boot.LightRagAutoConfigurationTest`
- `.\gradlew.bat :lightrag-core:test --rerun-tasks --tests io.github.lightrag.indexing.ChunkingOrchestratorTest`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.E2ELightRagTest`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.storage.mysql.MySqlStoresTest --tests io.github.lightrag.storage.postgres.PostgresStorageProviderTest --tests io.github.lightrag.storage.arcadedb.ArcadeStorageProviderTest`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentWithLlmCacheDeletionRemovesCacheAndDocument --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentWithUnsupportedLlmCacheDeletionFailsFastAndPersistsRetryMetadata --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentCanRetryFailedLlmCacheCleanupFromPersistedMetadata --tests io.github.lightrag.api.LightRagTaskApiTest.taskExecutionSerializesWorkWithinSameWorkspace --rerun-tasks --no-daemon`
- `.\gradlew.bat :lightrag-core:testClasses --no-daemon`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentWithLlmCacheDeletionRemovesCacheAndDocument --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentRemovesOnlyTargetChunkGraphReferencesWithoutReextractingRemainingDocuments --tests io.github.lightrag.E2ELightRagTest.ingestWithUnsupportedLlmCacheStoreFailsFast --tests io.github.lightrag.E2ELightRagTest.deleteByDocumentCanRetryFailedLlmCacheCleanupFromPersistedMetadata --tests io.github.lightrag.api.LightRagTaskApiTest --rerun-tasks --no-daemon`
- `.\gradlew.bat :lightrag-core:test --tests io.github.lightrag.storage.mysql.MySqlStoresTest --tests io.github.lightrag.storage.postgres.PostgresStorageProviderTest --tests io.github.lightrag.storage.arcadedb.ArcadeStorageProviderTest --rerun-tasks --no-daemon`
- `.\gradlew.bat :lightrag-core:test --no-daemon` was attempted but timed out after 5 minutes in this environment; targeted suites above passed.
- Earlier in this alignment sequence: chunking/Spring targeted tests for `F/R/V/P` and keyword role wiring passed.

## Remaining Recommended Order

1. If VLM is required, first design a minimal Java VLM model interface and sidecar analysis result schema before adding Spring role properties.
2. If exact paragraph native-sidecar parity is required, port upstream's remaining anchor-position heuristics into `ParagraphSemanticChunker`.
