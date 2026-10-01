# Query-side parity verification (2026-09-28)

Verification record for `docs/superpowers/plans/2026-09-28-java-lightrag-query-upstream-parity-plan.md`
(15 tasks; query side of the 2026-09-28 upstream alignment report).

## 1. Environment and method

| Item | Value |
|---|---|
| Java repo | `lightrag-java` @ 0.24.0-SNAPSHOT, branch `main`; the plan's implementation is committed per task (Task 1 `3803fee` … Task 13 `d80a019`, Task 14 in `97f3139` + `d9f44f4`). The verification run below executed on the unchanged code of HEAD `d80a019`; the only working-tree changes at that point are this note and the Task 15 README edit |
| Upstream reference | `D:\ai-code\LightRAG` (HKUDS/LightRAG, `_version.py` 1.5.8) |
| JDK / Gradle | JDK 17 (Gradle toolchain), wrapper `./gradlew` |
| Docker | Docker 29.2.0 available on this host, so Testcontainers-backed suites executed rather than skipped |
| LLM credentials | not available in this environment — the RAGAS batch runs below use the same deterministic local OpenAI-compatible embedding stub as the build-side verification (8-dimension sha256 hash vectors on `127.0.0.1:8931`), and the chat surface is stubbed by `--retrieval-only true` |

## 2. Commands run

All commands ran from the repository root on 2026-10-01.

```bash
# 1. Full build (Task 15 Step 1)
./gradlew build

# 2. RAGAS batch evaluation, retrieval-only, against the deterministic embedding stub
#    2a. previous settings (baseline-comparable with the build-side note's §4.2 run)
LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL=http://127.0.0.1:8931/v1/ \
LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL=mock-embed \
LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY=test \
./gradlew :lightrag-core:runRagasBatchEval \
  --args="--retrieval-only true --top-k 10 --chunk-top-k 10 --run-label query-parity-10-10"

#    2b. new upstream defaults (topK 40 / chunkTopK 20, relatedChunkNumber 5, chunkPickMethod VECTOR)
LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL=http://127.0.0.1:8931/v1/ \
LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL=mock-embed \
LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY=test \
./gradlew :lightrag-core:runRagasBatchEval \
  --args="--retrieval-only true --top-k 40 --chunk-top-k 20 --run-label query-parity-40-20"
```

The evaluation CLI carries its own `--top-k 10 --chunk-top-k 10` defaults
(`RagasBatchEvaluationCli.java:64-65`), which would otherwise shadow the SDK's new 40/20 defaults, so
both runs pin the top-k settings explicitly. The remaining settings are the live defaults (mode `MIX`,
`maxHop` 2, `pathTopK` 3, multi-hop enabled, in-memory storage, `relatedChunkNumber` 5 /
`chunkPickMethod` `VECTOR`). With `--retrieval-only true` the extraction stub makes the KG channels
empty, so the runs measure chunk retrieval only — the same protocol as the build-side run, which keeps
the two records comparable.

## 3. Test results

`./gradlew build` — **BUILD SUCCESSFUL in 13m 27s**, `GRADLE_EXIT=0`, all three modules compiled and
tested. The run started 2026-10-01 16:07 (+0800) on HEAD `d80a019`; `:lightrag-core:test` re-executed
fresh (all 135 per-class XMLs in `lightrag-core/build/test-results/test/` are timestamped 16:19),
`:lightrag-spring-boot-starter:test` re-executed, and `:lightrag-spring-boot-demo:test` was UP-TO-DATE
from its most recent execution at 16:06 earlier the same day (no demo inputs changed since).

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `lightrag-core` | 1106 | 0 | 0 | 24 |
| `lightrag-spring-boot-demo` | 56 | 0 | 0 | 0 |
| `lightrag-spring-boot-starter` | 44 | 0 | 0 | 0 |
| **Total** | **1206** | **0** | **0** | **24** |

The core count is 72 higher than the build-side verification record (1034); the delta is this plan's
new and extended query-side tests (Tasks 2–14).

The 24 skips are the same manual/real-API suites as the build-side record
(`MineruSmartChunkerRealApiManualTest`, `PublicDocumentSmartChunkerRealApiManualTest`,
`EmbeddingSemanticMergeRealApiManualTest`, `ArcadeLargeGraphVectorRetrievalBenchmarkManualTest`,
`ArcadeVectorStoreManualIntegrationTest`, `ArcadeVectorStoreRetrievalBenchmarkManualTest`) — they
require external endpoints by design.

Docker 29.2.0 was running during the build, so the Testcontainers-backed suites executed rather than
being skipped: `PostgresStorageProviderTest` (29), `PostgresMilvusNeo4jStorageProviderTest` (13),
`MySqlMilvusNeo4jStorageProviderTest` (11), `PostgresNeo4jStorageProviderTest` (14),
`WorkspaceScopedNeo4jGraphStoreTest` (10), `MilvusVectorStoreTest` (18), plus the per-store integration
suites — identical counts to the build-side record.

## 4. Evaluation deltas

### 4.1 RAGAS batch retrieval at the previous settings (topK 10 / chunkTopK 10)

`runRagasBatchEval --retrieval-only true --top-k 10 --chunk-top-k 10 --run-label query-parity-10-10`
finished `GRADLE_EXIT=0` and emitted one JSON envelope (`request` + `summary` + 6 `results`). The
"baseline" column is the build-side verification record's §4.2 run, which used the same settings and the
same deterministic embedding stub.

| Case | Retrieved contexts | Answer chars | Ground-truth token coverage | Baseline coverage (§4.2) |
|---|---|---|---|---|
| 0 | 10 | 8072 | 0.69 (18/26) | 0.69 |
| 1 | 10 | 8059 | 0.64 (18/28) | 0.64 |
| 2 | 10 | 8359 | 0.97 (32/33) | 0.97 |
| 3 | 10 | 8073 | 0.75 (40/53) | 0.75 |
| 4 | 10 | 8351 | 0.97 (36/37) | 0.97 |
| 5 | 10 | 8365 | 0.85 (50/59) | 0.85 |

Reading: at constant settings the retrieval path is unchanged for all six questions — comparing the
per-case `sourceId` lists of this run against the build-side log shows identical chunk sets *and*
identical orders, and the coverage is per-case identical to the baseline. The only delta is the rendered context: every answer is 229–245
chars longer and now carries numbered chunk lines (`- [1] 02-rag-architecture:1 | 3.781 | …`) plus a
`Reference Document List` of the six fixture documents — the Task 12 format change. The structured
`references` list and `Context.referenceId`/`source` stay empty in this run because the evaluation
harness leaves `includeReferences` at its `false` default; only the assembled-context rendering changes.

### 4.2 RAGAS batch retrieval at the new defaults (topK 40 / chunkTopK 20)

`runRagasBatchEval --retrieval-only true --top-k 40 --chunk-top-k 20 --run-label query-parity-40-20`
finished `GRADLE_EXIT=0`. Related-chunk picking runs at its defaults (`relatedChunkNumber=5`,
`chunkPickMethod=VECTOR`), though they are inactive here because the extraction stub empties the KG
channels.

| Case | Retrieved contexts | Answer chars | Ground-truth token coverage | vs 10/10 |
|---|---|---|---|---|
| 0 | 14 | 11926 | 1.00 (26/26) | +0.31 |
| 1 | 14 | 11926 | 0.79 (22/28) | +0.15 |
| 2 | 14 | 11926 | 0.97 (32/33) | 0.00 |
| 3 | 14 | 11926 | 0.91 (48/53) | +0.16 |
| 4 | 14 | 11926 | 0.97 (36/37) | 0.00 |
| 5 | 14 | 11926 | 0.97 (57/59) | +0.12 |

Reading: the sample corpus holds exactly 14 chunks, so the wider candidate pool (topK 40) admits the
whole corpus and every question retrieves all 14 chunks — in a question-dependent order (the per-case
chunk-line orders differ). Mean coverage rises from 0.81 (10/10) to 0.94 (40/20), with two cases
saturating at 1.00/0.97 whose shortfall at 10/10 was purely a candidate-pool limit. The identical
answer length across cases is expected: the same 14-chunk set is rendered, and character count is
order-insensitive. No answer-quality score is claimed — the retrieval-only protocol stubs both the chat
and the extraction surfaces (§1), so this run is the Java-side half of Task 15 Step 2.

## 5. Behavior-change verification table

The "After" column is copied from the plan's *Behavior Changes (user-visible)* table; "verified by" names
the automated check that pins the behavior.

| # | Change | Before | After | Verified by |
|---|---|---|---|---|
| 1 | Default `topK` / `chunkTopK` | 10 / 10 | 40 / 20 | `LightRagBuilderTest#queryRequestDefaultsMatchUpstreamTopKAndChunkTopK`, `#queryRequestDefaultsToMixMode` |
| 2 | Empty / <3-weight query | accepted | `IllegalArgumentException` (bypass: empty only) | `QueryValidationTest` (`rejectsEmptyAndWhitespaceOnlyQueries`, `weightsEastAsianCharactersTwice`, `rejectsQueriesBelowMinimumWeightWithUpstreamMessage`, `bypassPathOnlyRejectsEmpty`); guard call sites `QueryEngine.java:385-388`, `:433-436` |
| 3 | Empty retrieval | LLM call over `(none)` context | canned `fail_response`, no LLM call, `llmGenerated=false` | `QueryEngineTest#returnsFailResponseWithoutCallingTheModelWhenNothingIsRetrieved`, `#failResponseIsStreamedAsASingleChunkWhenStreamingIsRequested`, `#contextOnlyAndPromptOnlyRequestsStillGetTheFailResponse`, `#failResponseMatrixCoversEveryKgMode`, `#emptyRetrievalUnderTheMultiHopRouteFailsToo` |
| 4 | Retrieval non-empty but budget-truncated to nothing | indistinguishable from a normal query (LLM call) | stays a normal query: empty context preserved, never converted into `fail_response` (`operate.py:6015-6027`) | `QueryEngineTest#budgetExhaustionKeepsTheEmptyContextInsteadOfTheFailResponse`, `#budgetExhaustionKeepsTheEmptyContextInNaiveAndStructuredToo` |
| 5 | `user_prompt` | sent verbatim | server prefix prepended unless `disableUserPromptPrefix` | `QueryEngineTest#prependsConfiguredUserPromptPrefixToTheSystemPromptSlot`, `#prefixAloneIsUsedWhenTheRequestHasNoUserPrompt`, `#disableUserPromptPrefixSuppressesThePrefix`, `#noPrefixAndNoUserPromptRenderNa` |
| 6 | KG→chunk selection | union of all `sourceChunkIds`, score-sorted | `related_chunk_number` quota, WEIGHT/VECTOR picker, first-owner dedup | `KgChunkSelectorTest` (9 cases); `LocalQueryStrategyTest#selectsKgChunksByQuotaInsteadOfUnioningEverySourceChunk`, `#zeroRelatedChunkNumberDisablesKgChunksEntirely`; `GlobalQueryStrategyTest#globalSelectsKgChunksByQuotaInsteadOfUnioningEverySourceChunk`, `#globalZeroRelatedChunkNumberDisablesKgChunksEntirely` |
| 7 | hybrid/mix chunk merge | global score sort | round-robin source interleave | `ChunkMergesTest` (3 cases); `MixQueryStrategyTest#mixMergesHybridChunksWithDirectChunkRetrieval`; `HybridQueryStrategyTest#hybridMergesLocalAndGlobalByIdWithMaxScoreRetention` |
| 8 | local relation order | parent-entity score | `(degree, weight)` desc | `LocalQueryStrategyTest#ranksRelationsByCombinedEndpointDegreeThenWeight` |
| 9 | Rerank call | no `top_n`, leftovers appended | `top_n=chunkTopK`, provider order authoritative, configurable failure mode | `QueryEngineTest#passesChunkTopKAsRerankTopNAndTreatsProviderOrderAsAuthoritative`, `#ignoresOutOfRangeAndNonFiniteRerankResults`, `#dropsCandidatesOmittedByTheRerankerAndIgnoresUnknownIds`, `#fallsBackToOriginalOrderWhenConfiguredAndTheRerankerFails`, `#defaultFailureModeStillFailsFast` |
| 10 | Token counts | whitespace splitting | `TokenCounter` (CJK-aware default) | `HeuristicTokenCounterTest` (`countsEachWideCodePointAsOneToken`, `countsNonWideRunsAtFourCharactersPerToken`, `handlesMixedTextAndEmptyInput`) |
| 11 | Chunk budget buffer | 16 | 200 (`buffer_tokens` parity) | constant `QueryEngine.REFERENCE_LIST_BUDGET_BUFFER_TOKENS` (`QueryEngine.java:33`), exercised by the budget cases in `QueryEngineTest` and `ChunkBudgetTruncatorTest` |
| 12 | Chunk budget counting | sum of stored per-chunk `tokenCount()` (rendered line overhead uncounted) | two-stage render-verified count over the exact context projection; whole chunks only, boundary chunk dropped | `ChunkBudgetTruncatorTest` (7 cases incl. `dropsTheBoundaryChunkWholeInsteadOfTrimmingItsText`, `stageTwoShrinksWhatStageOneOverAdmitted`); `QueryEngineTest#trimsFinalChunksToRemainingMaxTotalTokensAfterRetrieval`, `#rerankStillAppliesOriginalMaxTotalTokensAfterExpandedRetrieval`, `#recalculatesMultiHopChunkBudgetWithoutReasoningContextWhenTrimmedChunksPreventReuse` |
| 13 | Context chunk lines | `- id \| score \| text` | `- [n] id \| score \| headings \| text` + `Reference Document List` | `ContextAssemblerTest#rendersReferenceIdsHeadingsAndReferenceList`, `#referenceIdsMatchQueryReferencesOrdering`, `#approxProjectionOmitsReferenceIdSoStageOneNeverUndercountsTheRenderer`; `ChunkHeadingsTest` (6 cases) |
| 14 | `QueryResult` | answer/contexts/references | + `responseTime`, `llmGenerated` | `QueryEngineTest#reportsNonNegativeResponseTime`, `#marksContextOnlyAndPreviewResultsAsNotLlmGenerated`, `#structuredFailResponseIsNotLlmGenerated`, `#failResponseIsStreamedAsASingleChunkWhenStreamingIsRequested` |
| 15 | LLM cache key | `default:{role}:{hash}` | `v2:{role}:{sha256(identity)}:{sha256(request+options)}`, identity folds merge defaults, history bypass | `CachedChatModelTest` (`cacheKeyIncludesPolicyVersionAndHashesTheModelIdentity`, `cacheKeyStaysWithinTheMysqlColumnLimitForLongIdentities`, `modelIdentityChangeInvalidatesCachedAnswers`, `requestOptionsChangeTheCacheKeyWithoutChangingThePrompt`, `historyCarryingRequestsBypassTheCache`, `extractionHistoryIsKeyedInsteadOfBypassed`, `cachesCompleteResponsesAndReplaysThemWithoutCallingTheDelegateAgain`, `doesNotCacheTruncatedResponses`, `staticCacheIdMatchesTheKeyTheWrappedModelUses`); `OpenAiCompatibleChatModelTest#cacheIdentityIncludesConstructorDefaultsButNotTimeouts` |

## 6. Divergences that remain open

Each item was re-checked against the working tree on 2026-10-01.

1. **One-shot retrieval bypasses KG chunk selection** — when a `StorageProvider` implements
   `OneShotRetrievalStore`, its `retrieveLocal`/`retrieveGlobal`/`retrieveMix` results skip
   `KgChunkSelector` (and therefore `relatedChunkNumber` and the VECTOR/WEIGHT pickers) for the chunks
   it returns. The interface javadoc documents the divergence (`OneShotRetrievalStore.java:14-21`);
   closing it needs the store contract to accept a `relatedChunkNumber` input.
2. **Entity/relation context lines keep the Java pipe format** — upstream renders entities/relations as
   JSON lines, while the Java prompt still writes `- id | name | score`
   and `- srcId -> tgtId | keywords | score` (`QueryBudgeting.java:32-42`). The plan lists this as
   out of scope; only chunk lines adopted the upstream shape (Task 12).
3. **Chunk truncation markers** — the finish-reason/usage metadata, the `TruncatedResponse` semantics
   (never caching truncated answers) came from the companion build-side plan Task 11 (`ChatResponse`,
   `CachedChatModel`), not from this plan; recorded here so the two records agree.

Platform-side follow-ups (out of this repository's scope, from the plan's platform checklist):

- Map the query-validation `IllegalArgumentException` to HTTP 400 on the platform query path — short
  queries currently surface as 500 through `LightRagPlatformChatModel`'s call chain.
- Override `cacheIdentity()` in `LightRagPlatformChatModel` (provider + model + endpoint); expect the
  one-time `v2:` invalidation of cached answers, and optionally drop the old `default:*` key space.
- Re-baseline retrieval quality on the platform for Tasks 5–8 and 10–12 (candidate sets, order and
  prompt shape all changed).
- Watch LOCAL-query latency after Task 8 on Neo4j; batch `findRelations` if it regresses.
- Decide exposure of `relatedChunkNumber` / `chunkPickMethod` / `disableUserPromptPrefix` in
  `KnowledgeRetrievalConfigResolver`; defaults (5 / VECTOR / off) already match upstream.
- Migrate `disableSdkRerank` to a builder-based copy when convenient; the legacy constructor remains
  public API for the platform.
- The platform rerank model sends its own `top_n = candidates.size()` and ignores `request.topN()`
  (optional follow-up noted by the plan's Task 9 platform note).

## 7. Deviations between the plan text and the delivered code

Every deviation below is a deliberate, test-backed adjustment; the plan's requirements remain satisfied.

### 7.1 Delivered during Tasks 1–15 (recorded per task)

- **Task 2**: the guard lives in `QueryValidation` with the shared `UnicodeWidths` wide-codepoint table
  (`QueryValidation.java:9`); the engine rejects empty queries on both paths and applies the
  CJK-weighted minimum only to RAG modes (`QueryEngine.java:385-388`, `:433-436`).
- **Task 5/6**: the VECTOR branch of `KgChunkSelector` is batch all-or-nothing — it ranks the full
  candidate set through the `ChunkVectorRanker` seam and falls back to WEIGHT polling only when the
  ranker returns nothing or throws (`KgChunkSelector.java:67-87`); upstream pulls vectors per group and
  can partially rank. Production ranker: `VectorStoreChunkVectorRanker`; tests inject fakes.
- **Task 7**: the one-shot path merges graph chunks before direct chunks
  (`MixQueryStrategy.java:147-152`) while the branching path merges direct chunks first (`:222-227`), so
  the deterministic interleave can differ by path when source counts are uneven.
- **Task 8**: the one-shot local fast path keeps the store's relation order
  (`LocalQueryStrategy.java:141-143`); the `(degree, weight)` ranking applies on the strategy-side
  traversal only.
- **Task 9**: delivered per the plan sketch; the pre-existing test pinning the leftover-append behavior
  was inverted (`appendsOmittedCandidatesInOriginalOrderAfterRerankResults` →
  `dropsCandidatesOmittedByTheRerankerAndIgnoresUnknownIds`), and the 2-arg `RerankRequest` constructor
  is retained for platform source compatibility (`RerankModel.java:16-18`).
- **Task 10**: `QueryBudgeting` is instance-based and package-private; `QueryEngine`'s legacy
  convenience constructors default to `HeuristicTokenCounter` (`QueryEngine.java:336`); the multi-hop
  strategy keeps the default counter rather than the configured one.
- **Task 11**: stage 2 counts the full assembled context wrapper (KG placeholders plus the reference
  list) through the real `ContextAssembler`, not just the chunk lines
  (`QueryEngine.java:830-836`) — conservative, so it can only under-admit chunks; whole chunks only,
  never a mid-text prefix (`ChunkBudgetTruncator.java:22-45`).
- **Task 12**: `ChunkHeadings.resolve(ScoredChunk, TokenCounter)` is 2-arg
  (`ChunkHeadings.java:35`); `ContextAssembler.approxChunkProjection(ScoredChunk, Optional<String>)`
  (`ContextAssembler.java:55`) keeps stage 1 from counting a reference id it cannot know yet; a
  no-arg convenience constructor is kept for legacy call sites (`ContextAssembler.java:22`).
- **Task 13**: `queryStructured` has no `System.nanoTime()` capture — `StructuredQueryResult` has no
  `responseTime` field, so the capture would be dead code; streaming results report `responseTime`
  `0.0d` (the duration is unknown at stream creation); the duration reuses the existing long-typed
  `elapsedMillis` (`QueryEngine.java:744`) with `/ 1000.0d`.
- **Task 14**: the cache key is `v2:`-prefixed with a per-model identity from
  `ChatModel.cacheIdentity()` (default: model class name; `OpenAiCompatibleChatModel` overrides it and
  folds its constructor defaults in via `ChatRequestOptions.cacheIdentitySuffix()`, as does
  `ConfiguredChatModel` `:33`); the identity is hashed into the key, which bounds `cache_id` well
  under the MySQL `VARCHAR(191)` primary key even for long base URLs or defaults suffixes; the history
  bypass is role-scoped to the answer role (`CachedChatModel.java:34-41`, upstream
  `operate.py:4683-4690`) while the extraction role folds the history into the key (upstream
  `utils.py:5645-5662`) so recorded cache ids stay reachable; wrapper identity propagation was fixed in
  follow-up commits `97f3139` + `d9f44f4`.
- **Plan file-list drift**: `QueryBudgetingTest` and `QueryReferencesTest` were listed as
  modify/create targets but no such files exist; budget coverage lives in `QueryEngineTest` /
  `ChunkBudgetTruncatorTest`, and reference numbering is pinned by
  `ContextAssemblerTest#referenceIdsMatchQueryReferencesOrdering`.
- **Task 15**: the RAGAS CLI does not pass `relatedChunkNumber` / `chunkPickMethod`, so the evaluation
  runs exercise the 5 / VECTOR defaults; with `--retrieval-only true` the extraction stub empties the
  KG channels, so the runs measure chunk retrieval only.
