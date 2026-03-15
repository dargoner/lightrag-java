# Java LightRAG Graph Management Design

## Overview

This document defines the next upstream-alignment step for the Java LightRAG SDK: manual knowledge-graph management for entities and relations.

The upstream LightRAG README exposes graph-management APIs for:

- creating entities
- editing entities, including rename
- creating relations
- editing relations

The Java SDK currently supports ingest, query, snapshot restore, and delete operations, but it does not let callers create or edit graph records directly after ingestion.

This phase should add the missing graph-management surface before larger upstream features such as entity merge or document-status orchestration.

## Goals

- Add public Java APIs for manual entity and relation creation and editing.
- Keep graph storage and vector storage consistent after each mutation.
- Support entity rename while migrating all affected relations and vectors.
- Work across current `AtomicStorageProvider` implementations without widening provider SPIs.

## Non-Goals

- Do not implement `merge_entities` in this phase.
- Do not introduce asynchronous graph-management APIs in this phase.
- Do not add document-status storage or chunk-tracking stores in this phase.
- Do not redesign the current Java relation identity model.

## Upstream Behavior To Align

According to the upstream LightRAG README:

- `create_entity(name, data)` creates a new entity with manual metadata
- `edit_entity(name, data)` updates metadata and can rename the entity
- `create_relation(source, target, data)` creates a new relation between existing entities
- `edit_relation(source, target, data)` updates an existing relation

The upstream Python implementation uses graph-native storage plus vector upserts. The Java SDK can align with the closest feasible visible behavior by materializing snapshots, mutating graph records in memory, and restoring the replacement snapshot.

## Java API Shape

Add synchronous APIs to `LightRag`:

- `createEntity(CreateEntityRequest request)`
- `editEntity(EditEntityRequest request)`
- `createRelation(CreateRelationRequest request)`
- `editRelation(EditRelationRequest request)`

Each method should return a stable Java view object rather than exposing storage records directly:

- `GraphEntity`
- `GraphRelation`

### Why Request/Result Records

The upstream Python API accepts dynamic dictionaries. That is a poor fit for the Java SDK because:

- validation becomes late and inconsistent
- rename and type changes need explicit semantics
- public API evolution is safer with typed request objects

Java should therefore use immutable request/result records with builders where optional fields exist.

## Relation Identity Decision

This is the main Java-specific constraint in this phase.

The current Java graph model identifies a relation by:

- normalized source entity ID
- normalized relation type
- normalized target entity ID

That means Java relation management cannot match Python's endpoint-only edit semantics exactly. The Java API must require `relationType` when creating a relation and `currentRelationType` when editing one. The Java field `relationType` is the SDK's current single persisted relation label field, which is the closest available mapping for upstream relation metadata in this schema. The Java SDK does not add a separate persisted `keywords` field in this phase.

This is an intentional compatibility boundary, not an accident.

## Recommended Design

Adopt a new internal coordinator:

- `GraphManagementPipeline`

Responsibilities:

- resolve entities by name or alias
- validate create/edit preconditions
- atomically append pure create operations through `writeAtomically(...)`
- compute replacement entity and relation records for edit operations
- regenerate affected entity and relation vectors
- restore the replacement snapshot only for edit operations that must rewrite IDs or relations
- persist autosnapshot if configured

This keeps graph-management logic out of `LightRag` while reusing the same cross-provider strategy already used for delete alignment.

## Entity Resolution Rule

Entity lookup must be deterministic.

For every graph-management operation:

- first check for an exact normalized name match
- if no exact-name match exists, allow alias lookup only when it resolves to exactly one entity
- if alias lookup resolves to multiple entities, fail with an ambiguity error instead of picking one arbitrarily

For create operations, names and aliases share one external-resolution namespace:

- a new entity name must not reuse an existing entity name
- a new entity name must not reuse an existing alias
- a new alias must not reuse an existing entity name
- a new alias must not reuse an existing alias

This prevents later graph-management calls from silently changing which entity a user-supplied lookup string targets.

## Entity Create Semantics

`createEntity(...)` should:

1. validate non-blank entity name
2. reject creation when the normalized name already matches an existing entity name or alias
3. reject creation when any supplied alias collides with an existing entity name or alias
4. create a new entity record with:
   - ID `entity:<normalized-name>`
   - supplied display name
   - supplied type, description, aliases
   - empty `sourceChunkIds`
5. generate one entity vector from the same summary format used during ingest
6. persist the new graph and vector records through `writeAtomically(...)`

Manual entity creation does not create documents, chunks, or chunk vectors.

## Entity Edit Semantics

`editEntity(...)` should:

1. resolve the target entity by the deterministic entity-resolution rule
2. reject when the entity does not exist
3. apply optional metadata updates
4. if rename is requested:
   - reject when the destination normalized name matches a different entity
   - reject when the destination name is only reachable through an ambiguous alias match
   - create a new entity ID from the new normalized name
   - migrate all affected relations to the new entity ID
   - regenerate vectors for the renamed entity and all affected relations
   - preserve the old display name as an alias unless the caller already supplied it explicitly
5. restore the replacement snapshot

Entity edit does not rewrite documents, chunks, or chunk vectors.

## Relation Create Semantics

`createRelation(...)` should:

1. resolve both endpoint entities by the deterministic entity-resolution rule
2. reject when either endpoint is missing
3. require a non-blank relation type
4. reject when a relation with the same resolved source entity ID, target entity ID, and normalized type already exists
5. create a new relation record with:
   - generated relation ID
   - resolved endpoint IDs
   - supplied type, description, weight
   - empty `sourceChunkIds`
6. generate one relation vector from the same summary format used during ingest
7. persist the new graph and vector records through `writeAtomically(...)`

## Relation Edit Semantics

`editRelation(...)` should:

1. resolve both endpoint entities by the deterministic entity-resolution rule
2. locate the existing relation by resolved endpoint IDs plus `currentRelationType`
3. reject when the relation does not exist
4. apply optional updates to:
   - new relation type
   - description
   - weight
5. if the relation type changes:
   - regenerate the relation ID
   - reject when the replacement relation ID already exists on a different relation
6. regenerate the relation vector
7. restore the replacement snapshot

## Validation Rules

- entity names must not be blank
- relation types must not be blank for create and for `currentRelationType` / `newRelationType` when supplied during edit
- descriptions may be blank in Java to stay compatible with current ingest model
- relation weight must be finite
- alias lists should be normalized for trim, duplicate removal, and self-name removal
- ambiguous alias matches must fail rather than selecting an arbitrary entity

## Failure And Concurrency Semantics

Create operations should apply through `writeAtomically(...)`, so they do not need a stale whole-snapshot restore step.
Edit operations still apply through snapshot restore, so each individual mutation is all-or-nothing relative to a single writer.

Like delete alignment, edit operations still assume a single writer during the read-modify-write window because the current SPI does not offer atomic snapshot compare-and-swap semantics across capture and restore.

## Testing Strategy

Required coverage:

- creating entities writes graph and entity-vector state
- creating relations writes graph and relation-vector state
- editing entity metadata updates the entity vector
- renaming an entity migrates relation IDs and relation vectors
- editing a relation can update description, weight, and type
- duplicate entity or relation creation fails
- edits against missing entity or relation fail
- autosnapshot reflects graph-management changes when configured

Verification should include targeted end-to-end tests plus full `./gradlew test`.

Provider-backed coverage should include both:

- PostgreSQL storage
- PostgreSQL + Neo4j storage
