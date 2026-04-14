# Java LightRAG Query Metadata Filtering Design

## Overview

This document defines query-time metadata filtering for the Java LightRAG SDK.

The current Java SDK does not expose metadata-aware query controls on `QueryRequest`. Callers can retrieve contexts, references, and prompt-only outputs, but cannot constrain results by business metadata such as region, document type, score, or publish date.

This phase adds two caller-visible query controls:

- `metadataFilters`
- `metadataConditions`

The design keeps platform-compatible filter semantics, centralizes metadata matching in the query layer, and uses a hybrid execution model:

- apply filters as early as practical before final candidate selection
- push down simple filters when the current query path can do so safely
- always keep a unified post-filter matcher as the correctness backstop

## Goals

- Add `metadataFilters` to `QueryRequest`.
- Add `metadataConditions` to `QueryRequest`.
- Keep `metadataFilters` compatible with the platform semantics:
  - field-level `OR`
  - cross-field `AND`
- Support `metadataConditions` operators:
  - `EQ`
  - `IN`
  - `GT`
  - `GTE`
  - `LT`
  - `LTE`
  - `BEFORE`
  - `AFTER`
- Apply a single, shared metadata matching model across `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, `MIX`, and `MULTI_HOP`.
- Filter as early as practical before final context assembly.
- Preserve correctness with a shared post-filter fallback when full pushdown is unavailable.
- Preserve existing query behavior when no metadata filter is provided.

## Non-Goals

- Do not add new string operators such as `CONTAINS`, `STARTS_WITH`, or regex matching in this phase.
- Do not add case-insensitive metadata matching by default.
- Do not redesign storage adapters around a new universal native filter API in this phase.
- Do not add separate metadata models for entities or relations.
- Do not make multi-hop path expansion itself metadata-aware in this phase.
- Do not require storage-specific schema registration before callers can use simple equality filters.

## Problem Statement

`QueryRequest` currently exposes retrieval controls such as `topK`, `chunkTopK`, `includeReferences`, `onlyNeedContext`, `onlyNeedPrompt`, keyword overrides, and rerank flags, but no metadata filtering.

At the same time, `ChunkStore.ChunkRecord` already carries chunk metadata as `Map<String, String>`, which means the SDK has enough information to perform metadata-aware filtering in the query layer without redesigning ingestion.

The gap is that filtering semantics, validation, normalization, and execution planning do not exist yet.

## Architectural Options Considered

### Option 1: Post-filter only in each query strategy

Pros:

- smallest implementation delta
- no planner or shared internal filter model required

Cons:

- duplicates semantics across strategies
- makes `NAIVE`, `LOCAL`, `GLOBAL`, `HYBRID`, and `MIX` more likely to drift
- does not satisfy the requirement to prefer earlier filtering
- keeps optimization paths hard to evolve later

### Option 2: Shared metadata filter model with early application and post-filter fallback

Pros:

- one semantics definition for all query modes
- supports the desired execution policy: early when possible, fallback when not
- keeps API stable while allowing later storage-specific pushdown improvements
- minimizes behavioral divergence across strategies

Cons:

- adds internal planner and matcher components
- first phase still relies partly on query-layer filtering instead of full native pushdown

### Option 3: Storage-first native pushdown redesign

Pros:

- strongest long-term performance profile
- closest to pure pre-retrieval filtering

Cons:

- much larger refactor
- requires immediate storage interface expansion across adapters
- makes this feature larger than necessary for the current phase

## Recommendation

Adopt Option 2.

This phase should introduce a shared query-layer metadata filter model, normalize the public API into strong internal structures, apply filters before final candidate selection where practical, and keep a unified matcher as the final correctness guard.

That gives the SDK a stable public API and stable semantics now, while preserving the ability to add deeper pushdown later without changing callers again.

## API Shape

### QueryRequest

Extend `QueryRequest` with:

- `Map<String, List<String>> metadataFilters`
- `List<MetadataCondition> metadataConditions`

Recommended defaults:

- `metadataFilters = Map.of()`
- `metadataConditions = List.of()`

Compatibility requirements:

- preserve existing builder defaults when the new fields are not set
- preserve existing delegating constructors by defaulting the new fields to empty values
- preserve existing behavior for all callers that do not opt in to metadata filtering

Builder additions:

- `Builder metadataFilters(Map<String, ?> filters)`
- `Builder metadataConditions(List<MetadataCondition> conditions)`

The builder should accept a permissive `Map<String, ?>` for caller convenience, then normalize into the record's canonical `Map<String, List<String>>` shape before construction.

### MetadataCondition

Add a new public record:

```java
public record MetadataCondition(
    String field,
    MetadataOperator operator,
    Object value
) {}
```

### MetadataOperator

Add a new public enum:

```java
public enum MetadataOperator {
    EQ,
    IN,
    GT,
    GTE,
    LT,
    LTE,
    BEFORE,
    AFTER
}
```

Using an enum instead of a free-form string keeps the SDK API safer and reduces invalid runtime combinations.

## Public Semantics

### metadataFilters

`metadataFilters` is the convenience syntax and must remain compatible with the platform semantics.

Rules:

- fields combine with logical `AND`
- values within the same field combine with logical `OR`
- scalar values are normalized to a single-element value list
- empty values are discarded
- duplicate values are removed while preserving encounter order

Example:

```json
{
  "region": ["shanghai", "beijing"],
  "docType": "policy"
}
```

Semantic meaning:

```text
(region = "shanghai" OR region = "beijing")
AND docType = "policy"
```

### metadataConditions

`metadataConditions` is the structured syntax for comparison operations.

Operator meanings:

- `EQ`: metadata value equals the condition value
- `IN`: metadata value equals any provided condition value
- `GT`: metadata value is greater than the condition value
- `GTE`: metadata value is greater than or equal to the condition value
- `LT`: metadata value is less than the condition value
- `LTE`: metadata value is less than or equal to the condition value
- `BEFORE`: metadata timestamp/date occurs before the condition value
- `AFTER`: metadata timestamp/date occurs after the condition value

### Combined semantics

If both `metadataFilters` and `metadataConditions` are present, both apply.

Combination rule:

```text
metadataFilters AND metadataConditions
```

No special conflict resolution is required. A record must satisfy every normalized filter family and every normalized condition.

## Validation and Normalization

Introduce a shared normalization step before query execution.

Recommended internal components:

- `MetadataFilterNormalizer`
- `MetadataMatcher`
- `MetadataPushdownPlanner`

### Field normalization

Field names must:

- be non-null
- be non-blank after trimming
- match `[A-Za-z0-9_]+`

Invalid field names should fail request construction with `IllegalArgumentException`.

### metadataFilters normalization

Normalization rules:

- `null` map becomes empty
- blank keys are rejected
- scalar values become single-element lists
- collection values are normalized element by element
- `null` elements are removed
- blank string elements are removed
- remaining values are converted to trimmed strings

If a field becomes empty after normalization, that field is omitted.

### metadataConditions normalization

Normalization rules:

- `field` must pass the same field validation rules
- `operator` must be non-null
- `value` must be structurally compatible with the operator

Operator-specific validation:

- `EQ`
  - accepts any non-null scalar
- `IN`
  - requires a non-empty collection of non-null scalar values
- `GT`, `GTE`, `LT`, `LTE`
  - require a scalar value parseable as a number
- `BEFORE`, `AFTER`
  - require a scalar value parseable as a supported date/time literal

Invalid conditions should fail request construction with `IllegalArgumentException`.

## Metadata Type Rules

`ChunkStore.ChunkRecord` metadata is currently `Map<String, String>`, so all stored values are string-backed at query time.

This phase should therefore use a deterministic query-layer type system rather than relying on storage-native typing.

### String equality

`EQ` and `IN` use normalized string comparison.

Rules:

- trim both actual and expected values
- preserve case sensitivity
- if actual metadata is conceptually multi-valued in the future, match succeeds when any actual element matches

### Numeric comparisons

`GT`, `GTE`, `LT`, and `LTE` use `BigDecimal`.

Rules:

- parse the condition value to `BigDecimal` during normalization
- parse the actual metadata value to `BigDecimal` during matching
- if actual value parsing fails, the record does not match that condition

### Date/time comparisons

`BEFORE` and `AFTER` compare normalized instants.

Supported input formats in this phase:

- `yyyy-MM-dd`
- `yyyy-MM-dd'T'HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ssXXX`
- epoch milliseconds as a numeric string

Recommended parse order:

1. offset datetime
2. local datetime
3. local date
4. epoch milliseconds

If actual value parsing fails, the record does not match that condition.

## Internal Execution Model

The public API normalizes into an internal shared plan object before query execution.

Suggested internal model:

- `MetadataFilterPlan`
  - normalized convenience filters
  - normalized structured conditions
  - pushdown eligibility summary

Suggested normalized condition model:

- `NormalizedMetadataCondition`
  - normalized field
  - operator
  - normalized typed value
  - normalized value kind

The implementation must keep these execution-model properties:

- public API types simple
- internal evaluation strongly normalized
- one shared semantics definition for all query strategies

## Query Execution Semantics

The query execution order should be:

```text
QueryRequest
  -> request normalization
  -> metadata filter plan creation
  -> pushdown planning
  -> strategy retrieval
  -> fallback metadata matching
  -> budgeting
  -> context assembly
  -> final QueryResult
```

### Early application policy

This phase adopts "early where practical" rather than "all filtering must be native pushdown".

There are two acceptable early-filter stages:

1. query-layer pre-selection filtering
   - apply filtering after candidate records are loaded but before final candidate lists are formed
2. storage-native pushdown
   - use deeper adapter-specific pushdown only where already safe and local to the current path

### Fallback policy

Even when some early filtering is applied, the shared `MetadataMatcher` remains the final correctness guard before final context assembly.

This guarantees consistent behavior across query modes and storage backends.

## Query Strategy Integration

### NaiveQueryStrategy

`NaiveQueryStrategy` should:

- retrieve candidate chunk matches
- load chunk records
- apply metadata filtering before final chunk expansion and final chunk selection

### LocalQueryStrategy

`LocalQueryStrategy` should:

- retrieve entity candidates
- derive relation candidates
- derive chunk candidates
- apply metadata filtering to chunks before final chunk budgeting and context assembly

In this phase, entities and relations do not gain their own metadata-aware matching model. The filter contract is satisfied by ensuring returned chunk contexts honor the metadata filters.

### GlobalQueryStrategy

`GlobalQueryStrategy` should:

- retrieve relation candidates
- derive entity and chunk candidates
- apply metadata filtering to chunks before final chunk budgeting and context assembly

### HybridQueryStrategy and MixQueryStrategy

`HYBRID` and `MIX` should not implement independent metadata semantics.

They should compose already-filtered results from the underlying strategies and may apply the shared matcher as a final safeguard before budgeting if needed.

### MultiHopQueryStrategy

This phase should keep multi-hop filtering bounded.

Required guarantee:

- the final returned chunk contexts satisfy the metadata filters

Out of scope for this phase:

- making every intermediate hop expansion metadata-aware
- filtering reasoning paths by metadata at each internal step

## Pushdown Planning

This phase includes a `MetadataPushdownPlanner` even if its first implementation is conservative.

Initial pushdown policy:

- `EQ` and `IN`
  - eligible for early application
  - may be pushed down where the current query path can safely do so
- `GT`, `GTE`, `LT`, `LTE`, `BEFORE`, `AFTER`
  - normalized centrally
  - matched reliably in the shared fallback matcher
  - deeper native pushdown can be added later without changing public API

This keeps phase-one scope controlled while still aligning with the requirement to prefer earlier filtering.

## Error Handling

### Request construction failures

These are caller errors and should fail fast with `IllegalArgumentException`:

- invalid field name
- missing field name
- null operator
- empty `IN` list
- non-collection `IN` value
- non-numeric comparison value for numeric operators
- non-date comparison value for date operators

### Query execution behavior for bad stored metadata

Bad stored metadata must not fail the whole query.

Examples:

- missing metadata field
- numeric comparison against a non-numeric stored value
- date comparison against an unparsable stored value

Execution behavior:

- the affected record simply does not match that clause
- the query continues normally

This rule prevents one malformed chunk metadata value from breaking the entire query.

## Compatibility Notes

`QueryRequest` is a public record, so adding new components changes the canonical record shape.

Compatibility handling in this phase:

- preserve existing delegating constructors
- default new fields to empty values in older construction paths
- preserve builder defaults
- document that record-shape-aware reflection or serialization code may observe the new fields

This matches the repository's existing approach for prior public `QueryRequest` feature additions.

## Testing Strategy

Required coverage is split into five layers.

### 1. QueryRequest API coverage

Test class:

- `QueryRequestTest`

Coverage:

- new fields default to empty collections
- builder accepts `metadataFilters`
- builder accepts `metadataConditions`
- scalar filter values normalize to single-element lists
- invalid field names fail
- invalid condition shapes fail
- existing constructors still default the new fields to empty values

### 2. Metadata normalization coverage

Test class:

- `MetadataFilterNormalizerTest`

Coverage:

- filter key normalization
- filter value trimming and de-duplication
- empty filter cleanup
- `IN` normalization
- numeric condition normalization
- date condition normalization

### 3. Metadata matching coverage

Test class:

- `MetadataMatcherTest`

Coverage:

- `EQ`
- `IN`
- `GT`
- `GTE`
- `LT`
- `LTE`
- `BEFORE`
- `AFTER`
- missing metadata field
- unparsable stored numeric value
- unparsable stored date value
- combined `metadataFilters` and `metadataConditions`

This test class is the semantic center of the feature and should be built first.

### 4. Query strategy coverage

Test classes:

- `NaiveQueryStrategyTest`
- `LocalQueryStrategyTest`
- `GlobalQueryStrategyTest`
- `HybridQueryStrategyTest`

Coverage:

- matching chunks are retained
- non-matching chunks are excluded
- filtering still respects `chunkTopK`
- merged strategies do not reintroduce filtered-out chunks

### 5. Query engine integration coverage

Test class:

- `QueryEngineTest`

Coverage:

- full query execution with `metadataFilters`
- full query execution with `metadataConditions`
- filters do not break `includeReferences`
- filters do not break `onlyNeedContext`
- filters do not conflict with rerank disablement
- empty filtered result produces a stable result shape

## TDD Sequencing Recommendation

Recommended implementation order:

1. `QueryRequestTest`
2. `MetadataFilterNormalizerTest`
3. `MetadataMatcherTest` for `EQ` and `IN`
4. `MetadataMatcherTest` for numeric operators
5. `MetadataMatcherTest` for date operators
6. `NaiveQueryStrategyTest`
7. `LocalQueryStrategyTest`
8. `GlobalQueryStrategyTest`
9. `HybridQueryStrategyTest`
10. `QueryEngineTest`

This order stabilizes semantics before strategy integration.

## Risks and Trade-Offs

- Query-layer filtering can still allow some irrelevant candidates to be retrieved before filtering, especially in phase-one non-native paths.
- `ChunkStore` metadata is string-backed, so typed comparisons necessarily rely on deterministic parsing rather than storage-native types.
- Adding fields to a public record remains a compatibility surface change, even with delegating constructors preserved.

These trade-offs are acceptable for this phase because they keep the public API stable, keep semantics explicit, and avoid a larger storage redesign.

## Acceptance Criteria

- callers can express platform-compatible `metadataFilters`
- callers can express structured `metadataConditions`
- `EQ`, `IN`, `GT`, `GTE`, `LT`, `LTE`, `BEFORE`, and `AFTER` are available
- all query modes use the same metadata semantics
- filtering is applied before final context assembly
- a shared fallback matcher guarantees consistent correctness
- existing callers without metadata filters continue to behave as before
