# Build-side parity verification (2026-09-30)

Verification record for `docs/superpowers/plans/2026-09-30-java-lightrag-build-side-and-engineering-alignment-plan.md`
(19 tasks; build side + engineering side of the 2026-09-28 upstream alignment report).

## 1. Environment and method

| Item | Value |
|---|---|
| Java repo | `lightrag-java` @ 0.24.0-SNAPSHOT, branch `main`; the plan's implementation is uncommitted working-tree state on top of HEAD `07d7460`. The final build (§3) was re-run after the five unrelated commits that landed between `906786b` and `07d7460` (document-scoped task concurrency + javadoc UTF-8 fix, a separate work stream) so it validates the combined state |
| Upstream reference | `D:\ai-code\LightRAG` (HKUDS/LightRAG, `_version.py` 1.5.8) |
| JDK / Gradle | JDK 17 (Gradle toolchain), wrapper `./gradlew` |
| Docker | Docker 29.2.0 available on this host, so Testcontainers-backed suites executed rather than skipped |
| LLM credentials | not available in this environment — the RAGAS batch run below uses a deterministic local OpenAI-compatible stub for embeddings; the graph-quality delta is measured with an in-process deterministic chat model instead of a live LLM |

## 2. Commands run

All commands ran from the repository root on 2026-10-01.

```bash
# 1. Full build (Task 19 Step 1)
./gradlew build

# 2. Deterministic graph-quality harness, also re-run standalone while fixing it
./gradlew :lightrag-core:test --tests "io.github.lightrag.evaluation.OfflineGraphQualityEvaluationTest"

# 3. RAGAS batch evaluation, retrieval-only, against a local OpenAI-compatible stub
LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL=http://127.0.0.1:8931/v1/ \
LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL=mock-embed \
LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY=test \
./gradlew --no-daemon :lightrag-core:runRagasBatchEval \
  --args="--retrieval-only true --run-label task19-verification"

# 4. rebuild-vdb drift scenario (Task 19 Step 3): check -> rebuild --force -> check
#    drifted-recheck.* is a fresh copy of the drifted fixture because rebuild writes back in place
LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL=http://127.0.0.1:8931/v1/ \
LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL=mock-embed LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY=test \
./gradlew :lightrag-core:runRebuildVdb --args="--mode check --workspace default \
  --storage-profile in-memory --snapshot-file lightrag-core/build/rebuild-vdb-fixture/drifted-recheck.json"
# (same env) --mode rebuild ... --force --snapshot-file .../drifted-recheck.json
# (same env) --mode check  ... --snapshot-file .../drifted-recheck.json

# 5. Pre-existing demo-failure triage (detached worktree at the pre-plan HEAD)
git worktree add --detach ../lightrag-java-headcheck 906786b
# (in the worktree) ./gradlew :lightrag-spring-boot-demo:test \
#   --tests "io.github.lightrag.demo.DemoApplicationTest.uploadsDocxAndAnswersQueryThroughRawSourcePath"
```

The embedding stub is an in-process Python HTTP server that answers `/v1/embeddings` with deterministic
8-dimension hash vectors, so the retrieval numbers below are reproducible without credentials. The chat
surface is stubbed by `--retrieval-only true` (the CLI returns an empty extraction payload), which means
Task 19 exercises retrieval, not answer synthesis.

Command 3 initially failed with `java.io.FileNotFoundException: evaluation\ragas\sample_dataset.json`:
Gradle `JavaExec` runs in the module directory (`lightrag-core/`), so the CLI's repo-root-relative
defaults did not resolve. Fixed by adding `workingDir = rootProject.projectDir` to the three `JavaExec`
tasks in `lightrag-core/build.gradle.kts` (recorded in §7.2); the command above is the post-fix form.
The rebuild-vdb tasks also need the embedding-stub env vars even in `check` mode (the CLI builds the
embedding model up front), hence the prefix on command 4.

`maxParallelInsert` default asserted at `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java:71`
(`private int maxParallelInsert = 3;`).

## 3. Test results

`./gradlew build` — **BUILD SUCCESSFUL in 19m 41s**, `GRADLE_EXIT=0`, all three modules compiled and tested.
The run finished at 2026-10-01 07:38 (+0800), i.e. after the five unrelated commits at the top of `main`; a
follow-up `./gradlew build` re-checked the tree afterwards and reported all 18 tasks up-to-date with
`GRADLE_EXIT=0`, confirming the counts below describe the current working tree. Per-test XML timestamps in
`lightrag-core/build/test-results/test/` (07:37–07:38) cover the concurrency suites from that work stream
(`TaskExecutionServiceTest`, `LightRagDocumentConcurrencyTest`) alongside this plan's tests.

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `lightrag-core` | 1034 | 0 | 0 | 24 |
| `lightrag-spring-boot-demo` | 56 | 0 | 0 | 0 |
| `lightrag-spring-boot-starter` | 44 | 0 | 0 | 0 |
| **Total** | **1134** | **0** | **0** | **24** |

The 24 skips are the manual/real-API suites (`MineruSmartChunkerRealApiManualTest`,
`PublicDocumentSmartChunkerRealApiManualTest`, `EmbeddingSemanticMergeRealApiManualTest`,
`ArcadeLargeGraphVectorRetrievalBenchmarkManualTest`, `ArcadeVectorStoreManualIntegrationTest`,
`ArcadeVectorStoreRetrievalBenchmarkManualTest`) — they require external endpoints by design.

Docker 29.2.0 was running during the build, so the Testcontainers-backed suites executed rather than being
skipped: `PostgresStorageProviderTest` (29), `PostgresMilvusNeo4jStorageProviderTest` (13),
`MySqlMilvusNeo4jStorageProviderTest` (11), `PostgresNeo4jStorageProviderTest` (14),
`WorkspaceScopedNeo4jGraphStoreTest` (10), `MilvusVectorStoreTest` (18), plus the per-store integration
suites.

`OfflineGraphQualityEvaluationTest` passes deterministically (single test method covering three
configurations, ~6 s per run) and writes `lightrag-core/build/graph-quality-metrics.json` with the full
per-config description dump used in §4.

## 4. Evaluation deltas

### 4.1 Graph quality, before/after Task 1 (deterministic, LLM-free)

`OfflineGraphQualityEvaluationTest` ingests the three `evaluation/ragas/sample_documents` files with a
rule-based chat model and measures the stored graph under three configurations on the same fixtures.
`summarization-disabled` reproduces the pre-Task-1 concatenation path (`forceLlmSummaryOnMerge` /
`summaryMaxTokens` pushed to `Integer.MAX_VALUE`).

| Config | Entities | Relations | Mean entity desc | Max entity desc | Mean relation desc | LLM summary calls | Descriptions containing `<SEP>` (entity/relation) |
|---|---|---|---|---|---|---|---|
| `summarization-disabled` (pre-Task-1 path) | 15 | 16 | 207 | 847 | 66 | 0 | 4 / 1 |
| `shipped-defaults` (8 fragments / 1200 tokens) | 15 | 16 | 131 | 456 | 66 | 2 | 2 / 1 |
| `aggressive-threshold` (2 / 400) | 15 | 16 | 117 | 240 | 66 | 15 | 0 / 0 |

Reading: graph shape is unchanged (15 entities, 16 relations in every configuration — the assertions pin
this), while entity descriptions condense 36.7 % (mean 207→131) under the shipped defaults and 43.5 %
(207→117) under the aggressive threshold; the longest entity description shrinks 46 %/72 %
(847→456/240). The `<SEP>` counters prove the substitution: with the aggressive threshold every merged
description was LLM-summarized, so no raw fragment join survives in the stored graph. Relation
descriptions in this fixture have at most two fragments, where a deterministic mock cannot add value
beyond the join — hence the flat 66.

The full per-config description dump is written to `lightrag-core/build/graph-quality-metrics.json` by the
test itself (the JSON also powers the numbers above).

The harness is also what surfaced the merge-time deadlock described in §7.2: before the lock fix the test
hung inside `:lightrag-core:test` for 11 minutes with `IndexingPipeline.mergeGroupedConcurrently` waiting
on workers parked in `InMemoryLlmCacheStore.load`; after the fix it completes in ~6 s.

### 4.2 RAGAS batch retrieval (retrieval-only, deterministic embedding stub)

`runRagasBatchEval` ingested the three `evaluation/ragas/sample_documents` files and ran the six
`sample_dataset.json` questions in `MIX` mode (topK 10, chunkTopK 10, maxHop 2, pathTopK 3, multi-hop
enabled, in-memory storage). The run finished `GRADLE_EXIT=0` and emitted one JSON envelope
(`request` + `summary` + 6 `results`).

| Case | Retrieved contexts (topK) | Answer chars | Entities/relations in answer | Ground-truth token coverage |
|---|---|---|---|---|
| 0 | 10 | 7827 | none (stub) | 0.69 |
| 1 | 10 | 7814 | none (stub) | 0.64 |
| 2 | 10 | 8130 | none (stub) | 0.97 |
| 3 | 10 | 7828 | none (stub) | 0.75 |
| 4 | 10 | 8106 | none (stub) | 0.97 |
| 5 | 10 | 8120 | none (stub) | 0.85 |

Reading: all 6/6 questions return the full 10-chunk context budget, sourced from every fixture document
(`01-lightrag-overview` … `05-evaluation-and-deployment`, plus `readme`) — the retrieval path
(chunk vectors, ranking, context assembly) works end-to-end on the deterministic stub. Coverage of
ground-truth vocabulary ranges 0.64–0.97, which is what the fixture granularity allows: the retrieval-only
mode deliberately stubs extraction with `EMPTY_GRAPH_EXTRACTION_RESPONSE`, so no entity/relation context
can appear and the graph channel contributes nothing by design. No answer-quality score is claimed here —
RAGAS's LLM-judged metrics need a live chat model, which this environment does not have (§1); this run is
the Java-side half of Step 2, complementing the LLM-free graph-quality delta in §4.1.

### 4.3 rebuild-vdb drift scenario

Fixture: `drifted-recheck.json` → `drifted-recheck.payload.json`, a store whose graph side holds chunks
`c1,c2`, entities `e1,e2` and relation `r1`, while its vector namespace holds only chunk `c1` and entity
`e1` — i.e. injected drift (`c2`, `e2`, `r1` vectors missing). The copy is made per run because
`--mode rebuild` writes back to the same snapshot path, so the check/recheck pair can only be replayed on
a fresh fixture (command 4 in §2).

| Step | Command | JSON result fields |
|---|---|---|
| check (drift) | `--mode check` | `clean=false`, `missingItems=3`, `staleItems=0`, `missingChunkVectorIds=[c2]`, `missingEntityVectorIds=[e2]`, `missingRelationVectorIds=[r1]` |
| rebuild | `--mode rebuild --force` | `embeddedItems=5`, pre-rebuild counts `missingItems=3`, `clean=false` |
| recheck | `--mode check` | `clean=true`, `missingItems=0`, `staleItems=0`, all id lists empty |

After the rebuild, the payload on disk contained exactly the regenerated namespace — chunk vectors
`[c1,c2]`, entity vectors `[e1,e2]`, relation vectors `[r1]`, each 8-dim, matching the stub embedding
dimension — confirming the write-back went to the same snapshot file. The same three-step sequence run
earlier against `drifted-store.json` produced identical counters (check → 3 missing, rebuild → 5
embedded, recheck → clean), so the scenario is reproducible across fixture copies.

## 5. Behavior-change verification table

The "After" column is copied from the plan's *Behavior Changes (user-visible)* table; "verified by" names
the automated check that pins the behavior.

| # | Change | Before | After | Verified by |
|---|---|---|---|---|
| 1 | Entity/relation descriptions | first non-empty description wins | `<SEP>`-joined deduped fragments; LLM map-reduce summary at ≥8 fragments or ≥1200 tokens | `DescriptionSummarizerTest`, `DescriptionFragmentsTest`, `CachedChatModelTest`, and the three-config harness `OfflineGraphQualityEvaluationTest` (§4) |
| 2 | Entity type on merge | first non-empty wins | per-row majority vote across batch + stored type counted once (ties: first seen) | `GraphAssemblerTest#picksTheMajorityEntityTypeAcrossTheBatch`, `#breaksEntityTypeTiesByFirstSeenOrder`, `#exposesPerRowEntityTypeCountsInFirstSeenOrder`, `#countsTypeVotesFromAliasMergedEntitiesPerRow`; merge-with-stored-type: `IndexingPipelineBatchGraphPersistenceTest#letsTheIncomingBatchTypeWinTiesAgainstTheStoredType`, `#countsEveryBatchRowWhenVotingAgainstTheStoredType`, `#treatsAnEmptyStoredEntityTypeAsNoVote`, `#treatsAnEmptyIncomingEntityTypeAsNoVote` |
| 3 | Extracted relation weight | clamped model `weight`/`confidence` in `[0,1]` | constant `1.0`; merge accumulates + evidence floor | `RelationEvidenceTest`, `GraphAssemblerTest` (weight/floor cases) |
| 4 | `sourceChunkIds` size | unbounded | ≤200 (KEEP default; FIFO selectable), deletion survival via attribution index | `SourceIdLimitsTest`, `E2ELightRagTest#deletingCappedOutSourceChunksStillKeepsTheEntityWhenAttributionRemains` |
| 5 | Relation `filePath` | always `""` | accumulated from chunk `file_path`, deduped, ≤75 + `...truncated...(KEEP Old)` | `FilePathLimitsTest`, assembler/materialization cases in `GraphAssemblerTest` |
| 6 | Extraction prompt | no quantity guidance | `at most 100 total rows / 40 entity rows` (configurable) | `KnowledgeExtractorTest` (caps injected), `LightRagBuilderTest` (config plumbing) |
| 7 | Extraction output | used as-is | optional `kgExtractionValidator` hook per chunk | `KnowledgeExtractorTest` (drop/rewrite/null-fails-chunk) |
| 8 | Extraction prompt | no section context | `---Section Context---` breadcrumb from chunk heading metadata (≤256 tokens) | `SectionContextFormatterTest`, `KnowledgeExtractorTest`, `E2ELightRagTest` |
| 9 | Extracted text with LaTeX | `\f`/`\b` damage kept | JSON-escape repair + dollar-math whitespace repair | `LatexEscapeRepairTest`, `ChunkTextSanitizerTest` |
| 10 | Chat request payload | `model`/`messages`/`stream` | + `temperature`/`max_tokens`/`top_p`/`response_format` from config and per-call overrides | `ChatRequestOptionsTest`, `ConfiguredChatModelTest`, `OpenAiCompatibleChatModelTest`, `LightRagAutoConfigurationTest` |
| 11 | Model responses | `String`, metadata dropped | `ChatResponse` with `finishReason`/`usage`; truncated answers never cached | `ChatResponseTest`, `CachedChatModelTest#doesNotCacheTruncatedResponses`, `OpenAiCompatibleChatModelTest` (finish_reason/usage parsing) |
| 12 | Model calls | single attempt | 3 attempts, exponential backoff, transient-only classification | `ModelRetrySupportTest`, `OpenAiCompatibleChatModelTest`/`OpenAiCompatibleEmbeddingModelTest` (non-retryable fail-fast) |
| 13 | LLM concurrency | unbounded per pipeline thread pool | fair per-role semaphore (LLM 4 / embedding 8), `maxParallelInsert` 2→3 | `LlmConcurrencyBudgetTest`, `LightRagDocumentConcurrencyTest` |
| 14 | Rerank | interface only | `OpenAiCompatibleRerankModel` (Cohere/Jina-compatible `/rerank`) + Spring property | `OpenAiCompatibleRerankModelTest`, `LightRagAutoConfigurationTest`, `LightRagProperties` binding |
| 15 | Graph read | CRUD only | `labels()`, `searchLabels()`, bounded BFS `getKnowledgeGraph()` with `truncated` flag | `GraphStoreReadSurfaceTest`, `LightRagGraphReadApiTest`, `GraphManagementPipelineTest`, `GraphSnapshotCapabilitiesTest` |
| 16 | Vector writes | dimension check only | embedding-space marker + typed refusal on model/space change; Noop vector route | `EmbeddingSpaceGuardTest`, `NoopVectorStoreTest`, `OpenAiCompatibleEmbeddingModelTest` (cache identity), `PostgresStorageProviderTest` |
| 17 | Doc status | get-by-id / list-all | status filter + pagination + batch ids | `LightRagDocumentStatusQueryTest` (5 cases, incl. pagination and batch ids) |
| 18 | Ops | none | `rebuild-vdb` CLI (`--mode check\|rebuild`) + Gradle task | `RebuildVectorIndexServiceTest` (6 cases) and the captured CLI outputs in §4 |

## 6. Divergences that remain open

Each item was re-checked against the working tree on 2026-10-01; none blocks the plan's scope.

1. **Entity `fileName`** — upstream writes `file_path` on entities as well (`operate.py:714`, `:758`: the
   entity record carries `file_path`), while the Java `GraphStore.EntityRecord` has no file-path field
   (`GraphStore.java:289` defines it only on `RelationRecord`). Task 5 aligned relations only; entities
   keep no provenance field, so entity citations cannot name a source file. Closing this needs a storage
   contract change (record + schema + snapshot migration) and was left out of the plan's scope.
2. **Priority-based LLM scheduling** — upstream dispatches LLM calls through a priority queue
   (`priority_limit_async_func_call`), so interactive queries outrank background extraction. Java uses a
   fair per-role semaphore (`LlmConcurrencyBudget`) with FIFO ordering and no priority classes; README
   states this explicitly. A query that arrives while 4 extraction calls hold the budget waits behind them.
3. **Provider breadth** — upstream ships ~20 KV/vector/graph backends (Redis, MongoDB, Qdrant, Milvus,
   Neo4j, NetworkX, PG, …). Java's `StorageProvider` family covers in-memory, Postgres, MySQL, Neo4j,
   Milvus, and ArcadeDB subsets. Parity is per-contract, not per-backend list; new backends are additive
   work outside the plan.
4. **Chunk-tracking store parity** — upstream keeps dedicated `entity_chunks` / `relation_chunks` tables
   for "which chunks back this node" queries. Java derives the same information from the per-document
   graph snapshots (`GraphChunkAttribution` over `DocumentGraphSnapshotStore`). Deletion semantics match
   the plan's requirement, but the "list chunks for an entity" query surface does not exist yet.
5. **Pushdown graph traversal** — upstream graph stores push label search and BFS into the backend
   (`base.py:1119-1252`). Java implements `labels()`, `searchLabels()`, and `getKnowledgeGraph()` as
   `GraphStore` default methods (`GraphStore.java:90`, `:103`, `:135`) that scan in memory, so a remote
   Neo4j store still ships the full graph to the client for BFS. Correctness is pinned by tests; scale
   behavior is not yet upstream-equivalent.

## 7. Deviations between the plan text and the delivered code

Every deviation below is a deliberate, test-backed adjustment; the plan's requirements remain satisfied.

### 7.1 Delivered during Tasks 1–18 (recorded per task)

- **Task 4/5**: `file_path` accumulation lives in the assembler layer with its own cap, and
  `DeletionPipeline` preserves `filePath` on relation records it rebuilds — the plan sketched these at
  storage level.
- **Task 6**: the record caps are carried on `LightRagConfig` rather than only as builder defaults.
- **Task 7**: a validator returning `null` raises `ExtractionException` (the plan sketch suggested NPE).
- **Task 8**: the breadcrumb separator is `" → "` (plan sketch wrote `">"`).
- **Task 9**: the plan's example `"a \f rac"` expectation conflicted with the upstream rule that repairs
  `\f`/`\b` only when directly followed by a letter; implementation follows upstream. Repair runs before
  sanitize (as planned), and the code avoids `List.getFirst` (Java 17).
- **Task 10**: the builder wraps injected role models in a package-private `ConfiguredChatModel`; the
  window-extraction call site also carries the `JSON_OBJECT` response format.
- **Task 11**: `CachedChatModelTest` was created (the plan listed it as Modify but it did not exist); the
  plan's sketched 4-arg `ModelException` / 1-arg `ModelTimeoutException` constructors do not exist (they
  are 5-arg and 3-arg).
- **Task 12**: the plan text contradicted itself on 429 handling; implementation follows upstream's
  `_is_retryable_rate_limit_error` — plain rate limits retry, `budget_exceeded`/`insufficient_quota` fail
  fast. `.modelMaxAttempts(int)` wraps injected models in `RetryingChatModel`/`RetryingEmbeddingModel`
  decorators and skips models that already retry internally (no 3×3 amplification).
- **Task 16**: added `NoopVectorStorageProvider`/`NoopVectorWorkspaceStorageProvider` decorators (outside
  the plan's file list); noop `search` returns empty instead of upstream's `StorageCapabilityError`; the
  space marker is not cleared by `restore`/`truncateAll`; `GraphManagementPipeline`'s restore path does
  not consult the guard; Postgres schema version 6→7; both retry decorators delegate `cacheIdentity()`.
- **Task 17**: `DocumentStatusPage` is its own file (repo style: one public type per file).
- **Task 18**: the service accepts `WorkspaceStorageProvider`/`AtomicStorageProvider` (workspace
  resolution), the marker is deleted after a rebuild so the next ingest re-records it, the shared vector
  builder was extracted to `indexing/GraphVectorIndexer`, and the CLI gained `--snapshot-file` for
  offline-reproducible drift scenarios (used by Task 19 Step 3).
- All Commit steps inside Tasks 1–18 were skipped: commits require an explicit user instruction.

### 7.2 Found and fixed while executing Task 19

- **Deadlock between merge-time summarization and the LLM cache (production severity).** Task 1 puts
  summary LLM calls on merge worker threads inside `writeAtomically`, whose provider-wide fair
  `ReentrantReadWriteLock` was also the lock of `InMemoryLlmCacheStore` (and of the four `LockedLlmCacheStore`
  wrappers in the DB providers). Workers blocked on the cache read lock while the committing thread held
  the write lock — a guaranteed hang for any ingest that triggers summarization. Fixed by giving the cache
  its own lock: the in-memory provider now passes a private `ReentrantReadWriteLock`, and the four DB
  providers use the new `IndependentlyLockedLlmCacheStore` (`lightrag-core/.../storage/IndependentlyLockedLlmCacheStore.java`).
  The cache is content-addressed and is not part of any storage snapshot, so the shared lock carried no
  transactional value. Evidence: `jstack` showed `InMemoryLlmCacheStore.load` parked while
  `IndexingPipeline.mergeGroupedConcurrently` waited on workers.
- **Pre-existing demo failure fixed (unrelated to this plan).** `DemoApplicationTest.uploadsDocxAndAnswersQueryThroughRawSourcePath`
  asserted a 202 DOCX round-trip that commit `58166d4` (2026-05-23, "fail fast on parser and rerank
  errors") had invalidated; reproduced identically on a detached worktree at HEAD `906786b` before this
  plan's changes. Rewritten as `rejectsSyncDocxUploadWhenMineruIsNotConfigured` (expects the documented
  400 + `Mineru provider is not configured`).
- **Harness fidelity fixes (test-only).** `OfflineGraphQualityEvaluationTest`'s summary mock initially
  re-parsed Jackson-escaped description lines without a JSON unescape (each round multiplied `\r` escapes
  and inflated lengths up to the 240-char cap) and concatenated fragments literally, re-appending
  sentences a stored summary already contained. It now performs a real JSON unescape and a sentence-level
  consolidation, which reproduces a summarizer's condensing behavior deterministically.
- **README scope (Task 19 Step 5)** covers the new builder options, Spring properties, defaults table
  updates (`maxParallelInsert` 1→3), and the `runRebuildVdb` usage with the "stop writers first" warning.
