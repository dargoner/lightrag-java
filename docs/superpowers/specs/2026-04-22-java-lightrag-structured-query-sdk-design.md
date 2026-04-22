# Java LightRAG Structured Query SDK Design

**Date:** 2026-04-22

**Status:** Approved for planning

## Goal

Add a stable public SDK query surface that returns structured retrieval results alongside the final answer, so callers can directly consume entities, relations, and chunks without depending on internal query engine types.

## Context

The current Java SDK exposes `LightRag.query(String workspaceId, QueryRequest request)` and returns `QueryResult`, which contains:

- `answer`
- `contexts`
- `references`

Internally, the query pipeline already produces rich retrieval state:

- matched entities with scores
- matched relations with scores
- matched chunks with scores

That retrieval state currently stays inside internal query engine types such as `QueryContext`, `ScoredEntity`, `ScoredRelation`, and `ScoredChunk`.

Upstream LightRAG exposes API routes that support more structured inspection of retrieval outputs. For the Java SDK, the right adaptation is not to expose internal engine types directly, but to add a stable, typed SDK result model that future REST or frontend layers can reuse.

## Non-Goals

This design explicitly does not include:

- changing the existing `LightRag.query(...)` return type
- exposing internal `QueryContext` as public SDK API
- adding REST controllers or HTTP response models
- adding streaming structured query results in the first version
- changing vector store schemas or query storage contracts
- changing retrieval logic, reranking logic, or multi-hop behavior
- adding path-level structured output in the first version

## Recommended Approach

Introduce a new public SDK method:

```java
LightRag.queryStructured(String workspaceId, QueryRequest request)
```

This method returns a new public result type:

- `StructuredQueryResult`

That result contains:

- `answer`
- `contexts`
- `references`
- `entities`
- `relations`
- `chunks`

The result objects are public API records and are populated by mapping internal scored retrieval objects into stable SDK-facing models.

This approach is preferred because it:

- preserves backward compatibility for existing `query(...)` callers
- avoids leaking internal retrieval engine data structures
- exposes graph-aware fields such as `entity.name` and `relation.srcId/tgtId/filePath`
- creates a reusable SDK contract for future REST or frontend integration

## Public API Design

### New SDK Entry Point

Add a new public method on `LightRag`:

```java
public StructuredQueryResult queryStructured(String workspaceId, QueryRequest request)
```

Behavior:

- resolves workspace scope exactly like `query(...)`
- delegates to `QueryEngine`
- reuses current query behavior and retrieval flow
- returns a structured result with both final answer data and retrieval details
- rejects `QueryRequest.stream() == true` in the first version with a clear `IllegalArgumentException`

### New Public Result Models

#### StructuredQueryResult

Fields:

- `String answer`
- `List<QueryResult.Context> contexts`
- `List<QueryResult.Reference> references`
- `List<StructuredQueryEntity> entities`
- `List<StructuredQueryRelation> relations`
- `List<StructuredQueryChunk> chunks`

Rationale:

- keeps the existing answer-oriented experience
- avoids forcing callers to invoke two separate methods
- preserves current context/reference semantics
- intentionally reuses `QueryResult.Context` and `QueryResult.Reference` instead of duplicating equivalent public SDK types in the first version

#### StructuredQueryEntity

Fields:

- `String id`
- `String name`
- `String type`
- `String description`
- `List<String> aliases`
- `List<String> sourceChunkIds`
- `double score`

Rationale:

- exposes the public entity identity directly
- makes entity name available to callers without requiring vector payload inspection
- carries score for inspection and ranking-sensitive UI use cases

#### StructuredQueryRelation

Fields:

- `String id`
- `String srcId`
- `String tgtId`
- `String keywords`
- `String description`
- `double weight`
- `List<String> sourceChunkIds`
- `String filePath`
- `double score`

Rationale:

- keeps relation graph identity explicit
- surfaces `srcId` and `tgtId` directly for graph rendering and debugging
- preserves `filePath` because the current domain model already treats it as source provenance

#### StructuredQueryChunk

Fields:

- `String id`
- `String documentId`
- `String text`
- `int tokenCount`
- `int order`
- `Map<String, String> metadata`
- `double score`

Rationale:

- exposes chunk-level retrieval results directly
- makes chunk source document and metadata accessible to SDK callers
- preserves ranking score for debugging and display ordering

## Internal Mapping Design

The new public API must not expose `QueryContext` directly.

Instead, the implementation maps internal retrieval objects to SDK-facing models:

- `ScoredEntity -> StructuredQueryEntity`
- `ScoredRelation -> StructuredQueryRelation`
- `ScoredChunk -> StructuredQueryChunk`

The mapping happens after the query engine has completed retrieval, chunk limiting, context assembly, and answer generation decisions.

This preserves the existing query flow while adding a stable serialization boundary around the public result.

The first version deliberately keeps `contexts` and `references` aligned with the existing `QueryResult` public model because:

- they are already public SDK types
- their semantics already match the desired structured query output
- duplicating them would add API surface without adding new caller value

The new API boundary is therefore created around entities, relations, and chunks, which are the missing structured retrieval payloads today.

## Query Flow

The new structured query call should reuse the current query engine pipeline:

1. Resolve query request options
2. Run keyword extraction if enabled
3. Execute retrieval strategy
4. Apply rerank and chunk budgeting
5. Assemble context
6. Produce answer, or prompt, or context-only response according to current flags
7. Build references from chunks
8. Map matched entities, relations, and chunks to public structured models
9. Return `StructuredQueryResult`

No retrieval behavior changes are required.

Streaming rule for the first version:

- if `QueryRequest.stream()` is `true`, `queryStructured(...)` must fail fast with a clear `IllegalArgumentException`
- non-streaming query modes continue to follow existing behavior

## Ordering Rules

The first version should preserve the current internal ordering of retrieval results.

Rules:

- entities preserve the current `matchedEntities` order
- relations preserve the current `matchedRelations` order
- chunks preserve the current `matchedChunks` order
- no additional re-sorting is introduced in the structured result layer

This avoids introducing subtle behavior changes and ensures the structured result is a faithful public view of the existing retrieval pipeline.

## Compatibility

Backward compatibility requirements:

- existing `LightRag.query(...)` callers continue to work unchanged
- no existing public models are modified incompatibly
- no storage provider contracts are changed
- no vector schema changes are required

This is an additive SDK feature.

## Testing Strategy

The implementation should be covered with targeted tests in three categories.

### 1. Structured Mapping Coverage

Verify that `queryStructured(...)` exposes:

- entity `name`, `type`, `description`, `aliases`, `score`
- relation `srcId`, `tgtId`, `filePath`, `score`
- chunk `documentId`, `text`, `metadata`, `score`

### 2. Behavioral Compatibility

Verify that existing `query(...)` behavior remains unchanged and existing query tests still pass.

### 3. Ordering Stability

Verify that entities, relations, and chunks in `StructuredQueryResult` appear in the same order as the internal retrieval output used to produce the answer.

### 4. Query Flag Semantics

Verify explicit structured-query behavior for current query flags:

- `onlyNeedContext=true` returns assembled context in `answer` and still includes structured entities, relations, and chunks
- `onlyNeedPrompt=true` returns rendered prompt in `answer` and still includes structured entities, relations, and chunks
- `includeReferences=true` includes references exactly as current `query(...)` does
- `includeReferences=false` preserves current reference behavior for non-reference queries
- `stream=true` is rejected with a clear exception in the first version

## File Plan

Expected implementation touch points:

- Modify: `lightrag-core/src/main/java/io/github/lightrag/api/LightRag.java`
- Modify: `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryResult.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryEntity.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryRelation.java`
- Create: `lightrag-core/src/main/java/io/github/lightrag/api/StructuredQueryChunk.java`

Expected test touch points:

- Modify or create query engine and public API tests that exercise `queryStructured(...)`

## Why This Is Better Than Exposing QueryContext

Exposing `QueryContext` directly would create a long-term API coupling between public SDK consumers and internal retrieval engine implementation details.

That would make later changes to:

- multi-hop retrieval
- rerank stages
- context assembly boundaries
- internal scored model shape

harder to evolve safely.

`StructuredQueryResult` creates a stable public API boundary while still exposing the retrieval information that SDK consumers actually need.

## Future Extensions

This design intentionally leaves room for future additions without blocking the first version:

- path or multi-hop trace output
- REST layer reuse of the same models
- frontend graph explorer integration
- richer chunk provenance views

Those should be layered onto the structured result model later rather than delaying the first public SDK interface.
