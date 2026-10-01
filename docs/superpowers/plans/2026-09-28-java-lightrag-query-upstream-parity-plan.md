# Java LightRAG Query-Side Upstream Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the query-side implementation gaps between lightrag-java 0.24.0-SNAPSHOT and upstream Python LightRAG 1.5.8 as catalogued in `docs/superpowers/specs/2026-09-28-upstream-lightrag-alignment-report.md` §3, so that retrieval parameters, chunk selection, merging, token governance, context rendering and result metadata behave the same way as the Python implementation.

**Architecture:** All work lands in `lightrag-core` (plus demo config/doc updates). The strategy layer (`LocalQueryStrategy`, `GlobalQueryStrategy`, `HybridQueryStrategy`, `MixQueryStrategy`) keeps its current shape; the upstream algorithms are extracted into small, unit-testable helpers (`KgChunkSelector`, `ChunkMerges`, `ChunkBudgetTruncator`, `QueryValidation`, `TokenCounter`) that the strategies call. New knobs are appended to the end of the `QueryRequest` record together with an explicit legacy 23-argument constructor that keeps the one out-of-tree positional caller compiling (aiplatform — see Platform calibration), and server-level options ride the existing `LightRagBuilder → LightRag → QueryEngine` wiring chain.

**Tech Stack:** Java 17, Gradle (multi-module), JUnit 5, AssertJ, Jackson (already present), Spring Boot demo module. No new third-party dependencies.

**Upstream reference:** `D:\ai-code\LightRAG` @ `453dce83d` (declares `1.5.8`; latest tag `v1.5.7`). Line numbers below refer to that checkout.

**Decisions locked with the requester (2026-09-28):**

1. **Tokenizer** — introduce a pluggable `TokenCounter` contract with a CJK-aware heuristic default (CJK/Kana/Hangul code points count as 1 token, other text at ~4 characters per token). No new dependencies, no real BPE tokenizer in this phase.
2. **Defaults** — align to upstream: `top_k` 10 → 40, `chunk_top_k` 10 → 20. Test-baseline changes are accepted.

**Explicitly out of scope (tracked, not implemented here):** NDJSON streaming contract and progress event types (§3 streaming, needs a server-layer task); JSON-lines entity/relation context format (chunk citations are fixed here, entity/relation line format is left as-is); production `RerankModel` provider implementations (§7); `finish_reason`/`usage` capture and `TruncatedResponse` markers (§7, blocks "never cache truncated answers"); keyword-extraction cache keys and `response_format=json_object` (§3 keyword extraction); indexing-side tokenizer wiring (`KnowledgeExtractor` budget, §7) — the `TokenCounter` contract introduced in Task 10 is the hook for that follow-up.

**Accepted Java-only divergences (do not "fix" in this plan):** `rerankCandidateMultiplier` candidate window (Task 9 re-scopes it, keeps the knob), metadata filter suite, parent-chunk expansion, multi-hop strategy, per-request `modelFunc`, `OneShotRetrievalStore` fast paths (they bypass KG chunk selection; see Task 6 note), Chinese keyword heuristics.

---

## Platform calibration (aiplatform, verified 2026-10-01, revised after Codex review round 1)

Re-checked against the only production consumer: `D:\ai-code\aiplatform` (Maven backend, module `backend/aide-kno`). **The platform is mid-upgrade onto this SDK's main line** (uncommitted working tree): `lightrag.version` is bumped to `0.24.0-SNAPSHOT` (`backend/pom.xml:47`), **both forked SDK classes are deleted** (`io/github/lightrag/api/LightRag.java`, `io/github/lightrag/indexing/GraphMaterializationPipeline.java` — staged deletions in `git status`), and the main-line APIs are adopted (`CancellationCheckpoint` in `KnowledgeGraphServiceImpl:2083-2089`, `builder.maxConcurrentDocumentTasks(...)` wired from `LightRagProperties` in `LightRagRuntimeFactory:277-283`). Every task below lands on the platform atomically with that upgrade. Load-bearing facts (verified against the working tree, not the last commit):

1. **Query entry is context-only and config-driven.** `LightRagRetrievalEngine` resolves one runtime per KB (`LightRagRuntimeFactory:240-319`, LRU `LightRagRuntimeRegistry:33-54`) and calls `runtime.lightRag().query(workspaceId, request)` (`:70`, `:91`). `LightRagQueryMapper:28-44` builds the request with `QueryRequest.builder()`, sets `topK = chunkTopK = expandedCandidateLimit` (derived from platform candidate config, `:90-95`), `onlyNeedContext(true)`, `includeReferences(true)`, recall-term hl/ll keywords, metadata filters/conditions — and **never sets `userPrompt` or `conversationHistory`**. RetrievalMode KEYWORD→NAIVE, HYBRID→MIX, else configured default (MIX fallback, `:97-117`).
2. **Results are consumed as contexts only.** `LightRagResultMapper:27-86` reads only `QueryResult.contexts()` (`sourceId`, `text`) and copies `referenceId`/`source` into hit metadata (`:108-124`); platform main code never reads `answer`, `references`, `answerStream` or `streaming`, and never constructs a `QueryResult`. Its tests construct the 3-argument `QueryResult(answer, contexts, references)` and the 4-argument `QueryResult.Context(...)` convenience forms.
3. **One positional `QueryRequest` construction — a hard compile constraint.** `LightRagRetrievalEngine.disableSdkRerank:476-501` (untouched by the upgrade) rebuilds the request through the **23-argument canonical constructor**, called at `:240` whenever platform rerank is on (`config.enableRerank() && rerankService != null`). Appending record components changes that arity, so Tasks 4 and 5 must keep an explicit legacy 23-argument constructor, and Task 13 must keep the 3-argument `QueryResult` convenience constructor (both are already called out in the task notes) — otherwise the platform build breaks. With the fork gone this is a direct SDK-API constraint, not a fork artifact.
4. **The answer cache is real on the platform.** The storage provider exposes `llmCacheStore()`, and the SDK itself wraps query/keyword/extract/summary models in `CachedChatModel` (`lightrag-core: api/LightRag.java:836-837` extract+summary, `:916` deletion path, `:1063-1064` query+keyword, helper at `:1077-1078`), so Task 14 changes live keys, not dead code. `LlmCacheStore.drop()` stays available for reclaiming the stale key space.
5. **Models are hand-written adapters.** `LightRagPlatformChatModel` (reads `systemPrompt`/`userPrompt`/`conversationHistory`, own retries) does **not** override `cacheIdentity()`; `EmbeddingModel` is wired at `LightRagRuntimeFactory:243` (so VECTOR picking's query embedding works); `LightRagPlatformRerankModel` implements `RerankModel`, its tests build the 2-argument `RerankRequest`, and its `buildPayload:193-214` sends its own `top_n = candidates.size()`.
6. **Error surface.** The only platform-side query guard is `@NotBlank` on `RetrievalSearchRequest.query` (`RetrievalSearchRequest:13-15`); `querySingleKnowledgeBase:556-582` does not catch, and `GlobalExceptionHandler:41-70` maps plain `IllegalArgumentException` through its catch-all `Exception` handler → **HTTP 500**.
7. **Chunk metadata already carries the heading key this plan reads.** Platform chunker metadata includes `smart_chunker.section_path` (plus `sectionPath`/`section_path`, `headingPath` JSON) — exactly Task 12's fallback — and still has **no `file_path`** (same note as the build-side plan Task 5).

### Per-task verdicts

| Task | Verdict for aiplatform |
|---|---|
| 1 Defaults 40/20 | **No effect** — the platform sets `topK`/`chunkTopK` explicitly from candidate config; demo/docs only. |
| 2 Query validation | **Effective + companion change** — 1-2 character queries (one CJK char weighs 2) now throw `IllegalArgumentException` → platform 500 (fact 6). Mirror the guard platform-side with a friendly 400/empty result in the same upgrade. |
| 3 `fail_response` | **Inert on the retrieval path** — requests are `onlyNeedContext(true)` and the mapper ignores `answer`; empty contexts already map to empty hits. Benefits only answer-consuming consumers (demo / future chat paths). |
| 4 `user_prompt_prefix` | **Inert by default** — platform never sets `userPrompt`; prefix `""` + opt-out off. Useful later for KB-level prompt preambles. |
| 5/6 KG→chunk selection | **Effective** — candidate chunks change (quota, first-owner dedup, VECTOR over the Milvus `chunks` namespace; the WEIGHT fallback now fires on empty vector matches only, and partial coverage keeps the ranked subset — a documented Java-only divergence, see Task 6). Re-baseline retrieval eval. |
| 7 Round-robin merge | **Effective for MIX** (platform's main mode) — result order and `stageRank` change; platform fusion/rerank downstream absorb part of it. Re-baseline. |
| 8 `(degree, weight)` ranking | **Effective for LOCAL** — one batched `findRelations(endpointIds)` read per query against the Neo4j-backed `WorkspaceScopedNeo4jGraphStore` (see the corrected Task 8 Step 3); watch LOCAL latency. Display interacts with build-side Task 3 weight semantics. |
| 9 Rerank parity | **Effectively SDK-only** — the platform disables SDK rerank whenever its own rerank is active (`:240`). The 2-arg `RerankRequest` compatibility constructor is required by `LightRagPlatformRerankModelTest`; honoring `request.topN()` in `buildPayload` is an optional follow-up. |
| 10 TokenCounter + buffer | **Effective** — CJK-aware counts and the 200-token buffer change prompt budgets (better for Chinese KBs); the default counter is used. `UnicodeWidths` is shared with build-side Task 1. Token counts are a Java-only approximation of upstream's tokenizer (see Task 10). |
| 11 Render-verified truncation | **SDK-side only** — whole chunks are kept or dropped (upstream semantics; the earlier "trim the boundary chunk" draft was wrong and is corrected in Task 11); small context diffs, no platform change. |
| 12 Refs + headings + Reference List | **Effective and platform-visible** — `smart_chunker.section_path` yields heading breadcrumbs (fact 7) and `referenceId`/`source` already flow into hit metadata (fact 2). Prompt shape changes → re-baseline eval. |
| 13 `responseTime`/`llmGenerated` | **Additive** — platform consumes neither; its tests keep compiling as long as the 3-arg constructor survives. Optional adoption in explain responses later. |
| 14 Cache key `v2:...` | **Effective** — real `llmCacheStore()` (fact 4); one-time full invalidation. Identity slot stays `"unknown"` until the platform overrides `cacheIdentity()` in `LightRagPlatformChatModel` — do it so model switches invalidate. History bypass never triggers (no history on platform queries). |
| 15 Verification | **SDK-only** — the RAGAS settings recorded are SDK defaults; the platform is config-driven. |

### Platform rollout checklist (on the 0.24.0 upgrade)

The platform-side upgrade is already in flight in the aiplatform working tree (version bump, fork deletion, `CancellationCheckpoint`/`maxConcurrentDocumentTasks` adoption); this checklist runs in that same window.

- [ ] **Compile gate:** confirm the SDK ships the legacy 23-argument `QueryRequest` constructor before the plan's Tasks 4/5 land; run `mvn -pl backend/aide-kno -am compile` plus `LightRagRetrievalEngineTest` / `LightRagResultMapperTest` (they pin the record shapes).
- [ ] Add a platform-side query minimum-length guard mirroring Task 2 (or map `IllegalArgumentException` to 400 in `GlobalExceptionHandler`) — short queries currently 500. Cover it with a real call-chain test (`LightRagRetrievalEngineTest` or the controller test): a 1–2 character query must surface 400 (guard) or an empty result (mapped path), never a 500.
- [ ] Override `cacheIdentity()` in `LightRagPlatformChatModel` (provider + model + endpoint); expect the one-time `v2:` invalidation; optionally call `llmCacheStore().drop()` once to reclaim the old `default:*` key space.
- [ ] Re-baseline retrieval quality/eval for Tasks 5-8 and 10-12 (candidate sets, order and prompt shape all change).
- [ ] Watch LOCAL-query latency after Task 8 on Neo4j; batch `findRelations` if it regresses.
- [ ] Decide exposure of `relatedChunkNumber` / `chunkPickMethod` / `disableUserPromptPrefix` in `KnowledgeRetrievalConfigResolver`; defaults (5 / VECTOR / off) already match upstream.
- [ ] Optional: migrate `disableSdkRerank` to a builder-based copy so future record-arity changes stop being compile-breaking; until then the legacy constructor is public API for the platform.

---

## Behavior Changes (user-visible)

| Change | Before | After | Introduced in |
|---|---|---|---|
| Default `topK` / `chunkTopK` | 10 / 10 | 40 / 20 | Task 1 |
| Empty / <3-weight query | accepted | `IllegalArgumentException` (bypass: empty only) | Task 2 |
| Empty retrieval | LLM call over `(none)` context | canned `fail_response`, no LLM call, `llmGenerated=false` | Task 3, 13 |
| Retrieval non-empty but budget-truncated to nothing | indistinguishable from a normal query (LLM call) | stays a normal query: empty context preserved, never converted into `fail_response` (`operate.py:6015-6027`) | Task 3 |
| `user_prompt` | sent verbatim | server prefix prepended unless `disableUserPromptPrefix` | Task 4 |
| KG→chunk selection | union of all `sourceChunkIds`, score-sorted | `related_chunk_number` quota, WEIGHT/VECTOR picker, first-owner dedup | Task 5, 6 |
| hybrid/mix chunk merge | global score sort | round-robin source interleave | Task 7 |
| local relation order | parent-entity score | `(degree, weight)` desc | Task 8 |
| Rerank call | no `top_n`, leftovers appended | `top_n=chunkTopK`, provider order authoritative, configurable failure mode | Task 9 |
| Token counts | whitespace splitting | `TokenCounter` (CJK-aware default) | Task 10 |
| Chunk budget buffer | 16 | 200 (`buffer_tokens` parity) | Task 10 |
| Chunk budget counting | sum of stored per-chunk `tokenCount()` (rendered line overhead, reference ids and headings uncounted) | two-stage render-verified count over the exact context projection; whole chunks only, boundary chunk dropped | Task 11 |
| Context chunk lines | `- id \| score \| text` | `- [n] id \| score \| headings \| text` + `Reference Document List` | Task 12 |
| `QueryResult` | answer/contexts/references | + `responseTime`, `llmGenerated` | Task 13 |
| LLM cache key | `default:{role}:{hash}` | `v2:{role}:{identity}:{sha256(request+options)}`, history bypass | Task 14 |

---

## File Structure

### New files — core

- `lightrag-core/src/main/java/io/github/lightrag/text/UnicodeWidths.java` — upstream `query_validation.py` wide-codepoint table, shared by validation and the token counter.
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryValidation.java` — empty/too-short query guard.
- `lightrag-core/src/main/java/io/github/lightrag/api/KgChunkPickMethod.java` — `WEIGHT` / `VECTOR` enum.
- `lightrag-core/src/main/java/io/github/lightrag/query/KgChunkSelector.java` — occurrence dedup, WEIGHT polling, VECTOR quota, tracking.
- `lightrag-core/src/main/java/io/github/lightrag/query/ChunkVectorRanker.java` — ranker seam (tests inject fakes).
- `lightrag-core/src/main/java/io/github/lightrag/query/VectorStoreChunkVectorRanker.java` — production ranker over `VectorStore.search`.
- `lightrag-core/src/main/java/io/github/lightrag/query/ChunkMerges.java` — round-robin merge for chunks/entities/relations.
- `lightrag-core/src/main/java/io/github/lightrag/query/ChunkHeadings.java` — `content_headings` resolution from chunk metadata.
- `lightrag-core/src/main/java/io/github/lightrag/query/ChunkBudgetTruncator.java` — two-stage, render-verified chunk truncation.
- `lightrag-core/src/main/java/io/github/lightrag/model/TokenCounter.java` — tokenizer contract.
- `lightrag-core/src/main/java/io/github/lightrag/model/HeuristicTokenCounter.java` — CJK-aware default.
- `lightrag-core/src/main/java/io/github/lightrag/model/RerankFailureMode.java` — `FAIL_FAST` / `FALLBACK_TO_ORIGINAL`.

### Modified files — core

- `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` — defaults; `disableUserPromptPrefix`, `relatedChunkNumber`, `chunkPickMethod` appended.
- `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java` — `userPromptPrefix`, `failResponse`, `tokenCounter`, `rerankFailureMode`.
- `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java` — constructor fields + `newQueryEngine` wiring (`:945-977`).
- `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java` — `responseTime`, `llmGenerated`.
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java` — validation, fail short-circuit, prefix, truncation, result metadata.
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java` — instance-based, `TokenCounter`, buffer 200.
- `lightrag-core/src/main/java/io/github/lightrag/query/ContextAssembler.java` — reference ids, headings, reference list.
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryReferences.java` — extract shared numbering.
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java` — positional copy update.
- `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java`, `GlobalQueryStrategy.java` — KG chunk selection, relation ranking.
- `lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java`, `MixQueryStrategy.java` — round-robin merge.
- `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java` — `default String cacheIdentity()`.
- `lightrag-core/src/main/java/io/github/lightrag/model/CachedChatModel.java` — key policy.
- `lightrag-core/src/main/java/io/github/lightrag/model/RerankModel.java` — `topN` on `RerankRequest`.
- `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java` — identity.

### Modified files — demo / docs

- `lightrag-spring-boot-demo/src/main/resources/application.yml:27-28` — default top-k 40 / chunk-top-k 20.
- `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/QueryController.java`, `QueryStreamService.java`, `QueryRequestMapper.java` — new result fields if exposed.
- `README.md` — defaults and new builder options.

### Tests (create unless listed as modify)

- `lightrag-core/src/test/java/io/github/lightrag/query/QueryValidationTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/KgChunkSelectorTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/ChunkMergesTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/ChunkBudgetTruncatorTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/query/ChunkHeadingsTest.java`
- `lightrag-core/src/test/java/io/github/lightrag/model/HeuristicTokenCounterTest.java`
- Modify `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`, `QueryBudgetingTest.java`, `ContextAssemblerTest.java`, `LocalQueryStrategyTest.java`, `GlobalQueryStrategyTest.java`, `HybridQueryStrategyTest.java`, `MixQueryStrategyTest.java`, `QueryReferencesTest.java`
- Modify `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java` (`:1019-1020`), `E2ELightRagTest.java`
- Modify `lightrag-core/src/test/java/io/github/lightrag/model/CachedChatModelTest.java`
- Modify `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/QueryControllerTest.java` (if the response shape changes)

---

## Phase 1 — Contract and cheap wins

### Task 1: Align query defaults to upstream 40/20

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java:46-47`
- Modify: `lightrag-spring-boot-demo/src/main/resources/application.yml:27-28`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java`

- [ ] **Step 1: Pin the new defaults with a literal-value test (write it first)**

Append to `LightRagBuilderTest`:

```java
@Test
void queryRequestDefaultsMatchUpstreamTopKAndChunkTopK() {
    assertThat(QueryRequest.DEFAULT_TOP_K).isEqualTo(40);
    assertThat(QueryRequest.DEFAULT_CHUNK_TOP_K).isEqualTo(20);
    var request = QueryRequest.builder().query("international trade tariffs").build();
    assertThat(request.topK()).isEqualTo(40);
    assertThat(request.chunkTopK()).isEqualTo(20);
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.LightRagBuilderTest"`
Expected: FAIL — expected 40 but was 10.

- [ ] **Step 3: Change the constants and the demo config**

```java
public static final int DEFAULT_TOP_K = 40;        // upstream constants.py:57
public static final int DEFAULT_CHUNK_TOP_K = 20;  // upstream constants.py:58
```

```yaml
    default-top-k: ${LIGHTRAG_QUERY_DEFAULT_TOP_K:40}
    default-chunk-top-k: ${LIGHTRAG_QUERY_DEFAULT_CHUNK_TOP_K:20}
```

- [ ] **Step 4: Sweep for hard-coded assumptions**

Run: `grep -rn "topK())\.isEqualTo(10\|chunkTopK())\.isEqualTo(10\|topK(10)\|chunkTopK(10)" lightrag-core/src/test lightrag-spring-boot-demo/src/test`
Expected: only `LightRagBuilderTest:1019-1020` (which compares against the constants and therefore stays green). Update any literal assertions found.

Also check `README.md` for documented defaults: `grep -n "top_k\|topK" README.md` and update prose that states 10/10.

- [ ] **Step 5: Re-run the focused and module tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.api.*" --tests "io.github.lightrag.query.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java \
        lightrag-spring-boot-demo/src/main/resources/application.yml \
        lightrag-core/src/test/java/io/github/lightrag/api/LightRagBuilderTest.java
git commit -m "feat: align query defaults to upstream top_k 40 / chunk_top_k 20"
```

---

### Task 2: Query validation (empty + CJK-weighted minimum length)

Upstream: `lightrag/query_validation.py` — `MIN_RAG_QUERY_WEIGHT=3`, East Asian characters weigh 2, empty rejected on every path including bypass (whose caller skips only the *minimum*: `lightrag.py:5012-5015,5135-5138`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/text/UnicodeWidths.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/QueryValidation.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/query/QueryValidationTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java:278-335`

- [ ] **Step 1: Write the failing validation tests**

```java
class QueryValidationTest {
    @Test
    void rejectsEmptyAndWhitespaceOnlyQueries() {
        assertThatThrownBy(() -> QueryValidation.validateRagQuery("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void weigthsEastAsianCharactersTwice() {
        assertThat(QueryValidation.meetsMinWeight("住房公积金")).isTrue();   // 5 x 2 = 10
        assertThat(QueryValidation.meetsMinWeight("你好")).isTrue();       // 2 x 2 = 4 >= 3
        assertThat(QueryValidation.meetsMinWeight("好")).isFalse();        // 2 < 3
        assertThat(QueryValidation.meetsMinWeight("日本語")).isTrue();     // Kana/Kanji block
        assertThat(QueryValidation.meetsMinWeight("한국")).isTrue();       // Hangul block
        assertThat(QueryValidation.meetsMinWeight("ab")).isFalse();
        assertThat(QueryValidation.meetsMinWeight("abc")).isTrue();
        assertThat(QueryValidation.meetsMinWeight("  tariffs  ")).isTrue();
    }

    @Test
    void bypassPathOnlyRejectsEmpty() {
        QueryValidation.validateNotEmpty("好");
        assertThatThrownBy(() -> QueryValidation.validateNotEmpty(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryValidationTest"`
Expected: FAIL — `QueryValidation` does not exist.

- [ ] **Step 3: Implement `UnicodeWidths` and `QueryValidation`**

`UnicodeWidths` ports the exact block list from `lightrag/query_validation.py:41-54` (whole blocks, never per-character, and never ending a block early):

```java
package io.github.lightrag.text;

/** East Asian wide code point blocks, ported from upstream query_validation.py. */
public final class UnicodeWidths {
    private static final int[][] WIDE_RANGES = {
        {0x1100, 0x11FF},   // Hangul Jamo
        {0x2E80, 0x33FF},   // CJK Radicals, Kangxi, Symbols, Kana, Bopomofo, Hangul
        {0x3400, 0x4DBF},   // CJK Unified Ideographs Extension A
        {0x4E00, 0x9FFF},   // CJK Unified Ideographs
        {0xA960, 0xA97F},   // Hangul Jamo Extended-A
        {0xAC00, 0xD7FF},   // Hangul Syllables, Hangul Jamo Extended-B
        {0xF900, 0xFAFF},   // CJK Compatibility Ideographs
        {0xFE30, 0xFE4F},   // CJK Compatibility Forms
        {0xFF00, 0xFFEE},   // Halfwidth and Fullwidth Forms
        {0x16FE0, 0x16FFF}, // Ideographic Symbols and Punctuation
        {0x1AFF0, 0x1B2FF}, // Kana Extended/Supplement, Small Kana, Nushu
        {0x20000, 0x3FFFD}, // Planes 2 and 3 - every CJK extension
    };

    private UnicodeWidths() {
    }

    public static boolean isWide(int codePoint) {
        for (var range : WIDE_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
```

```java
package io.github.lightrag.query;

import io.github.lightrag.text.UnicodeWidths;

public final class QueryValidation {
    public static final int MIN_RAG_QUERY_WEIGHT = 3;
    private static final String TOO_SHORT_MESSAGE =
        "RAG query is too short. Enter at least 3 English characters or an equivalent "
            + "combination where each Chinese, Japanese or Korean character counts as 2.";

    private QueryValidation() {
    }

    public static void validateNotEmpty(String query) {
        if (query == null || query.strip().isEmpty()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }
    }

    public static void validateRagQuery(String query) {
        validateNotEmpty(query);
        if (!meetsMinWeight(query.strip())) {
            throw new IllegalArgumentException(TOO_SHORT_MESSAGE);
        }
    }

    /** Short-circuits as soon as the threshold is reached (upstream: meets_min_rag_query_weight). */
    public static boolean meetsMinWeight(String query) {
        var weight = 0;
        for (var index = 0; index < query.length(); ) {
            var codePoint = query.codePointAt(index);
            index += Character.charCount(codePoint);
            weight += UnicodeWidths.isWide(codePoint) ? 2 : 1;
            if (weight >= MIN_RAG_QUERY_WEIGHT) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Wire into `QueryEngine.query` and `queryStructured`**

At the top of both methods:

```java
if (query.mode() == QueryMode.BYPASS) {
    QueryValidation.validateNotEmpty(query.query());
    return bypassQuery(query);
}
QueryValidation.validateRagQuery(query.query());
```

Documented divergence: the Java engine validates but does not strip `request.query()` before use (the record is immutable and the raw text also feeds cache keys); upstream passes the stripped value downstream. Trim-sensitive tests are therefore unnecessary.

**Platform note (aiplatform):** the platform's only pre-SDK guard is `@NotBlank` on `RetrievalSearchRequest.query`; a 1-2 character query (a single CJK character weighs 2) reaches the SDK and the new `IllegalArgumentException` surfaces through `GlobalExceptionHandler`'s catch-all as HTTP 500. Ship a platform-side mirror of `meetsMinWeight` (friendly 400 / empty result) in the same upgrade — see Platform calibration, fact 6 and the rollout checklist.

- [ ] **Step 5: Sweep short query literals in tests**

Run: `grep -rEn "query\(\"[^\"]{1,2}\"\)|\.query\(\"[^\"]{1,2}\"\)" lightrag-core/src/test lightrag-spring-boot-demo/src/test`
Expected: hits that shorten the query to fewer than 3 weight units now throw. Replace each with a realistic query (for example `"q1"` → `"query one"`), keeping test intent.

- [ ] **Step 6: Run the module tests**

Run: `./gradlew :lightrag-core:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/text/UnicodeWidths.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryValidation.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/test/java/io/github/lightrag/query/QueryValidationTest.java
git commit -m "feat: reject empty and too-short RAG queries with CJK-weighted minimum"
```

---

### Task 3: `fail_response` short-circuit on empty retrieval

Upstream returns a canned response without an LLM call when no context could be built, and the ordering is load-bearing: `kg_query`/`naive_query` bail out with `None` on empty context (`operate.py:4786-4788`, at the top of the function) **before** the `only_need_context` branch (`:4791`), the `only_need_prompt` branch (`:4819`) and any streaming setup, and the public wrapper turns that `None` into `fail_response` with `llm_generated = False` (`lightrag.py:5229-5242`). So an `only_need_context` request that retrieves nothing gets the canned fail text, never an empty preview — the Java short-circuit below must sit before all of `onlyNeedContext` / `onlyNeedPrompt` / `stream`, exactly where this plan puts it. Message: `"Sorry, I'm not able to provide an answer to that question.[no-context]"` (`lightrag/prompt.py:330-332`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java:106-128,945-977`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java:278-335`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void returnsFailResponseWithoutCallingTheModelWhenNothingIsRetrieved() {
    var model = new RecordingChatModel();          // existing test double in this test class
    var engine = new QueryEngine(                  // same injected-strategy style as the other tests here
        model,
        new ContextAssembler(),
        strategiesReturning(retrievalReturnsNothing()),
        null,
        false,                                     // no keyword extraction: keep the model call count exact
        2
    );

    var result = engine.query(QueryRequest.builder().query("unknown topic entirely").mode(QueryMode.LOCAL).build());

    assertThat(result.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    assertThat(result.contexts()).isEmpty();
    assertThat(result.references()).isEmpty();
    assertThat(model.callCount()).isZero();        // RecordingChatModel.callCount() counts answer calls only
    assertThat(model.streamCallCount()).isZero();
}

@Test
void failResponseIsStreamedAsASingleChunkWhenStreamingIsRequested() {
    var engine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(retrievalReturnsNothing()), null, false, 2);
    var result = engine.query(QueryRequest.builder().query("unknown topic entirely")
        .mode(QueryMode.LOCAL).stream(true).build());
    assertThat(result.streaming()).isTrue();
    assertThat(readAll(result.answerStream())).containsExactly(QueryEngine.DEFAULT_FAIL_RESPONSE);
}

@Test
void contextOnlyAndPromptOnlyRequestsStillGetTheFailResponse() {
    // upstream returns None on empty context before the only_need_* branches (operate.py:4786-4791),
    // so both preview switches receive the canned text rather than an empty preview
    for (var request : List.of(
        QueryRequest.builder().query("unknown topic entirely").mode(QueryMode.LOCAL).onlyNeedContext(true).build(),
        QueryRequest.builder().query("unknown topic entirely").mode(QueryMode.LOCAL).onlyNeedPrompt(true).build())) {
        var result = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
            strategiesReturning(retrievalReturnsNothing()), null, false, 2).query(request);
        assertThat(result.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    }
    var structured = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(retrievalReturnsNothing()), null, false, 2)
        .queryStructured(QueryRequest.builder().query("unknown topic entirely").mode(QueryMode.LOCAL).build());
    assertThat(structured.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
}

@Test
void failResponseMatrixCoversEveryKgMode() {
    // the retrievalEmpty rule is mode-dependent (operate.py:6116-6118 kg modes, :6901-6905 naive);
    // an empty strategy output must fail in every mode, and structured queries take the same path.
    // Every mode needs a registered strategy: executeStandardQuery throws
    // IllegalStateException("No query strategy configured for mode: ...") when the map lacks the
    // mode (QueryEngine.java:400-403), and the LOCAL-only strategiesReturning(...) helper
    // (QueryEngineTest.java:1412-1415) would abort NAIVE/GLOBAL/HYBRID/MIX before retrieval runs.
    // BYPASS is deliberately NOT a row here: it never retrieves and never consults the map
    // (QueryEngine.java:280-282), so its fail-path coverage stays the separate validation test
    // (bypassPathOnlyRejectsEmpty, Task 2).
    for (var mode : List.of(QueryMode.NAIVE, QueryMode.LOCAL, QueryMode.GLOBAL, QueryMode.HYBRID, QueryMode.MIX)) {
        var model = new RecordingChatModel();
        var result = new QueryEngine(model, new ContextAssembler(),
            strategiesReturningAllModes(retrievalReturnsNothing()), null, false, 2)
            .query(QueryRequest.builder().query("unknown topic entirely").mode(mode).build());
        assertThat(result.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
        assertThat(model.callCount()).isZero();               // the answer model is never called

        var structuredModel = new RecordingChatModel();
        var structured = new QueryEngine(structuredModel, new ContextAssembler(),
            strategiesReturningAllModes(retrievalReturnsNothing()), null, false, 2)
            .queryStructured(QueryRequest.builder().query("unknown topic entirely").mode(mode).build());
        assertThat(structured.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
        assertThat(structured.contexts()).isEmpty();
        assertThat(structured.references()).isEmpty();
        assertThat(structured.entities()).isEmpty();
        assertThat(structured.relations()).isEmpty();
        assertThat(structured.chunks()).isEmpty();
        assertThat(structuredModel.callCount()).isZero();
    }
}

@Test
void emptyRetrievalUnderTheMultiHopRouteFailsToo() {
    // multi-hop is a SEPARATE route, not a mode: executeStandardQuery picks multiHopStrategy before
    // the mode map (QueryEngine.java:399-400), so the five-mode matrix above cannot catch a
    // regression here. The classifier is a stub that always answers MULTI_HOP; the engine overload
    // is the 9-arg one taking (classifier, multiHopStrategy, pathAwareAnswerSynthesizer)
    // (QueryEngine.java:215-228). multiHopEnabled defaults to true (QueryRequest.java:225).
    var model = new RecordingChatModel();
    var result = new QueryEngine(model, new ContextAssembler(),   // the SAME model the zero-call assertion counts
        strategiesReturningAllModes(retrievalReturnsNothing()),
        null, false, 2,
        request -> QueryIntent.MULTI_HOP,
        new RecordingQueryStrategy(retrievalReturnsNothing()),
        new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer())   // FQN, as the existing tests write it (:1181)
        .query(QueryRequest.builder().query("multi hop question").mode(QueryMode.LOCAL).build());
    assertThat(result.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    assertThat(model.callCount()).isZero();
}

@Test
void budgetExhaustionKeepsTheEmptyContextInsteadOfTheFailResponse() {
    // upstream distinguishes "no context could be built" (search stage empty -> None, operate.py:6113-6118)
    // from "context truncated/rendered empty" (("", failure raw data), operate.py:6015-6027); the latter is
    // still what the only_need_context branch returns (operate.py:4791-4796) -- it is NOT a fail_response
    var engine = new QueryEngine(
        new RecordingChatModel(),
        new ContextAssembler(),
        strategiesReturning(baseContext()),        // three chunks retrieved, no entities/relations
        null,
        false,
        2
    );

    var result = engine.query(QueryRequest.builder()
        .query("which chunk?")
        .mode(QueryMode.LOCAL)
        // QueryRequest rejects maxTotalTokens <= 0 (QueryRequest.java:72-80); 1 is the smallest legal
        // value and the fixed prompt overhead drives the REMAINING chunk budget to 0 -> every chunk
        // is budget-dropped. "Budget zero" always means the residual chunk budget, never the request knob.
        .maxTotalTokens(1)
        .onlyNeedContext(true)
        .build());

    assertThat(result.answer()).isNotEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    assertThat(result.answer()).contains("Chunks:");   // context assembled, just empty ("(none)")
    assertThat(result.contexts()).isEmpty();
    assertThat(result.references()).isEmpty();
}

@Test
void budgetExhaustionKeepsTheEmptyContextInNaiveAndStructuredToo() {
    // NAIVE's retrievalEmpty looks at the chunk result only (operate.py:6901-6905) and structured
    // queries run the same executeStandardQuery budgeting path, so neither may convert a
    // budget-emptied context into a fail_response. The NAIVE engine needs the all-modes strategy
    // map too — strategiesReturning(...) registers LOCAL only and the engine throws before retrieval.
    var engine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturningAllModes(baseContext()), null, false, 2);

    var naive = engine.query(QueryRequest.builder().query("which chunk?").mode(QueryMode.NAIVE)
        .maxTotalTokens(1).onlyNeedContext(true).build());
    assertThat(naive.answer()).isNotEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE).contains("Chunks:");
    assertThat(naive.contexts()).isEmpty();

    var structured = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturningAllModes(baseContext()), null, false, 2)
        .queryStructured(QueryRequest.builder().query("which chunk?").mode(QueryMode.LOCAL)
            .maxTotalTokens(1).build());
    assertThat(structured.answer()).isNotEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    assertThat(structured.contexts()).isEmpty();
}

@Test
void customFailResponseIsUsedWhenConfigured() {
    // builderWithFailResponse("No data found.") - assert answer equals that string
}
```

Add the fixture next to `baseContext()`/`referenceContext()`:

```java
private static QueryContext retrievalReturnsNothing() {
    return new QueryContext(List.of(), List.of(), List.of(), "");
}

/** Registers the same recording strategy for every mode the engine can route to (BYPASS short-circuits
 *  before the map, QueryEngine.java:280-282); the matrix test cannot use the LOCAL-only
 *  strategiesReturning(...) helper. */
private static EnumMap<QueryMode, QueryStrategy> strategiesReturningAllModes(QueryContext context) {
    var strategy = new RecordingQueryStrategy(context);
    var strategies = new EnumMap<QueryMode, QueryStrategy>(QueryMode.class);
    for (var mode : List.of(QueryMode.NAIVE, QueryMode.LOCAL, QueryMode.GLOBAL, QueryMode.HYBRID, QueryMode.MIX)) {
        strategies.put(mode, strategy);
    }
    return strategies;
}
```

Notes: `RecordingChatModel.callCount()` already exists and counts answer-path calls only — keyword-extraction calls go to `keywordExtractionCallCount()`, and the harness above disables extraction entirely so the counter is exact. Only the *answer* model must stay silent (upstream returns `None` before any LLM answer call).

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`
Expected: FAIL — the engine currently calls the answer model with a `(none)` context.

- [ ] **Step 3: Add the builder option**

`LightRagBuilder`: field `private String failResponse = QueryEngine.DEFAULT_FAIL_RESPONSE;` plus

```java
public LightRagBuilder failResponse(String failResponse) {
    this.failResponse = Objects.requireNonNull(failResponse, "failResponse");
    return this;
}
```

Append the parameter to the internal `LightRag(...)` constructor, store it, and pass it into `newQueryEngine`. Update both convenience constructors (`LightRag.java:72-104`) with the default.

- [ ] **Step 4: Implement the short-circuit in `QueryEngine`**

```java
static final String DEFAULT_FAIL_RESPONSE = "Sorry, I'm not able to provide an answer to that question.[no-context]";
private final String failResponse;
```

In `query(QueryRequest)`, after `executeStandardQuery` and before the `onlyNeedContext` / `onlyNeedPrompt` / `stream` branches (`failResponseResult` picks the streaming shape from `request.stream()`; `queryStructured`'s equivalent is specified after the divergence note below):

```java
if (execution.retrievalEmpty()) {
    return failResponseResult(execution.resolvedQuery(), execution.references());
}
```

**The flag must come from the retrieval stage, never from the final (post-budget) lists.** Upstream returns `None` from `_build_query_context` only when the *search stage* found nothing — kg modes: no `final_entities` and no `final_relations` (`operate.py:6113-6115`; mix survives with tracked chunks only, so it additionally requires an empty `chunk_tracking`, `:6116-6118`); naive has its own rule: an empty vector-chunk result (`operate.py:6901-6905`). Emptiness that only appears **after** token truncation is **not** a failure: when the stage-4 render truncation drops the last records, `_build_context_str` returns `("", failure raw_data)` (`operate.py:6015-6027`) — a non-`None` result — so `only_need_context` returns that **empty context** (`:4791-4796`) and the answer path renders a prompt with an empty context and still calls the LLM. Checking the final `QueryContext` lists would misclassify that case as a fail response.

Compute the flag inside `executeStandardQuery` immediately after `strategy.retrieve(retrievalRequest)` (i.e. from the pre-budget strategy output) and carry it on the existing private record:

```java
var retrievalEmpty = switch (resolvedQuery.mode()) {
    // operate.py:6901-6905 — naive has no KG branch; the vector-chunk result alone decides
    case NAIVE -> retrievedContext.matchedChunks().isEmpty();
    // operate.py:6113-6118 — kg modes: entities+relations empty decides; mix additionally needs an
    // empty chunk result. Java keeps the chunk term for every kg mode: its strategies can return
    // chunk-only contexts (OneShotRetrievalStore carries chunks as an independent list) and a canned
    // no-answer while usable chunks exist would be a regression — see the divergence note below.
    default -> retrievedContext.matchedEntities().isEmpty()
        && retrievedContext.matchedRelations().isEmpty()
        && retrievedContext.matchedChunks().isEmpty();
};
```

```java
private record QueryExecution(
    ChatModel responseModel,
    QueryRequest resolvedQuery,
    QueryContext queryContext,
    QueryReferences.Result references,
    ChatModel.ChatRequest chatRequest,
    boolean retrievalEmpty
) {
}
```

`QueryExecution` is private to `QueryEngine`, so the extra component has no platform impact (unlike the `QueryRequest`/`QueryResult` arity guards elsewhere in this plan). `retrievedContext` must be read **before** rerank/filter/budget: emptiness caused by budget limits is not a failure state (the test above locks this in).

```java
private QueryResult failResponseResult(QueryRequest request, QueryReferences.Result references) {
    if (request.stream()) {
        return QueryResult.streaming(
            CloseableIterator.of(List.of(failResponse)),
            references.contexts(),
            references.references()
            // Task 13: add the llmGenerated argument here and pass false — the canned response is not
            // LLM-generated (upstream lightrag.py:5235-5241). Task 13 Step 3 adds that overload.
        );
    }
    return new QueryResult(failResponse, references.contexts(), references.references());
}
```

**Documented divergence (narrower failure set than upstream).** For LOCAL/GLOBAL/HYBRID, upstream fails on empty entities+relations even when chunk results exist; Java requires the retrieved chunks to be empty as well, because Java's retrieval contracts surface chunks independently of KG records (`OneShotRetrievalStore.LocalRetrievalResult/GlobalRetrievalResult` carry `chunks` as a separate list, consumed by `LocalQueryStrategy:78-86`) and the test fixtures are chunk-only contexts. The failure set is therefore narrower — never wider — than upstream's. NAIVE's condition is exact (the vector-chunk result alone, `operate.py:6901-6905`); MIX additionally requires an empty chunk term, using `matchedChunks` as Java's analogue of upstream's tracked-chunk set — no separate `chunk_tracking` exists on `QueryContext`, so the approximation is documented rather than invented. Upstream's second `None` site (`operate.py:6141-6146`, *after* stage-2 entity/relation truncation) is deliberately not mirrored either: Java's budget runs in a single pass after retrieval, so "retained something at retrieval time" is the closest faithful analog of upstream's search-stage `search_result`, and treating budget-emptied results as an empty context (rather than `fail_response`) keeps `only_need_context` previews honest and matches the stage-4 outcome above.

`queryStructured` needs its **own** fail helper — it returns `StructuredQueryResult` (`QueryEngine.java:312-334`) and rejects `stream=true` at `:315`, so `failResponseResult` above (which returns `QueryResult` and owns the streaming branch) cannot be reused or widened without a type conflict:

```java
private StructuredQueryResult failStructuredResult(QueryReferences.Result references) {
    // Task 13: add the llmGenerated argument here and pass false — the canned response is not
    // LLM-generated (upstream lightrag.py:5235-5241).
    return new StructuredQueryResult(
        failResponse,
        references.contexts(),
        references.references(),
        List.of(),                                   // entities
        List.of(),                                   // relations
        List.of()                                    // chunks
    );
}
```

called from the same place, right after `executeStandardQuery` and before the answer resolution:

```java
public StructuredQueryResult queryStructured(QueryRequest request) {
    // ... existing BYPASS branch (:317-319) ...
    var execution = executeStandardQuery(query);
    if (execution.retrievalEmpty()) {
        return failStructuredResult(execution.references());
    }
    return new StructuredQueryResult(/* unchanged arguments (:321-334) */);
}
```

The structured helper deliberately takes no `QueryRequest`: unlike `failResponseResult` it has no streaming branch to consult, and `contexts()`/`references()` are carried through so the fail response keeps the same retrieval metadata as the streaming variant. `llmGenerated` arrives in Task 13 (it must be `false` here); leave the `// Task 13: llmGenerated=false` breadcrumb only if the field does not exist yet.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS. If existing E2E tests assert an LLM answer for queries that retrieve nothing, adjust them to index at least one document first — do not weaken the new assertion.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: short-circuit empty retrieval with upstream fail_response"
```

---

### Task 4: `user_prompt_prefix` with per-request opt-out

Upstream `resolve_user_prompt` (`lightrag/utils.py:398-426`): `text = (prefix unless disabled) + user_prompt` with no normalization; the system-prompt slot is `"\n\n" + text`, or `"n/a"` when both sides are empty; an empty `user_prompt` does **not** disable the prefix.

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` (append component)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java:559-566,618-620`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java:352-378` (positional copy)
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void prependsConfiguredUserPromptPrefixToTheSystemPromptSlot() {
    var engine = engineWithPrefix("Always answer as a tabular summary.");
    var result = engine.queryStructured(QueryRequest.builder().query("tariff schedule").build());
    assertThat(result.answer()).contains("\n\nAlways answer as a tabular summary.");
}

@Test
void prefixAloneIsUsedWhenTheRequestHasNoUserPrompt() {
    var engine = engineWithPrefix("Be terse.");
    var result = engine.queryStructured(QueryRequest.builder().query("tariff schedule").build());
    assertThat(result.answer()).contains("Be terse.");
}

@Test
void disableUserPromptPrefixSuppressesThePrefix() {
    var request = QueryRequest.builder().query("tariff schedule").disableUserPromptPrefix(true).build();
    var result = engineWithPrefix("Be terse.").queryStructured(request);
    assertThat(result.answer()).doesNotContain("Be terse.");
}

@Test
void noPrefixAndNoUserPromptRenderNa() {
    var result = engineWithPrefix("").queryStructured(QueryRequest.builder().query("tariff schedule").build());
    assertThat(result.answer()).contains("n/a");
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`
Expected: FAIL — `disableUserPromptPrefix` does not exist / prefix not applied.

- [ ] **Step 3: Extend `QueryRequest`**

Append at the **end** of the component list (after `metadataConditions`) to avoid breaking positional construction:

```java
boolean disableUserPromptPrefix
```

Update the compact constructor (no validation needed), the 23-argument constructor chain, the `Builder` (field + `disableUserPromptPrefix(boolean)` + `build()`), and both positional copy sites: `QueryEngine.expandChunkRequest` (`:354-381`) and `QueryKeywordExtractor.copyWithKeywords` (`:352-378`).

**Platform constraint:** appending the component changes the record's canonical constructor arity — the previous 23-argument signature must survive as an explicit legacy constructor delegating with `disableUserPromptPrefix=false`, because aiplatform calls it positionally (`LightRagRetrievalEngine.disableSdkRerank:476-501`, invoked at `:240` whenever platform rerank is on). Keep that constructor as the platform-compat entry point; Task 5 extends its default set, not its arity.

- [ ] **Step 4: Add the server-level option and apply it**

`LightRagBuilder`: field `private String userPromptPrefix = "";` + `userPromptPrefix(String)` setter (reject `null`; empty means "no prefix"); thread through `LightRag` into `QueryEngine`.

```java
private record EffectiveUserPrompt(String text, String slot) {
}

private EffectiveUserPrompt effectiveUserPrompt(QueryRequest query) {
    var prefix = query.disableUserPromptPrefix() ? "" : userPromptPrefix;
    var text = prefix + query.userPrompt();
    return new EffectiveUserPrompt(text, text.isEmpty() ? "n/a" : "\n\n" + text);
}
```

Use `slot()` where `effectiveUserPrompt(query.userPrompt())` was used in `buildSystemPrompt` (`:559-566`); delete the old static helper. `remainingChunkBudget` renders through `buildSystemPrompt`, so it picks the prefix up automatically.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java \
        lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: support server user_prompt_prefix with per-request opt-out"
```

---

## Phase 2 — KG→chunk selection

### Task 5: `related_chunk_number` / `kg_chunk_pick_method` contract + selector core

Upstream `_find_related_text_unit_from_entities` (`lightrag/operate.py:6380-6535`): occurrence counting, first-owner dedup, drop-emptied groups, per-group occurrence-desc ordering, then VECTOR (quota) or WEIGHT (`pick_by_weighted_polling(..., min_related_chunks=1)`) selection; `_vector_chunk_quota` = `max(1, max * group_count / 2)` with `max <= 0` a genuine kill switch (`operate.py:6316-6347`); `pick_by_weighted_polling` at `utils.py:6642-6721`.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/KgChunkPickMethod.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/KgChunkSelector.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/query/KgChunkSelectorTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java` (append components)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`, `QueryKeywordExtractor.java` (positional copies)

- [ ] **Step 1: Write the failing selector tests**

```java
class KgChunkSelectorTest {
    @Test
    void dedupeKeepsChunksForTheEarlierPositionedGroupAndDropsEmptiedGroups() {
        var groups = List.of(
            group("e1", "a", "b"),
            group("e2", "b", "c"),
            group("e3", "c")
        );
        var tracked = KgChunkSelector.dedupeByFirstOwner(groups);
        assertThat(tracked.groups()).extracting(g -> g.groupId()).containsExactly("e1", "e2");
        assertThat(tracked.groups().get(0).chunkIds()).containsExactly("a", "b");
        assertThat(tracked.groups().get(1).chunkIds()).containsExactly("c");
        assertThat(tracked.frequency()).containsEntry("b", 2).containsEntry("c", 2);
    }

    @Test
    void ordersEachGroupsChunksByOccurrenceCountDescending() {
        var tracked = KgChunkSelector.dedupeByFirstOwner(List.of(
            group("e1", "a", "b"), group("e2", "b")
        ));
        assertThat(tracked.groups().get(0).chunkIds()).containsExactly("b", "a");
    }

    @Test
    void weightedPollingAllocatesALinearGradientAcrossGroups() {
        var groups = List.of(group("e1", "a1", "a2", "a3"), group("e2", "b1", "b2"), group("e3", "c1"));
        assertThat(KgChunkSelector.pickByWeightedPolling(groups, 3, 1))
            .containsExactly("a1", "a2", "a3", "b1", "b2", "c1");
    }

    @Test
    void weightedPollingSpreadsLeftoverQuotaInAdditionalRounds() {
        var groups = List.of(group("e1", "a1"), group("e2", "b1", "b2", "b3", "b4"));
        assertThat(KgChunkSelector.pickByWeightedPolling(groups, 4, 1))
            .containsExactly("a1", "b1", "b2", "b3", "b4");
    }

    @Test
    void weightedPollingReturnsSingleGroupPrefixAndHonoursKillSwitch() {
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(group("e1", "a", "b", "c")), 2, 1))
            .containsExactly("a", "b");
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(group("e1", "a")), 0, 1)).isEmpty();
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(), 5, 1)).isEmpty();
    }

    @Test
    void vectorQuotaMatchesUpstreamFlooredFormula() {
        assertThat(KgChunkSelector.vectorQuota(5, 4)).isEqualTo(10);   // 5 * 4 / 2
        assertThat(KgChunkSelector.vectorQuota(5, 1)).isEqualTo(2);    // 5 * 1 / 2
        assertThat(KgChunkSelector.vectorQuota(1, 1)).isEqualTo(1);    // floor of 1
        assertThat(KgChunkSelector.vectorQuota(0, 5)).isZero();        // kill switch
        assertThat(KgChunkSelector.vectorQuota(5, 0)).isZero();
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.KgChunkSelectorTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement the enum, the request contract and the selector**

`KgChunkPickMethod` in `io.github.lightrag.api`:

```java
public enum KgChunkPickMethod {
    WEIGHT,
    VECTOR
}
```

`QueryRequest`: append `int relatedChunkNumber` and `KgChunkPickMethod chunkPickMethod` after `disableUserPromptPrefix`; constants `DEFAULT_RELATED_CHUNK_NUMBER = 5`, `DEFAULT_KG_CHUNK_PICK_METHOD = KgChunkPickMethod.VECTOR`; validate `relatedChunkNumber >= 0` (`"relatedChunkNumber must not be negative"`) and non-null method. Update `Builder`, both positional copies (see Task 4 Step 3) and the convenience constructors, and keep the explicit 23-argument platform-compat constructor from Task 4 Step 3 — its delegation supplies the `relatedChunkNumber`/`chunkPickMethod` defaults so aiplatform keeps compiling.

`KgChunkSelector` (package-private, `io.github.lightrag.query`):

```java
final class KgChunkSelector {
    record Group(String groupId, List<String> chunkIds, double score) {
        Group {
            groupId = Objects.requireNonNull(groupId, "groupId");
            chunkIds = List.copyOf(Objects.requireNonNull(chunkIds, "chunkIds"));
        }
    }

    record Tracked(List<Group> groups, Map<String, Integer> frequency) {
    }

    private KgChunkSelector() {
    }

    static Tracked dedupeByFirstOwner(List<Group> groups) {
        var occurrence = new LinkedHashMap<String, Integer>();
        var deduped = new ArrayList<Group>(groups.size());
        for (var group : groups) {
            var kept = new ArrayList<String>();
            for (var chunkId : group.chunkIds()) {
                if (occurrence.merge(chunkId, 1, Integer::sum) == 1) {
                    kept.add(chunkId);
                }
            }
            if (!kept.isEmpty()) {
                deduped.add(new Group(group.groupId(), kept, group.score()));
            }
        }
        var ordered = deduped.stream()
            .map(group -> new Group(group.groupId(), group.chunkIds().stream()
                .sorted(Comparator.comparingInt((String id) -> occurrence.getOrDefault(id, 0))
                    .reversed())
                .toList(), group.score()))
            .toList();
        return new Tracked(ordered, Map.copyOf(occurrence));
    }

    static int vectorQuota(int maxRelatedChunks, int groupCount) {
        if (maxRelatedChunks <= 0 || groupCount <= 0) {
            return 0;
        }
        return Math.max(1, maxRelatedChunks * groupCount / 2);
    }

    static List<String> pickByWeightedPolling(List<Group> groups, int maxRelatedChunks, int minRelatedChunks) {
        if (groups.isEmpty() || maxRelatedChunks <= 0) {
            return List.of();
        }
        if (groups.size() == 1) {
            return groups.get(0).chunkIds().stream().limit(maxRelatedChunks).toList();
        }
        var expected = new int[groups.size()];
        for (var index = 0; index < groups.size(); index++) {
            var ratio = (double) index / (groups.size() - 1);
            expected[index] = (int) Math.round(maxRelatedChunks - ratio * (maxRelatedChunks - minRelatedChunks));
        }
        var selected = new ArrayList<String>();
        var used = new int[groups.size()];
        var remaining = 0;
        for (var index = 0; index < groups.size(); index++) {
            var available = groups.get(index).chunkIds();
            var actual = Math.min(expected[index], available.size());
            selected.addAll(available.subList(0, actual));
            used[index] = actual;
            remaining += Math.max(0, expected[index] - actual);
        }
        for (var round = 0; round < remaining; round++) {
            var allocated = false;
            for (var index = 0; index < groups.size(); index++) {
                var available = groups.get(index).chunkIds();
                if (used[index] < available.size()) {
                    selected.add(available.get(used[index]));
                    used[index]++;
                    allocated = true;
                    break;
                }
            }
            if (!allocated) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
```

- [ ] **Step 4: Run the selector tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.KgChunkSelectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/KgChunkPickMethod.java \
        lightrag-core/src/main/java/io/github/lightrag/api/QueryRequest.java \
        lightrag-core/src/main/java/io/github/lightrag/query/KgChunkSelector.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryKeywordExtractor.java \
        lightrag-core/src/test/java/io/github/lightrag/query/KgChunkSelectorTest.java
git commit -m "feat: add related_chunk_number contract and weighted chunk selector"
```

---

### Task 6: VECTOR picking with fallback, wired into local/global retrieval

Upstream falls back to WEIGHT when vector selection returns empty, when no embedding function exists, or when the vector call throws (`operate.py:6445-6497`). Selected ids are loaded once, first-wins de-duplicated and logged through `chunk_tracking` (`source`/`frequency`/`order`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/ChunkVectorRanker.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/VectorStoreChunkVectorRanker.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/KgChunkSelector.java` (selection orchestration)
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java:42-201`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java:40-176`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/KgChunkSelectorTest.java`, `LocalQueryStrategyTest.java`, `GlobalQueryStrategyTest.java`

- [ ] **Step 1: Write the failing tests**

In `KgChunkSelectorTest`:

```java
@Test
void vectorSelectionFallsBackToWeightedPollingWhenTheRankerReturnsNothing() {
    var groups = List.of(group("e1", "a", "b"), group("e2", "c"));
    var selection = KgChunkSelector.select(KgChunkPickMethod.VECTOR, 5, groups,
        () -> List.of(), (queryVector, candidates, topK) -> List.of());
    assertThat(selection.chunkIds()).containsExactly("a", "b", "c");
    assertThat(selection.method()).isEqualTo(KgChunkPickMethod.WEIGHT);
}

@Test
void vectorSelectionFallsBackWhenTheRankerThrows() {
    var groups = List.of(group("e1", "a", "b"));
    var selection = KgChunkSelector.select(KgChunkPickMethod.VECTOR, 5, groups,
        () -> List.of(0.1d), (queryVector, candidates, topK) -> {
            throw new IllegalStateException("vdb down");
        });
    assertThat(selection.chunkIds()).containsExactly("a", "b");
    assertThat(selection.method()).isEqualTo(KgChunkPickMethod.WEIGHT);
}

@Test
void vectorSelectionHonoursQuotaAndKillSwitch() {
    var groups = List.of(group("e1", "a", "b", "c"), group("e2", "d"));
    var selection = KgChunkSelector.select(KgChunkPickMethod.VECTOR, 5, groups,
        () -> List.of(0.1d), (queryVector, candidates, topK) -> List.of("c", "d"));
    assertThat(selection.chunkIds()).containsExactly("c", "d"); // quota = max(1, 5*2/2) = 5, ranker decides
    assertThat(selection.method()).isEqualTo(KgChunkPickMethod.VECTOR);
    assertThat(KgChunkSelector.select(KgChunkPickMethod.VECTOR, 0, groups,
        () -> List.of(0.1d), (queryVector, candidates, topK) -> List.of("c")).chunkIds()).isEmpty();
}

// VectorStoreChunkVectorRankerTest
@Test
void returnsOnlyRankedMatchesSoEmptyStoresTriggerTheWeightFallback() {
    // vector store returns no match for any candidate -> rank(...) == empty
    // (the selector's !ranked.isEmpty() check then falls back to WEIGHT, upstream operate.py:6473-6478)
}

@Test
void partialCoverageKeepsMatchedIdsWithoutAppendingUnrankedCandidates() {
    // candidates [a, b, c], store matches only b -> rank(...) == [b], never [b, a, c]
}
```

In `LocalQueryStrategyTest` (same package, existing harness style):

```java
@Test
void selectsKgChunksByQuotaInsteadOfUnioningEverySourceChunk() {
    // entity e1 -> chunks c1, c2 ; entity e2 -> chunks c2, c3 ; relatedChunkNumber = 1
    // expected chunks: c1 (e1) then c2? -> with quota 1 and polling min 1: e1 keeps c1, e2 keeps c3 after dedup
    var context = strategy.retrieve(QueryRequest.builder()
        .query("hydrogen storage")
        .mode(QueryMode.LOCAL)
        .relatedChunkNumber(1)
        .chunkPickMethod(KgChunkPickMethod.WEIGHT)
        .build());
    assertThat(context.matchedChunks()).extracting(ScoredChunk::chunkId).containsExactly("c1", "c3");
}

@Test
void zeroRelatedChunkNumberDisablesKgChunksEntirely() {
    var context = strategy.retrieve(...relatedChunkNumber(0)...);
    assertThat(context.matchedChunks()).isEmpty();
}
```

In `GlobalQueryStrategyTest`: the same two cases over relation groups.

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.KgChunkSelectorTest" --tests "io.github.lightrag.query.LocalQueryStrategyTest"`
Expected: FAIL — `select` missing; local strategy still unions source chunks.

- [ ] **Step 3: Add the ranker seam and the selection entry point**

```java
@FunctionalInterface
interface ChunkVectorRanker {
    List<String> rank(List<Double> queryVector, List<String> candidateIds, int topK);
}
```

```java
final class VectorStoreChunkVectorRanker implements ChunkVectorRanker {
    private static final String CHUNK_NAMESPACE = "chunks";
    private final VectorStore vectorStore;

    VectorStoreChunkVectorRanker(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    @Override
    public List<String> rank(List<Double> queryVector, List<String> candidateIds, int topK) {
        if (candidateIds.isEmpty() || topK <= 0) {
            return List.of();
        }
        var candidates = new LinkedHashSet<>(candidateIds);
        var selected = new ArrayList<String>(Math.min(topK, candidateIds.size()));
        var matches = vectorStore.search(CHUNK_NAMESPACE, queryVector, Math.max(topK, candidateIds.size()));
        for (var match : matches) {
            if (selected.size() >= topK) {
                break;
            }
            if (candidates.contains(match.id())) {
                selected.add(match.id());
            }
        }
        // Ranked matches only. An empty result must reach KgChunkSelector so it can fall back to
        // WEIGHT (upstream operate.py:6473-6478); unranked candidates must never be appended —
        // upstream's pick_by_vector_similarity only returns ids with a computed similarity
        // (utils.py:6845-6851).
        return List.copyOf(selected);
    }
}
```

**Divergence note (keep, document in the class javadoc):** upstream aborts vector ranking whenever any candidate lacks a stored vector — `len(chunk_vectors) != len(all_chunk_ids)` → `return []` → WEIGHT (`utils.py:6803-6820`). Java's `VectorStore.search(namespace, vector, limit)` returns the store's top-N and cannot distinguish "this candidate has no vector" from "the store returned fewer than the candidate list", so partial coverage keeps the ranked subset instead of aborting. Only the total-miss case falls back, via the `ranked.isEmpty()` check below — this is a deliberate, documented Java-only divergence.

`KgChunkSelector` orchestration (upstream `_find_related_text_unit_from_entities` steps 2-4):

```java
record Selection(List<String> chunkIds, KgChunkPickMethod method, Map<String, Integer> frequency) {
}

static Selection select(
    KgChunkPickMethod requestedMethod,
    int relatedChunkNumber,
    List<Group> groups,
    Supplier<List<Double>> queryVector,
    ChunkVectorRanker ranker
) {
    var tracked = dedupeByFirstOwner(groups);
    if (relatedChunkNumber <= 0 || tracked.groups().isEmpty()) {
        return new Selection(List.of(), KgChunkPickMethod.WEIGHT, Map.of());
    }
    if (requestedMethod == KgChunkPickMethod.VECTOR) {
        var quota = vectorQuota(relatedChunkNumber, tracked.groups().size());
        try {
            var vector = queryVector.get();
            if (vector != null && !vector.isEmpty()) {
                var candidates = tracked.groups().stream().flatMap(g -> g.chunkIds().stream()).distinct().toList();
                var ranked = ranker.rank(vector, candidates, quota);
                if (!ranked.isEmpty()) {
                    return new Selection(ranked, KgChunkPickMethod.VECTOR, tracked.frequency());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("LightRAG vector chunk selection failed, falling back to WEIGHT: {}", exception.toString());
        }
    }
    var selected = pickByWeightedPolling(tracked.groups(), relatedChunkNumber, 1);
    return new Selection(selected, KgChunkPickMethod.WEIGHT, tracked.frequency());
}
```

- [ ] **Step 4: Replace `collectChunks` in both strategies**

`LocalQueryStrategy`: after `limitEntities` / `limitRelations`, build groups from the limited entity list (order already score-desc; the entity list order is the "earlier position" that owns shared chunks):

```java
var selection = KgChunkSelector.select(
    query.chunkPickMethod(),
    query.relatedChunkNumber(),
    limitedEntities.stream()
        .map(entity -> new KgChunkSelector.Group(entity.entityId(), entity.entity().sourceChunkIds(), entity.score()))
        .toList(),
    () -> queryVector,
    new VectorStoreChunkVectorRanker(storageProvider.vectorStore())
);
var selectedChunks = loadChunksInOrder(selection.chunkIds(), scoreByChunkId(limitedEntities, limitedRelations));
```

`GlobalQueryStrategy`: identical shape over `limitedRelations` (upstream’s relation path additionally excludes chunks already delivered by the entity path — Java retrieves them in separate strategies, so there is nothing to exclude; note this in a short comment).

Both strategies:

- load the selected ids with one `chunkStore.loadAll(selection.chunkIds())` call, then emit `ScoredChunk`s **in selection order** (no re-sorting by score). Score = max of the owning groups’ scores, used only for prompt display/reference ordering.
- keep `QueryMetadataFilterSupport.expandAndFilter(metadataPlan, chunks, parentChunkExpander, query.chunkTopK())`. That helper filters and parent-expands while preserving incoming order — assert this in the existing `QueryMetadataFilterSupport` tests if not already covered.
- log the selection: `method`, `selected`, `candidates`, `frequency` totals (upstream `chunk_tracking`).
- delete `collectChunks` / `retainChunks` where they become dead.

Note for `OneShotRetrievalStore` implementations (Java-only fast paths): they return chunks directly from the store and bypass `KgChunkSelector`; leave that behavior, and document it in the `OneShotRetrievalStore` javadoc as a divergence so the fast path can be revisited when the store contract grows a `relatedChunkNumber` input.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS. Existing tests that asserted the union-then-sort behavior must be rewritten to the new selection-order expectations (this is the accepted baseline change from decision 2).

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/ChunkVectorRanker.java \
        lightrag-core/src/main/java/io/github/lightrag/query/VectorStoreChunkVectorRanker.java \
        lightrag-core/src/main/java/io/github/lightrag/query/KgChunkSelector.java \
        lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightrag/query/GlobalQueryStrategy.java \
        lightrag-core/src/test/java/io/github/lightrag/query/KgChunkSelectorTest.java \
        lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java
git commit -m "feat: select KG-related chunks by quota with VECTOR/WEIGHT pickers"
```

---

## Phase 3 — Merge, ranking, rerank

### Task 7: Round-robin source merge for hybrid and mix

Upstream interleaves chunks, entities and relations from each source one item at a time, de-duplicating on first occurrence (`operate.py:5430-5486` entities/relations, `5811-5862` chunks).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/ChunkMerges.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/query/ChunkMergesTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java:32-66`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/HybridQueryStrategyTest.java`, `MixQueryStrategyTest.java`

- [ ] **Step 1: Write the failing merge tests**

```java
class ChunkMergesTest {
    @Test
    void interleavesSourcesAndKeepsTheFirstOccurrence() {
        var local = List.of(chunk("a", 0.9d), chunk("b", 0.8d));
        var global = List.of(chunk("b", 0.7d), chunk("c", 0.6d));
        assertThat(ChunkMerges.roundRobinChunks(List.of(local, global)))
            .extracting(ScoredChunk::chunkId)
            .containsExactly("a", "b", "c");
    }

    @Test
    void handlesUnevenSourceLengths() {
        var first = List.of(chunk("a", 1.0d));
        var second = List.of(chunk("b", 1.0d), chunk("c", 1.0d), chunk("d", 1.0d));
        assertThat(ChunkMerges.roundRobinChunks(List.of(first, second)))
            .extracting(ScoredChunk::chunkId)
            .containsExactly("a", "b", "c", "d");
    }

    @Test
    void interleavesEntitiesAndRelationsByTheirIdentity() {
        assertThat(ChunkMerges.roundRobinEntities(List.of(entity("e1"), entity("e2")), List.of(entity("e2"), entity("e3"))))
            .extracting(ScoredEntity::entityId)
            .containsExactly("e1", "e2", "e3");
        assertThat(ChunkMerges.roundRobinRelations(List.of(relation("r1")), List.of(relation("r2"))))
            .extracting(ScoredRelation::relationId)
            .containsExactly("r1", "r2");
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.ChunkMergesTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement `ChunkMerges`**

```java
final class ChunkMerges {
    private ChunkMerges() {
    }

    static List<ScoredChunk> roundRobinChunks(List<List<ScoredChunk>> sources) {
        var seen = new LinkedHashSet<String>();
        var merged = new ArrayList<ScoredChunk>();
        var maxLength = sources.stream().mapToInt(List::size).max().orElse(0);
        for (var index = 0; index < maxLength; index++) {
            for (var source : sources) {
                if (index < source.size()) {
                    var chunk = source.get(index);
                    if (seen.add(chunk.chunkId())) {
                        merged.add(chunk);
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    static List<ScoredEntity> roundRobinEntities(List<ScoredEntity> first, List<ScoredEntity> second) {
        // same interleave, de-duplicated by entityId (first occurrence wins), mirroring upstream
    }

    static List<ScoredRelation> roundRobinRelations(List<ScoredRelation> first, List<ScoredRelation> second) {
        // same interleave, de-duplicated by relationId
    }
}
```

- [ ] **Step 4: Use it in both hybrid and mix**

`HybridQueryStrategy`: replace the score-based `LinkedHashMap` merges — chunks via `ChunkMerges.roundRobinChunks(List.of(local.context().matchedChunks(), global.context().matchedChunks()))`, entities/relations via the two-source helpers. Keep `QueryMetadataFilterSupport.filterChunks` and `.limit(query.chunkTopK())` afterwards, and drop the now-unused `scoreOrder`/`pickEntity`-style helpers.

`MixQueryStrategy` branching path: `ChunkMerges.roundRobinChunks(List.of(directChunkPass.directChunks(), hybrid.context().matchedChunks()))` where the direct pass list is `List.copyOf(...values())` (its own order is the vector-search order). One-shot path (`retrieveOneShotMix`): `ChunkMerges.roundRobinChunks(List.of(retrieval.graphChunks(), retrieval.directChunks()))`. The adaptive `searchTopK` growth loop in `mergeDirectChunkMatches` stays, but its "already merged" bookkeeping must switch from a `LinkedHashMap` to a `LinkedHashSet` of ids so the interleaving is recomputed after each growth step.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.HybridQueryStrategyTest" --tests "io.github.lightrag.query.MixQueryStrategyTest" --tests "io.github.lightrag.query.ChunkMergesTest"`
Expected: PASS after updating order expectations in the two strategy tests.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/ChunkMerges.java \
        lightrag-core/src/main/java/io/github/lightrag/query/HybridQueryStrategy.java \
        lightrag-core/src/main/java/io/github/lightrag/query/MixQueryStrategy.java \
        lightrag-core/src/test/java/io/github/lightrag/query/ChunkMergesTest.java
git commit -m "feat: merge hybrid/mix retrieval sources round-robin"
```

---

### Task 8: Rank local relations by `(degree, weight)`

Upstream sorts matched edges by `(rank, weight)` descending, where `rank` is the edge degree from a batched `edge_degrees_batch` call (`operate.py:6282-6314`; degree definition `kg/networkx_impl.py:782-787`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java:120-160`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void ranksRelationsByCombinedEndpointDegreeThenWeight() {
    // Graph: hub -- r1 --> a   (hub degree 3, a degree 1 -> rank 4, weight 0.4)
    //        hub -- r2 --> b   (rank 3, weight 0.9)
    // Both relations match the query; r1 must come first despite the lower weight.
    var context = strategy.retrieve(QueryRequest.builder().query("hub connectivity").mode(QueryMode.LOCAL).build());
    assertThat(context.matchedRelations()).extracting(ScoredRelation::relationId)
        .containsExactly("r1", "r2");
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.LocalQueryStrategyTest"`
Expected: FAIL — current order follows the parent-entity similarity score.

- [ ] **Step 3: Compute degree from one batched graph read and re-order**

```java
var endpointIds = matchedRelations.stream()
    .flatMap(relation -> java.util.stream.Stream.of(relation.relation().srcId(), relation.relation().tgtId()))
    .distinct()
    .toList();
// One batched read: findRelations(List) returns Map<entityId, List<RelationRecord>> — take each id's
// list size, never the map size (a one-element map is always size 1). Same call shape the existing
// expansion step already uses (LocalQueryStrategy:135).
var relationsByEntityId = storageProvider.graphStore().findRelations(endpointIds);
var orderedRelations = matchedRelations.stream()
    .sorted(Comparator
        .<ScoredRelation>comparingInt(relation ->
            relationsByEntityId.getOrDefault(relation.relation().srcId(), List.of()).size()
                + relationsByEntityId.getOrDefault(relation.relation().tgtId(), List.of()).size())
        .reversed()
        .thenComparing(Comparator.comparingDouble(
            (ScoredRelation relation) -> relation.relation().weight()).reversed())
        .thenComparing(ScoredRelation::relationId))
    .toList();
```

Use `orderedRelations` for the context, for `limitRelations`, and for the KG chunk groups. Keep the existing parent-entity-derived `score` untouched for display; ordering is now explicit. Degree is the incident-relation count of each endpoint — the String overload `findRelations(entityId)` returns exactly that, and the batched `Map` overload's per-id values are computed from it (`GraphStore.java:66-75`); if a store's `findRelations(String)` does not count both directions, add a focused test in that store's test class rather than special-casing here.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.LocalQueryStrategyTest" --tests "io.github.lightrag.storage.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/LocalQueryStrategy.java \
        lightrag-core/src/test/java/io/github/lightrag/query/LocalQueryStrategyTest.java
git commit -m "feat: rank local relations by endpoint degree then weight"
```

---

### Task 9: Rerank parity — `top_n`, score validation, configurable failure mode

Upstream reranks with `top_n = chunk_top_k` (`utils.py:7084-7092`), validates each provider result (`normalize_rerank_result`, `utils.py:7026-7052`), filters below `min_rerank_score` when set, and on any failure logs and keeps the original order (`utils.py:7013-7021`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/RerankFailureMode.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/RerankModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java:639-668`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `LightRag.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void passesChunkTopKAsRerankTopNAndTreatsProviderOrderAsAuthoritative() {
    var model = new RecordingRerankModel(List.of(
        new RerankModel.RerankResult("c2", 0.9d),
        new RerankModel.RerankResult("c1", 0.5d)));
    var result = engineWithRerank(model).queryStructured(QueryRequest.builder()
        .query("tariff schedule").mode(QueryMode.MIX).chunkTopK(2).enableRerank(true).build());
    assertThat(model.lastRequest().topN()).isEqualTo(2);
    assertThat(result.chunks()).extracting(StructuredQueryChunk::chunkId).containsExactly("c2", "c1");
}

@Test
void ignoresOutOfRangeAndNonFiniteRerankResults() {
    var model = new RecordingRerankModel(List.of(
        new RerankModel.RerankResult("unknown-id", 0.9d),
        new RerankModel.RerankResult("c1", Double.NaN),
        new RerankModel.RerankResult("c2", 0.4d)));
    // only c2 survives: unknown ids are ignored, non-finite scores are rejected, and candidates
    // missing from the provider response are NOT appended any more (provider order is authoritative)
    var result = engineWithRerank(model).queryStructured(QueryRequest.builder()
        .query("tariff schedule").mode(QueryMode.MIX).chunkTopK(2).enableRerank(true).build());
    assertThat(result.chunks()).extracting(StructuredQueryChunk::chunkId).containsExactly("c2");
}

@Test
void fallsBackToOriginalOrderWhenConfiguredAndTheRerankerFails() {
    var model = new FailingRerankModel();
    var result = engineWithRerankAndMode(model, RerankFailureMode.FALLBACK_TO_ORIGINAL).queryStructured(...);
    assertThat(result.chunks()).extracting(StructuredQueryChunk::chunkId).isNotEmpty();
}

@Test
void defaultFailureModeStillFailsFast() {
    assertThatThrownBy(() -> engineWithRerank(new FailingRerankModel()).query(...))
        .isInstanceOf(RuntimeException.class);
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`
Expected: FAIL — `RerankRequest` has no `topN`; failure mode option missing.

- [ ] **Step 3: Extend the model contract**

```java
public record RerankRequest(String query, List<RerankCandidate> candidates, int topN) {
    public RerankRequest {
        topN = topN <= 0 ? candidates.size() : topN;
    }

    public RerankRequest(String query, List<RerankCandidate> candidates) {
        this(query, candidates, 0);
    }
}
```

```java
public enum RerankFailureMode {
    FAIL_FAST,
    FALLBACK_TO_ORIGINAL
}
```

**Platform note:** the 2-argument `RerankRequest` constructor above is load-bearing — aiplatform's `LightRagPlatformRerankModelTest` builds it that way, and the kept `LightRagPlatformRerankModel` sends its own `top_n = candidates.size()` (`buildPayload:193-214`); honoring `request.topN()` there is an optional platform-side follow-up.

`LightRagBuilder.rerankFailureMode(RerankFailureMode)` (default `FAIL_FAST`, so today's behavior is preserved unless opted in); thread through `LightRag` into `QueryEngine`.

- [ ] **Step 4: Rewrite `rerankChunks`**

```java
private List<ScoredChunk> rerankChunks(QueryRequest request, List<ScoredChunk> matchedChunks) {
    var originalOrder = List.copyOf(matchedChunks);
    var byId = new LinkedHashMap<String, ScoredChunk>();
    for (var chunk : matchedChunks) {
        byId.put(chunk.chunkId(), chunk);
    }
    List<RerankModel.RerankResult> results;
    try {
        results = Objects.requireNonNull(rerankModel, "rerankModel").rerank(new RerankModel.RerankRequest(
            request.query(),
            matchedChunks.stream()
                .map(chunk -> new RerankModel.RerankCandidate(chunk.chunkId(), chunk.chunk().text()))
                .toList(),
            request.chunkTopK()
        ));
    } catch (RuntimeException exception) {
        if (rerankFailureMode == RerankFailureMode.FALLBACK_TO_ORIGINAL) {
            log.warn("LightRAG rerank failed, using original retrieval order: {}", exception.toString());
            return originalOrder.stream().limit(request.chunkTopK()).toList();
        }
        throw exception;
    }

    var ordered = new ArrayList<ScoredChunk>(matchedChunks.size());
    for (var result : results) {
        if (!Double.isFinite(result.score()) || result.score() < minRerankScore) {
            continue;
        }
        var chunk = byId.remove(result.id());
        if (chunk == null) {
            log.warn("LightRAG rerank returned unknown chunk id, ignoring: {}", result.id());
            continue;
        }
        ordered.add(chunk);
    }
    // Empty provider output means "no opinion": keep the retrieval order (upstream utils.py:7019-7021).
    if (ordered.isEmpty()) {
        return originalOrder.stream().limit(request.chunkTopK()).toList();
    }
    return ordered.stream().limit(request.chunkTopK()).toList();
}
```

The previous `minRerankScore == 0.0 → append unscored leftovers` behavior is removed: provider order is authoritative once results exist. Document the change in the `LightRagBuilder.rerankModel` javadoc (`:124-131`), which currently claims the opposite.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`
Expected: PASS. Update any test that relied on leftovers being appended.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/RerankModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/RerankFailureMode.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: align rerank top_n, score validation and failure handling"
```

---

## Phase 4 — Token governance and context rendering

### Task 10: Pluggable `TokenCounter` with CJK-aware default

Upstream budgets with the provider's real tokenizer (`Tokenizer.encode` on the exact text it later sends), so its counts are exact; this task keeps that *shape* (one counter instance threaded through every budget check, including Task 11's render verification) but the default implementation is a heuristic: each CJK/Kana/Hangul code point counts as 1 token, other text at ~4 characters per token. **This is a documented Java-only divergence** — the seam exists so a consumer can plug a real tokenizer, and the heuristic should err toward over-counting CJK (the safe direction: keep less context, never overflow the provider window). Absolute counts in tests must assert the counter's own arithmetic, not real-BPE numbers.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/TokenCounter.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/model/HeuristicTokenCounter.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/HeuristicTokenCounterTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java:28,622-637` and the strategy constructors
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java`, `LightRag.java:945-977`

- [ ] **Step 1: Write the failing counter tests**

```java
class HeuristicTokenCounterTest {
    private final TokenCounter counter = new HeuristicTokenCounter();

    @Test
    void countsEachWideCodePointAsOneToken() {
        assertThat(counter.countTokens("住房公积金")).isEqualTo(5);
    }

    @Test
    void countsNonWideRunsAtFourCharactersPerToken() {
        assertThat(counter.countTokens("abcd")).isEqualTo(1);
        assertThat(counter.countTokens("abcde")).isEqualTo(2);
        assertThat(counter.countTokens("hello world")).isEqualTo(3); // 5 -> 2, space+5 -> 2
    }

    @Test
    void handlesMixedTextAndEmptyInput() {
        assertThat(counter.countTokens("")).isZero();
        assertThat(counter.countTokens(null)).isZero();
        assertThat(counter.countTokens("租金 rent")).isEqualTo(4); // 2 wide + 5 non-wide chars -> 2 + 2
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.HeuristicTokenCounterTest"`
Expected: FAIL — classes missing.

- [ ] **Step 3: Implement contract and default**

```java
package io.github.lightrag.model;

@FunctionalInterface
public interface TokenCounter {
    int countTokens(String text);
}
```

```java
public final class HeuristicTokenCounter implements TokenCounter {
    private static final int NON_WIDE_CHARS_PER_TOKEN = 4;

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var tokens = 0;
        var runLength = 0;
        for (var index = 0; index < text.length(); ) {
            var codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (UnicodeWidths.isWide(codePoint)) {
                tokens += ceilDiv(runLength);
                runLength = 0;
                tokens++;
            } else {
                runLength++;
            }
        }
        return tokens + ceilDiv(runLength);
    }

    private static int ceilDiv(int length) {
        return length == 0 ? 0 : (length + NON_WIDE_CHARS_PER_TOKEN - 1) / NON_WIDE_CHARS_PER_TOKEN;
    }
}
```

- [ ] **Step 4: Make `QueryBudgeting` instance-based and pin the buffer**

`QueryBudgeting` gains a `TokenCounter` constructor parameter and an instance `approximateTokenCount` (same name, now delegating); all `static` uses become instance calls. In `LightRag.newQueryEngine`, create one `var budgeting = new QueryBudgeting(configTokenCounter);` and pass it to `NaiveQueryStrategy`, `LocalQueryStrategy`, `GlobalQueryStrategy`, `HybridQueryStrategy`, `MixQueryStrategy`, `MultiHopQueryStrategy` and `QueryEngine` (constructor additions; keep the old constructor signatures out — update every call site instead of keeping shims).

`QueryEngine`:

```java
private static final int REFERENCE_LIST_BUDGET_BUFFER_TOKENS = 200; // upstream operate.py buffer_tokens
```

Replace `CHUNK_BUDGET_BUFFER_TOKENS` in `remainingChunkBudget` (`:635`). Log the buffer in the stage log line.

`LightRagBuilder`: `tokenCounter(TokenCounter)` (default `new HeuristicTokenCounter()`); README documents the seam for users who want a real tokenizer.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test`
Expected: PASS. Tests asserting exact chunk counts against CJK text may shift — update them to the counter’s arithmetic rather than loosening assertions.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/TokenCounter.java \
        lightrag-core/src/main/java/io/github/lightrag/model/HeuristicTokenCounter.java \
        lightrag-core/src/main/java/io/github/lightrag/text/UnicodeWidths.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRagBuilder.java \
        lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java \
        lightrag-core/src/test/java/io/github/lightrag/model/HeuristicTokenCounterTest.java
git commit -m "feat: pluggable token counter with CJK-aware default and 200-token buffer"
```

---

### Task 11: Two-stage, render-verified chunk truncation

Upstream keeps **whole chunks only**: stage 1 takes a token-budgeted prefix counted from each chunk's `{content, content_headings}` projection, then stage 2 re-renders that prefix through the real reference formatter and shrinks the kept count until the rendered text fits (`_truncate_chunks_for_unified_context`, `utils.py:7825-7874`; called from `process_chunks_unified`, `utils.py:7106-7136`). No chunk is ever trimmed mid-text — the boundary chunk is dropped whole. (An earlier draft of this plan trimmed the boundary chunk; that was wrong and is corrected below.)

**Sequencing:** if Task 11 and Task 12 are implemented in one sitting, land **Task 12 first** — stage 2 renders through the Task 12 formatter (`[n]` ids + `Reference Document List`), and the reference-id assignment is exactly the input stage 1 cannot see.

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/ChunkBudgetTruncator.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/query/ChunkBudgetTruncatorTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java:51-68`, `QueryEngine.java:436-450`

- [ ] **Step 1: Write the failing tests**

```java
class ChunkBudgetTruncatorTest {
    private final TokenCounter counter = new HeuristicTokenCounter();
    private final ChunkBudgetTruncator truncator = new ChunkBudgetTruncator(counter);

    @Test
    void keepsWholeChunksWhileTheyFit() {
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta"));
        var kept = truncate(chunks, counter.countTokens(render(chunks)));
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1", "c2");
    }

    @Test
    void dropsTheBoundaryChunkWholeInsteadOfTrimmingItsText() {
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta epsilon zeta"));
        var kept = truncate(chunks, counter.countTokens(render(List.of(chunks.get(0)))));
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
        assertThat(kept).extracting(chunk -> chunk.chunk().text())
            .containsExactly("alpha beta");                    // never a mid-text prefix of c2
    }

    @Test
    void stageTwoShrinksWhatStageOneOverAdmitted() {
        // approxKey sees only the {content} projection; the real renderer adds per-chunk
        // overhead (reference ids), so stage 1 admits both chunks and stage 2 must shrink to one.
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta"));
        var oneRendered = counter.countTokens(render(List.of(chunks.get(0))));
        var kept = truncate(chunks, oneRendered + 2);
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void cjkTextIsMeasuredThroughTheSameProjectionItRendersWith() {
        // CJK characters weigh more than ASCII in the default heuristic counter; the kept prefix
        // must fit the *rendered* budget and the boundary chunk is dropped whole
        var chunks = List.of(chunkWithText("c1", "住房公积金提取流程"), chunkWithText("c2", "租房提取申请材料"));
        var budget = counter.countTokens(render(List.of(chunks.get(0))));
        var kept = truncate(chunks, budget);
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void escapedJsonProjectionsAreMeasuredByTheRendererNotTheRawText() {
        // the real projection is JSON: quotes and newlines escape (\" \n) and cost extra tokens;
        // a stage-1 prefix computed from the raw text can still need shrinking in stage 2
        Function<List<ScoredChunk>, String> jsonRenderer = list -> list.stream()
            .map(chunk -> "{\"content\":\"" + chunk.chunk().text().replace("\"", "\\\"").replace("\n", "\\n") + "\"}")
            .collect(Collectors.joining("\n"));
        var chunks = List.of(
            chunkWithText("c1", "safe"),
            chunkWithText("c2", "\"\"\"\"\"\"\"\" and\nescapes"));
        var budget = counter.countTokens(jsonRenderer.apply(List.of(chunks.get(0))));
        var kept = truncator.truncate(chunks, budget, jsonRenderer, chunk -> "{\"content\":\"" + chunk.chunk().text() + "\"}");
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void renderedReferenceIdsStillFitTheBudget() {
        // the renderer numbers survivors positionally ([1]..[n]) and two-digit ids cost more than
        // stage 1's {content} projection saw, so stage 2 must re-verify against the renderer
        var chunks = new ArrayList<ScoredChunk>();
        for (int index = 1; index <= 12; index++) {
            chunks.add(chunkWithText("c" + index, "chunk number " + index + " text"));
        }
        Function<List<ScoredChunk>, String> numbered = list -> IntStream.range(0, list.size())
            .mapToObj(index -> "[" + (index + 1) + "] " + list.get(index).chunk().text())
            .collect(Collectors.joining("\n"));
        var budget = counter.countTokens(numbered.apply(chunks.subList(0, 9)));
        var kept = truncator.truncate(chunks, budget, numbered, chunk -> chunk.chunk().text());

        assertThat(counter.countTokens(numbered.apply(kept))).isLessThanOrEqualTo(budget);
        assertThat(counter.countTokens(numbered.apply(chunks.subList(0, 10)))).isGreaterThan(budget);
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactlyElementsOf(
            chunks.subList(0, kept.size()).stream().map(ScoredChunk::chunkId).toList());
    }

    @Test
    void returnsEmptyWhenNothingFits() {
        var kept = truncate(List.of(chunkWithText("c1", "alpha beta gamma")), 1);
        assertThat(kept).isEmpty();
    }

    private List<ScoredChunk> truncate(List<ScoredChunk> chunks, int maxTokens) {
        return truncator.truncate(chunks, maxTokens, ChunkBudgetTruncatorTest::render, ChunkBudgetTruncatorTest::approxKey);
    }

    private static String render(List<ScoredChunk> chunks) {
        return chunks.stream()
            .map(chunk -> "[" + chunk.chunkId() + "] " + chunk.chunk().text())
            .collect(Collectors.joining("\n"));
    }

    private static String approxKey(ScoredChunk chunk) {
        return chunk.chunk().text();
    }

    private static ScoredChunk chunkWithText(String id, String text) {
        return new ScoredChunk(id, new Chunk(id, "doc", text, text.length(), 0, Map.of()), 1.0d);
    }
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.ChunkBudgetTruncatorTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
final class ChunkBudgetTruncator {
    private final TokenCounter counter;

    ChunkBudgetTruncator(TokenCounter counter) {
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    List<ScoredChunk> truncate(
        List<ScoredChunk> chunks,
        int maxTokens,
        Function<List<ScoredChunk>, String> renderer,
        Function<ScoredChunk, String> approxKey
    ) {
        if (maxTokens <= 0 || chunks.isEmpty()) {
            return List.of();
        }
        // Stage 1: greedy prefix over the {content, content_headings} projection only. reference_id
        // cannot be counted here — it is derived from the survivor set, which is exactly what this
        // stage decides (upstream _truncate_chunks_for_unified_context, utils.py:7825-7874).
        // Counting each candidate join directly is the Java equivalent of upstream's
        // truncate_by_token_limit + shrink (utils.py:4111-4123): both verify the exact kept prefix.
        var approx = approximatePrefix(chunks, maxTokens, approxKey);
        // Stage 2: re-render the candidates through the real renderer (same call the prompt uses,
        // reference ids recomputed for this exact candidate list) and shrink k until it fits.
        var k = approx.size();
        while (k > 0) {
            if (counter.countTokens(renderer.apply(approx.subList(0, k))) <= maxTokens) {
                break;
            }
            k--;
        }
        return List.copyOf(approx.subList(0, k));
    }

    private List<ScoredChunk> approximatePrefix(
        List<ScoredChunk> chunks,
        int maxTokens,
        Function<ScoredChunk, String> approxKey
    ) {
        var kept = new ArrayList<ScoredChunk>();
        var joined = "";
        for (var chunk : chunks) {
            var key = approxKey.apply(chunk);
            var candidate = kept.isEmpty() ? key : joined + "\n" + key;
            if (counter.countTokens(candidate) > maxTokens) {
                break;
            }
            kept.add(chunk);
            joined = candidate;
        }
        return List.copyOf(kept);
    }
}
```

Wire it into `QueryEngine.executeStandardQuery`: replace `QueryBudgeting.limitChunks(filteredChunks, budget)` with

```java
chunkBudgetTruncator.truncate(
    filteredChunks,
    budget,
    chunks -> contextAssembler.assemble(contextWithChunks(chunks)),
    chunk -> ContextAssembler.approxChunkProjection(chunk, chunkHeadings))
```

so both stages run through the same shapes the prompt uses: stage 2 through the full renderer (including reference ids from Task 12 — sequence Task 12 first if implementing both in one sitting) and stage 1 through the per-chunk `{content, content_headings}` JSON projection that renderer emits, minus `reference_id`. Delete `QueryBudgeting.limitChunks` once unused; keep `limitEntities`/`limitRelations` as instance methods.

**Documented divergence (stage-1 prefix seed).** Upstream's `truncate_list_by_token_size` (`utils.py:4082-4124`, with `_rendered_prefix_item_count` at `:4069-4080`) slices the *fully joined* candidate text at the token limit, counts how many items sit entirely inside that span, and then re-verifies/shrinks because BPE token counts are not monotone in text length. Java's `approximatePrefix` accumulates per chunk instead and stops at the first per-chunk overflow. For a monotone counter the two seeds coincide; for a non-monotone custom counter Java may keep a **shorter** stage-1 prefix (never a longer unverified one) — stage 2 re-verifies the exact rendered prefix in every case, so the returned list is always budget-safe. The default `HeuristicTokenCounter` is monotone by construction (fixed per-character-class costs), and the boundary tests above pin CJK weights, JSON escaping and two-digit reference ids.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/ChunkBudgetTruncator.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/test/java/io/github/lightrag/query/ChunkBudgetTruncatorTest.java
git commit -m "feat: render-verified two-stage chunk truncation"
```

---

### Task 12: Reference ids, `content_headings` and `Reference Document List` in the context

Upstream renders chunk context lines as `{reference_id, content[, content_headings]}` plus a `Reference Document List`, so the LLM can emit citations that line up with the returned references (`prompt.py:442-467`, `utils.py:7735-7822`); headings come from the chunk’s parent-heading metadata and are attached before token truncation (`operate.py:5719-5762`).

**Files:**
- Create: `lightrag-core/src/main/java/io/github/lightrag/query/ChunkHeadings.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/query/ChunkHeadingsTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryReferences.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/ContextAssembler.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java` (`formatChunk`)
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/ContextAssemblerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
class ChunkHeadingsTest {
    @Test
    void joinsTheHeadingPathIntoTheUpstreamBreadcrumb() {
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"第三章\",\"3.2 租房提取\"]"));
        assertThat(ChunkHeadings.resolve(chunk)).contains("第三章 → 3.2 租房提取");
    }

    @Test
    void fallsBackToThePreJoinedSectionPath() {
        var chunk = chunkWithMetadata(Map.of("smart_chunker.section_path", "Chapter 1 > Setup"));
        assertThat(ChunkHeadings.resolve(chunk)).contains("Chapter 1 → Setup");
    }

    @Test
    void capsEachLevelAtEightyCharactersWithAnEllipsis() {
        var level = "x".repeat(200);
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"" + level + "\"]"));
        assertThat(ChunkHeadings.resolve(chunk)).contains("x".repeat(79) + "…");
    }

    @Test
    void returnsEmptyForChunksWithoutHeadingMetadata() {
        assertThat(ChunkHeadings.resolve(chunkWithMetadata(Map.of()))).isEmpty();
    }
}
```

The test metadata shapes are the platform's real ones (`ParagraphChunkingStrategy:315-321` writes `headingPath` as a JSON array and `section_path`/`smart_chunker.section_path` pre-joined with `" > "`); upstream's `" → "` separator and per-level `…` elision come from `_truncate_heading_level` (`chunk_schema.py:125-131`), so the SDK normalizes to the upstream rendering rather than re-emitting the platform's `>`-joined string. `content_headings` is the **parent** chain only — the same thing `headingPath` carries for content chunks.

`ContextAssemblerTest`:

```java
@Test
void rendersReferenceIdsHeadingsAndReferenceList() {
    var context = new QueryContext(List.of(), List.of(), List.of(
        scoredChunk("c1", "alpha", Map.of("smart_chunker.section_path", "Setup > Install")),
        scoredChunk("c2", "beta", Map.of())), "");
    var assembled = new ContextAssembler(counter).assemble(context);
    assertThat(assembled)
        .contains("- [1] c1")
        .contains("headings: Setup → Install")
        .contains("Reference Document List:")
        .contains("- [1] ");
}

@Test
void referenceIdsMatchQueryReferencesOrdering() {
    // two chunks from the same document; assert the [n] in the context equals
    // the referenceId returned by QueryReferences.fromChunks for the same chunk id
}

@Test
void approxProjectionOmitsReferenceIdSoStageOneNeverUndercountsTheRenderer() {
    // approxChunkProjection(chunk, headings) must equal the renderer's own per-chunk
    // projection minus the reference_id field
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.ContextAssemblerTest" --tests "io.github.lightrag.query.ChunkHeadingsTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `ChunkHeadings`**

Resolve, in order: `headingPath` / `sectionHierarchy` (JSON array of levels), then `section_path` / `sectionPath` / `smart_chunker.section_path` (pre-joined with `" > "`, split back into levels). Clean each level the way `_clean_heading_text` does (`chunk_schema.py:85-124`: flatten whitespace to single spaces, drop Cc/Cf control chars, strip any `→` so it cannot forge a level), cap each level at 80 characters (`DEFAULT_HEADING_LEVEL_MAX_CHARS`, `constants.py:47`, enforced by `format_parent_headings`, `chunk_schema.py:148-171`; elide with `…` so the capped length stays ≤ 80), join with `" → "` (`HEADING_BREADCRUMB_SEP`, `chunk_schema.py:38`), and token-budget the joined breadcrumb at 256 tokens (`DEFAULT_MAX_SECTION_CONTEXT_TOKENS`, `constants.py:43`) measured with the configured `TokenCounter`. Return `Optional<String>`. (An earlier draft capped each level at 128 and used the platform's `>` separator; 80 matches upstream, and the cap is load-bearing — 80 chars < 256/3 is what keeps the collapsed `first → … → leaf` fallback inside the token budget.)

- [ ] **Step 4: Extract shared reference numbering and render it**

Move the ordering algorithm inside `QueryReferences.fromChunks` into a reusable `static Map<String, String> assignReferenceIds(List<ScoredChunk> chunks)` and a `static String sourceOf(ScoredChunk chunk)`; `fromChunks` keeps its current output (`contexts`/`references`) by delegating. `ContextAssembler` takes a `TokenCounter` and calls the same helpers so `[n]` in the prompt and `referenceId` in the result always agree:

```
Chunks:
- [1] c1 | 0.912 | headings: Setup > Install | alpha

Reference Document List:
- [1] <source>
```

`QueryBudgeting.formatChunk` gains the reference id and headings (only when present) in that exact separator scheme, and `ContextAssembler` exposes `static String approxChunkProjection(ScoredChunk chunk, ChunkHeadings headings)` — the same per-chunk `{content[, content_headings]}` projection the renderer emits, minus `reference_id`. Task 11's stage 1 counts exactly this string (`render_chunks_context_text`'s `entry` dict, `utils.py:7815-7820`), so both sides cannot drift.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.*" --tests "io.github.lightrag.E2ELightRagTest"`
Expected: PASS after updating prompt-shape assertions in E2E tests. Demo `QueryControllerTest` may need the same fixture updates.

- [ ] **Step 6: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/query/ChunkHeadings.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryReferences.java \
        lightrag-core/src/main/java/io/github/lightrag/query/ContextAssembler.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryBudgeting.java \
        lightrag-core/src/test/java/io/github/lightrag/query/ChunkHeadingsTest.java \
        lightrag-core/src/test/java/io/github/lightrag/query/ContextAssemblerTest.java
git commit -m "feat: cite sources in context with reference ids and heading breadcrumbs"
```

---

## Phase 5 — Result surface and cache

### Task 13: `responseTime` and `llmGenerated` on `QueryResult`

Upstream returns `response_time` and `llm_generated` on every result and sets `llm_generated=false` for fail responses and context/prompt previews (`operate.py:114-128,4736-4788`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryResult.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- Modify: `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/QueryController.java`, `QueryStreamService.java`, `QueryRequestMapper.java` (only if the response DTO should expose the fields; keep SSE payload additive)
- Modify: `lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java`, `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/QueryControllerTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void marksContextOnlyAndPreviewResultsAsNotLlmGenerated() {
    // No shared `engine` field exists in this test class — every test builds its own (QueryEngineTest.java:30-40).
    // Two fixtures: normal retrieval drives the context/preview/answer branches; the fail branch needs an
    // engine whose strategy retrieves NOTHING, or "nothing matches this" would still answer via baseContext().
    var engine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(baseContext()), null, false, 2);
    var failEngine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(retrievalReturnsNothing()), null, false, 2);

    var contextOnly = engine.query(QueryRequest.builder().query("tariff schedule").onlyNeedContext(true).build());
    var preview = engine.query(QueryRequest.builder().query("tariff schedule").onlyNeedPrompt(true).build());
    var fail = failEngine.query(QueryRequest.builder().query("nothing matches this").build());
    var answer = engine.query(QueryRequest.builder().query("tariff schedule").build());

    assertThat(contextOnly.llmGenerated()).isFalse();
    assertThat(preview.llmGenerated()).isFalse();
    assertThat(fail.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);   // pins the empty-retrieval path
    assertThat(fail.llmGenerated()).isFalse();
    assertThat(answer.llmGenerated()).isTrue();
}

@Test
void reportsNonNegativeResponseTime() {
    var engine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(baseContext()), null, false, 2);
    assertThat(engine.query(QueryRequest.builder().query("tariff schedule").build()).responseTime()).isGreaterThanOrEqualTo(0.0d);
}

@Test
void structuredFailResponseIsNotLlmGenerated() {
    // lands together with the component on StructuredQueryResult (Step 3): the canned fail answer
    // Task 3's failStructuredResult builds must report llmGenerated=false like the QueryResult twin.
    var failEngine = new QueryEngine(new RecordingChatModel(), new ContextAssembler(),
        strategiesReturning(retrievalReturnsNothing()), null, false, 2);
    var fail = failEngine.queryStructured(QueryRequest.builder().query("nothing matches this").build());
    assertThat(fail.answer()).isEqualTo(QueryEngine.DEFAULT_FAIL_RESPONSE);
    assertThat(fail.llmGenerated()).isFalse();
}
```

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest"`
Expected: FAIL — record has no such components.

- [ ] **Step 3: Extend `QueryResult` and thread the values**

Append `double responseTime` (seconds, matching upstream’s float seconds) and `boolean llmGenerated` to the record; update the canonical constructor validation (`responseTime` must be finite and ≥ 0), the convenience constructors (default `0.0d`, `false` unless the factory says otherwise), and `streaming(...)` — the existing form keeps `llmGenerated=true`, plus a new 4-argument overload `streaming(iterator, contexts, references, boolean llmGenerated)` that Task 3's fail short-circuit calls with `false` (upstream's canned streamed response carries `llm_generated=False`, `lightrag.py:5235-5241`). Update `equals`/`hashCode` to include both components.

**Platform note:** keep the 3-argument convenience constructor and `Context`'s 2-/4-argument forms source-identical — aiplatform tests construct them (`LightRagRetrievalEngineTest:94-107`, `LightRagResultMapperTest:59-66`) and platform main code never reads the new components (optional adoption in explain responses later).

`QueryEngine`: capture `System.nanoTime()` at the entry of `query`/`queryStructured`/`bypassQuery`, and pass `elapsedMillis(...) / 1000.0d` plus the correct flag (`false` for fail responses, `onlyNeedContext`, `onlyNeedPrompt`; `true` for generated answers and streams).

`StructuredQueryResult` gains the same `boolean llmGenerated` component (append as the 7th; keep a 6-arg convenience constructor delegating with `false` so the two `QueryEngine` call sites and existing tests compile — `QueryEngine.java:321-334`, `:528`). `queryStructured` and `bypassStructuredQuery` pass `false` for context/prompt previews and fail responses and `true` only when `resolveStructuredAnswer` actually generated an answer; `failStructuredResult` (Task 3) passes `false` and drops its breadcrumb comment. This closes the round-5 review point that the `llmGenerated=false` in the Task 3 fail helper must be realized on the real constructor and asserted (test above).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.query.QueryEngineTest" --tests "io.github.lightrag.E2ELightRagTest"` then `./gradlew :lightrag-spring-boot-demo:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java \
        lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java \
        lightrag-core/src/test/java/io/github/lightrag/query/QueryEngineTest.java
git commit -m "feat: expose response time and llm_generated on query results"
```

---

### Task 14: Answer cache key alignment

Upstream keys the answer cache on a policy version, the retrieval parameter set and the LLM identity, and bypasses the cache for history-carrying requests (`operate.py:4861-4919`, `utils.py:901-931`).

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/CachedChatModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java:242,257` (static `cacheId` call sites)
- Create: `lightrag-core/src/test/java/io/github/lightrag/model/CachedChatModelTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void cacheKeyIncludesPolicyVersionAndModelIdentity() {
    var key = CachedChatModel.cacheId("query", "openai-compatible:gpt-4o-mini@https://api.example/v1",
        new ChatModel.ChatRequest("system", "user"));
    assertThat(key).startsWith("v2:query:openai-compatible:gpt-4o-mini@https://api.example/v1:");
}

@Test
void modelIdentityChangeInvalidatesCachedAnswers() {
    // delegate A (identity "openai-compatible:model-a@url") caches a response,
    // then a second CachedChatModel over delegate B (identity "openai-compatible:model-b@url")
    // must not see it
}

@Test
void requestOptionsChangeTheCacheKeyWithoutChangingThePrompt() {
    // temperature/max_tokens/top_p/response_format alter provider behaviour but not the
    // prompt text; hashing the rendered request alone would under-split (review round 2, M6)
    var identity = "openai-compatible:gpt-4o-mini@https://api.example/v1";
    var plain = new ChatModel.ChatRequest("system", "user", List.of(), ChatRequestOptions.NONE);
    var base = CachedChatModel.cacheId("query", identity, plain);

    assertThat(CachedChatModel.cacheId("query", identity,
        new ChatModel.ChatRequest("system", "user", List.of(),
            new ChatRequestOptions(0.2d, null, null, null)))).isNotEqualTo(base);
    assertThat(CachedChatModel.cacheId("query", identity,
        new ChatModel.ChatRequest("system", "user", List.of(),
            new ChatRequestOptions(null, 512, null, null)))).isNotEqualTo(base);
    assertThat(CachedChatModel.cacheId("query", identity,
        new ChatModel.ChatRequest("system", "user", List.of(),
            new ChatRequestOptions(null, null, 0.9d, null)))).isNotEqualTo(base);
    assertThat(CachedChatModel.cacheId("query", identity,
        new ChatModel.ChatRequest("system", "user", List.of(),
            new ChatRequestOptions(null, null, null, "json_object")))).isNotEqualTo(base);
    assertThat(CachedChatModel.cacheId("query", identity,
        new ChatModel.ChatRequest("system", "user", List.of(), ChatRequestOptions.NONE))).isEqualTo(base);
}

@Test
void historyCarryingRequestsBypassTheCache() {
    var delegate = new RecordingChatModel("answer");
    var model = new CachedChatModel("query", delegate, cacheStore);
    var request = new ChatModel.ChatRequest("system", "user",
        List.of(new ChatModel.ConversationMessage("user", "earlier turn")));
    model.generate(request);
    model.generate(request);
    assertThat(delegate.generateCalls()).isEqualTo(2);   // neither read nor written
    assertThat(cacheStore.snapshot()).isEmpty();
}
```

`CachedChatModelTest` does not exist yet — create it in this step together with its `RecordingChatModel` double (`generateCalls()` counter) and the in-memory `LlmCacheStore` stub. `ChatRequestOptions` and the 4-component `ChatRequest` are defined by the build-side plan (Task 10); see the cross-plan dependency in Step 3.

- [ ] **Step 2: Run to confirm failure**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.CachedChatModelTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
public interface ChatModel {
    // ...

    /** Identity of the backing model+endpoint, folded into answer-cache keys. */
    default String cacheIdentity() {
        return "unknown";
    }
}
```

`CachedChatModel`: constructor takes `(String role, ChatModel delegate, LlmCacheStore cacheStore)` and captures `delegate.cacheIdentity()`; key prefix becomes

```java
private static final String CACHE_POLICY_VERSION = "v2";
...
return CACHE_POLICY_VERSION + ":" + role.toLowerCase(Locale.ROOT) + ":" + identity + ":" + sha256(canonical.toString());
```

The canonical string must cover **everything that can change the provider's output**, not just the prompt text — extend the current builder (`CachedChatModel.java:39-51`) with the request options:

```java
var canonical = new StringBuilder()
    .append("role=").append(role).append('\n')
    .append("system=").append(request.systemPrompt()).append('\n')
    .append("user=").append(request.userPrompt()).append('\n')
    .append("options.temperature=").append(request.options().temperature()).append('\n')
    .append("options.maxTokens=").append(request.options().maxTokens()).append('\n')
    .append("options.topP=").append(request.options().topP()).append('\n')
    .append("options.responseFormat=").append(request.options().responseFormat()).append('\n');
for (var message : request.conversationHistory()) {
    canonical
        .append("history.role=").append(message.role()).append('\n')
        .append("history.content=").append(message.content()).append('\n');
}
```

`generate`: if `!request.conversationHistory().isEmpty()` → delegate directly, no read, no write. Keep the stream bypass (Task scope note: truncated-response suppression needs §7 finish_reason work and stays open).

`CachedChatModel` also overrides `cacheIdentity()` to return its captured identity, and **every model wrapper must delegate that method** — including the concurrency wrappers the build-side plan adds (`LimitedChatModel`/`LimitedEmbeddingModel`, build plan Task 13): a wrapper that leaves the interface default `"unknown"` makes key-recording call sites compute ids the cache never wrote.

**Cross-plan dependency (build plan Task 10).** `ChatRequestOptions(Double temperature, Integer maxTokens, Double topP, String responseFormat)` and the fourth `ChatRequest` component are owned by the build-side plan (`2026-09-30-java-lightrag-build-side-and-engineering-alignment-plan.md`, Task 10). If Task 14 is implemented before that plan, port that component first (shape above, `NONE`/`JSON_OBJECT` constants, 3-/2-argument convenience constructors delegating with `NONE`) — do not ship the key without the options fields, or `temperature`/`max_tokens`/`top_p`/`response_format` changes silently read stale answers.

`OpenAiCompatibleChatModel.cacheIdentity()` returns `"openai-compatible:" + configuredModel + "@" + baseUrl`. The static `cacheId(String role, String identity, ChatRequest request)` now takes the identity as well; the 2-argument form is retired — its production call sites in `KnowledgeExtractor` compute the exact keys the deletion path removes later:

```java
cacheIds.add(CachedChatModel.cacheId("extract", chatModel.cacheIdentity(), request));      // :242
cacheIds.add(CachedChatModel.cacheId("extract", chatModel.cacheIdentity(), gleanRequest)); // :257
```

These ids flow into `ExtractionRun.cacheIds` and are deleted by `DeletionPipeline` (`cacheStore.delete(cacheIds)`, `DeletionPipeline.java:562-564`), so a key-format change that misses these call sites leaves orphaned extraction cache entries instead of failing loudly — update them in this step and add a test asserting `CachedChatModel.cacheId(role, identity, request)` equals the key the wrapped `CachedChatModel` actually reads/writes for the same request.

**Why a content hash is equivalent to upstream's parameter list.** Upstream folds an explicit list into `compute_args_hash` — policy version, mode, query, response type, `top_k`/`chunk_top_k`/`max_entity_tokens`/`max_relation_tokens`/`max_total_tokens`, both keyword lists, the composed `user_prompt`, `enable_rerank`, `enable_content_headings`, `related_chunk_number`, `kg_chunk_pick_method`, the system prompt and the LLM identity (`operate.py:4864-4892`). Every one of those components acts on the answer *only through the rendered request*: the retrieval-side ones (`top_k`, selection knobs, rerank, headings) because they decide which entities/relations/chunks end up in the context, the prompt-side ones because they are literally prompt text — and the Java key hashes that rendered request, so it captures all of them **by construction**, plus the `[n]` reference numbering Task 12 embeds. The LLM identity is not in the request text, hence the explicit `identity` slot. Upstream itself refuses to split the key on non-prompt-affecting knobs (`disable_user_prompt_prefix` is "deliberately NOT a separate key component", `operate.py:4874-4877`), which is the same rule: two requests that render byte-identical prompts under the same model may share an answer.

The exception — and the one place a hash-only key silently under-splits — is anything that changes the **provider's** result without changing prompt text: `temperature`, `max_tokens`, `top_p`, `response_format`. These are serialized into the canonical string explicitly (Step 3). Flags that merely select between prompt variants are already covered because the rendered prompts differ: the two-stage synthesis path (`PathAwareAnswerSynthesizer.shouldUseTwoStage`, `QueryEngine.java:542-543`) issues two requests with different rendered system prompts (`QueryEngine.java:337-347`), so each stage hashes to its own key. Any future knob that alters the payload rather than the text must be added to the canonical string with a test like `requestOptionsChangeTheCacheKeyWithoutChangingThePrompt`.

**Platform note:** the `CachedChatModel` constructor signature must remain `(String role, ChatModel delegate, LlmCacheStore cacheStore)` — the SDK's own `LightRag.cachedModel` helper constructs exactly that (`lightrag-core: api/LightRag.java:1077-1078`, called at `:1063-1064` for query+keyword), over the storage provider's real `llmCacheStore()`. The platform must override `cacheIdentity()` in `LightRagPlatformChatModel` for the identity slot to be meaningful, and should expect the one-time `v2:` invalidation (`llmCacheStore().drop()`, already exposed as `LightRag.clearCache(workspaceId)` at `api/LightRag.java:506-512`, can reclaim the old key space).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightrag.model.*" --tests "io.github.lightrag.api.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add lightrag-core/src/main/java/io/github/lightrag/model/ChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/CachedChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/model/openai/OpenAiCompatibleChatModel.java \
        lightrag-core/src/main/java/io/github/lightrag/indexing/KnowledgeExtractor.java \
        lightrag-core/src/test/java/io/github/lightrag/model/CachedChatModelTest.java
git commit -m "feat: align answer-cache keys with policy version and model identity"
```

---

## Phase 6 — Verification

### Task 15: Full-suite verification and evaluation run

**Files:**
- Create: `docs/superpowers/specs/2026-09-28-query-parity-verification.md`

- [ ] **Step 1: Run the full build**

Run: `./gradlew build`
Expected: PASS (Spring Boot demo included). If Testcontainers-backed tests need Docker, record which ones were skipped and why.

- [ ] **Step 2: Run the RAGAS batch evaluation on the sample dataset**

Run: `./gradlew :lightrag-core:runRagasBatchEval`
Record the before/after summary metrics from `evaluation/ragas` results for the sample dataset, plus the query settings used (`topK=40`, `chunkTopK=20`, `relatedChunkNumber=5`, `chunkPickMethod=VECTOR`).

- [ ] **Step 3: Write the verification note**

`docs/superpowers/specs/2026-09-28-query-parity-verification.md` contains: commands run, pass/skip counts, evaluation deltas, the behavior-change table from this plan with a "verified by" column (test name or eval run), and every divergence that remains open (OneShotRetrievalStore selection bypass, JSON entity/relation context lines, truncation markers, production rerank providers).

- [ ] **Step 4: Update README**

Document the new defaults, the three new `QueryRequest` knobs (`disableUserPromptPrefix`, `relatedChunkNumber`, `chunkPickMethod`) and the four new `LightRagBuilder` options (`userPromptPrefix`, `failResponse`, `tokenCounter`, `rerankFailureMode`), including the `FAIL_FAST` default note.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-09-28-query-parity-verification.md README.md
git commit -m "docs: record query parity verification results"
```

---

## Self-Review

**Spec coverage (report §3 bullets → tasks):**

| Report §3 item | Task |
|---|---|
| Empty-retrieval `fail_response` | 3 |
| Keyword extraction (prompt/cache/response_format) | out of scope (stated) |
| Short/empty query fallback | 2 (validation; short-literal bypass already exists in `QueryKeywordExtractor`) |
| local retrieval ranking `(degree, weight)` | 8 |
| hybrid/mix round-robin merging | 7 |
| KG→chunk selection family (`related_chunk_number`, WEIGHT/VECTOR, quotas, dedup) | 5, 6 |
| Token budgeting (counter + 200 buffer) | 10 |
| Chunk truncation (two-stage, render-verified) | 11 |
| rerank integration (`top_n`, validation, failure fallback) | 9 |
| Context format & references (`[n]` + Reference Document List) | 12 |
| content_headings | 12 |
| user_prompt_prefix | 4 |
| Query validation | 2 |
| Streaming/result surface (`response_time`, `llm_generated`) | 13 (progress events/NDJSON out of scope) |
| Answer cache | 14 |
| Defaults 40/20 | 1 |

**Placeholder scan:** no `TBD`/`TODO` tokens; every code block is either complete or explicitly marked as a mechanical copy update (positional record copies). Test names are descriptive per AGENTS.md.

**Type consistency:** `KgChunkSelector.Group/Tracked/Selection`, `ChunkVectorRanker.rank`, `ChunkMerges.roundRobin*`, `TokenCounter.countTokens`, `ChunkBudgetTruncator.truncate`, `RerankModel.RerankRequest.topN`, `RerankFailureMode`, `QueryResult.responseTime/llmGenerated` are used with the same shapes in every task that references them. New `QueryRequest` components are appended after `metadataConditions` in Tasks 4 and 5, and both positional copy sites (`QueryEngine.expandChunkRequest`, `QueryKeywordExtractor.copyWithKeywords`) plus the convenience constructors are called out each time.

**Known sequencing constraints:** Task 4/5/13 all touch `QueryRequest`/`QueryResult` records — implement in order to avoid rebasing record arity changes; Task 12 must land before Task 11 if they are implemented in the same sitting, because the truncator measures through the renderer that Task 12 finalizes; Task 10’s `QueryBudgeting` conversion touches every strategy constructor, so do it before Tasks 11-12 to avoid double edits.

**Platform calibration:** the per-task verdicts and companion changes live in the Platform calibration section; the binding constraints are the legacy-constructor compatibility (Tasks 4/5/9/13/14) and the platform-side changes shipped with the 0.24.0 bump (query guard, cache identity, eval re-baseline).

---

## Questions for Codex (review round 1)

Answer each question with `file:line` evidence from the actual sources (this repository, `D:\ai-code\LightRAG`, and for platform claims `D:\ai-code\aiplatform`). Round verdict format: per-question answer, then a `must-fix` / `should-fix` / `nit` list for this plan.

1. **Constructor compatibility (Tasks 4/5/13).** Verify that (a) aiplatform's only positional `new QueryRequest(...)` is the 23-argument rebuild in `LightRagRetrievalEngine.disableSdkRerank` (called at `:240` when platform rerank is on); (b) after the component appends, every SDK-internal positional copy site is covered by the plan (`QueryEngine.expandChunkRequest`, `QueryKeywordExtractor.copyWithKeywords`, the explicit 21/6/8-argument convenience constructors, `Builder`) — nothing else calls a canonical constructor positionally; (c) aiplatform tests construct `QueryResult` with 3 arguments and `QueryResult.Context` with 4, never the 5-argument canonical form or `QueryResult.streaming(...)`; (d) the legacy-constructor shape proposed here keeps all of the above compiling.
2. **Upstream fidelity** (spot-check against `D:\ai-code\LightRAG`): Task 2 weight table and bypass rule; Task 5/6 `_find_related_text_unit_from_entities` dedup ordering, vector-quota formula, WEIGHT polling (`min_related_chunks=1`), VECTOR fallback triggers; Task 7 round-robin merge; Task 8 `(degree, weight)` sort with degree = incident count of both endpoints; Task 9 `top_n=chunk_top_k`, rerank-result validation, failure keeping original order; Task 10 heuristic token counting plus the 200-token buffer provenance; Task 11 `_truncate_chunks_for_unified_context` (is binary search + render verification faithful? characters vs tokens?); Task 12 prompt chunk-line format and `Reference Document List` (separators, field order, empty fields, heading metadata keys); Task 14 cache key version/identity/history-bypass basis.
3. **Call-site coverage.** Does the plan miss any engine path that must change: `queryStructured`, streaming (`queryStream`), BYPASS, `onlyNeedContext`/`onlyNeedPrompt`, `OneShotRetrievalStore` fast paths, keyword-extractor short-query bypass, demo/starter modules? Is Task 3's short-circuit placement correct relative to those branches?
4. **Platform calibration claims** (written 2026-10-01): facts 1-7 and the per-task verdicts — verify each against `D:\ai-code\aiplatform`.
5. **Cross-plan consistency** with `2026-09-30-java-lightrag-build-side-and-engineering-alignment-plan.md`: shared `TokenCounter`/`HeuristicTokenCounter` ownership, `CachedChatModel` interplay (Task 14 here vs Task 11 there), and implementation sequencing.

---

## Review round 1 dispositions (Codex, 2026-10-01)

Review artifact: local Codex CLI run over both plans (`review-round1.md`); round verdict was "query plan not ready to start" with 8 must-fix items. Every claim below was re-verified against the sources before acting on it. Adjudication:

| # | Finding | Disposition |
|---|---|---|
| M1 | Platform calibration stale (claims 0.23.0 + forked SDK classes) | **Accepted — fixed.** Calibration section rewritten: platform is mid-upgrade in its working tree (`0.24.0-SNAPSHOT`, forks staged-deleted, `CancellationCheckpoint`/`maxConcurrentDocumentTasks` adopted); per-task verdicts and fact 3/4/6 citations recalibrated. |
| M2 | Task 8 degree code returns map size (1), not incident count | **Accepted — fixed.** Step 3 now uses one batched `findRelations(endpointIds)` and reads `List.size()` per entity id. |
| M3 | Task 11 binary-search trim is not upstream's algorithm | **Accepted — fixed (stage-1 deviation documented in round 2).** Task 11 rewritten to the upstream two-stage drop semantics (stage-1 prefix seed + stage-2 render-verified shrink, whole chunks only); the UTF-16 `substring` surrogate hazard disappears with the trim path. Round 2 noted the stage-1 seed is a greedy per-chunk prefix rather than `truncate_list_by_token_size`; that is now recorded as an explicit Java-only deviation with tokenizer boundary tests (see round 2 dispositions). |
| M4 | Heading per-level cap is 80, not 128 | **Accepted — fixed.** Task 12 Step 3 now cites `DEFAULT_HEADING_LEVEL_MAX_CHARS` (`constants.py:47`, `chunk_schema.py:148-171`) and the `→`/elision rules. |
| M5 | Ranker appends unmatched candidates, suppressing the WEIGHT fallback | **Accepted — fixed.** The ranker returns ranked matches only; empty results and thrown failures reach `KgChunkSelector` so `operate.py:6473-6478` fallback applies. Partial coverage remains a documented Java-only divergence. |
| M6 | Cache key lacks the upstream retrieval-parameter set | **Partially accepted — documented, then completed in round 2.** The key stays a content hash of the rendered request plus an explicit identity slot; Task 14 carries the equivalence argument and its precondition (anything that changes what the model is asked must be inside the hashed request), anchored to upstream's own refusal to split on non-prompt-affecting knobs (`operate.py:4874-4881`). Round 2 correctly pointed out that `temperature`/`max_tokens`/`top_p`/`response_format` change provider behavior *without* changing prompt text — these are now serialized into the canonical key string with a per-option test (see round 2 dispositions). |
| M7 | `QueryRequest` constructor plan is sound but its platform evidence is void | **Accepted — fixed.** The compile constraint is restated as a direct SDK-API constraint now that the fork is gone (fact 3); the single positional caller (`LightRagRetrievalEngine:476-501`) remains verified in the platform working tree. |
| M8 | Task 3's only-context behavior diverges from upstream | **Partially refuted — refined in round 2.** The ordering claim stands: upstream bails out with `None` on empty context *before* the `only_need_context` branch (`operate.py:4786-4788` vs `:4791`) and the wrapper converts that to `fail_response` with `llm_generated=False` (`lightrag.py:5229-5242`). But round 2 correctly identified that the plan's `isEmptyContext` tested the **final** (post-budget) lists, which misclassifies "retrieved, then budget-truncated to nothing" as a failure; Task 3 now derives the flag from the pre-budget retrieval stage with a mode-dependent rule (see round 2 dispositions). The requested only-context/only-prompt/structured acceptance tests were added in round 1 and extended in round 2. |

Should-fix items were also actioned: Task 10 gained the explicit "heuristic counter is a Java-only approximation of upstream's tokenizer" note; Task 3 gained the fail-path acceptance tests and the streamed-fail `llmGenerated=false` cross-reference to Task 13; the platform short-query consequence stays documented as a companion change (fact 6 + rollout checklist) and must ship with a platform-side test on the real call path. `bypassQuery` is intentionally untouched — it performs no retrieval, so there is no empty-context condition to short-circuit (and upstream has no fail-response branch for it).

Open verification for round 2: the fixes above changed only the plan; Tasks 5/6, 8, 11 and 12 must still be re-verified against upstream when implemented (Task 15's verification note is the checkpoint).

---

## Review round 2 dispositions (Codex, 2026-10-01)

Review artifact: `review-round2.md` (local Codex CLI, read-only sandbox over both plans + sources). Round verdict for this plan: **有条件可开工** — 4 conditions, 3 should-fix, 1 nit. All were actioned; the two contested findings were settled against the upstream source, not against the reviewer's summary.

| # | Finding | Disposition |
|---|---|---|
| C1 (M6) | Cache key still misses `ChatRequestOptions` / `response_format` — same prompt, different provider behavior, one cache entry | **Accepted — fixed.** Task 14 now serializes `temperature`/`maxTokens`/`topP`/`responseFormat` into the canonical key string, adds `requestOptionsChangeTheCacheKeyWithoutChangingThePrompt` (one sub-assertion per option field, plus the all-`NONE` equality case), switches the static `cacheId` to `cacheId(role, identity, request)`, and requires every wrapper to delegate `cacheIdentity()` (`LimitedChatModel` included). The orphaned `KnowledgeExtractor:242,257` call sites were found and updated in the same step — without that, deletion would stop finding extraction cache entries. |
| C2 (M8) | Final-list emptiness is not upstream's `None`: a query retrieved chunks but the budget cut them to zero would wrongly become `fail_response` | **Accepted — fixed.** Task 3 now computes `retrievalEmpty` from the **pre-budget retrieval stage** (mode-dependent: `NAIVE` = chunks only; default for kg modes = entities+relations+chunks empty; MIX's chunk term is `matchedChunks`, Java's approximation of upstream's `chunk_tracking`, so MIX additionally requires it empty — `operate.py:6116-6118`), adds the `budgetExhaustionKeepsTheEmptyContextInsteadOfTheFailResponse` acceptance test, and documents the remaining narrower divergence. Upstream's three-way behavior was verified in source: search-stage `None` (`:6113-6118`) → wrapper `fail_response` (`:4786-4796`, `lightrag.py:5223-5240`); post-truncation empty still renders an empty context (`:6141-6146`, `:6015-6027`) and only-context returns that empty context. |
| C3 (M3) | Task 11 stage 1 is a greedy per-chunk prefix, not upstream's `truncate_list_by_token_size`; must implement or document the deviation | **Accepted — documented as an explicit Java-only deviation** (implementing upstream verbatim would require a tokenizer whose encode/decode are stable under binary slicing; the pluggable `TokenCounter` contract cannot promise that). Task 11 now states the divergence, the direction of the error (Java may keep a **shorter** stage-1 prefix, never a longer unverified one), and why stage 2 makes the result budget-safe either way. Boundary tests added: CJK text through the real renderer projection, JSON-escaped projections, and reference-id renumbering at two-digit ids. |
| C4 (Task 9) | Task 9 prose still said "c1 falls back into original-order tail" while the implementation returns ranked matches only | **Accepted — fixed.** The stale prose was replaced with the concrete assertion (`containsExactly("c2")`), consistent with the round-1 M5 resolution. |

Should-fix / nit: the platform checklist entry for the short-query 500→400 behavior now demands a **real call-chain test** (`LightRagRetrievalEngineTest` or the controller test), not a mapping assertion; the cache test is per-option rather than identity/history-only; Task 11 opens with the explicit Task 12-first sequencing note and its boundary tests list CJK / JSON-escaping / reference-id cases.

Round-2 rebuttals (no change, evidence recorded): none — this plan's round-2 findings were all actionable as written. Round 3 should re-check only that the four condition fixes stayed consistent with the code blocks they touch.

---

## Review round 3 dispositions (Codex, 2026-10-01)

Review artifact: `review-round3.md` (same local Codex CLI run, read-only). Round verdict for this plan: **可开工** — all four round-2 findings adjudicated as fixed (C1 cache key, C2 retrievalEmpty, C3 stage-1 deviation, C4 Task 9 prose), with 3 should-fix items and 2 nits. All three should-fix items are actioned in this revision; both nits are already satisfied and stay as they are.

| Item | Finding | Disposition |
|---|---|---|
| Should-fix 1 | `requestOptionsChangeTheCacheKeyWithoutChangingThePrompt` uses an undeclared `identity` variable in its fixture | **Fixed.** The test now declares `var identity = "openai-compatible:gpt-4o-mini@https://api.example/v1";` before first use (the neighboring `cacheKeyIncludesPolicyVersionAndModelIdentity` still inlines its literal). |
| Should-fix 2 | No NAIVE/MIX/structured coverage for `retrievalEmpty` and the budget-exhaustion path; the round-2 disposition said MIX "additionally requires `chunk_tracking` empty" while the code uses `matchedChunks` | **Fixed.** Added `failResponseMatrixCoversEveryKgMode` (NAIVE/LOCAL/MIX × `query` and `queryStructured` against an empty strategy output → fail response in every mode) and `budgetExhaustionKeepsTheEmptyContextInNaiveAndStructuredToo` (NAIVE and structured keep the empty context after budget exhaustion). The round-2 C2 row wording is corrected to state that MIX's chunk term is `matchedChunks`, the Java approximation of upstream's `chunk_tracking` (`operate.py:6116-6118`), consistent with the Task 3 divergence note. |
| Should-fix 3 | "Budget zero" in the acceptance test is ambiguous — `QueryRequest` forbids `maxTotalTokens <= 0` (`QueryRequest.java:72-80`) | **Fixed.** The test comment now states it explicitly: `maxTotalTokens(1)` is the smallest legal value and the fixed prompt overhead drives the **remaining chunk budget** to 0, so "budget zero" always means the residual chunk budget, never the request knob. |

Nits (no change needed): Task 11 keeps the explicit Task 12-first sequencing note (the render-verified truncation needs Task 12's renderer); the C3 non-monotone-`TokenCounter` dedicated test remains a nice-to-have at implementation time, not a start condition.

**Cross-plan compile check (round 3, same note as the build plan).** Task 14 needs the build plan's `ChatRequestOptions` + 4-component `ChatRequest` (build Task 10), `ChatResponse.finishReason()/usage()` (build Task 11) and `LimitedChatModel.cacheIdentity()` delegation (build Task 13) to compile; implement or port those shapes before Task 14.

---

## Review round 4 dispositions (Codex, 2026-10-01)

Review artifact: `review-round4.md` (same local Codex CLI run, read-only). Round verdict for this plan: **不可开工** — 2 new must-fix, plus 2 should-fix; S1 (`identity` declared before use) and S3 ("budget zero" semantics) are confirmed fixed. Both must-fix items are actioned here.

| # | Finding | Disposition |
|---|---|---|
| S2-a | `failResponseMatrixCoversEveryKgMode` cannot run: the shared `strategiesReturning(...)` helper registers only `LOCAL` (`QueryEngineTest.java:1412-1415`), so NAIVE/MIX abort at the null-strategy guard (`QueryEngine.java:400-403`) before retrieval | **Accepted — fixed, including the same trap in `budgetExhaustionKeepsTheEmptyContextInNaiveAndStructuredToo` (its NAIVE engine also used the LOCAL-only map).** Added the `strategiesReturningAllModes(context)` fixture (registers NAIVE/LOCAL/GLOBAL/HYBRID/MIX; BYPASS short-circuits before the map) and pointed both tests at it, with a comment naming the LOCAL-only helper as the trap. |
| S2-b | The planned fail path returns a `QueryResult` from `queryStructured`, which returns `StructuredQueryResult` (`QueryEngine.java:312-334`) — a compile-level contradiction | **Accepted — fixed.** `failStructuredResult(QueryReferences.Result)` is now specified as its own helper (no streaming branch, no `QueryRequest` parameter, empty entity/relation/chunk lists, `llmGenerated=false` left as a Task 13 breadcrumb) with the exact insertion point in `queryStructured`. |

Should-fix folded in: the matrix now also asserts the answer model is never called (`RecordingChatModel.callCount()` is zero for both `query` and `queryStructured`) and that the structured fail result carries empty `contexts()`/`references()`/`entities()`/`relations()`/`chunks()`; GLOBAL and HYBRID were added to the matrix so every routed mode is covered, not only the two the round-2 note named (production wires all five: `LightRag.newQueryEngine:1057-1061`). The cross-plan Task 14 dependency note, the platform real call-chain test requirement, and Task 11's Task 12-first sequencing note are unchanged; the non-monotone `TokenCounter` dedicated test stays an implementation-time enhancement, not a start condition.

---

## Review round 5 dispositions (Codex, 2026-10-01)

Review artifact: `review-round5.md` (same local Codex CLI run, read-only). Round verdict for this plan: **有条件可开工** — both conditions are already plan invariants, and the three should-fix/nit items are actioned below.

Conditions re-checked, no plan change needed (evidence recorded):

1. **Build-side Task 10 lands first** — already stated twice: the cross-plan compile check (`Task 14 needs the build plan's ChatRequestOptions + 4-component ChatRequest (build Task 10), ChatResponse.finishReason()/usage() (build Task 11) and LimitedChatModel.cacheIdentity() delegation (build Task 13)`) and Task 14's own dependency note. Nothing here duplicates those shapes.
2. **`retrievalEmpty` comes from the retrieval stage, never from the post-budget lists** — already the Task 3 spec ("The flag must come from the retrieval stage, never from the final (post-budget) lists", with the `budgetExhaustion*` tests locking the budget-empty case in as a non-failure).

| Item | Finding | Disposition |
|---|---|---|
| should-fix | The five-mode matrix does not cover the `multiHopStrategy` route (`QueryEngine.java:399-400` picks it before the map) | **Accepted — fixed.** Added `emptyRetrievalUnderTheMultiHopRouteFailsToo` after the matrix: a stub classifier returning `QueryIntent.MULTI_HOP`, the 9-arg engine overload (`QueryEngine.java:215-228`, with `PathAwareAnswerSynthesizer`), empty retrieval from the multi-hop strategy → `DEFAULT_FAIL_RESPONSE` with a zero-call answer model. |
| should-fix | `failStructuredResult`'s `llmGenerated=false` is only a breadcrumb — Task 13 must realize it on the real constructor and assert it | **Accepted — fixed.** Task 13 now appends `boolean llmGenerated` to `StructuredQueryResult` (7th component; 6-arg convenience constructor kept so both `QueryEngine` call sites and existing tests compile), specifies the flag at `queryStructured`/`bypassStructuredQuery` (false for previews/fail, true for generated answers), and adds `structuredFailResponseIsNotLlmGenerated` asserting the fail path reports false. |
| nit | Keep BYPASS as a separate test rather than folding it into the matrix | **Accepted — recorded.** The matrix comment now states BYPASS is deliberately not a row (it never retrieves and never consults the strategy map, `QueryEngine.java:280-282`); its coverage stays the separate `bypassPathOnlyRejectsEmpty` (Task 2). |

Round-5 rebuttals: none — all points were actionable as written. Round 6 should only verify the three edits above against the code anchors they cite.

---

## Review round 6 dispositions (Codex, 2026-10-01)

Review artifact: `review-round6.md` (same local Codex CLI run, read-only). Round verdict for this plan: **有条件可开工** — 2 conditions (both about test snippets compiling and proving what they claim) plus 2 should-fix. All are actioned here; the round-5 items (multi-hop route anchored on the 9-arg overload, `StructuredQueryResult.llmGenerated` spec, BYPASS non-row, both start conditions) were confirmed correct by this round's independent re-check.

| # | Finding | Disposition |
|---|---|---|
| Condition 1 | `emptyRetrievalUnderTheMultiHopRouteFailsToo` created `var model` but passed a *different* `new RecordingChatModel()` into the engine, making `model.callCount() == 0` vacuous; and `new PathAwareAnswerSynthesizer()` lacked the import existing tests avoid by using the FQN | **Accepted — fixed.** The snippet now passes `model` itself (`new QueryEngine(model, …)`, comment marks it as the same instance the assertion counts) and constructs `new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()` with the FQN exactly as the existing tests do (`QueryEngineTest.java:1181`). Both verified in source this round. |
| Condition 2 | Task 13's structured test snippet used a bare `engine`; the test class has no such field, and the fail case needs an engine that actually distinguishes empty retrieval from normal retrieval | **Accepted — fixed.** All three Task 13 Step 1 snippets now declare local 6-arg fixtures: `strategiesReturning(baseContext())` for the context/preview/answer branches and `strategiesReturning(retrievalReturnsNothing())` for the fail path; `marksContextOnlyAndPreviewResultsAsNotLlmGenerated` additionally pins `fail.answer() == DEFAULT_FAIL_RESPONSE` so the empty-retrieval path is provable, and the comment records that the class has no shared `engine` field (`QueryEngineTest.java:30-40`). |
| Should-fix | Stale Java line refs: the `strategiesReturningAllModes` doc and the Task 3 sketch still cited `QueryEngine.java:314-316` for BYPASS; the structured-construction ref was off by one | **Accepted — fixed.** BYPASS now cites `:280-282` (the `query()` branch) in the helper doc and `:317-319` (the `queryStructured()` branch) in the sketch; the unchanged-arguments ref is `:321-334`. Every other `QueryEngine.java:…`/`QueryBudgeting.java:…` ref in this plan was re-verified line-by-line this round and matches. |
| Should-fix | Keep BYPASS as a separate test | **Accepted — already in place** (round 5); the matrix comment carries the rationale and this round confirmed it. |

---

## Review round 7 dispositions (Codex, 2026-10-01)

Review artifact: `review-round7.md` (same local Codex CLI run, read-only; incremental re-check). Round verdict for this plan: **可开工** — every item confirmed: the multi-hop snippet passes the same `model` into the 9-arg overload and uses the FQN synthesizer exactly as the existing tests do (`QueryEngineTest.java:1172-1182`); the Task 13 fixtures and helper anchors (`:551-565`, `QueryEngineTest.java:1412-1420`) hold; all `QueryEngine` / `QueryBudgeting` line refs match, including the two BYPASS branches (`:280-282`, `:317-319`) and the structured construction (`:321-334`); the BYPASS separate-test note is retained. No changes required in this round; the build plan's two round-7 documentation residues do not touch this plan.
