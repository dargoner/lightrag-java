# Java LightRAG Entity Merge Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: merging multiple existing entities into one target entity.

The upstream LightRAG README exposes `merge_entities(source_entities, target_entity, merge_strategy=None, target_entity_data=None)` as part of manual graph management.

The Java SDK now supports manual entity and relation create/edit flows, but it still lacks upstream entity-merge behavior. This phase adds that capability before larger upstream work such as document-status orchestration.

## Goals

- Add a public Java API for merging multiple entities into one target entity.
- Keep graph storage and vector storage consistent after merge.
- Redirect and deduplicate affected relations as part of the merge.
- Work across current `AtomicStorageProvider` implementations without widening provider SPIs.

## Non-Goals

- Do not add document-status storage or chunk-tracking stores in this phase.
- Do not redesign the current Java relation identity model.
- Do not implement configurable per-field dynamic merge maps exactly like Python.
- Do not introduce asynchronous graph-management APIs in this phase.

## Upstream Behavior To Align

According to the upstream LightRAG README:

- `merge_entities(...)` merges several source entities into one target entity
- source relations are redirected to the target
- duplicate relations are merged
- self-loops created by the merge should be removed
- source entities are deleted after the merge

The Java SDK can align with the visible behavior by capturing a snapshot, computing merged entity and relation records in memory, regenerating affected vectors, and restoring the replacement snapshot.

## Java API Shape

Add one synchronous API to `LightRag`:

- `mergeEntities(MergeEntitiesRequest request)`

Return a stable Java result object:

- `GraphEntity`

The request should be typed rather than map-based:

- `sourceEntityNames`
- `targetEntityName`
- optional `targetType`
- optional `targetDescription`
- optional `targetAliases`

This keeps Java validation explicit while still covering the upstream `target_entity_data` use case.

## Merge Strategy Decision

The upstream Python API exposes a dynamic `merge_strategy`. That is not a good fit for the Java SDK in this phase because:

- it weakens API clarity and type safety
- the current Java graph schema has a fixed, small field set
- a deterministic default merge strategy is enough to match the visible merge behavior

Java should therefore implement one built-in merge policy plus typed target overrides:

- target display name comes from `targetEntityName`
- target type uses explicit override when supplied, otherwise keeps the resolved target type
- target description uses explicit override when supplied, otherwise concatenates distinct non-blank descriptions from target and sources in stable order
- target aliases use explicit override when supplied, otherwise union target aliases, source aliases, and merged source display names with trim, deduplication, and self-name removal
- target `sourceChunkIds` become the stable union of target and source chunk IDs

This preserves useful metadata without adding a Java-only strategy DSL.

## Entity Resolution Rule

Entity lookup must stay deterministic and match current graph-management behavior:

- first check for an exact normalized name match
- if no exact-name match exists, allow alias lookup only when it resolves to exactly one entity
- if alias lookup resolves to multiple entities, fail with an ambiguity error

Additional merge-specific validation:

- `sourceEntityNames` must not be empty
- the resolved source set must be unique after normalization and resolution
- the resolved target must exist
- the target must not also appear in the resolved source set

## Merge Semantics

`mergeEntities(...)` should:

1. resolve the target entity by the deterministic resolution rule
2. resolve all source entities by the same rule
3. reject duplicates in the resolved source set
4. reject when target is included among the sources
5. compute one replacement target entity record
6. rewrite every relation touching a source entity to use the target entity ID
7. drop any relation that becomes a self-loop after rewriting
8. merge duplicate rewritten relations by relation ID
9. delete all source entities
10. regenerate the target entity vector
11. regenerate vectors for all surviving affected relations
12. restore the replacement snapshot

Entity merge does not rewrite documents, chunks, or chunk vectors.

## Relation Merge Semantics

Because Java relation identity is `sourceEntityId + normalizedRelationType + targetEntityId`, redirecting source relations can create duplicate relation IDs.

When multiple rewritten relations collapse to the same final relation ID:

- keep one merged relation record
- relation type is the shared normalized type implied by the ID
- description becomes the stable concatenation of distinct non-blank descriptions
- weight becomes the maximum finite weight among the collapsed relations
- `sourceChunkIds` become the stable union of all collapsed relations

If a rewritten relation becomes `target -> target`, drop it instead of keeping a self-loop.

## Failure And Concurrency Semantics

Entity merge is a rewrite operation, not a pure append. It should therefore use the same snapshot capture + restore model as current edit and delete flows.

Like current edit/delete alignment, merge still assumes a single writer during the read-modify-write window because the current SPI does not offer atomic compare-and-swap for snapshots.

## Testing Strategy

Required coverage:

- merging one source into one target removes the source entity
- merged metadata unions aliases and descriptions correctly
- affected relations are redirected to the target
- duplicate rewritten relations are folded into one surviving relation
- self-loops created by merge are removed
- ambiguous alias resolution fails
- duplicate source references fail
- target-in-source-set validation fails
- autosnapshot reflects merge results when configured
- PostgreSQL and PostgreSQL+Neo4j providers both support merge

Verification should include targeted end-to-end tests plus full `./gradlew test`.
