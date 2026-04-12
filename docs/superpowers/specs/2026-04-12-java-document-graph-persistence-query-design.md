# Java Document Graph Persistence And Query Design

## Summary

This design scopes the next document-graph phase to one concrete goal: make document/chunk graph state durably queryable in PostgreSQL and MySQL without changing the public Java SDK surface.

The work replaces the current in-memory `DocumentGraphSnapshotStore` and `DocumentGraphJournalStore` wiring inside relational providers with real database-backed stores, then points the existing inspect/query/repair paths at those persistent stores.

This phase intentionally does not add controller endpoints, demo UI work, or new public APIs.

## Goals

- Persist document graph snapshot state in PostgreSQL and MySQL.
- Persist document graph journal state in PostgreSQL and MySQL.
- Make `inspectDocumentGraph(...)` and chunk graph status queries work across process restarts.
- Keep existing synchronous and asynchronous SDK APIs unchanged.
- Preserve snapshot capture/restore compatibility after moving graph state into relational storage.

## Non-Goals

- Demo controller or Spring Boot endpoint exposure.
- UI or operator console work.
- Append-only audit history for graph materialization.
- Batch orchestration across many documents.
- New public SDK methods.

## Current Gap

The Java SDK now has graph materialization state models, query logic, targeted chunk repair/resume, and task metadata, but PostgreSQL/MySQL providers still wire `documentGraphSnapshotStore()` and `documentGraphJournalStore()` to in-memory implementations.

That means:

- graph state disappears when the process restarts
- document/chunk inspect APIs are only as durable as the current JVM
- relational snapshot/restore captures temporary store contents rather than true database state
- PostgreSQL/MySQL deployments cannot use graph state as an operational truth source

## Design Principles

- Durable truth first: relational providers must store graph state in their own databases.
- No public API churn: callers keep using the same `LightRag` methods.
- Query current state, not history: this phase optimizes for inspect/resume/repair, not auditing.
- Workspace isolation stays explicit: every graph state row is keyed by `workspaceId`.
- Keep recovery logic centralized: `GraphMaterializationPipeline` remains the orchestration layer.

## Workspace Scoping

The store SPI stays workspace-agnostic at the method level because relational storage adapters are already constructed with a single `workspaceId`, just like existing document, chunk, task, and status stores.

That means:

- SQL tables still store `workspace_id` on every graph-state row
- PostgreSQL/MySQL store implementations inject `workspaceId` from the owning adapter
- public store interfaces do not gain new workspace parameters in this phase

## Recommended Approach

### Chosen Approach

Use four relational tables plus JSON payload columns:

- `document_graph_snapshots`
- `chunk_graph_snapshots`
- `document_graph_journals`
- `chunk_graph_journals`

The stores keep one current-state row per document or chunk. JSON columns hold extracted entities/relations and expected/materialized key sets.

### Why This Approach

- It keeps the table count small.
- It works the same way in PostgreSQL and MySQL.
- It matches the current inspect/resume/repair read patterns, which are document/chunk scoped.
- It avoids turning this phase into a history/auditing project.

### Rejected Alternatives

#### Fully normalized entity/relation detail tables

Rejected because they increase schema and write complexity without helping the current SDK query contract.

#### Append-only journals in SQL

Rejected for this phase because inspect and repair only need the latest durable truth. History can be added later without blocking current-state persistence.

#### Single blob per document

Rejected because chunk-level query and targeted repair become awkward and expensive.

## Storage Model

### `document_graph_snapshots`

One row per `(workspace_id, document_id)`.

Columns:

- `workspace_id`
- `document_id`
- `version`
- `status`
- `source`
- `chunk_count`
- `created_at`
- `updated_at`
- `error_message`

Constraints:

- primary key: `(workspace_id, document_id)`
- `version >= 0`
- `chunk_count >= 0`

Semantics:

- stores the current effective snapshot header
- does not keep historical versions in this phase

### `chunk_graph_snapshots`

One row per `(workspace_id, document_id, chunk_id)`.

Columns:

- `workspace_id`
- `document_id`
- `chunk_id`
- `chunk_order`
- `content_hash`
- `extract_status`
- `entities_json`
- `relations_json`
- `updated_at`
- `error_message`

Constraints and indexes:

- primary key: `(workspace_id, document_id, chunk_id)`
- index: `(workspace_id, document_id, chunk_order)`

Semantics:

- stores the final extraction result for the chunk
- when contextual refinement is enabled, stores refined entities/relations

### `document_graph_journals`

One row per `(workspace_id, document_id)`.

Columns:

- `workspace_id`
- `document_id`
- `snapshot_version`
- `status`
- `last_mode`
- `expected_entity_count`
- `expected_relation_count`
- `materialized_entity_count`
- `materialized_relation_count`
- `last_failure_stage`
- `created_at`
- `updated_at`
- `error_message`

Constraints:

- primary key: `(workspace_id, document_id)`

Semantics:

- stores the latest materialization summary
- replaces append-only semantics with current-state upsert semantics underneath the existing SPI

### `chunk_graph_journals`

One row per `(workspace_id, document_id, chunk_id)`.

Columns:

- `workspace_id`
- `document_id`
- `chunk_id`
- `snapshot_version`
- `merge_status`
- `graph_status`
- `expected_entity_keys_json`
- `expected_relation_keys_json`
- `materialized_entity_keys_json`
- `materialized_relation_keys_json`
- `last_failure_stage`
- `updated_at`
- `error_message`

Constraints and indexes:

- primary key: `(workspace_id, document_id, chunk_id)`
- index: `(workspace_id, document_id, graph_status)`

Semantics:

- stores the latest chunk materialization state
- supports direct chunk-level inspect and targeted repair/resume

## Store SPI Semantics

Public SPI signatures stay unchanged:

- `DocumentGraphSnapshotStore`
- `DocumentGraphJournalStore`

But relational implementations reinterpret writes as upserts of current state:

- `saveDocument(...)` overwrites the current document snapshot row
- `saveChunks(...)` replaces the current chunk snapshot set for the document:
  upsert supplied chunk rows and delete rows whose `chunk_id` is no longer present
- `appendDocument(...)` becomes upsert of the current document journal row
- `appendChunks(...)` becomes upsert of current chunk journal rows

In-memory stores should be aligned to the same latest-state semantics so test behavior matches relational behavior.

For chunk journals, targeted `resume/repair` still updates only the addressed chunk rows. Full document snapshot rewrite paths must clear previous journal rows for the document before writing the new current-state journal set, so removed chunks do not leave stale durable status behind.

## Query Path Design

### Public SDK Surface

The following public methods remain unchanged:

- `inspectDocumentGraph(...)`
- `getDocumentChunkGraphStatus(...)`
- `listDocumentChunkGraphStatuses(...)`
- `materializeDocumentGraph(...)`
- `resumeChunkGraph(...)`
- `repairChunkGraph(...)`
- async submission methods for document/chunk materialization

### Internal Read Flow

`GraphMaterializationPipeline` keeps the orchestration role and loads state from:

1. `DocumentGraphSnapshotStore`
2. `DocumentGraphJournalStore`
3. `GraphStore`
4. `VectorStore`

Then it computes:

- graph/document status
- chunk graph status
- recommended repair/resume mode
- missing and orphan key sets
- summary counts

This preserves the current inspect behavior as a reconciliation between durable expected state and currently materialized state.

The current chunk snapshot set is authoritative for document membership. Chunk journal rows without a matching current chunk snapshot must not affect inspect/status results and should be cleaned up during full snapshot rewrites.

### Internal Write Flow

`IndexingPipeline` continues to produce the first snapshot/journal state during ingest.

`GraphMaterializationPipeline` continues to update snapshot/journal state after document/chunk materialization, but writes now land in PostgreSQL/MySQL instead of an in-memory provider-local store.

Full document snapshot replacement paths (`ingest` and `rebuildSnapshot(...)`) must:

1. write the new document snapshot row
2. replace the chunk snapshot set
3. clear prior document/chunk journal rows for that document
4. write the new current-state journal rows

Targeted chunk `resume/repair` paths keep their current behavior of upserting only the affected chunk journal row plus the document summary row.

## Relational Adapter Changes

### PostgreSQL

Replace:

- tracked in-memory `DocumentGraphSnapshotStore`
- tracked in-memory `DocumentGraphJournalStore`

With:

- `PostgresDocumentGraphSnapshotStore`
- `PostgresDocumentGraphJournalStore`

`PostgresRelationalStorageAdapter.captureSnapshot()` and `restore(...)` must read/write those stores through the relational implementation, not a sidecar in-memory state.

### MySQL

Replace:

- tracked in-memory `DocumentGraphSnapshotStore`
- tracked in-memory `DocumentGraphJournalStore`

With:

- `MySqlDocumentGraphSnapshotStore`
- `MySqlDocumentGraphJournalStore`

`MySqlRelationalStorageAdapter.captureSnapshot()` and `restore(...)` must mirror PostgreSQL behavior for snapshot/journal persistence.

## Schema Management

### PostgreSQL Schema

Extend PostgreSQL bootstrap to create the four graph state tables if absent.

Use:

- `jsonb` for JSON payload columns
- `text` or `varchar` for enum-like status fields
- `timestamptz` for timestamps

### MySQL Schema

Extend MySQL bootstrap to create the same four tables if absent.

Use:

- `json` for JSON payload columns
- `varchar` for enum-like status fields
- `timestamp` or `datetime(6)` for timestamps

### Migration Strategy

- create-if-absent only
- no destructive migration in this phase
- existing deployments can upgrade in place

## Snapshot Capture And Restore

`StorageSnapshots.capture(...)` and provider-specific relational restore must continue to include:

- document graph snapshots
- chunk graph snapshots
- document graph journals
- chunk graph journals

But after this phase those records come from relational state that survives process restarts.

## Task Query Expectations

No new task APIs are added, but graph materialization tasks should consistently expose:

- `documentId`
- `chunkId` for chunk tasks
- `requestedMode` or `requestedAction`
- `executedMode` for document tasks
- `finalStatus`
- `snapshotVersion`

This keeps task query output aligned with durable graph state.

## Testing Strategy

### Store Tests

Add PostgreSQL and MySQL store tests for:

- save/load current document snapshot state
- save/load current chunk snapshot state
- upsert semantics for journal rows
- workspace isolation
- chunk ordering

### Pipeline Tests

Add persistence-backed pipeline tests for:

- `inspectDocumentGraph(...)` after rebuilding a new `LightRag` instance
- `getDocumentChunkGraphStatus(...)` and `listDocumentChunkGraphStatuses(...)` after restart
- targeted chunk `repair/resume` using relational graph state

### Provider And Snapshot Tests

Update provider tests to verify:

- `captureSnapshot()` includes relational graph state
- `restore(...)` rehydrates relational graph state
- relational providers no longer depend on process-local graph state for inspection

## Risks And Mitigations

### Risk: store SPI says "append" but implementation becomes upsert

Mitigation:

- document the current-state semantics in tests and implementation comments
- align in-memory store behavior so the whole system agrees on latest-state reads

### Risk: PostgreSQL/MySQL JSON serialization diverges

Mitigation:

- centralize JSON mapping with one shared mapper strategy
- round-trip test the same records in both providers

### Risk: query behavior changes subtly after removing in-memory accumulation

Mitigation:

- preserve existing SDK API contracts
- add restart-based regression tests that assert query equivalence

## Delivery Scope

This P1 design is complete when:

- PostgreSQL and MySQL graph snapshot/journal state is truly durable
- inspect/chunk status queries survive process restart
- snapshot capture/restore still works
- public Java SDK graph APIs remain unchanged
- no demo/controller/UI work is included
