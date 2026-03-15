# Java LightRAG Neo4j Graph Design

## Overview

This document defines the next storage phase for the Java LightRAG SDK after the PostgreSQL production storage foundation.

The SDK now has a durable single-database provider built on PostgreSQL and `pgvector`. That solves restart-safe ingestion, but graph data still lives in relational tables. The next phase should add Neo4j support without breaking the existing `LightRag` builder contract or the provider-level atomic write semantics already expected by the indexing pipeline.

## Goals

- Add a Neo4j-backed graph read/write path for LightRAG graph records.
- Preserve the current `LightRag`, `StorageProvider`, and `AtomicStorageProvider` API surface.
- Keep PostgreSQL as the transactional source of truth for documents, chunks, vectors, and fallback graph recovery.
- Provide rollback behavior that keeps PostgreSQL and Neo4j logically aligned from the SDK caller's perspective.
- Keep the in-memory and PostgreSQL-only providers unchanged.

## Non-Goals

- Do not add XA, two-phase commit, or distributed transaction coordinators.
- Do not move document, chunk, or vector storage into Neo4j.
- Do not redesign query strategies or add deeper graph traversal semantics in this phase.
- Do not require Neo4j for users who only want the PostgreSQL durable backend.
- Do not add background workers, message queues, or eventual-consistency infrastructure.

## Approach Options

### Recommended: PostgreSQL Source Of Truth With Synchronous Neo4j Projection

Use PostgreSQL as the authoritative durable store and sync graph entities and relations into Neo4j as part of provider writes.

Benefits:

- keeps the current builder and indexing pipeline contract intact
- avoids redesigning document, chunk, and vector persistence
- lets Neo4j power graph reads without making it the only durable copy
- gives a deterministic recovery path by rebuilding Neo4j from PostgreSQL graph rows

Trade-offs:

- write atomicity is achieved through compensation, not a real distributed commit
- provider implementation is more complex than the PostgreSQL-only backend

### Alternative: PostgreSQL + Neo4j Eventual Consistency

Commit PostgreSQL first and update Neo4j later out of band.

Benefits:

- smallest write-path implementation
- easy to reason about PostgreSQL durability

Trade-offs:

- breaks the current expectation that a successful ingest is immediately queryable through the configured graph store
- requires operational machinery that the SDK does not currently have

### Alternative: Neo4j As Primary Graph Store Without PostgreSQL Graph Mirror

Persist graph records only in Neo4j while PostgreSQL stores documents, chunks, and vectors.

Benefits:

- minimizes duplicated graph data
- gives pure graph-native reads and writes

Trade-offs:

- no straightforward recovery path when Neo4j sync fails after PostgreSQL succeeds
- makes `restore(Snapshot)` and rollback harder to implement safely

## Recommended Design

The next phase should implement a mixed provider where PostgreSQL remains the source of truth and Neo4j is the graph projection used for top-level graph reads, while the stable top-level graph facade mirrors writes into both stores.

### Provider Shape

Add a new provider package:

```text
io.github.lightragjava.storage.neo4j
```

Recommended components:

- `Neo4jGraphConfig`
- `Neo4jGraphStore`
- `Neo4jGraphSnapshot`
- `PostgresNeo4jStorageProvider`

The mixed provider should internally compose:

- a `PostgresStorageProvider` for documents, chunks, vectors, snapshot restore, and graph recovery
- a `Neo4jGraphStore` for top-level graph reads and graph projection writes
- a stable graph facade that mirrors top-level graph writes into PostgreSQL and Neo4j while serving reads from Neo4j

### Read And Write Contract

Top-level store getters should behave like this:

- `documentStore()` -> PostgreSQL
- `chunkStore()` -> PostgreSQL
- `vectorStore()` -> PostgreSQL
- `graphStore()` -> stable mirroring facade backed by PostgreSQL + Neo4j
- `snapshotStore()` -> delegated configured `SnapshotStore`

The top-level graph facade must preserve current `GraphStore` behavior for callers that directly invoke `saveEntity(...)` or `saveRelation(...)` outside `writeAtomically(...)`. Those writes should update PostgreSQL first and then project into Neo4j using the same compensation strategy as provider-level writes.

Inside `writeAtomically(...)`, the provider should:

1. capture a pre-write PostgreSQL snapshot for the LightRAG namespaces and graph rows
2. capture a pre-write Neo4j graph snapshot
3. execute the requested operation against an atomic view where:
   - document, chunk, and vector stores point to the PostgreSQL transaction-bound stores
   - graph writes go to a transactional facade that writes PostgreSQL graph rows and stages Neo4j graph changes
4. commit the PostgreSQL transaction
5. apply the staged graph projection to Neo4j
6. if Neo4j projection fails, restore PostgreSQL and Neo4j from the captured snapshots and surface a failure

This preserves the current SDK contract even though the implementation relies on compensation instead of a distributed commit protocol.

### Neo4j Graph Model

Use one node label and one relationship type family:

- `:Entity {id, name, type, description, aliases, sourceChunkIds}`
- `[:RELATION {id, type, description, weight, sourceChunkIds}]`

Requirements:

- entity IDs are unique
- relation IDs are unique
- `findRelations(entityId)` returns all one-hop relations touching the entity
- read ordering remains deterministic by relation ID and entity ID
- saves are idempotent through Cypher `MERGE`

### Rollback Model

The provider should treat PostgreSQL restore as the recovery anchor.

On write failure before PostgreSQL commit:

- rollback the PostgreSQL transaction
- discard staged Neo4j writes

On Neo4j projection failure after PostgreSQL commit:

- restore PostgreSQL to the captured pre-write snapshot
- restore Neo4j to the captured pre-write graph snapshot
- rethrow a storage failure with the original projection error preserved

The same rule applies to top-level graph facade writes that occur outside provider-wide `writeAtomically(...)`: if PostgreSQL graph persistence succeeds and the Neo4j mirror fails, the provider must compensate by restoring PostgreSQL graph rows and Neo4j graph state to the captured pre-write snapshot.

Residual risk remains if a compensating restore also fails. The provider should preserve the primary failure and suppress restore failures onto it, matching the defensive pattern already used in the PostgreSQL transaction code.

### Restore And Bootstrap

`restore(Snapshot)` should:

- call through to PostgreSQL restore for the full snapshot
- rebuild Neo4j graph state from the restored graph rows

Bootstrap should ensure:

- Neo4j connectivity is validated on provider startup
- entity ID and relation ID constraints exist
- the graph can be truncated and rebuilt deterministically during restore

## API Impact

### Builder

The public builder remains unchanged:

```java
LightRag.builder()
    .storage(storageProvider)
```

Users opt into Neo4j by constructing `PostgresNeo4jStorageProvider`.

### Configuration

Add a backend-specific Neo4j config object:

- `Neo4jGraphConfig`

It should carry:

- Bolt URI
- username
- password
- database name

`PostgresNeo4jStorageProvider` should accept:

- `PostgresStorageConfig`
- `Neo4jGraphConfig`
- delegated `SnapshotStore`

## Testing Strategy

Required coverage:

- Neo4j schema bootstrap and connectivity
- entity and relation save/load/find behavior through `Neo4jGraphStore`
- deterministic ordering parity with the existing `GraphStore` contract
- mixed-provider successful ingest with PostgreSQL stores plus Neo4j graph reads
- rollback when Neo4j projection fails after PostgreSQL commit
- `restore(Snapshot)` rebuilding Neo4j from PostgreSQL-backed snapshot data
- end-to-end query parity using Neo4j-backed graph reads

Use Testcontainers-backed Neo4j integration tests:

- `neo4j:5-community`

The mixed-provider tests should continue using `pgvector/pgvector:pg16` for PostgreSQL so the durable vector path remains production-like.
