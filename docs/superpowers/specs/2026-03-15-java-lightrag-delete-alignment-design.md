# Java LightRAG Delete Alignment Design

## Overview

This document defines the next feature-alignment step for the Java LightRAG SDK: deletion capabilities matching the original LightRAG surface area.

The upstream LightRAG README exposes three delete operations:

- delete by entity name
- delete by relation endpoints
- delete by document ID

The current Java SDK has no public delete API. It can ingest, query, snapshot, and restore, but it cannot remove graph nodes, relations, vectors, or documents while preserving internal consistency.

The first Java implementation should prioritize behavioral alignment with upstream over storage-level optimization.

## Goals

- Add public delete APIs to `LightRag` for entity, relation, and document deletion.
- Preserve consistency between documents, graph records, and vector indexes after deletion.
- Work across all current `AtomicStorageProvider` implementations without adding provider-specific delete SPI methods.
- Match upstream semantics closely enough that entity/relation deletion is immediate and document deletion preserves shared knowledge by rebuilding from remaining documents.

## Non-Goals

- Do not add asynchronous delete APIs in this phase.
- Do not add doc-status storage in this phase.
- Do not optimize document deletion incrementally; correctness is more important than efficiency for the first aligned implementation.
- Do not redesign the storage SPI around native delete methods yet.

## Upstream Behavior To Align

According to the upstream LightRAG README:

- `delete_by_entity(name)` removes the entity, all associated relations, and related vectors
- `delete_by_relation(source, target)` removes the relation and its vector, while preserving entities
- `delete_by_doc_id(docId)` removes the document, removes text chunks, preserves shared knowledge, rebuilds affected entity/relation descriptions from remaining documents, updates vectors, and cleans document status

The Java SDK can align with the first two directly and approximate the third by rebuilding all remaining knowledge from the remaining documents.

## Approach Options

### Recommended: Snapshot-Based Delete Coordinator

Implement delete operations above storage, using existing snapshot/restore capabilities:

- capture current state from the configured `AtomicStorageProvider`
- compute the replacement state in memory
- call `restore(...)` with the new snapshot

For document deletion:

- rebuild graph and vector state from remaining documents using the current indexing pipeline components

Benefits:

- works immediately for in-memory, PostgreSQL, and mixed PostgreSQL+Neo4j providers
- avoids widening the storage SPI
- preserves current transactional restore semantics
- gives upstream-aligned behavior quickly

Trade-offs:

- delete operations are only safe under a single-writer discipline because the current SPI does not offer atomic snapshot read-modify-write
- document deletion is O(all remaining documents), not incremental
- deletes rebuild more state than strictly necessary

### Alternative: Add Native Delete Methods To Every Store SPI

Extend `DocumentStore`, `ChunkStore`, `GraphStore`, and `VectorStore` with delete primitives.

Benefits:

- more efficient, especially for document deletion
- less snapshot copying

Trade-offs:

- much larger cross-provider change
- more storage-specific delete logic and tests
- slower path to upstream feature parity

### Alternative: Add Doc-Status And Incremental Rebuild First

Model upstream document deletion more literally by storing extraction/rebuild status.

Benefits:

- closer to upstream internals

Trade-offs:

- large feature surface
- unnecessary for first functional alignment
- blocks smaller entity/relation deletion features

## Recommended Design

Adopt a snapshot-based delete coordinator with a full-rebuild path for document deletion.

### Public API

Add synchronous methods to `LightRag`:

- `deleteByEntity(String entityName)`
- `deleteByRelation(String sourceEntityName, String targetEntityName)`
- `deleteByDocumentId(String documentId)`

These names match Java conventions while preserving the upstream capability split.

### Coordinator

Add one internal coordinator, for example:

- `DeletionPipeline`

Responsibilities:

- capture the current storage snapshot
- resolve entities and relations by external delete inputs
- build replacement snapshots for entity/relation deletes
- rebuild remaining state for document deletes
- persist replacement snapshot through `restore(...)`
- persist autosnapshot if configured

This keeps delete-specific logic out of `LightRag`.
It also makes the concurrency boundary explicit: with the current SPI, the coordinator assumes a single writer is mutating storage while a delete operation is in progress.

### Entity Delete Semantics

`deleteByEntity(name)` should:

1. capture current snapshot
2. resolve the target entity by name or alias, case-insensitive
3. remove that entity
4. remove all relations where the entity is source or target
5. remove matching entity and relation vectors
6. restore the replacement snapshot

If the entity is not found, the operation should be a no-op.
This operation does not delete source documents, chunks, or chunk vectors; it aligns with upstream graph-focused delete semantics. Removing the underlying text requires `deleteByDocumentId(...)`.

### Relation Delete Semantics

`deleteByRelation(sourceName, targetName)` should:

1. capture current snapshot
2. resolve source and target entities by name or alias
3. remove relations between the resolved entity IDs in either direction
4. remove matching relation vectors
5. restore the replacement snapshot

Entities and unrelated relations remain unchanged.
Source documents, chunks, and chunk vectors also remain unchanged; only the graph edge and relation vectors are removed.

If either entity is not found, the operation should be a no-op.

### Document Delete Semantics

`deleteByDocumentId(docId)` should:

1. capture current snapshot
2. remove the target document from the document list
3. if the document is absent, no-op
4. restore an empty snapshot to clear current storage state
5. rebuild all remaining documents through the same extraction/graph/vector pipeline used for ingest
6. persist autosnapshot if configured

This is intentionally heavier than upstream’s incremental rebuild, but it matches the visible semantics:

- deleted chunks disappear
- knowledge from deleted-only documents disappears
- shared knowledge is reconstructed from remaining documents
- entity/relation vectors are regenerated from the rebuilt graph

The clear-and-rebuild path is not atomically visible to concurrent readers. During step 4 and the rebuild window, readers can observe an empty or partially rebuilt store before the remaining documents finish re-ingesting.
The rebuild also uses the current `LightRag` instance's configured chunking, extraction, and embedding pipeline, which matches upstream's rebuild-oriented delete model.

### Snapshot Utilities

The Java SDK already has multiple places that materialize snapshots from storage. This delete work should centralize that behavior in a small helper so snapshot capture and autosnapshot persistence are not duplicated again.

Recommended helpers:

- capture current provider snapshot
- write current snapshot to configured autosave path

### Failure Semantics

Entity and relation deletion should be all-or-nothing because they apply through `restore(...)`.
That guarantee only holds relative to a single writer. Concurrent writers can still race between snapshot capture and restore.

Document deletion must protect against rebuild failure:

- capture the full pre-delete snapshot first
- if rebuild fails after clearing storage, restore the pre-delete snapshot
- preserve the original failure and suppress restore failures if rollback also fails

That keeps deletion behavior consistent with the defensive recovery model already used elsewhere in the storage layer.

## Testing Strategy

Required coverage:

- deleting an entity removes the entity, its connected relations, and the matching vectors
- deleting a relation removes only the relation and its vector
- deleting a missing entity or relation is a no-op
- deleting a document rebuilds remaining documents and preserves shared knowledge
- document delete rollback restores original state when rebuild fails
- autosnapshot continues to reflect the post-delete state when configured for relation and document deletes

Verification should include focused unit/integration tests plus full `./gradlew test`.
